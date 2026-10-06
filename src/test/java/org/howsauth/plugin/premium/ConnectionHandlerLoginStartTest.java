package org.howsauth.plugin.premium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.retrooper.packetevents.event.LoginEventScaffold;
import com.github.retrooper.packetevents.event.LoginEventScaffold.FakeClient;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import org.howsauth.plugin.support.MockBukkitHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

/**
 * 登录握手（{@link ConnectionHandler}）状态机回归，由 {@link LoginEventScaffold} 驱动。
 * <p>
 * 只覆盖在解密/发包之前返回的分支：它们不依赖 LoginStart 的包体解码，也不需要真实加密材料。
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ConnectionHandlerLoginStartTest {

    private MockBukkitHarness env;
    private ConnectionHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        env = MockBukkitHarness.start("hsauth-handshake-");
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

    /** 驱动 EncryptionResponse 处理（入口在 PremiumHandshake，经私有字段反射取得） */
    private void handleEncryptionResponse(PacketReceiveEvent event) {
        PremiumHandshake handshake = LoginEventScaffold.readField(handler, "handshake");
        LoginEventScaffold.invokePrivate(handshake, "handleEncryptionResponse",
                new Class<?>[]{PacketReceiveEvent.class}, event);
    }

    /** 反射拿到私有会话注册表，用于注入会话状态 */
    private LoginSessions sessions() {
        return LoginEventScaffold.readField(handler, "sessions");
    }

    /** 为给定 channel 登记一个会话（阶段由调用方推进） */
    private void putSession(FakeClient client, SessionContext session) {
        sessions().put(client.channel(), session);
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
        putSession(client, session);
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
        putSession(client, session);

        PacketReceiveEvent event = LoginEventScaffold.encryptionResponse(client);
        handleEncryptionResponse(event);

        assertTrue(event.isCancelled(),
                "an encryption response at the expected stage must be cancelled");
    }
}
