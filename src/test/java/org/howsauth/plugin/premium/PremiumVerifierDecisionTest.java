package org.howsauth.plugin.premium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.retrooper.packetevents.event.LoginEventScaffold;
import com.github.retrooper.packetevents.event.LoginEventScaffold.FakeClient;
import com.github.retrooper.packetevents.protocol.player.User;
import io.netty.channel.Channel;
import org.howsauth.plugin.support.MockBukkitHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * {@link PremiumVerifier} 的结果分支回归：验证失败时必须踢出。
 * <p>
 * 这里覆盖的是 hasJoined 之后的分支决策。之所以值得单独测：该分支曾出现过
 * "接口新增了回调方法、但实现留空"的退化——{@code handler.kick(...)} 变成空操作后，
 * 玩家既收不到断开提示也不会被断连，而编译与既有测试（只覆盖握手早退分支）都发现不了。
 * <p>
 * 直接用假的 {@link PremiumVerifier.ResultHandler} 记录回调，断言"走了哪个分支"，
 * 因此不需要真实 Mojang 响应，也不需要加密材料。
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PremiumVerifierDecisionTest {

    /** 记录被调用回调的假处理器：验证失败分支应调用 kick，回退分支应调用 proceedWithLogin */
    private static final class RecordingHandler implements PremiumVerifier.ResultHandler {
        final List<String> calls = new ArrayList<>();
        String kickedUsername;
        UUID proceededUuid;

        @Override
        public void proceedWithLogin(Channel channel, User user, SessionContext session,
                                     UUID uuid, String username, String properties) {
            calls.add("proceedWithLogin");
            proceededUuid = uuid;
        }

        @Override
        public void kick(Channel channel, User user) {
            calls.add("kick");
            kickedUsername = user.getName();
        }

        @Override
        public void failAsyncLogin(Channel channel, User user, SessionContext session, String reason) {
            calls.add("failAsyncLogin");
        }
    }

    private MockBukkitHarness env;
    private PremiumVerifier verifier;
    private RecordingHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        env = MockBukkitHarness.start("hsauth-verifier-");
        handler = new RecordingHandler();
        verifier = new PremiumVerifier(env.plugin(), new DataService(env.data(), env.config()),
                env.data(), new MojangClient(env.plugin()), handler);
    }

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    /** 无正版记录、且非升级尝试：验证失败必须踢出（不得静默放行） */
    @Test
    void verificationFailureKicksWhenNoPremiumAccountExists() {
        FakeClient client = LoginEventScaffold.newClient("NoRecordUser");
        SessionContext session = new SessionContext();
        session.username("NoRecordUser");
        session.ip("203.0.113.20");
        // premiumAccount=false 使 premiumAccountByName 直接返回 null（非数据库正版账号）
        session.premiumAccount(false);

        LoginEventScaffold.invokePrivate(verifier, "handleVerificationFailed",
                new Class<?>[]{Channel.class, User.class, SessionContext.class, String.class},
                client.channel(), client.user(), session, "NoRecordUser");

        assertEquals(List.of("kick"), handler.calls,
                "a failed verification without fallback must kick, not silently continue");
        assertEquals("NoRecordUser", handler.kickedUsername, "kick must receive the same user");
        assertNull(handler.proceededUuid, "the player must not be let through");
    }

    /** 升级尝试失败：先清除升级标记，再走同一踢出兜底（不得重复回调） */
    @Test
    void verificationFailureOnUpgradeAttemptStillKicks() {
        FakeClient client = LoginEventScaffold.newClient("UpgradeUser");
        SessionContext session = new SessionContext();
        session.username("UpgradeUser");
        session.ip("203.0.113.21");
        session.upgradeAttempt(true);
        session.offlineUuid(UUID.randomUUID());

        LoginEventScaffold.invokePrivate(verifier, "handleVerificationFailed",
                new Class<?>[]{Channel.class, User.class, SessionContext.class, String.class},
                client.channel(), client.user(), session, "UpgradeUser");

        assertEquals(List.of("kick"), handler.calls,
                "an upgrade attempt that fails verification must still be kicked exactly once");
    }
}
