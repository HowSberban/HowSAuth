package org.howsauth.plugin.premium;

import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.config.ConfigManager;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mojang 验证端点的构建、熔断与后台探测。
 * <p>
 * 端点来源是"HTTP 代理 → 官方地址"加"直连 → 镜像候选"；连续失败按指数退避隔离，
 * 冷却到期由后台轻量探测确认恢复，玩家验证永不当试探。HTTP 查询与结果解析在
 * {@link MojangClient}。
 */
final class MojangEndpointPool {

    // hasJoined 接口路径，拼接在会话验证服务器基础 URL 之后
    static final String HAS_JOINED_PATH = "/session/minecraft/hasJoined?username=";
    // Mojang sessionserver 要求 User-Agent，否则可能返回 403/429
    static final String USER_AGENT = "HowSAuth-Premium/1.0";

    // 熔断冷却基数（毫秒）：连续失败按 15s、30s、60s... 指数退避
    private static final long BREAKER_BASE_COOLDOWN_MS = 15_000L;
    // 熔断冷却封顶（毫秒）
    private static final long BREAKER_MAX_COOLDOWN_MS = 300_000L;
    // TCP 建连超时，只约束握手阶段，不含请求/响应
    private static final int CONNECT_TIMEOUT_SECONDS = 5;
    // 后台探测请求的响应超时：探测不占线程，但要限制单个探测的寿命
    private static final int PROBE_TIMEOUT_SECONDS = 5;

    /** 验证端点：唯一 key + HTTP 客户端（绑定出站代理或直连）+ 目标服务器基础 URL + 日志描述 */
    record Endpoint(String key, HttpClient client, String baseUrl, String description) {}

    private final HowSAuth plugin;
    // 直连 HttpClient（无出站代理），本实例所有直连端点共用；
    // 实例字段而非静态：close() 会关闭它，静态会导致 reload 后新实例拿到已关闭的 client
    private final HttpClient directClient = newHttpClient(null);
    // 按代理地址缓存 HttpClient：出站代理在 HttpClient 构建时绑定且创建开销大，同地址代理复用
    private final Map<String, HttpClient> proxyClients = new ConcurrentHashMap<>();
    // 端点熔断状态：连续失败按指数退避隔离，冷却到期由后台轻量探测确认恢复（玩家验证永不当试探）
    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();

    MojangEndpointPool(HowSAuth plugin) {
        this.plugin = plugin;
    }

    /**
     * 按当前配置构建全部验证端点（不过滤，供启动探测使用）。
     * 端点顺序即尝试顺序：
     * 1. 每个 HTTP 出站代理 → 官方地址（借道代理访问真正的 Mojang，验证结果可信）
     * 2. 直连 → 镜像候选列表（官方地址硬编码首位 + 配置的镜像，代理均不可用时的备选）
     */
    private List<Endpoint> allEndpoints() {
        ConfigManager config = plugin.config();
        List<Endpoint> list = new ArrayList<>();
        String official = config.premium().sessionServerCandidates().getFirst();
        for (Proxy proxy : config.premium().httpProxies()) {
            var addr = (InetSocketAddress) proxy.address();
            String proxyDesc = addr.getHostString() + ":" + addr.getPort();
            String proxyKey = "proxy:" + proxyDesc + "@" + official;
            list.add(new Endpoint(proxyKey, proxyClient(proxyDesc, proxy), official,
                    I18n.get("log.premium_endpoint_via_proxy", official, proxyDesc)));
        }
        for (String baseUrl : config.premium().sessionServerCandidates()) {
            list.add(new Endpoint("direct:" + baseUrl, directClient, baseUrl, baseUrl));
        }
        return list;
    }

    /**
     * 构建验证端点列表：跳过熔断中的端点（连续失败后按指数退避隔离）。
     * 冷却到期时触发后台轻量探测确认恢复（探测在途期间继续跳过，玩家验证永不当试探）。
     * 全部端点处于熔断时兜底返回全部（宁试死节点也不直接拒登录）。
     */
    List<Endpoint> candidates() {
        List<Endpoint> all = allEndpoints();
        List<Endpoint> list = new ArrayList<>(all.size());
        long now = System.currentTimeMillis();
        for (Endpoint endpoint : all) {
            Breaker b = breakers.get(endpoint.key());
            int fails = b == null ? 0 : b.failCount.get();
            if (fails == 0) {
                list.add(endpoint);
                continue;
            }
            if (now - b.failedAt >= breakerCooldownMs(fails)) {
                // 冷却到期：后台探测确认恢复（在途不重复触发），本次仍跳过
                probe(endpoint);
            } else if (!b.announced) {
                // 每次熔断期仅告警一次，避免每次验证重复输出
                b.announced = true;
                plugin.getLogger().warning(I18n.get("log.premium_endpoint_skipped", endpoint.description()));
            }
        }
        return list.isEmpty() ? all : list;
    }

