package org.howsauth.plugin.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 数据库连接与表结构层：连接池的建立、{@code players} 表的创建与列迁移，以及全部针对该表的 SQL 语句。
 * <p>
 * 本类只负责"怎么读写数据库"，不持有任何内存缓存、脏标记或账号语义——那些属于 {@link PlayerDataManager}
 * 与 {@link AccountMigration}。这样 SQL 语句集中在一处，改表结构时不必在业务代码里翻找。
 * <p>
 * 建表策略：{@code CREATE TABLE IF NOT EXISTS} + 若干 {@code ALTER TABLE ADD COLUMN}，
 * 使旧版本的库文件在升级后自动补齐新列（列已存在时 ALTER 抛异常并忽略）。
 */
final class PlayerDatabase implements AutoCloseable {

    /** 表名：运行时由 {@link #initTable()} 创建，静态 SQL 检查解析不到该表 */
    private static final String TABLE = "players";

    @SuppressWarnings("SqlResolve")
    private static final String SQL_DELETE = "DELETE FROM players WHERE uuid = ?";
    @SuppressWarnings("SqlResolve")
    private static final String SQL_UPDATE_PREMIUM =
            "UPDATE players SET premium = ?, properties = ?, name = ? WHERE uuid = ?";
    @SuppressWarnings("SqlResolve")
    private static final String SQL_SELECT_ALL =
            "SELECT uuid, name, password_hash, ip, last_login, logout_location, premium, properties, game_mode, totp_secret, last_active FROM players";

    /** upsert 语句的列清单与取值占位符（两种方言共用） */
    private static final String UPSERT_COLUMNS =
            "(uuid, name, password_hash, ip, last_login, logout_location, premium, properties, game_mode, totp_secret, last_active)";
    private static final String UPSERT_VALUES = "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /**
     * 显式 upsert：主键冲突时只更新列，不做删除重建。
     * <p>
     * 刻意不用 {@code REPLACE INTO}——其语义是"冲突则先 DELETE 再 INSERT"，
     * 一旦语句漏列（新增字段未同步加入），该列会被静默重置为默认值而不报错。
     * SQLite 用 {@code ON CONFLICT ... DO UPDATE}（excluded 伪表），
     * MySQL 用 {@code ON DUPLICATE KEY UPDATE}（VALUES(col)），故按方言在构造时定稿。
     */
    @SuppressWarnings("SqlResolve")
    private static String upsertSqlSqlite() {
        return "INSERT INTO " + TABLE + " " + UPSERT_COLUMNS + " " + UPSERT_VALUES
                + " ON CONFLICT(uuid) DO UPDATE SET "
                + "name = excluded.name, password_hash = excluded.password_hash, ip = excluded.ip, "
                + "last_login = excluded.last_login, logout_location = excluded.logout_location, "
                + "premium = excluded.premium, properties = excluded.properties, "
                + "game_mode = excluded.game_mode, totp_secret = excluded.totp_secret, "
                + "last_active = excluded.last_active";
    }

    /** MySQL 方言的 upsert（MySQL 无 excluded 伪表，用 VALUES(col)） */
    @SuppressWarnings("SqlResolve")
    private static String upsertSqlMySql() {
        return "INSERT INTO " + TABLE + " " + UPSERT_COLUMNS + " " + UPSERT_VALUES
                + " ON DUPLICATE KEY UPDATE "
                + "name = VALUES(name), password_hash = VALUES(password_hash), ip = VALUES(ip), "
                + "last_login = VALUES(last_login), logout_location = VALUES(logout_location), "
                + "premium = VALUES(premium), properties = VALUES(properties), "
                + "game_mode = VALUES(game_mode), totp_secret = VALUES(totp_secret), "
                + "last_active = VALUES(last_active)";
    }

    private final HowSAuth plugin;
    private final HikariDataSource dataSource;
    // upsert 语句按方言在构造时定稿，之后只读
    private final String sqlUpsert;

