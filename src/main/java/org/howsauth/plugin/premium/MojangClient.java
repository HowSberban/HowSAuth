package org.howsauth.plugin.premium;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.config.ConfigManager;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Mojang 会话验证客户端（模块3）。
 * <p>
 * 本类只负责 hasJoined 查询、重试与响应解析；端点构建、代理客户端、熔断与探测在
 * {@link MojangEndpointPool}。所有 HTTP 调用严格异步执行，超时后返回空结果。
 * <p>
 * 线程模型：所有方法在异步线程执行，返回 CompletableFuture。
 */
public final class MojangClient {

    // 插件禁用时等待在途验证收尾的时间，超时则强制关停线程池
    private static final int SHUTDOWN_WAIT_SECONDS = 5;

    private final HowSAuth plugin;
    // 端点池：代理/直连端点、熔断状态与后台探测
    private final MojangEndpointPool endpointPool;
    // 独立线程池：hasJoined 内部含 sleep + 重试等待，最长可阻塞较久，
    // 用专用线程池避免占用公共 ForkJoinPool 拖累其它插件的异步任务。
    // 池大小由配置 premium.http-pool-size 控制（阻塞式任务，高并发正版验证时可按负载调大）
    private final ExecutorService httpExecutor;

    public MojangClient(HowSAuth plugin) {
        this.plugin = plugin;
        this.endpointPool = new MojangEndpointPool(plugin);
        int poolSize = plugin.config().premium().httpPoolSize();
        this.httpExecutor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "HowSAuth-Mojang");
            t.setDaemon(true);
            return t;
        });
    }

    /** 关闭线程池与全部 HttpClient（插件禁用时调用），等待在途验证任务收尾 */
    public void close() {
        httpExecutor.shutdown();
        try {
            // 等待在途验证完成，避免旧实例的回调打到已注销的监听器
            if (!httpExecutor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                httpExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            httpExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        endpointPool.close();
    }

    /** 启动时异步探测所有验证端点，预热连接池并给失败端点进入熔断的机会。 */
    public void probeAll() {
        endpointPool.probeAll();
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
                ConfigManager config = plugin.config();
                long deadline = System.currentTimeMillis() + config.premium().verifyDeadlineMs();
                String encodedName = URLEncoder.encode(username, StandardCharsets.UTF_8);
                // 依次尝试端点，直到某个端点给出确定答复或总时限耗尽
                for (MojangEndpointPool.Endpoint endpoint : endpointPool.candidates()) {
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
    private QueryResult queryServer(MojangEndpointPool.Endpoint endpoint, String serverHash, String encodedName,
                                    String username, ConfigManager config, long deadline) {
        int maxRetries = config.premium().maxRetries();
        long retryIntervalMs = config.premium().retryIntervalMs();
        long requestTimeoutMs = config.premium().timeoutSeconds() * 1000L;
        String url = endpoint.baseUrl() + MojangEndpointPool.HAS_JOINED_PATH + encodedName + "&serverId=" + serverHash;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            long remaining = deadline - System.currentTimeMillis();
            // 总时限耗尽：端点不可用，终止当前端点并切换下一个
            if (remaining <= 0) return QueryResult.UNAVAILABLE;
            HttpResponse<String> response;
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        // 请求超时不超过剩余时限，保证整体不超总时限
                        .timeout(Duration.ofMillis(Math.min(requestTimeoutMs, remaining)))
                        .header("User-Agent", MojangEndpointPool.USER_AGENT)
                        .header("Accept", "application/json")
                        .GET()
                        .build();
                response = endpoint.client().send(request, HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException e) {
                // 连接失败（含连接/读取超时）：端点不可达，立即切换下一个（不在本端点重试，快速回退）
                endpointPool.failure(endpoint);
                return QueryResult.UNAVAILABLE;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return QueryResult.UNAVAILABLE;
            }

            int code = response.statusCode();
            if (code == 200) {
                endpointPool.success(endpoint);
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
                endpointPool.success(endpoint);
                return QueryResult.NOT_JOINED;
            }
            if (!isRetryable(code)) {
                // 其他状态码：确定失败（端点本身可达）
                endpointPool.success(endpoint);
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
        endpointPool.failure(endpoint);
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
