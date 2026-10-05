package org.howsauth.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.howsauth.plugin.auth.AuthManager;
import org.howsauth.plugin.auth.AuthManager.LoginResult;
import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.support.MockBukkitHarness;
import org.howsauth.plugin.support.MockBukkitHarness.TestPlayerMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 暴力破解防护（FailProtection）：连续失败达阈值踢出、踢出期内拒绝正确密码、踢出到期解除、
 * 失败计数跨连接保留（重连不能重置进度）。
 * <p>
 * 独立于 {@code LongRunStabilityTest}——后者为规避失败计数干扰已关闭该功能。
 * 计数过期重置行为由 {@code FailProtectionResetTest} 覆盖（需要更短的过期窗口）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FailProtectionTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final int KICK_SECONDS = 1;

    private MockBukkitHarness env;
    private AuthManager auth;

    @BeforeAll
    void bootstrap() throws Exception {
        env = MockBukkitHarness.start("hsauth-failprot-test", config -> {
            MockBukkitHarness.inject(ConfigManager.class, "bcryptCost", config, 4);
            MockBukkitHarness.inject(ConfigManager.class, "failProtectionEnabled", config, true);
            MockBukkitHarness.inject(ConfigManager.class, "failMaxAttempts", config, MAX_ATTEMPTS);
            MockBukkitHarness.inject(ConfigManager.class, "failKickDuration", config, KICK_SECONDS);
            // 过期窗口远大于用例时长：确保本节用例不会因计数过期而重置
            MockBukkitHarness.inject(ConfigManager.class, "failProtectionResetSeconds", config, 60);
        });
        auth = env.auth();
    }

    @AfterAll
    void teardown() {
        if (env != null) {
            env.close();
        }
    }

    /** 未达阈值不踢出；达到阈值进入踢出期，且踢出期内即使密码正确也被拒 */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void kicksAfterConsecutiveFailures() throws Exception {
        UUID uuid = UUID.randomUUID();
        String ip = "10.50.0.1";
        String password = "pw";
        assertTrue(env.accounts().registerConfig(uuid, "kick1", password, ip), "registration must succeed");

        // 前 MAX_ATTEMPTS-1 次失败：累计但不应踢出
        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            assertEquals(LoginResult.FAILED, env.loginBlocking(uuid, "wrong", ip),
                    "wrong password attempt " + attempt + " must fail login");
            assertFalse(env.failProtection().isKicked(uuid), "failure " + attempt + " must stay below the threshold, so no kick");
        }

        // 第 MAX_ATTEMPTS 次失败：达阈值，进入踢出期
        MockBukkitHarness.LoginOutcome threshold = env.login(uuid, "wrong", ip);
        assertEquals(LoginResult.FAILED, threshold.result(), "the threshold-reaching failure must still return FAILED");
        assertTrue(env.failProtection().isKicked(uuid), "reaching the threshold must start the kick window");
        assertTrue(env.failProtection().getKickRemaining(uuid) > 0, "remaining kick time must be greater than 0");

        // 踢出期内密码正确也必须被拒（isKicked 是登录入口的第一道判断）
        MockBukkitHarness.LoginOutcome whileKicked = env.login(uuid, password, ip);
        assertEquals(LoginResult.FAILED, whileKicked.result(), "login must be rejected while the kick window is active, even with the correct password");
        assertTrue(whileKicked.kickRemainingSeconds() > 0, "remaining kick seconds must be reported during the kick window");
    }

    /** 踢出期到期后自动解除，正确密码可重新登录 */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void kickExpiresAfterDuration() throws Exception {
        UUID uuid = UUID.randomUUID();
        String ip = "10.50.0.2";
        String password = "pw";
        assertTrue(env.accounts().registerConfig(uuid, "kick2", password, ip), "registration must succeed");

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            assertEquals(LoginResult.FAILED, env.loginBlocking(uuid, "wrong", ip), "wrong password must fail login");
        }
        assertTrue(env.failProtection().isKicked(uuid), "reaching the threshold must start the kick window");

        Thread.sleep(KICK_SECONDS * 1000L + 200L);
        assertFalse(env.failProtection().isKicked(uuid), "the kick must be lifted once its duration expires (lazy cleanup of expired records)");
        assertEquals(LoginResult.SUCCESS, env.loginBlocking(uuid, password, ip), "the correct password must work again after the kick is lifted");
    }

    /** 失败计数跨连接保留：重连（clearSession）不能重置累计进度，否则可反复重连绕过阈值 */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void failureCountsSurviveSessionClear() throws Exception {
        TestPlayerMock player = env.addScheduledPlayer("kick3");
        UUID uuid = player.getUniqueId();
        String ip = "10.50.0.3";
        assertTrue(env.accounts().registerConfig(uuid, "kick3", "pw", ip), "registration must succeed");

        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            assertEquals(LoginResult.FAILED, env.loginBlocking(uuid, "wrong", ip), "wrong password must fail login");
        }
        assertFalse(env.failProtection().isKicked(uuid), "below-threshold failures must not kick");

        // 模拟玩家退出重连：会话状态清理不应丢弃失败计数
        auth.clearSession(player);
        assertEquals(LoginResult.FAILED, env.loginBlocking(uuid, "wrong", ip), "wrong password must still fail after reconnect");
        assertTrue(env.failProtection().isKicked(uuid), "failure count must survive session clear: threshold reached after reconnect must kick");
    }
}
