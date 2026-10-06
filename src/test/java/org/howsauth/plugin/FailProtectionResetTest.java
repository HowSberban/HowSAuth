package org.howsauth.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.howsauth.plugin.auth.AuthManager;
import org.howsauth.plugin.auth.AuthManager.LoginResult;
import org.howsauth.plugin.config.LoginConfig;
import org.howsauth.plugin.config.PasswordConfig;
import org.howsauth.plugin.support.MockBukkitHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 失败计数过期重置（login.fail-protection.reset-seconds）：
 * 距上次失败超过该时长后计数清零，避免长期不清误伤正常玩家（如偶尔打错密码的玩家被累计踢出）。
 * <p>
 * 单独成类的原因：该行为需要很短的过期窗口与真实时间推进（AuthManager 的失败计数走系统时钟），
 * 而 {@code FailProtectionTest} 需要足够长的窗口以避免用例执行期间计数被重置。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FailProtectionResetTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final int RESET_SECONDS = 1;

    private MockBukkitHarness env;
    private AuthManager auth;

    @BeforeAll
    void bootstrap() throws Exception {
        env = MockBukkitHarness.start("hsauth-failreset-test", config -> {
            MockBukkitHarness.inject(PasswordConfig.class, "bcryptCost", config.password(), 4);
            MockBukkitHarness.inject(LoginConfig.class, "failProtectionEnabled", config.login(), true);
            MockBukkitHarness.inject(LoginConfig.class, "failMaxAttempts", config.login(), MAX_ATTEMPTS);
            MockBukkitHarness.inject(LoginConfig.class, "failKickDuration", config.login(), 1);
            MockBukkitHarness.inject(LoginConfig.class, "failProtectionResetSeconds", config.login(), RESET_SECONDS);
        });
        auth = env.auth();
    }

    @AfterAll
    void teardown() {
        if (env != null) {
            env.close();
        }
    }

    /** 过期窗口内连续失败仍累计并最终踢出；越过窗口后计数重新开始，不再因历史失败被踢 */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void failureCountResetsAfterResetWindow() throws Exception {
        UUID uuid = UUID.randomUUID();
        String ip = "10.51.0.1";
        String password = "pw";
        assertTrue(env.accounts().registerConfig(uuid, "reset1", password, ip), "registration must succeed");

        // 窗口内失败 2 次：未达阈值
        for (int attempt = 1; attempt < MAX_ATTEMPTS; attempt++) {
            assertEquals(LoginResult.FAILED, env.loginBlocking(uuid, "wrong", ip), "wrong password must fail login");
        }
        assertFalse(env.failProtection().isKicked(uuid), "below-threshold failures (" + (MAX_ATTEMPTS - 1) + ") must not kick");

        // 越过过期窗口：计数应清零（这里同时触发一次周期清理，验证清理路径不误删）
        Thread.sleep(RESET_SECONDS * 1000L + 200L);
        auth.cleanupExpiredStates();

        // 关键断言：这是"新窗口的第 1 次失败"。若计数未过期重置，累计值将达到阈值并立即踢出
        assertEquals(LoginResult.FAILED, env.loginBlocking(uuid, "wrong", ip), "wrong password must fail login");
        assertFalse(env.failProtection().isKicked(uuid), "failure count must restart after the reset window, so no kick");

        // 新窗口内再失败一次：累计 2 次，仍未达阈值
        assertEquals(LoginResult.FAILED, env.loginBlocking(uuid, "wrong", ip), "wrong password must fail login");
        assertFalse(env.failProtection().isKicked(uuid), "accumulated failures (" + (MAX_ATTEMPTS - 1) + ") in the new window must still not kick");
    }
}
