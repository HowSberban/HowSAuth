package org.howsauth.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.howsauth.plugin.auth.AuthManager;
import org.howsauth.plugin.auth.AuthManager.LoginResult;
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
 * 生产强度哈希下的认证链路：不覆盖 bcrypt cost，直接使用出厂配置（cost 12），
 * 覆盖注册 → 登录 → 错误密码 → 改密 → 2FA 绑定/验证的完整流程。
 * <p>
 * 与 {@code LongRunStabilityTest} 的分工：压测套件用低成本换取规模与速度，
 * 本类用真实强度证明生产参数下链路可用，并防止默认配置被改成弱参数而无人察觉。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HighCostAuthTest {

    private static final int PRODUCTION_BCRYPT_COST = 12;

    private MockBukkitHarness env;
    private AuthManager auth;

    @BeforeAll
    void bootstrap() throws Exception {
        // 不调整 bcryptCost：验证出厂配置本身的生产强度
        env = MockBukkitHarness.start("hsauth-highcost-test");
        auth = env.auth();
    }

    @AfterAll
    void teardown() {
        if (env != null) {
            env.close();
        }
    }

    /** 出厂配置即生产强度：避免"测试用低成本"掩盖哈希强度退化 */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shippedConfigUsesProductionHashStrength() {
        assertEquals("bcrypt", env.config().passwordHashAlgorithm(), "the default hash algorithm must be bcrypt");
        assertEquals(PRODUCTION_BCRYPT_COST, env.config().bcryptCost(), "the default bcrypt cost must be 12");
    }

    /** 生产强度下的完整认证链路，包含 2FA 绑定与验证 */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void fullAuthFlowAtProductionCost() throws Exception {
        TestPlayerMock player = env.addScheduledPlayer("highcost1");
        UUID uuid = player.getUniqueId();
        String ip = "10.60.0.1";
        String password = "Prod-pw1";
        String changed = "Prod-pw2";

        assertTrue(auth.registerConfig(uuid, "highcost1", password, ip), "registration must succeed");
        assertEquals(LoginResult.SUCCESS, env.loginBlocking(uuid, password, ip), "the correct password must log in");
        assertEquals(LoginResult.FAILED, env.loginBlocking(uuid, "wrong-pw", ip), "wrong password must fail login");

        assertTrue(auth.forceChangePassword(uuid, changed), "password change must succeed");
        assertEquals(LoginResult.FAILED, env.loginBlocking(uuid, password, ip), "the old password must stop working");
        assertEquals(LoginResult.SUCCESS, env.loginBlocking(uuid, changed, ip), "the new password must log in");

        // 2FA 全链路（生产强度哈希不参与 TOTP，但绑定/验证走真实代码路径）
        String secret = env.twoFactor().setup(player);
        assertNotNull(secret, "setup must return a temporary secret");
        assertTrue(env.twoFactor().confirm(player, env.totpCode(secret)), "a valid code must confirm the 2FA setup");
        assertTrue(env.twoFactor().has2fa(uuid), "2FA must be active after binding");
        assertEquals(LoginResult.NEED_2FA, env.loginBlocking(uuid, changed, ip), "login must require 2FA after binding");
        assertTrue(auth.verify2faConfig(uuid, env.totpCode(secret), ip), "a valid 2FA code must pass");

        // 数据可持久化读取（确认生产强度哈希落库后仍可校验）
        assertNotNull(env.data().getPlayer(uuid), "account data must exist");
        assertEquals(LoginResult.NEED_2FA, env.loginBlocking(uuid, changed, ip), "the 2FA binding must stay in effect");
    }
}
