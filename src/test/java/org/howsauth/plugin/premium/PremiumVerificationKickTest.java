package org.howsauth.plugin.premium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.retrooper.packetevents.event.LoginEventScaffold;
import com.github.retrooper.packetevents.event.LoginEventScaffold.FakeClient;
import com.github.retrooper.packetevents.protocol.player.User;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.netty.channel.Channel;
import org.howsauth.plugin.support.MockBukkitHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 验证失败必须真正踢出（端到端回归）。
 * <p>
 * 这是为一次真实退化补的测试：{@code ResultHandler.kick} 曾被改成空实现，
 * 于是验证失败的玩家既收不到断开提示也不会被断连——编译通过、既有测试全绿，
 * 因为缺口正好落在 hasJoined 之后的那一层。
 * <p>
 * 本测试驱动**完整的真实链路**：真实 {@link MojangClient} 打进程内 HTTP 端点并返回 204
 * 「未加入」，真实 {@link PremiumVerifier} 跑完 hasJoined 回调与分支决策，回调最终落到
 * 一个会真正关闭通道的处理器上。因此"决策不踢"或"踢了但没人执行关闭"都会让它失败。
 * <p>
 * <b>覆盖边界</b>：处理器由本测试实现（{@link ClosingHandler}），所以它锁住的是
 * "PremiumVerifier 决定踢出 + 处理器执行关闭"这条契约；{@code ConnectionHandler} 里那个匿名
 * 处理器实现未被本测试覆盖（它不可直接实例化）。若要把那一层也纳入，需要把该匿名实现提为具名类。
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PremiumVerificationKickTest {

    /** 本地会话验证端点：固定返回给定状态码与响应体 */
    private static final class TestEndpoint {
        final HttpServer server;
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

    /**
     * 会真正关闭通道的处理器：模拟 {@code ConnectionHandler} 在踢出时应做的事。
     * <p>
     * 关闭通道是本测试的观测点——它把"决策说踢出"与"连接确实被断"连成可断言的一步。
     */
    private static final class ClosingHandler implements PremiumVerifier.ResultHandler {
        final List<String> calls = new ArrayList<>();

        @Override
        public void proceedWithLogin(Channel channel, User user, SessionContext session,
                                     java.util.UUID uuid, String username, String properties) {
            calls.add("proceedWithLogin");
        }

        @Override
        public void kick(Channel channel, User user) {
            calls.add("kick");
            channel.close();
        }

        @Override
        public void failAsyncLogin(Channel channel, User user, SessionContext session, String reason) {
            calls.add("failAsyncLogin");
        }
    }

    private MockBukkitHarness env;
    private ClosingHandler handler;
    private final List<TestEndpoint> endpoints = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        env = MockBukkitHarness.start("hsauth-kick-");
        handler = new ClosingHandler();
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
     * 把验证端点替换为给定的本地端点。
     * <p>
     * 与 {@code MojangClientHasJoinedTest} 同一思路：替换 {@code PremiumConfig.sessionServerCandidates}，
     * 绕开"官方端点恒在候选首位"带来的不确定性，使请求必然落到本地服务器。
     */
    private void useMirrors(TestEndpoint... mirrors) {
        useMirrorsWith(null, mirrors);
    }

    /**
     * 同上，并在同一次 {@code writeConfig} 里附加配置覆盖。
     * <p>
     * 必须合成一次：{@code writeConfig} 会重新 {@code load()} 配置并重建领域对象，
     * 若在其后再注入候选表，注入会被随后的 reload 冲掉（回退判定就会去打真实 Mojang 端点）。
     */
    private void useMirrorsWith(java.util.function.Consumer<org.bukkit.configuration.file.YamlConfiguration> overlay,
                                TestEndpoint... mirrors) {
        List<String> urls = new ArrayList<>();
        for (TestEndpoint m : mirrors) {
            urls.add(m.baseUrl());
        }
        env.writeConfig(yaml -> {
            yaml.set("premium.timeout-seconds", 3);
            yaml.set("premium.verify-deadline-ms", 30000);
            yaml.set("premium.max-retries", 0);
            yaml.set("premium.retry-interval-ms", 10);
            if (overlay != null) {
                overlay.accept(yaml);
            }
        });
        MockBukkitHarness.inject(org.howsauth.plugin.config.PremiumConfig.class,
                "sessionServerCandidates", env.config().premium(), List.copyOf(urls));
    }

    /** 等待条件成立，超时即失败（异步链路用轮询观察，不依赖内部锁） */
    private static void awaitTrue(String what, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    /**
     * 端点答复"玩家未加入会话"（204）时必须踢出：处理器被调用，且连接确实被关闭。
     * <p>
     * 用 204 而非可重试状态码：它是确定结论，链路立即走到踢出分支，不经历重试与端点回退。
     */
    @Test
    void notJoinedResponseKicksAndClosesTheConnection() throws Exception {
        TestEndpoint mirror = endpoint(204, "");
        useMirrors(mirror);

        FakeClient client = LoginEventScaffold.newClient("NotJoinedUser");
        Channel channel = client.channel();
        SessionContext session = new SessionContext();
        session.username("NotJoinedUser");
        session.ip("203.0.113.30");
        // premiumAccount=false：非数据库正版账号，验证失败时无可回退路径，直接踢出
        session.premiumAccount(false);

        PremiumVerifier verifier = new PremiumVerifier(env.plugin(), new DataService(env.data(), env.config()),
                env.data(), new MojangClient(env.plugin()), handler);

        // 直接驱动验证：LoginStart 包体解码路径受限，而 verify 是包内可见的
        verifier.verify(channel, client.user(), session, new byte[]{1, 2, 3, 4},
                new byte[]{5, 6, 7, 8});

        awaitTrue("handler kicked", () -> handler.calls.contains("kick"));
        assertEquals(List.of("kick"), handler.calls,
                "a failed verification without fallback must kick, and must not call other callbacks");
        awaitTrue("connection closed", () -> !channel.isOpen());
        assertFalse(channel.isOpen(), "kicking must actually close the connection");
    }

    /** 正版验证失败但允许密码回退时，不得踢出（应放行走密码登录） */
    @Test
    void verificationFailureWithFallbackDoesNotKick() throws Exception {
        TestEndpoint mirror = endpoint(204, "");
        // 与镜像注入合成一次配置写入：分开写会因 reload 冲掉候选表注入
        // 无密码的正版账号只有在 reject-no-auth-account 关闭时才允许回退
        useMirrorsWith(yaml -> {
            yaml.set("premium.fallback.enabled", true);
            yaml.set("protection.reject-no-auth-account", false);
        }, mirror);

        // 造一个数据库正版账号：名字索引与假客户端 UUID 必须一致，回退分支才能命中
        FakeClient client = LoginEventScaffold.newClient("FallbackUser");
        java.util.UUID premiumUuid = client.user().getUUID();
        env.data().createPremiumPlayer(premiumUuid, "FallbackUser", "203.0.113.31", "[]");

        Channel channel = client.channel();
        SessionContext session = new SessionContext();
        session.username("FallbackUser");
        session.ip("203.0.113.31");
        session.premiumAccount(true);

        PremiumVerifier verifier = new PremiumVerifier(env.plugin(), new DataService(env.data(), env.config()),
                env.data(), new MojangClient(env.plugin()), handler);

        verifier.verify(channel, client.user(), session, new byte[]{1, 2, 3, 4},
                new byte[]{5, 6, 7, 8});

        awaitTrue("a callback fired", () -> !handler.calls.isEmpty());
        assertFalse(handler.calls.contains("kick"),
                "an account eligible for password fallback must not be kicked");
    }
}
