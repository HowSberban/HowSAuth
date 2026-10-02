package org.howsauth.plugin.premium;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.config.ConfigManager;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mojang 会话验证客户端（模块3）。
 * <p>
 * 仅负责与 Mojang 会话服务器交互，执行 hasJoined 验证。
 * 所有 HTTP 调用严格异步执行，超时后返回空结果。
 * <p>
 * 线程模型：所有方法在异步线程执行，返回 CompletableFuture。
 */
public final class MojangClient {

    // hasJoined 接口路径，拼接在会话验证服务器基础 URL 之后
    private static final String HAS_JOINED_PATH = "/session/minecraft/hasJoined?username=";

    // Mojang sessionserver 要求 User-Agent，否则可能返回 403/429
    private static final String USER_AGENT = "HowSAuth-Premium/1.0";

    // 熔断冷却基数（毫秒）：连续失败按 15s、30s、60s... 指数退避
    private static final long BREAKER_BASE_COOLDOWN_MS = 15_000L;
    // 熔断冷却封顶（毫秒）
    private static final long BREAKER_MAX_COOLDOWN_MS = 300_000L;

    /** 验证端点：唯一 key + HTTP 客户端（绑定出站代理或直连）+ 目标服务器基础 URL + 日志描述 */
    private record Endpoint(String key, HttpClient client, String baseUrl, String description) {}

    private final HowSAuth plugin;
    // 直连 HttpClient（无出站代理），本实例所有直连端点共用；
    // 实例字段而非静态：close() 会关闭它，静态会导致 reload 后新实例拿到已关闭的 client
    private final HttpClient directClient = newHttpClient(null);
    // 按代理地址缓存 HttpClient：出站代理在 HttpClient 构建时绑定且创建开销大，同地址代理复用
    private final Map<String, HttpClient> proxyClients = new ConcurrentHashMap<>();
    // 端点熔断状态：连续失败按指数退避隔离，冷却到期由后台轻量探测确认恢复（玩家验证永不当试探）
    private final Map<String, Breaker> breakers = new ConcurrentHashMap<>();
    // 独立线程池：hasJoined 内部含 sleep + 重试等待，最长可阻塞较久，
    // 用专用线程池避免占用公共 ForkJoinPool 拖累其它插件的异步任务。
    // 池大小由配置 premium.http-pool-size 控制（阻塞式任务，高并发正版验证时可按负载调大）
    private final ExecutorService httpExecutor;

    public MojangClient(HowSAuth plugin) {
        this.plugin = plugin;
        int poolSize = plugin.getConfigManager().premiumHttpPoolSize();
        this.httpExecutor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "HowSAuth-Mojang");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 按当前配置构建全部验证端点（不过滤，供启动探测使用）。
     * 端点顺序即尝试顺序：
     * 1. 每个 HTTP 出站代理 → 官方地址（借道代理访问真正的 Mojang，验证结果可信）
     * 2. 直连 → 镜像候选列表（官方地址硬编码首位 + 配置的镜像，代理均不可用时的备选）
     */
    private List<Endpoint> allEndpoints() {
        ConfigManager config = plugin.getConfigManager();
        List<Endpoint> list = new ArrayList<>();
        String official = config.premiumSessionServerCandidates().getFirst();
        for (Proxy proxy : config.premiumHttpProxies()) {
            var addr = (InetSocketAddress) proxy.address();
            String proxyDesc = addr.getHostString() + ":" + addr.getPort();
            String proxyKey = "proxy:" + proxyDesc + "@" + official;
            list.add(new Endpoint(proxyKey, proxyClient(proxyDesc, proxy), official,
                    I18n.get("log.premium_endpoint_via_proxy", official, proxyDesc)));
        }
        for (String baseUrl : config.premiumSessionServerCandidates()) {
            list.add(new Endpoint("direct:" + baseUrl, directClient, baseUrl, baseUrl));
        }
        return list;
    }