    /** 熔断冷却时长：15s 起按连续失败次数倍增，封顶 5 分钟（位移上限防极端溢出） */
    private static long breakerCooldownMs(int failCount) {
        int shift = Math.min(failCount - 1, 20);
        return Math.min(BREAKER_BASE_COOLDOWN_MS << shift, BREAKER_MAX_COOLDOWN_MS);
    }

    /** 记录端点验证成功：清除熔断状态；从熔断中恢复时输出 INFO 便于运维确认节点回归 */
    void success(Endpoint endpoint) {
        Breaker b = breakers.get(endpoint.key());
        if (b == null || b.failCount.get() == 0) return;
        b.failCount.set(0);
        b.announced = false;
        plugin.getLogger().info(I18n.get("log.premium_endpoint_recovered", endpoint.description()));
    }

    /** 记录端点失败（不可达/持续过载）：失败次数 +1，进入或延长熔断冷却 */
    void failure(Endpoint endpoint) {
        breakers.computeIfAbsent(endpoint.key(), k -> new Breaker()).recordFailure();
    }

    /** 端点熔断状态：failCount=0 正常；>0 熔断中，冷却时长随失败次数指数增长 */
    private static final class Breaker {
        final AtomicInteger failCount = new AtomicInteger();
        volatile long failedAt;
        // 本次熔断期是否已告警（恢复时重置，再熔断可再次告警）
        volatile boolean announced;
        // 后台探测在途标记（防重复触发）
        final AtomicBoolean probing = new AtomicBoolean();

        void recordFailure() {
            failCount.incrementAndGet();
            failedAt = System.currentTimeMillis();
        }
    }

    /** 构建 HttpClient：统一连接超时（CONNECT_TIMEOUT_SECONDS），proxy 为 null 时直连 */
    private static HttpClient newHttpClient(ProxySelector proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS));
        return proxy == null ? builder.build() : builder.proxy(proxy).build();
    }

    /** 获取/创建绑定指定出站代理的 HttpClient（Java HttpClient 不支持按请求切换代理，须按代理构建） */
    private HttpClient proxyClient(String key, Proxy proxy) {
        return proxyClients.computeIfAbsent(key,
                k -> newHttpClient(ProxySelector.of((InetSocketAddress) proxy.address())));
    }

    /**
     * 启动时异步探测所有验证端点（不阻塞服务器启动）：预热连接池（首个玩家省去握手耗时），
     * 同时探测失败仅记 1 次熔断失败（15 秒后即有自愈机会），不会永久判死刑。
     */
    void probeAll() {
        for (Endpoint endpoint : allEndpoints()) {
            probe(endpoint);
        }
    }

    /**
     * 向单个端点发起后台轻量探测（sendAsync 不占线程），结果写回熔断状态：
     * 可达 → 清除熔断；不可达 → 失败次数 +1（冷却指数延长）。
     * probing 标记保证同一端点同一时刻至多一个探测在途。
     */
    // client 为共享长生命周期单例（directClient / proxyClients），不能在请求内关闭：否则首次探测后连接池即失效
    @SuppressWarnings("resource")
    private void probe(Endpoint endpoint) {
        Breaker b = breakers.computeIfAbsent(endpoint.key(), k -> new Breaker());
        if (!b.probing.compareAndSet(false, true)) return;
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        endpoint.baseUrl() + HAS_JOINED_PATH + "probe&serverId=probe"))
                .timeout(Duration.ofSeconds(PROBE_TIMEOUT_SECONDS))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        endpoint.client().sendAsync(request, HttpResponse.BodyHandlers.discarding())
                // 收到任意 HTTP 响应：服务可达；抛异常（连接失败/超时）：不可达
                .thenApply(r -> true)
                .exceptionally(e -> false)
                .thenAccept(ok -> {
                    if (ok) success(endpoint);
                    else failure(endpoint);
                })
                .whenComplete((r, t) -> b.probing.set(false));
    }

    /** 关闭全部 HttpClient（插件禁用时调用），避免 selector 线程随 reload 泄漏 */
    void close() {
        proxyClients.values().forEach(HttpClient::close);
        proxyClients.clear();
        directClient.close();
    }
}
