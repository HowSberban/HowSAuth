package org.howsauth.plugin.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.auth.PasswordValidator;
import org.howsauth.plugin.support.MockBukkitHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * 配置读取与校验：出厂默认值是否符合预期，以及非法配置值的回退/钳制。
 * <p>
 * 非法值回退属于安全相关路径（例如哈希算法退化、数据库类型误配），
 * 出厂默认值断言用于防止"默认配置被悄悄改成弱参数"。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConfigValidationTest {

    private MockBukkitHarness env;

    @BeforeAll
    void bootstrap() throws Exception {
        env = MockBukkitHarness.start("hsauth-config-test");
    }

    @AfterAll
    void teardown() {
        if (env != null) {
            env.close();
        }
    }

    /** 出厂默认值：安全相关的默认项应符合预期 */
    @Test
    @Order(1)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void shippedDefaultsAreSafe() {
        ConfigManager config = env.config();

        // 哈希算法与 cost 的默认值断言在 HighCostAuthTest
        assertEquals(6, config.minPasswordLength(), "default minimum password length must be 6");
        assertEquals(32, config.maxPasswordLength(), "default maximum password length must be 32");
        assertNotNull(config.passwordPattern(), "a password character pattern must be configured by default");
        assertEquals("sqlite", config.databaseType(), "default database type must be sqlite");
        assertEquals(3, config.maxAccountsPerIp(), "default per-IP account limit must be 3");
        assertTrue(config.failProtectionEnabled(), "brute-force protection must be enabled by default");
        assertEquals(3, config.failMaxAttempts(), "default maximum failed attempts must be 3");
        assertTrue(config.rejectNoAuthAccount(), "credential-less accounts must be rejected by default");
        assertFalse(config.pearlEnabled(), "pearl custody must be disabled by default");
        assertTrue(config.pearlReturnEntity(), "pearls must be returned as an entity by default (return: entity)");
        assertEquals(120, config.loginTimeout(), "default login timeout must be 120 seconds");
    }

    /** 非 ASCII 密码（中文“密码”×3）：用字符码构造，源码不含中文 */
    private static final String NON_ASCII_PASSWORD =
            new String(new char[]{0x5bc6, 0x7801, 0x5bc6, 0x7801, 0x5bc6, 0x7801});

    /** 密码规则校验：长度上下界与字符规则，且边界值应通过 */
    @Test
    @Order(2)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void passwordRulesAreEnforced() {
        HowSAuth plugin = env.plugin();
        ConfigManager config = env.config();

        assertNull(PasswordValidator.invalidMessage(plugin, "zh_CN", "abcdef"),
                "a valid 6-character alphanumeric password must be accepted");
        assertNull(PasswordValidator.invalidMessage(plugin, "zh_CN", "a".repeat(config.minPasswordLength())),
                "the minimum-length boundary must be accepted");
        assertNull(PasswordValidator.invalidMessage(plugin, "zh_CN", "a".repeat(config.maxPasswordLength())),
                "the maximum-length boundary must be accepted");
        assertNotNull(PasswordValidator.invalidMessage(plugin, "zh_CN", "abc"),
                "a password shorter than the minimum length must be rejected");
        assertNotNull(PasswordValidator.invalidMessage(plugin, "zh_CN", "a".repeat(config.maxPasswordLength() + 1)),
                "a password longer than the maximum length must be rejected");
        assertNotNull(PasswordValidator.invalidMessage(plugin, "zh_CN", "abc def"),
                "a space outside the allowed character set must be rejected");
        assertNotNull(PasswordValidator.invalidMessage(plugin, "zh_CN", NON_ASCII_PASSWORD),
                "non-ASCII password must be rejected");
    }

    /**
     * 非法配置值回退：非法哈希算法、数据库类型、珍珠返还方式应回退到安全/默认值，
     * 越界长度应被钳制（而不是让非法值进入运行时）。
     * <p>
     * 本用例会改写 config.yml，故置于最后执行。
     */
    @Test
    @Order(3)
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void invalidValuesFallBackToSafeDefaults() throws Exception {
        HowSAuth plugin = env.plugin();
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        assertTrue(configFile.exists(), "the config file must have been generated");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile);
        yaml.set("password.hash", "md5");
        yaml.set("database.type", "oracle");
        yaml.set("pearl.return", "bogus");
        yaml.set("password.min-length", 2);
        yaml.set("password.max-length", 999);
        yaml.save(configFile);

        ConfigManager reloaded = new ConfigManager(plugin);

        assertEquals("bcrypt", reloaded.passwordHashAlgorithm(), "invalid hash algorithm must fall back to bcrypt");
        assertEquals("sqlite", reloaded.databaseType(), "invalid database type must fall back to sqlite");
        assertFalse(reloaded.pearlReturnEntity(), "invalid pearl return mode must fall back to item");
        assertEquals(4, reloaded.minPasswordLength(), "a too-small minimum password length must be clamped to 4");
        assertEquals(128, reloaded.maxPasswordLength(), "a too-large maximum password length must be clamped to 128");
    }

}
