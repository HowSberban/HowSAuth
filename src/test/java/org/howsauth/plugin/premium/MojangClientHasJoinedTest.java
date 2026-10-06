package org.howsauth.plugin.premium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.howsauth.plugin.premium.MojangClient.PremiumProfile;
import org.howsauth.plugin.support.MockBukkitHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 正版会话验证客户端（{@link MojangClient}）的 hasJoined 状态机回归。
 * <p>
 * 该状态机决定"玩家是否正版、能否免密进入"，分支多且此前零测试覆盖。本测试用**进程内 HTTP 服务器**
 * 充当会话验证端点：把配置里的镜像地址指向 localhost，官方端点因无法连通而让位，
 * 于是请求必然落到本地服务器，从而端到端驱动真实 HttpClient + JSON 解析 + 重试 + 端点回退逻辑
 * （而不是用 mock 替掉被测代码）。
 * <p>
 * 覆盖分支：
 * <ul>
 *   <li>200 + 合法 JSON → 解析出 UUID 与皮肤 properties；</li>
 *   <li>200 但缺 id 字段 → 视为"未加入"；</li>
 *   <li>200 + 畸形 JSON → 不抛异常，降级为未加入；</li>
 *   <li>缺 properties → 返回既有行为（字面量 "null"）；</li>
 *   <li>204 → 未加入（确定结论，零重试）；</li>
 *   <li>非可重试状态码（403）→ 未加入且不重试；</li>
 *   <li>可重试状态码（429）→ 重试耗尽后回退到下一个端点；</li>
 *   <li>端点不可达 → 立即回退下一个端点；</li>
 *   <li>所有端点不可用 → 返回空（调用方走踢出路径，而非让 future 异常完成）。</li>
 * </ul>
 */
class MojangClientHasJoinedTest {

    /** 一个本地端点：可设定响应状态码与响应体，并记录被请求次数 */
    private static final class TestEndpoint {
        final HttpServer server;
        final AtomicInteger hits = new AtomicInteger();
        private final int status;
        private final String body;

        TestEndpoint(int status, String body) throws IOException {
            this.status = status;
            this.body = body;
            this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(null);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            hits.incrementAndGet();
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (var out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }
    }

    private MockBukkitHarness env;
    private final List<TestEndpoint> endpoints = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        env = MockBukkitHarness.start("hsauth-mojang-");
    }

    @AfterEach
    void tearDown() {
        for (TestEndpoint e : endpoints) {
            e.stop();
        }
        endpoints.clear();
        if (env != null) {
            env.close();
        }
    }

    /** 起一个本地端点（由 tearDown 统一停止） */
    private TestEndpoint endpoint(int status, String body) throws IOException {
        TestEndpoint e = new TestEndpoint(status, body);
        endpoints.add(e);
        return e;
    }

    /**
     * 把验证端点配成给定的本地端点（顺序即尝试顺序），并收紧超时使失败路径快速返回。
     * <p>
     * 关键在于**直接替换 {@code PremiumConfig.sessionServerCandidates}**：生产实现总是把官方端点
     * 硬编码放在候选列表首位，而测试环境里官方端点是否可达、失败要多久都不确定（曾导致用例时通时不通）。
     * 替换成只含本地端点后，被测代码的第一个候选就是可确定性应答的本地服务器。
     */
    private void useMirrors(TestEndpoint... mirrors) {
        List<String> urls = new ArrayList<>();
        for (TestEndpoint m : mirrors) {
            urls.add(m.baseUrl());
        }
        env.writeConfig(yaml -> {
            yaml.set("premium.timeout-seconds", 2);
            yaml.set("premium.verify-deadline-ms", 30000);
            yaml.set("premium.max-retries", 1);
            yaml.set("premium.retry-interval-ms", 10);
        });
        // 注入候选列表：绕开"官方端点恒在首位"，使本地端点成为确定性的第一个候选
        MockBukkitHarness.inject(org.howsauth.plugin.config.PremiumConfig.class,
                "sessionServerCandidates", env.config().premium(), List.copyOf(urls));
    }

    /** 200 的响应体：给定 id（去横线）与可选皮肤 properties */
    private static String joinedBody(UUID uuid, String textures) {
        String id = uuid.toString().replace("-", "");
        if (textures == null) {
            return "{\"id\":\"" + id + "\"}";
        }
        return "{\"id\":\"" + id + "\",\"name\":\"Notch\",\"properties\":[{\"name\":\"textures\",\"value\":\""
                + textures + "\"}]}";
    }

    // ---------- 成功与解析 ----------

    /** 200 + 合法响应：解析出 UUID 与皮肤 properties */
    @Test
    void joinedPlayerIsParsedFromA200Response() throws Exception {
        UUID expected = UUID.randomUUID();
        TestEndpoint mirror = endpoint(200, joinedBody(expected, "abc"));
        useMirrors(mirror);

        MojangClient client = new MojangClient(env.plugin());
        try {
            Optional<PremiumProfile> result = client.hasJoined("serverhash", "Notch").get();

            assertTrue(result.isPresent(), "a 200 with an id must be treated as a joined premium player");
            assertEquals(expected, result.get().uuid(), "the dash-less id must be parsed into a UUID");
            assertNotNull(result.get().propertiesJson(), "skin properties must be captured");
            assertTrue(result.get().propertiesJson().contains("abc"), "the textures value must survive");
        } finally {
            client.close();
        }
    }

