package org.howsauth.plugin.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.howsauth.plugin.support.MockBukkitHarness;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 落库 upsert 的语义回归：冲突时必须"就地更新"，不得删除重建。
 * <p>
 * 早期实现用 {@code REPLACE INTO}，其语义是"冲突则先 DELETE 再 INSERT"。当前语句显式列出全部列，
 * 所以当时没有数据丢失；但一旦将来新增字段而漏加进 upsert 语句，缺失的列会被<b>静默重置</b>为默认值。
 * 改用 {@code ON CONFLICT(uuid) DO UPDATE} 后，未出现在 SET 子句里的列保持原值。
 * <p>
 * 判别方式是"往表里加一个 upsert 语句未覆盖的列"：{@code REPLACE INTO} 会把它重置为默认值，
 * {@code ON CONFLICT DO UPDATE} 则保持原值。该用例已用变异测试确认能抓住改回 {@code REPLACE INTO} 的回归。
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PlayerDatabaseUpsertTest {


    private MockBukkitHarness env;

    @BeforeEach
    void setUp() throws Exception {
        env = MockBukkitHarness.start("hsauth-upsert-",
                config -> MockBukkitHarness.inject(org.howsauth.plugin.config.PasswordConfig.class,
                        "bcryptCost", config.password(), 4));
    }

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    /**
     * 反射取内部连接池的连接。
     * <p>
     * HikariCP 在测试中只是 {@code testRuntimeOnly}，编译期不可见，故此处不引用
     * {@code HikariDataSource} 类型，直接反射调用 {@code getConnection()}。
     */
    private Connection openRawConnection() throws Exception {
        Field dbField = PlayerDataManager.class.getDeclaredField("database");
        dbField.setAccessible(true);
        Object db = dbField.get(env.data());
        Field dsField = db.getClass().getDeclaredField("dataSource");
        dsField.setAccessible(true);
        Object dataSource = dsField.get(db);
        return (Connection) dataSource.getClass().getMethod("getConnection").invoke(dataSource);
    }

    /**
     * 冲突更新必须保留所有既有列（含未出现在 SET 子句里的列）。
     * <p>
     * 这是本测试类唯一在意的判别特征：{@code REPLACE INTO} 会 DELETE+INSERT，
     * 于是"未列入 upsert 语句的列"被重置为默认值；{@code ON CONFLICT DO UPDATE} 则保持原值。
     * （已用变异测试验证：把实现改回 {@code REPLACE INTO} 时本用例会失败。）
     */
    @Test
    void conflictingUpsertPreservesEveryExistingColumn() throws Exception {
        UUID uuid = UUID.randomUUID();
        PlayerDataManager data = env.data();
        data.createPlayer(uuid, "hash-keep", "198.51.100.4");

        // 造一行"额外字段"：模拟将来新增但未加入 upsert 语句的列
        try (Connection conn = openRawConnection(); Statement st = conn.createStatement()) {
            st.executeUpdate("ALTER TABLE players ADD COLUMN future_flag TEXT DEFAULT 'default'");
            st.executeUpdate("UPDATE players SET future_flag = 'preserved' WHERE uuid = '"
                    + uuid + "'");
        }

        // 同一 UUID 再次落库：不得把 future_flag 重置为默认值
        data.saveNow(uuid);
        data.awaitPendingWrites();

        try (Connection conn = openRawConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT password_hash, ip, future_flag FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the row must still exist after the upsert");
                assertEquals("hash-keep", rs.getString("password_hash"), "existing password must be kept");
                assertEquals("198.51.100.4", rs.getString("ip"), "existing ip must be kept");
                assertEquals("preserved", rs.getString("future_flag"),
                        "a column absent from the upsert statement must keep its value, "
                                + "which REPLACE INTO would have silently reset");
            }
        }
    }

    /** 新 UUID 的 upsert 仍应正常插入 */
    @Test
    void upsertInsertsWhenTheRowDoesNotExistYet() throws Exception {
        UUID uuid = UUID.randomUUID();
        PlayerDataManager data = env.data();
        data.createPlayer(uuid, "hash-new", "192.0.2.11");
        data.awaitPendingWrites();

        try (Connection conn = openRawConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) AS c FROM players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt("c"), "a brand new account must be inserted exactly once");
            }
        }
        assertNotNull(data.getPlayer(uuid), "the cache must hold the created account");
    }
}