    PlayerDatabase(HowSAuth plugin) {
        this.plugin = plugin;
        boolean mySql = "mysql".equals(plugin.config().database().type());
        this.sqlUpsert = mySql ? upsertSqlMySql() : upsertSqlSqlite();
        this.dataSource = openDataSource(plugin);
        initTable();
    }

    /**
     * 建立连接池。MySQL 由配置拼接 JDBC URL 与连接参数；SQLite 固定单连接（写入依赖文件锁，多连接会阻塞）。
     */
    private HikariDataSource openDataSource(HowSAuth plugin) {
        var cm = plugin.config();
        HikariConfig config = new HikariConfig();
        config.setPoolName("HowSAuth-DB");

        if ("mysql".equals(cm.database().type())) {
            // 拼接 MySQL JDBC URL 与连接参数
            StringBuilder url = new StringBuilder()
                    .append("jdbc:mysql://")
                    .append(cm.database().host())
                    .append(":")
                    .append(cm.database().port())
                    .append("/")
                    .append(cm.database().database());
            if (!cm.database().params().isEmpty()) {
                String query = cm.database().params().entrySet().stream()
                        .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                        .collect(Collectors.joining("&"));
                url.append("?").append(query);
            }
            config.setJdbcUrl(url.toString());
            config.setUsername(cm.database().username());
            config.setPassword(cm.database().password());
            config.setMaximumPoolSize(Math.max(1, cm.database().poolSize()));
        } else {
            // SQLite：单连接即可，避免文件锁竞争
            File dbFile = new File(plugin.getDataFolder(), "players.db");
            if (!dbFile.getParentFile().exists() && !dbFile.getParentFile().mkdirs()) {
                plugin.getLogger().warning(I18n.get("log.create_data_dir_failed", dbFile.getParentFile().getAbsolutePath()));
            }
            config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            // SQLite 写入依赖文件锁，多连接会阻塞，强制单连接
            config.setMaximumPoolSize(1);
        }

        HikariDataSource ds = new HikariDataSource(config);
        if (Debug.on()) {
            Debug.log("db", "datasource init: type=%s poolSize=%s", cm.database().type(), config.getMaximumPoolSize());
        }
        return ds;
    }

