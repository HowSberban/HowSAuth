package org.howsauth.plugin.premium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.retrooper.packetevents.event.LoginEventScaffold;
import com.github.retrooper.packetevents.event.LoginEventScaffold.FakeClient;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import io.netty.channel.Channel;
import org.howsauth.plugin.support.MockBukkitHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 登录握手（{@link ConnectionHandler}）状态机回归，由 {@link LoginEventScaffold} 驱动。
 * <p>
 * 脚手架使用真实 {@code EmbeddedChannel}（真 pipeline + 内联 EventLoop）、真实 {@code User}
 * 与真实 {@code PacketReceiveEvent}，因此被测的是生产代码本身而不是替身。
 * <p>
 * 本类只覆盖<b>早退分支</b>：这些分支在解密/发包之前返回，不依赖 LoginStart 的包体解码，
 * 因此既不需要真实加密材料，也不会引入等待，天然规避时序漂移。
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ConnectionHandlerLoginStartTest {

    private MockBukkitHarness env;
    private ConnectionHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        env = MockBukkitHarness.start("hsauth-handshake-");
        // dataService 只用于构造 handler，无需保存为字段
        DataService dataService = new DataService(env.data(), env.config());
        handler = new ConnectionHandler(env.plugin(), dataService, new MojangClient(env.plugin()),
                new PlayerInjector(env.plugin()), env.data());
    }

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    /** 反射调用 handleEncryptionResponse（可见性不为测试放宽） */
    private void handleEncryptionResponse(PacketReceiveEvent event) {
        LoginEventScaffold.invokePrivate(handler, "handleEncryptionResponse",
                new Class<?>[]{PacketReceiveEvent.class}, event);
    }

    /** 反射拿到私有 sessions 表，用于注入会话状态（readField 带类型参数，无需强制转换） */
    private Map<Channel, SessionContext> sessions() {
        return LoginEventScaffold.readField(handler, "sessions");
    }

    // ---------- handleEncryptionResponse ----------

    /** 无会话的 EncryptionResponse（非正版流程）：放行且不取消包 */
    @Test
    void encryptionResponseWithoutSessionPassesThrough() throws Exception {
        FakeClient client = LoginEventScaffold.newClient("NoSession");

        PacketReceiveEvent event = LoginEventScaffold.encryptionResponse(client);
        handleEncryptionResponse(event);

        assertFalse(event.isCancelled(),
                "an encryption response without a session must not be intercepted");
    }

    /** 会话阶段不匹配的 EncryptionResponse：放行且不取消包 */
    @Test
    void encryptionResponseWithWrongStagePassesThrough() throws Exception {
        FakeClient client = LoginEventScaffold.newClient("WrongStage");
        SessionContext session = new SessionContext();
        session.username("WrongStage");
        sessions().put(client.channel(), session);
        assertEquals(SessionContext.Stage.START, session.stage(), "precondition: a fresh session is at START");

        PacketReceiveEvent event = LoginEventScaffold.encryptionResponse(client);
        handleEncryptionResponse(event);

        assertFalse(event.isCancelled(),
                "an encryption response arriving before the encryption request must not be intercepted");
    }

    /** 阶段匹配时会取消包（阻止服务端在 state=HELLO 下处理，否则会因状态不匹配抛异常） */
    @Test
    void encryptionResponseAtExpectedStageIsCancelled() throws Exception {
        FakeClient client = LoginEventScaffold.newClient("ExpectedStage");
        SessionContext session = new SessionContext();
        session.username("ExpectedStage");
        session.advance(SessionContext.Stage.START, SessionContext.Stage.WAITING_ENCRYPTION_RESPONSE);
        sessions().put(client.channel(), session);

        PacketReceiveEvent event = LoginEventScaffold.encryptionResponse(client);
        handleEncryptionResponse(event);

        assertTrue(event.isCancelled(),
                "an encryption response at the expected stage must be cancelled");
    }
}