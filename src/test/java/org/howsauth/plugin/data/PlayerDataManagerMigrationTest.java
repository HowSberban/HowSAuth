package org.howsauth.plugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.howsauth.plugin.support.MockBukkitHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 账号身份迁移（离线↔正版）回归。
 * <p>
 * 迁移是本插件最不可逆的操作：它删除一个 UUID 的记录、写入另一个，且必须落在同一事务里
 * （否则中途崩溃会两条记录皆失，等于账号丢失）。两条路径此前各自复制了一遍事务骨架，
 * 现已收敛为 {@link PlayerDatabase#transactionalDeleteThenWrite}——本测试用于锁定收敛前后行为一致。
 * <p>
 * 断言分两层：
 * <ol>
 *   <li><b>内存态</b>：缓存项被移出/放入、名字索引更新、脏标记与重试计数不留残余；</li>
 *   <li><b>落库态</b>：排空写队列后重建一个连接同一 SQLite 文件的 {@link PlayerDataManager}，
 *       确认旧记录已删、新记录已写、字段逐个保留。</li>
 * </ol>
 */
class PlayerDataManagerMigrationTest {

    private static final String IP = "203.0.113.7";

    private MockBukkitHarness env;

    @BeforeEach
    void setUp() throws Exception {
        // bcrypt cost 覆盖为 4：本类不测哈希强度，但构造 ConfigManager 会读到该值
        env = MockBukkitHarness.start("hsauth-migration-",
                config -> MockBukkitHarness.inject(org.howsauth.plugin.config.PasswordConfig.class,
                        "bcryptCost", config.password(), 4));
    }

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    // ---------- 离线 → 正版（常规路径） ----------

    /** 离线号升级为正版：离线记录被替换，正版标记/皮肤/名字就地更新，密码置空 */
    @Test
    void migrateToPremiumReplacesTheOfflineRecord() {
        String name = "UpgradeUser";
        UUID offlineUuid = offlineUuidOf(name);
        UUID premiumUuid = UUID.randomUUID();
        PlayerDataManager data = env.data();
        data.createPlayer(offlineUuid, "hashed-pw", IP);
        data.getPlayer(offlineUuid).logoutLocation("world:1.0:64.0:2.0:0.0:0.0");
        data.getPlayer(offlineUuid).totpSecret("TOTPSECRET");
        data.getPlayer(offlineUuid).gameMode("SURVIVAL");
        data.saveNow(offlineUuid);

        assertTrue(data.migrateToPremium(offlineUuid, premiumUuid, name, IP, "{\"textures\":\"x\"}"),
                "a existing offline record must migrate");

        // 内存态：旧 UUID 不再存在，新 UUID 携带迁移过来的字段
        assertFalse(data.hasAccount(offlineUuid), "the offline record must be dropped from the cache");
        assertNull(data.getPlayer(offlineUuid), "the offline record must be gone");
        PlayerData premium = data.getPlayer(premiumUuid);
        assertNotNull(premium, "the premium record must exist");
        assertTrue(premium.premium(), "the migrated record must be flagged premium");
        assertEquals(name, premium.name(), "the name must be recorded");
        assertEquals("{\"textures\":\"x\"}", premium.properties(), "properties must be stored");
        assertEquals("world:1.0:64.0:2.0:0.0:0.0", premium.logoutLocation(), "logout location must carry over");
        assertEquals("TOTPSECRET", premium.totpSecret(), "2FA secret must carry over");
        assertEquals("SURVIVAL", premium.gameMode(), "game mode must carry over");
        assertEquals("", premium.passwordHash(), "premium accounts must have no password");
        assertEquals(premiumUuid, data.findUuidByName(name), "the premium name index must point at the new uuid");

        // 落库态：重建管理器，确认事务已提交且旧记录未被留下
        env.flushAndAwaitDbWrites();
        try (PlayerDataManager restarted = new PlayerDataManager(env.plugin())) {
            assertFalse(restarted.hasAccount(offlineUuid), "the offline row must be deleted from the database");
            assertTrue(restarted.hasAccount(premiumUuid), "the premium row must have been written in the same commit");
            PlayerData reloaded = restarted.getPlayer(premiumUuid);
            assertNotNull(reloaded, "the premium row must be persisted");
            assertEquals(name, reloaded.name(), "name must survive a restart");
            assertEquals("world:1.0:64.0:2.0:0.0:0.0", reloaded.logoutLocation(), "location must survive a restart");
            assertEquals("TOTPSECRET", reloaded.totpSecret(), "2FA secret must survive a restart");
            assertEquals(premiumUuid, restarted.findUuidByName(name), "the name index must be rebuilt on load");
        }
    }

    /** 离线记录不存在时报告未迁移（false），调用方据此跳过原版数据迁移 */
    @Test
    void migrateToPremiumReportsFalseWhenTheOfflineRecordIsMissing() {
        PlayerDataManager data = env.data();
        UUID offlineUuid = offlineUuidOf("GhostUser");
        UUID premiumUuid = UUID.randomUUID();

        assertFalse(data.migrateToPremium(offlineUuid, premiumUuid, "GhostUser", IP, "{}"),
                "a missing offline record must not migrate");
        assertNull(data.getPlayer(premiumUuid), "no premium record may be created");
    }

    // ---------- 离线 → 正版（目标已有正版记录的覆盖式迁移） ----------

    /**
     * 目标正版 UUID 已有记录：一律被离线号覆盖（新覆盖旧，无例外）——离线号的退出位置/2FA/游戏模式
     * 接管该正版 UUID，原正版记录的旧名字被摘除索引，密码置空；返回 true 让调用方随迁原版玩家数据。
     */
    @Test
    void migrateToPremiumOverwritesAnExistingPremiumRecord() {
        String offlineName = "OldOfflineName";
        String newName = "FreshPremiumName";
        UUID offlineUuid = offlineUuidOf(offlineName);
        UUID premiumUuid = UUID.randomUUID();
        PlayerDataManager data = env.data();

        // 离线号：带自己的密码/2FA/退出位置/游戏模式（这些将覆盖到原正版记录上）
        data.createPlayer(offlineUuid, "offline-pw", IP);
        PlayerData offline = data.getPlayer(offlineUuid);
        offline.totpSecret("OFFLINETOTP");
        offline.logoutLocation("offline_world:9.0:9.0:9.0:0.0:0.0");
        offline.gameMode("SURVIVAL");
        data.saveNow(offlineUuid);
        // 目标正版记录：先以前一个名字存在，带密码/2FA/位置/游戏模式（都将被覆盖）
        data.createPremiumPlayer(premiumUuid, "PreviousPremiumName", IP, "{\"old\":true}");
        PlayerData premium = data.getPlayer(premiumUuid);
        premium.passwordHash("premium-pw");
        premium.totpSecret("PREMIUMTOTP");
        premium.logoutLocation("premium_world:5.0:70.0:6.0:90.0:10.0");
        premium.gameMode("CREATIVE");
        data.saveNow(premiumUuid);

        assertTrue(data.migrateToPremium(offlineUuid, premiumUuid, newName, IP, "{\"new\":true}"),
                "overwriting an existing premium record must still report true (migrate vanilla data)");

        // 内存态：离线号作废；该正版 UUID 被离线号数据整体覆盖
        assertFalse(data.hasAccount(offlineUuid), "the offline record must be dropped");
        PlayerData merged = data.getPlayer(premiumUuid);
        assertNotNull(merged, "the overwritten premium record must exist");
        assertEquals(newName, merged.name(), "the name must be rebound");
        assertEquals("{\"new\":true}", merged.properties(), "properties must be replaced");
        assertTrue(merged.premium(), "the record must stay premium");
        assertEquals("", merged.passwordHash(), "premium accounts must have no password");
        assertEquals("OFFLINETOTP", merged.totpSecret(), "the offline 2FA secret must overwrite the old one");
        assertEquals("offline_world:9.0:9.0:9.0:0.0:0.0", merged.logoutLocation(),
                "the offline logout location must overwrite the old one");
        assertEquals("SURVIVAL", merged.gameMode(), "the offline game mode must overwrite the old one");
        // 名字索引：新名指向该记录，原正版旧名（与离线名）都不再可反查
        assertEquals(premiumUuid, data.findUuidByName(newName), "the new name must resolve");
        assertNull(data.getByName("PreviousPremiumName"), "the previous premium name must be unindexed");
        assertNull(data.getByName(offlineName), "the offline name must never be indexed");

        // 落库态
        env.flushAndAwaitDbWrites();
        try (PlayerDataManager restarted = new PlayerDataManager(env.plugin())) {
            assertFalse(restarted.hasAccount(offlineUuid), "the offline row must be deleted");
            PlayerData reloaded = restarted.getPlayer(premiumUuid);
            assertNotNull(reloaded, "the overwritten premium row must exist");
            assertEquals(newName, reloaded.name(), "the rebound name must persist");
            assertEquals("", reloaded.passwordHash(), "the password must be cleared on overwrite");
            assertEquals("OFFLINETOTP", reloaded.totpSecret(), "the offline 2FA secret must persist");
            assertEquals("offline_world:9.0:9.0:9.0:0.0:0.0", reloaded.logoutLocation(),
                    "the offline location must persist");
        }
    }

    // ---------- 正版 → 离线 ----------

    /** 正版降级为离线：新离线记录保留密码/2FA/位置/游戏模式，清除正版标记与皮肤 */
    @Test
    void migrateToOfflineCreatesTheOfflineRecord() {
        String name = "DowngradeUser";
        UUID premiumUuid = UUID.randomUUID();
        UUID offlineUuid = offlineUuidOf(name);
        PlayerDataManager data = env.data();
        data.createPremiumPlayer(premiumUuid, name, IP, "{\"textures\":\"skin\"}");
        PlayerData premium = data.getPlayer(premiumUuid);
        premium.passwordHash("kept-pw");
        premium.totpSecret("KEPTTOTP");
        premium.logoutLocation("world:3.0:65.0:4.0:180.0:0.0");
        premium.gameMode("ADVENTURE");
        data.saveNow(premiumUuid);
        assertTrue(data.hasAccount(premiumUuid), "precondition: the premium record exists");

        assertTrue(data.migrateToOffline(premiumUuid, offlineUuid), "an existing premium record must downgrade");

        // 内存态：正版 UUID 消失，离线 UUID 承接数据
        assertFalse(data.hasAccount(premiumUuid), "the premium record must be dropped");
        PlayerData offline = data.getPlayer(offlineUuid);
        assertNotNull(offline, "the offline record must exist");
        assertFalse(offline.premium(), "the downgraded record must not be premium");
        assertNull(offline.name(), "offline records must not carry a name");
        assertNull(offline.properties(), "premium skin properties must be cleared");
        assertEquals("kept-pw", offline.passwordHash(), "the password must carry over");
        assertEquals("KEPTTOTP", offline.totpSecret(), "the 2FA secret must carry over");
        assertEquals("world:3.0:65.0:4.0:180.0:0.0", offline.logoutLocation(), "the location must carry over");
        assertEquals("ADVENTURE", offline.gameMode(), "the game mode must carry over");
        // 名字索引必须摘除，否则 downgrade 后仍能按名反查到已作废的正版号
        assertNull(data.getByName(name), "the premium name index must be cleared");

        // 落库态
        env.flushAndAwaitDbWrites();
        try (PlayerDataManager restarted = new PlayerDataManager(env.plugin())) {
            assertFalse(restarted.hasAccount(premiumUuid), "the premium row must be deleted");
            assertTrue(restarted.hasAccount(offlineUuid), "the offline row must have been written in the same commit");
            PlayerData reloaded = restarted.getPlayer(offlineUuid);
            assertNotNull(reloaded, "the offline row must be persisted");
            assertFalse(reloaded.premium(), "the downgrade must persist premium=0");
            assertEquals("kept-pw", reloaded.passwordHash(), "the password must survive a restart");
            assertEquals("KEPTTOTP", reloaded.totpSecret(), "the 2FA secret must survive a restart");
            assertEquals("world:3.0:65.0:4.0:180.0:0.0", reloaded.logoutLocation(), "the location must survive");
        }
    }

    /** 正版记录不存在时报告未迁移（false）：注销竞态下不得凭空造出离线号 */
    @Test
    void migrateToOfflineReportsFalseWhenThePremiumRecordIsMissing() {
        PlayerDataManager data = env.data();
        UUID premiumUuid = UUID.randomUUID();
        UUID offlineUuid = offlineUuidOf("VanishedUser");

        assertFalse(data.migrateToOffline(premiumUuid, offlineUuid),
                "a missing premium record must not downgrade");
        assertNull(data.getPlayer(offlineUuid), "no offline record may be created");
    }

    // ---------- 辅助 ----------

    /** 原版离线模式 UUID 推导，与生产实现同式 */
    private static UUID offlineUuidOf(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