    /** 建表（如果不存在）+ 迁移新列 */
    private void initTable() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                    "  uuid VARCHAR(36) PRIMARY KEY," +
                    "  name VARCHAR(16)," +
                    "  password_hash VARCHAR(255) NOT NULL," +
                    "  ip VARCHAR(45) NOT NULL DEFAULT ''," +
                    "  last_login BIGINT NOT NULL DEFAULT 0," +
                    "  logout_location TEXT," +
                    "  premium BOOLEAN NOT NULL DEFAULT 0," +
                    "  properties TEXT" +
                    ")"
            );
            // 迁移：为旧表添加新列（ALTER TABLE ADD COLUMN 在列已存在时抛异常，忽略即可）
            addColumnIfMissing(stmt, conn, "name", "VARCHAR(16)");
            addColumnIfMissing(stmt, conn, "premium", "BOOLEAN NOT NULL DEFAULT 0");
            addColumnIfMissing(stmt, conn, "properties", "TEXT");
            addColumnIfMissing(stmt, conn, "game_mode", "VARCHAR(16)");
            addColumnIfMissing(stmt, conn, "totp_secret", "VARCHAR(64)");
            addColumnIfMissing(stmt, conn, "last_active", "BIGINT NOT NULL DEFAULT 0");
            if (Debug.on()) {
                Debug.log("db", "schema migration: %s add-column steps applied", 6);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe(I18n.get("log.init_table_failed", e.getMessage()));
        }
    }

    /** 安全添加列：若列不存在则执行 ALTER TABLE ADD COLUMN */
    private void addColumnIfMissing(Statement stmt, Connection conn, String column, String type) {
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, TABLE, column)) {
            if (!rs.next()) {
                stmt.executeUpdate("ALTER TABLE " + TABLE + " ADD COLUMN " + column + " " + type);
            }
        } catch (SQLException ignored) {
            // 列已存在或其他异常，忽略
        }
    }

    /** 全量读取所有玩家行。调用方负责把结果装入内存缓存（含名字索引的建立）。 */
    List<PlayerData> loadAllRows() throws SQLException {
        List<PlayerData> loaded = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(SQL_SELECT_ALL)) {
            while (rs.next()) {
                loaded.add(new PlayerData(
                        UUID.fromString(rs.getString("uuid")),
                        rs.getString("name"),
                        rs.getString("password_hash"),
                        rs.getString("ip"),
                        rs.getLong("last_login"),
                        rs.getString("logout_location"),
                        rs.getBoolean("premium"),
                        rs.getString("properties"),
                        rs.getString("game_mode"),
                        rs.getString("totp_secret"),
                        rs.getLong("last_active")
                ));
            }
        }
        return loaded;
    }

    /**
     * 整批 upsert，同一事务；成功返回 true。
     * 用批量 addBatch 减少往返，失败整体回滚，避免只写一半。
     */
    boolean upsertRows(List<PlayerData> list) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sqlUpsert)) {
                for (PlayerData data : list) {
                    bindPlayerData(ps, data);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
        return true;
    }

    /** 删除一行（自动提交）。 */
    void deleteRow(UUID uuid) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_DELETE)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    /**
     * 单事务内：删除 {@code deleteUuid} 行，再执行一次写入（upsert 或 premium 更新）。
     * <p>
     * 账号迁移必须走本方法：删除与写入若不在同一事务，中途崩溃会两条记录皆失（账号丢失）。
     * 三个迁移路径（离线→正版、保留式合并、正版→离线）共用此实现。
     *
     * @param deleteUuid 要删除的旧记录
     * @param write      在同一事务内执行的写入语句构造（使用传入的同一连接）
     */
    void transactionalDeleteThenWrite(UUID deleteUuid, TransactionalWrite write) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement del = conn.prepareStatement(SQL_DELETE)) {
                    del.setString(1, deleteUuid.toString());
                    del.executeUpdate();
                }
                write.apply(conn);
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /** 在同一事务连接内写入一条完整玩家记录（显式 upsert，按方言） */
    void upsertRow(Connection conn, PlayerData data) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sqlUpsert)) {
            bindPlayerData(ps, data);
            ps.executeUpdate();
        }
    }

    /** 在同一事务连接内仅更新正版标记/皮肤/名字（保留密码、2FA、退出位置等原有数据）。 */
    void updatePremiumRow(Connection conn, UUID uuid, String name, String properties) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(SQL_UPDATE_PREMIUM)) {
            ps.setBoolean(1, true);
            ps.setString(2, properties);
            ps.setString(3, name);
            ps.setString(4, uuid.toString());
            ps.executeUpdate();
        }
    }

    private static void bindPlayerData(PreparedStatement ps, PlayerData data) throws SQLException {
        ps.setString(1, data.uuid().toString());
        ps.setString(2, data.name());
        ps.setString(3, data.passwordHash());
        ps.setString(4, data.ip());
        ps.setLong(5, data.lastLogin());
        ps.setString(6, data.logoutLocation());
        ps.setBoolean(7, data.premium());
        ps.setString(8, data.properties());
        ps.setString(9, data.gameMode());
        ps.setString(10, data.totpSecret());
        ps.setLong(11, data.lastActive());
    }

    @Override
    public void close() {
        if (!dataSource.isClosed()) {
            dataSource.close();
        }
    }

    /** 事务内的写入动作：使用传入的同一连接，抛出的异常会触发整体回滚 */
    @FunctionalInterface
    interface TransactionalWrite {
        void apply(Connection conn) throws SQLException;
    }
}