    /** 200 但缺 id 字段：不得视为正版通过 */
    @Test
    void a200WithoutIdIsTreatedAsNotJoined() throws Exception {
        TestEndpoint mirror = endpoint(200, "{\"name\":\"Notch\"}");
        useMirrors(mirror);

        MojangClient client = new MojangClient(env.plugin());
        try {
            assertFalse(client.hasJoined("serverhash", "Notch").get().isPresent(),
                    "a response without an id must not authenticate the player");
        } finally {
            client.close();
        }
    }

    /** 200 但响应体是畸形 JSON：不得让 future 异常完成（否则调用方回调不执行、玩家卡在加密阶段） */
    @Test
    void malformedJsonDegradesToNotJoinedWithoutFailingTheFuture() throws Exception {
        TestEndpoint mirror = endpoint(200, "this is not json at all");
        useMirrors(mirror);

        MojangClient client = new MojangClient(env.plugin());
        try {
            assertFalse(client.hasJoined("serverhash", "Notch").get().isPresent(),
                    "malformed JSON must degrade to not-joined instead of throwing");
        } finally {
            client.close();
        }
    }

    /** 缺 properties 时按无皮肤处理（返回 null，调用方不得 NPE） */
    @Test
    void missingPropertiesYieldsNullProperties() throws Exception {
        TestEndpoint mirror = endpoint(200, joinedBody(UUID.randomUUID(), null));
        useMirrors(mirror);

        MojangClient client = new MojangClient(env.plugin());
        try {
            Optional<PremiumProfile> result = client.hasJoined("serverhash", "Notch").get();
            assertTrue(result.isPresent(), "an id alone is enough to authenticate");
            assertNull(result.get().propertiesJson(),
                    "a missing properties field must surface as null (no skin data)");
        } finally {
            client.close();
        }
    }

    // ---------- 确定否定与重试 ----------

    /** 204：未加入是确定结论 */
    @Test
    void a204MeansNotJoined() throws Exception {
        TestEndpoint mirror = endpoint(204, "");
        useMirrors(mirror);

        MojangClient client = new MojangClient(env.plugin());
        try {
            assertFalse(client.hasJoined("serverhash", "Notch").get().isPresent(),
                    "HTTP 204 must mean not joined");
        } finally {
            client.close();
        }
    }

    /** 403 等非可重试状态码：未加入 */
    @Test
    void nonRetryableStatusIsNotRetried() throws Exception {
        TestEndpoint mirror = endpoint(403, "forbidden");
        useMirrors(mirror);

        MojangClient client = new MojangClient(env.plugin());
        try {
            Optional<PremiumProfile> result = client.hasJoined("serverhash", "Notch").get();
            assertFalse(result.isPresent(),
                    "a non-retryable status must not authenticate the player");
        } finally {
            client.close();
        }
    }

    /** 端点顺序无关：首个端点过载（可重试状态耗尽）时应能由后续健康端点给出结论 */
    @Test
    void exhaustedOverloadedEndpointFallsBackToTheNextEndpoint() throws Exception {
        TestEndpoint overloaded = endpoint(429, "slow down");
        UUID expected = UUID.randomUUID();
        TestEndpoint healthy = endpoint(200, joinedBody(expected, "skin"));
        useMirrors(overloaded, healthy);

        MojangClient client = new MojangClient(env.plugin());
        try {
            Optional<PremiumProfile> result = client.hasJoined("serverhash", "Notch").get();

            assertTrue(result.isPresent(), "a healthy later endpoint must still authenticate the player");
            assertEquals(expected, result.get().uuid(), "the answer must come from the healthy mirror");
        } finally {
            client.close();
        }
    }

    /** 端点不可达：立即回退下一个，不在本端点重试 */
    @Test
    void unreachableEndpointFallsBackImmediately() throws Exception {
        // 先起端点拿到端口号再立刻停掉，保证该地址必然连不上
        TestEndpoint dead = endpoint(200, "{}");
        dead.stop();
        UUID expected = UUID.randomUUID();
        TestEndpoint healthy = endpoint(200, joinedBody(expected, "skin"));
        useMirrors(dead, healthy);

        MojangClient client = new MojangClient(env.plugin());
        try {
            Optional<PremiumProfile> result = client.hasJoined("serverhash", "Notch").get();
            assertTrue(result.isPresent(), "an unreachable endpoint must not stop the fallback chain");
            assertEquals(expected, result.get().uuid(), "the reachable mirror must answer");
        } finally {
            client.close();
        }
    }

    /** 所有端点均不可用：返回空，调用方按验证失败踢出（而不是让 future 异常完成） */
    @Test
    void allEndpointsUnavailableYieldsEmpty() throws Exception {
        TestEndpoint overloaded = endpoint(429, "slow down");
        useMirrors(overloaded);

        MojangClient client = new MojangClient(env.plugin());
        try {
            assertFalse(client.hasJoined("serverhash", "Notch").get().isPresent(),
                    "when every endpoint is unavailable the player must not be authenticated");
        } finally {
            client.close();
        }
    }

    /** 没有配置任何镜像时（仅官方端点，测试环境不可达）也应安全降级为返回空，而非抛异常 */
    @Test
    void noConfiguredMirrorStillDegradesSafely() {
        env.writeConfig(yaml -> {
            yaml.set("premium.session-server-mirrors", List.of());
            yaml.set("premium.timeout-seconds", 1);
            yaml.set("premium.verify-deadline-ms", 3000);
            yaml.set("premium.max-retries", 0);
        });

        MojangClient client = new MojangClient(env.plugin());
        try {
            assertFalse(client.hasJoined("serverhash", "Notch").join().isPresent(),
                    "with no reachable endpoint the answer must be empty");
        } finally {
            client.close();
        }
    }
}