    /**
     * 构建验证端点列表：跳过熔断中的端点（连续失败后按指数退避隔离）。
     * 冷却到期时触发后台轻量探测确认恢复（探测在途期间继续跳过，玩家验证永不当试探）。
     * 全部端点处于熔断时兜底返回全部（宁试死节点也不直接拒登录）。
     */
    private List<Endpoint> buildEndpoints() {
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
    private void breakerSuccess(Endpoint endpoint) {
        Breaker b = breakers.get(endpoint.key());
        if (b == null || b.failCount.get() == 0) return;
        b.failCount.set(0);
        b.announced = false;
        plugin.getLogger().info(I18n.get("log.premium_endpoint_recovered", endpoint.description()));
    }

    /** 记录端点失败（不可达/持续过载）：失败次数 +1，进入或延长熔断冷却 */
    private void breakerFailure(Endpoint endpoint) {
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

    /** 构建 HttpClient：统一 5 秒连接超时，proxy 为 null 时直连 */
    private static HttpClient newHttpClient(ProxySelector proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        return proxy == null ? builder.build() : builder.proxy(proxy).build();
    }

    /** 获取/创建绑定指定出站代理的 HttpClient（Java HttpClient 不支持按请求切换代理，须按代理构建） */
    private HttpClient proxyClient(String key, Proxy proxy) {
        return proxyClients.computeIfAbsent(key,
                k -> newHttpClient(ProxySelector.of((InetSocketAddress) proxy.address())));
    }

    /** 关闭线程池与全部 HttpClient（插件禁用时调用），等待在途验证任务收尾 */
    public void close() {
        httpExecutor.shutdown();
        try {
            // 等待在途验证完成，避免旧实例的回调打到已注销的监听器
            if (!httpExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                httpExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            httpExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // HttpClient 持有内部 selector 线程，不关闭会在插件 reload 时随旧类加载器泄漏
        proxyClients.values().forEach(HttpClient::close);
        proxyClients.clear();
        directClient.close();
    }

    /**
     * 启动时异步探测所有验证端点（不阻塞服务器启动）：预热连接池（首个玩家省去握手耗时），
     * 同时探测失败仅记 1 次熔断失败（15 秒后即有自愈机会），不会永久判死刑。
     */
    public void probeAll() {
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
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        endpoint.client().sendAsync(request, HttpResponse.BodyHandlers.discarding())
                // 收到任意 HTTP 响应：服务可达；抛异常（连接失败/超时）：不可达
                .thenApply(r -> true)
                .exceptionally(e -> false)
                .thenAccept(ok -> {
                    if (ok) breakerSuccess(endpoint);
                    else breakerFailure(endpoint);
                })
                .whenComplete((r, t) -> b.probing.set(false));
    }

    /**
     * 异步向 Mojang 会话服务器发起 hasJoined 验证。
     * 依次尝试验证端点（HTTP 代理→官方地址优先，直连→镜像备选），直到某个端点给出确定答复；
     * 某端点不可达或持续过载时自动切换到下一个。
     * 整个验证（含端点切换）受总时限（premium.verify-deadline-ms）约束：
     * 客户端在"通讯加密中"界面约 30 秒未收到 LoginSuccess 会主动断开，
     * 服务端验证须在此之前结束，否则验证白做且玩家需重连。
     *
     * @param serverHash 服务器哈希（SHA-1(serverId + sharedSecret + publicKey) 的正十六进制）
     * @param username   玩家名称
     * @return 200 OK 时返回 PremiumProfile（含正版 UUID 和 properties），未加入/全部端点不可用/超时返回 empty
     */
    public CompletableFuture<Optional<PremiumProfile>> hasJoined(String serverHash, String username) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ConfigManager config = plugin.getConfigManager();
                long deadline = System.currentTimeMillis() + config.premiumVerifyDeadlineMs();
                String encodedName = URLEncoder.encode(username, StandardCharsets.UTF_8);
                // 依次尝试端点，直到某个端点给出确定答复或总时限耗尽
                for (Endpoint endpoint : buildEndpoints()) {
                    if (System.currentTimeMillis() >= deadline) break;
                    QueryResult result = queryServer(endpoint, serverHash, encodedName, username, config, deadline);
                    if (result.available()) {
                        return result.profile(); // 该端点可达且给出确定答复（验证成功或未加入）
                    }
                    // available=false：该端点不可用（不可达/持续过载/时限耗尽），尝试下一个
                    plugin.getLogger().warning(I18n.get("log.premium_session_server_unavailable", endpoint.description()));
                }
                // 所有端点均不可用或总时限耗尽
                logHasJoinedFailed(username, "all session servers unavailable");
                return Optional.empty();
            } catch (Exception e) {
                // 端点 URL 畸形、响应字段非法等运行时异常：视为验证失败走踢出路径，
                // 避免 future 异常完成后回调不执行，导致会话泄漏、玩家卡在加密阶段
                logHasJoinedFailed(username, e.toString());
                return Optional.empty();
            }
        }, httpExecutor);
    }

    /** 输出正版验证失败 WARNING 日志 */
    private void logHasJoinedFailed(String username, String reason) {
        plugin.getLogger().warning(I18n.get("log.premium_hasjoined_failed", username, reason));
    }

    /**
     * 向单个验证端点发起 hasJoined 查询（含重试）。
     * 无需等待：客户端在发送 EncryptionResponse 之前已向 sessionserver 发送 /join，
     * 收到 EncryptionResponse 即可立即查询（与原版服务端行为一致）。
     * 单次请求超时与重试等待均受总时限约束，避免超出客户端等待上限。
     *
     * @return 单端点查询结果：available=true 时 profile 为确定答复（有值=验证成功，空=未加入/拒绝）；
     *         available=false 表示该端点不可用（不可达/持续过载/时限耗尽），调用方应尝试下一个端点
     */
    // client 为共享长生命周期单例（directClient / proxyClients），不能在请求内关闭：否则首次请求后连接池即失效
    @SuppressWarnings("resource")
    private QueryResult queryServer(Endpoint endpoint, String serverHash, String encodedName,
                                    String username, ConfigManager config, long deadline) {
        int maxRetries = config.premiumMaxRetries();
        long retryIntervalMs = config.premiumRetryIntervalMs();
        long requestTimeoutMs = config.premiumTimeoutSeconds() * 1000L;
        String url = endpoint.baseUrl() + HAS_JOINED_PATH + encodedName + "&serverId=" + serverHash;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            long remaining = deadline - System.currentTimeMillis();
            // 总时限耗尽：端点不可用，终止当前端点并切换下一个
            if (remaining <= 0) return QueryResult.UNAVAILABLE;
            HttpResponse<String> response;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        // 请求超时不超过剩余时限，保证整体不超总时限
                        .timeout(Duration.ofMillis(Math.min(requestTimeoutMs, remaining)))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                response = endpoint.client().send(request, HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException e) {
                // 连接失败（含连接/读取超时）：端点不可达，立即切换下一个（不在本端点重试，快速回退）
                breakerFailure(endpoint);
                return QueryResult.UNAVAILABLE;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return QueryResult.UNAVAILABLE;
            }

            int code = response.statusCode();
            if (code == 200) {
                breakerSuccess(endpoint);
                // 只解析一次 JSON，复用 JsonObject 提取 id 与 properties
                JsonObject obj = parseJson(response.body());
                String id = obj == null ? null : extractId(obj);
                if (id == null) {
                    logHasJoinedFailed(username, "no id field");
                    return QueryResult.NOT_JOINED;
                }
                return new QueryResult(Optional.of(new PremiumProfile(parseUuid(id), extractProperties(obj))), true);
            }
            if (code == 204) {
                // 玩家未加入会话：客户端 /join 严格先于 EncryptionResponse 发出，204 即确定结论，零重试
                breakerSuccess(endpoint);
                return QueryResult.NOT_JOINED;
            }
            if (!isRetryable(code)) {
                // 其他状态码：确定失败（端点本身可达）
                breakerSuccess(endpoint);
                logHasJoinedFailed(username, "HTTP " + code);
                return QueryResult.NOT_JOINED;
            }
            // 429/5xx：服务器过载，等待后重试；重试耗尽落到循环外熔断
            if (attempt < maxRetries) {
                long remainingAfter = deadline - System.currentTimeMillis();
                // 总时限耗尽：不再等待重试，切换下一个端点
                if (remainingAfter <= 0) return QueryResult.UNAVAILABLE;
                plugin.getLogger().warning(I18n.get("log.premium_hasjoined_retry",
                        username, attempt + 1, maxRetries));
                sleep(Math.min(retryIntervalMs, remainingAfter));
            }
        }
        // 过载重试耗尽：熔断并换端点
        breakerFailure(endpoint);
        return QueryResult.UNAVAILABLE;
    }

    /** 单端点查询结果：available=false=端点不可用（换下一个），true=确定答复（profile 空=未加入/拒绝） */
    private record QueryResult(Optional<PremiumProfile> profile, boolean available) {
        /** 端点不可用（不可达/过载重试耗尽/总时限耗尽），调用方应换下一个端点 */
        static final QueryResult UNAVAILABLE = new QueryResult(Optional.empty(), false);
        /** 确定答复：玩家未加入会话（204）/请求被拒 */
        static final QueryResult NOT_JOINED = new QueryResult(Optional.empty(), true);
    }

    /** 判断 HTTP 状态码是否可重试 */
    private static boolean isRetryable(int code) {
        return code == 429 || code == 502 || code == 503 || code == 504;
    }

    /** 线程睡眠（不抛异常） */
    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    /** 解析 JSON 为 JsonObject，解析失败返回 null */
    private static JsonObject parseJson(String json) {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    /** 从已解析的 JsonObject 中提取 "id" 字段的无横线 UUID 字符串 */
    private static String extractId(JsonObject obj) {
        return obj.has("id") && !obj.get("id").isJsonNull() ? obj.get("id").getAsString() : null;
    }

    /** 从已解析的 JsonObject 中提取 "properties" 数组的原始 JSON 字符串（可能为 null） */
    private static String extractProperties(JsonObject obj) {
        JsonElement props = obj.get("properties");
        return props == null || props.isJsonNull() ? null : props.toString();
    }

    /** 将 Mojang 返回的无横线 UUID 解析为 UUID 对象 */
    private static UUID parseUuid(String id) {
        if (id.contains("-")) return UUID.fromString(id);
        return UUID.fromString(id.replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
    }

    /**
     * 正版验证结果（不可变）
     */
    public record PremiumProfile(UUID uuid, String propertiesJson) {
    }
}
