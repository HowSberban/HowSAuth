package org.howsauth.plugin.data;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.I18n;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

// 表 players 在运行时由 initTable() 创建，静态分析无法解析
@SuppressWarnings("SqlResolve")
public final class PlayerDataManager implements AutoCloseable {

    private final HowSAuth plugin;
    private final HikariDataSource dataSource;
    // 内存缓存：启动时全量加载，运行时读操作走缓存，写操作标记脏后由周期任务批量落库
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    // 脏标记：内存数据已修改但尚未落库的玩家 UUID，由周期任务批量 flush
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    // 批量落库失败重试计数：达到上限后放弃该玩家，防止数据库故障时无限重试刷日志
    private final Map<UUID, Integer> flushFailures = new ConcurrentHashMap<>();
    // 批量落库最大重试次数
    private static final int MAX_FLUSH_RETRY = 3;
    // 正版玩家名索引：name(小写) → uuid，用于 getByName 快速查找，避免 O(n) 遍历
    private final Map<String, UUID> premiumNameIndex = new ConcurrentHashMap<>();
    // 数据库全量加载是否失败：失败期间 fail-closed，拒绝新玩家进入（空缓存会把所有玩家误判为未注册）
    private volatile boolean loadFailed;
    // 加载失败自动重试任务是否已挂起，防止周期任务叠加
    private volatile boolean retryScheduled;
    // DB 写串行执行器：所有含 INSERT/REPLACE/DELETE 的写任务单线程排队执行
    // 注销/迁移先移除内存记录再入队删除任务，upsert 任务执行时复检内存记录仍在——
    // 复检可见的记录其删除任务必排在本次 upsert 之后，杜绝"已删行被并发 upsert 复活"
    // （注销账号重启后复活、迁移后新旧 UUID 双记录）
    private final ExecutorService dbWriteExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HowSAuth-DB-Writer");
        t.setDaemon(true);
        return t;
    });
    // IP 注册名额锁：同 IP 计数与建号须在同一临界区内完成，防止并发注册同时通过检查突破上限
    private final Object ipLimitLock = new Object();

    // REPLACE INTO 在 SQLite 与 MySQL 均支持：主键存在则先 DELETE 再 INSERT，否则直接 INSERT
    private static final String SQL_UPSERT =
            "REPLACE INTO players (uuid, name, password_hash, ip, last_login, logout_location, premium, properties, game_mode, totp_secret, last_active) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SQL_DELETE = "DELETE FROM players WHERE uuid = ?";
    private static final String SQL_UPDATE_PREMIUM =
            "UPDATE players SET premium = ?, properties = ?, name = ? WHERE uuid = ?";

    public PlayerDataManager(HowSAuth plugin) {
        this.plugin = plugin;
        this.dataSource = createDataSource(plugin);
        initTable();
        load();
    }

    private HikariDataSource createDataSource(HowSAuth plugin) {
        var cm = plugin.getConfigManager();
        HikariConfig config = new HikariConfig();
        config.setPoolName("HowSAuth-DB");

        if ("mysql".equals(cm.databaseType())) {
            // 拼接 MySQL JDBC URL 与连接参数
            StringBuilder url = new StringBuilder()
                    .append("jdbc:mysql://")
                    .append(cm.mysqlHost())
                    .append(":")
                    .append(cm.mysqlPort())
                    .append("/")
                    .append(cm.mysqlDatabase());
            if (!cm.mysqlParams().isEmpty()) {
                String query = cm.mysqlParams().entrySet().stream()
                        .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                        .collect(Collectors.joining("&"));
                url.append("?").append(query);
            }
            config.setJdbcUrl(url.toString());
            config.setUsername(cm.mysqlUsername());
            config.setPassword(cm.mysqlPassword());
            config.setMaximumPoolSize(Math.max(1, cm.poolSize()));
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
            Debug.log("db", "datasource init: type=%s poolSize=%s", cm.databaseType(), config.getMaximumPoolSize());
        }
        return ds;
    }

    /** 建表（如果不存在）+ 迁移新列 */
    private void initTable() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS players (" +
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
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, "players", column)) {
            if (!rs.next()) {
                stmt.executeUpdate("ALTER TABLE " + "players" + " ADD COLUMN " + column + " " + type);
            }
        } catch (SQLException ignored) {
            // 列已存在或其他异常，忽略
        }
    }

    /** 启动时全量加载玩家数据到内存缓存；失败时置 fail-closed 标记并自动重试直至成功 */
    public void load() {
        players.clear();
        premiumNameIndex.clear();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT uuid, name, password_hash, ip, last_login, logout_location, premium, properties, game_mode, totp_secret, last_active FROM players")) {
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                PlayerData data = new PlayerData(
                        uuid,
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
                );
                players.put(uuid, data);
                if (data.premium() && data.name() != null) {
                    premiumNameIndex.put(data.name().toLowerCase(), uuid);
                }
            }
            loadFailed = false;
            if (Debug.on()) {
                Debug.log("db", "load complete: %s accounts loaded", players.size());
            }
        } catch (SQLException e) {
            loadFailed = true;
            plugin.getLogger().severe(I18n.get("log.load_players_failed", e.getMessage()));
            scheduleLoadRetry();
        }
    }

    /** 数据库恢复前的自动重试（10 秒周期，成功即停）；期间 isLoadFailed=true 拒绝新玩家进入 */
    private void scheduleLoadRetry() {
        if (retryScheduled) return;
        retryScheduled = true;
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
            if (!loadFailed) {
                retryScheduled = false;
                task.cancel();
                return;
            }
            plugin.getLogger().warning(I18n.get("log.load_retry"));
            load();
        }, 10, 10, TimeUnit.SECONDS);
    }

    /** 数据库是否处于加载失败状态（fail-closed：玩家数据不可见，调用方应拒绝进入） */
    public boolean isLoadFailed() {
        return loadFailed;
    }

    /** 标记玩家数据为脏：由周期任务批量落库（合并写、降 DB 开销；崩溃时最多丢失一个 flush 周期内的改动） */
    public void save(UUID uuid) {
        if (players.containsKey(uuid)) {
            dirty.add(uuid);
        }
    }

    /**
     * 立即落库指定玩家（关键操作专用：注册、改密、2FA 绑定状态变更等防断电丢失）。
     * 经串行写队列直写，失败时兜底转交周期 flush 重试
     */
    public void saveNow(UUID uuid) {
        PlayerData data = players.get(uuid);
        if (data != null) {
            if (Debug.on()) {
                Debug.log("db", "save now: %s", uuid.toString().substring(0, 8));
            }
            saveNow(data);
        }
    }

    /** 周期任务调用（已在异步调度线程）：将脏标记的玩家数据批量落库，失败按上限重试 */
    public void flushDirty() {
        if (dirty.isEmpty()) return;
        final List<PlayerData> toSave = new ArrayList<>(dirty.size());
        // 逐个移除而非整体 clear：避免与主线程并发 save() 竞态（clear 可能清掉刚标记的脏数据）
        for (UUID uuid : dirty) {
            dirty.remove(uuid);
            PlayerData data = players.get(uuid);
            if (data != null) toSave.add(data);
        }
        if (toSave.isEmpty()) return;
        if (Debug.on()) {
            Debug.log("db", "flush dirty: %s records queued", toSave.size());
        }
        // 落库经串行写队列执行：与注销/迁移的删除任务按入队顺序落库
        submitDbWrite(() -> {
            // 执行时复检内存状态：快照后被移除或替换的记录跳过（已注销/迁移的账号不得被 upsert 复活）
            List<PlayerData> valid = new ArrayList<>(toSave.size());
            for (PlayerData data : toSave) {
                if (players.get(data.uuid()) == data) valid.add(data);
            }
            if (valid.isEmpty()) return;
            // 批量写失败时按重试上限重新标记脏，等待下轮 flush；成功则清除重试计数
            if (upsertBatchSync(valid)) {
                for (PlayerData data : valid) {
                    flushFailures.remove(data.uuid());
                }
            } else {
                for (PlayerData data : valid) {
                    int n = flushFailures.merge(data.uuid(), 1, Integer::sum);
                    if (n < MAX_FLUSH_RETRY) {
                        dirty.add(data.uuid());
                    } else {
                        flushFailures.remove(data.uuid()); // 放弃，停止重试
                    }
                }
            }
        });
    }

    /** DB 写任务加入串行队列。插件停用后队列已关闭，提交被丢弃（saveSync 全量落库兜底） */
    private void submitDbWrite(Runnable job) {
        try {
            dbWriteExecutor.execute(job);
        } catch (RejectedExecutionException ignored) {
            // 队列已关闭（停用中）：丢弃，saveSync 全量保存兜底
        }
    }

    /** 同步全量保存，用于 onDisable（必须在关服前完成，覆盖全部内存数据含脏标记）。
     *  先排空串行写队列（在途删除/upsert 先落库），再直接全量写入最终状态 */
    public void saveSync() {
        dirty.clear();
        dbWriteExecutor.shutdown();
        try {
            if (!dbWriteExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                // 超时未排空（DB 严重卡顿）：丢弃剩余任务（旧快照晚于全量保存落库会回退数据），最终状态由全量保存覆盖
                plugin.getLogger().severe(I18n.get("log.db_write_queue_timeout"));
                dbWriteExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        saveAllSync();
    }

    /**
     * 等待串行写队列排空（不关闭线程池）：向队列提交哨兵任务，其完成表明此前入队的写入均已落库。
     * 供需要"写入确定"的调用方与测试使用；关服保存请用 {@link #saveSync()}。
     */
    public void awaitPendingWrites() {
        try {
            dbWriteExecutor.submit(() -> {
            }).get(30, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            // 队列已关闭（saveSync/close 之后）：不存在在途写入需要等待
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            plugin.getLogger().warning(I18n.get("log.db_write_await_timeout", 30));
        }
    }

    /** 批量 upsert，整批一个事务；成功返回 true，失败返回 false */
    private boolean upsertBatchSync(List<PlayerData> list) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(SQL_UPSERT)) {
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
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe(I18n.get("log.save_all_failed", e.getMessage()));
            if (Debug.on()) {
                Debug.log("db", "upsert batch failed: %s", e.getMessage());
            }
            return false;
        }
    }

    private void saveAllSync() {
        if (players.isEmpty()) return;
        upsertBatchSync(new ArrayList<>(players.values()));
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

    /** 获取所有已注册玩家的 UUID 集合 */
    public Set<UUID> getAllUuids() {
        return players.keySet();
    }

    public boolean hasAccount(UUID uuid) {
        return players.containsKey(uuid);
    }

    public PlayerData getPlayer(UUID uuid) {
        return players.get(uuid);
    }

    /** 查找指定 IP 下的所有账号（用于管理员排查多账号） */
    public List<PlayerData> findByIp(String ip) {
        if (ip == null || ip.isEmpty()) return List.of();
        return players.values().stream()
                .filter(d -> ip.equals(d.ip()))
                .toList();
    }

    /** 当前 epoch 秒（PlayerData 时间戳字段的统一存储精度） */
    public static long nowEpochSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    public void createPlayer(UUID uuid, String passwordHash, String ip) {
        PlayerData data = new PlayerData(uuid, null, passwordHash, ip, nowEpochSeconds(), null, false, null, null, null, 0);
        players.put(uuid, data);
        // 创建账号为关键操作：立即落库，避免崩溃丢新账号（区别于登录/退出等的周期批量 flush）
        saveNow(data);
    }

    /**
     * 受同 IP 名额限制的原子建号：计数与建号在同一临界区内完成，并发注册同一 IP
     * 不会全部通过检查（防止 max-accounts-per-ip 被并发绕过）
     * @param maxAccounts 每 IP 允许的最大账号数（<=0 或 ip 为 null 时不限制）
     * @return 建号成功返回账号数据；名额已满返回 null
     */
    public PlayerData createPlayerIfIpAllowed(UUID uuid, String passwordHash, String ip, int maxAccounts) {
        synchronized (ipLimitLock) {
            if (maxAccounts > 0 && ip != null) {
                long count = players.values().stream().filter(d -> ip.equals(d.ip())).count();
                if (count >= maxAccounts) {
                    if (Debug.on()) {
                        Debug.log("db", "create player %s rejected: ip account limit reached", uuid.toString().substring(0, 8));
                    }
                    return null;
                }
            }
            PlayerData data = new PlayerData(uuid, null, passwordHash, ip, nowEpochSeconds(), null, false, null, null, null, 0);
            players.put(uuid, data);
            // 创建账号为关键操作：立即落库，避免崩溃丢新账号
            saveNow(data);
            return data;
        }
    }

    /** 立即落库单个玩家数据，失败时退化为脏标记由周期任务兜底重试。
     *  经串行写队列执行并复检内存记录仍在：快照后被移除（注销/迁移）的账号不得被落库复活 */
    private void saveNow(PlayerData data) {
        submitDbWrite(() -> {
            if (players.get(data.uuid()) != data) return;
            if (!upsertBatchSync(List.of(data))) {
                // 立即写失败：转交周期 flush 兜底重试
                dirty.add(data.uuid());
            } else {
                flushFailures.remove(data.uuid());
            }
        });
    }

    /**
     * 创建正版玩家记录（premium=1，无密码，带 properties 皮肤数据）。
     * 正版验证即身份凭证，无需密码；玩家可用 /addpassword 自行设置
     */
    public void createPremiumPlayer(UUID uuid, String name, String ip, String properties) {
        PlayerData data = new PlayerData(uuid, name, "", ip, nowEpochSeconds(), null, true, properties, null, null, 0);
        players.put(uuid, data);
        if (name != null) {
            premiumNameIndex.put(name.toLowerCase(), uuid);
        }
        // 创建正版账号同样为关键操作：立即落库，避免崩溃丢新账号
        saveNow(data);
    }

    /** 标记已有账号为正版（premium=1），更新 properties 和 name */
    public void markPremium(UUID uuid, String name, String properties) {
        PlayerData data = players.get(uuid);
        if (data == null) return;
        // 更新索引：移除旧名映射，添加新名映射
        if (data.name() != null) {
            premiumNameIndex.remove(data.name().toLowerCase());
        }
        data.premium(true);
        data.properties(properties);
        data.name(name);
        if (name != null) {
            premiumNameIndex.put(name.toLowerCase(), uuid);
        }
        saveNow(data);
    }

    /**
     * 强制标记账号为正版（管理员 /premium）：仅改 premium 标记，
     * 其余字段（UUID/名字/皮肤等）留待玩家下次正版验证进服时由正式流程写入
     */
    public void forceMarkPremium(UUID uuid) {
        PlayerData data = players.get(uuid);
        if (data == null) return;
        data.premium(true);
        saveNow(data);
    }

    /**
     * 撤销强制正版标记（管理员 /premium 对残留态再次切换）：仅把 premium 改回 false。
     * 该记录本是离线 UUID 且无名字，撤标记后即恢复普通离线账号
     */
    public void forceMarkOffline(UUID uuid) {
        PlayerData data = players.get(uuid);
        if (data == null) return;
        data.premium(false);
        saveNow(data);
    }

    /**
     * 将离线账号迁移到正版账号（离线账号升级为正版）。
     * 用正版 UUID 创建新记录，保留退出位置等数据，密码置空（正版默认无密码），premium=1。
     * 异步落库：删除离线账号 + 写入正版账号。
     * 目标正版 UUID 已有正版记录时（改名玩家意外注册同名离线号后升级等场景）：
     * 保留原正版记录的全部玩家数据（密码/2FA/退出位置/游戏模式，原版 dat/成就/统计也不迁移），
     * 仅更新名字与皮肤，离线号记录作废删除。
     *
     * @return true 常规迁移完成，调用方应随迁原版玩家数据文件；false 未迁移（离线号不存在或已保留原正版记录），调用方应跳过原版数据迁移
     */
    public boolean migrateToPremium(UUID offlineUuid, UUID premiumUuid, String name, String ip, String properties) {
        PlayerData offline = players.get(offlineUuid);
        if (offline == null) return false;
        if (Debug.on()) {
            Debug.log("db", "migrate offline->premium: %s", name);
        }
        // 目标已有正版记录：保留原账号数据，仅换绑名字与皮肤，离线号作废（返回 false 让调用方跳过原版数据迁移）
        PlayerData existing = players.get(premiumUuid);
        if (existing != null && existing.premium()) {
            preserveExistingPremium(offline, offlineUuid, premiumUuid, existing, name, properties);
            return false;
        }
        players.remove(offlineUuid);
        if (offline.name() != null) {
            premiumNameIndex.remove(offline.name().toLowerCase());
        }
        // 离线记录即将从数据库删除，脏标记不再有意义（防止残留）
        dirty.remove(offlineUuid);
        flushFailures.remove(offlineUuid);
        PlayerData premium = new PlayerData(premiumUuid, name, "",
                ip != null && !ip.isEmpty() ? ip : offline.ip(),
                nowEpochSeconds(), offline.logoutLocation(), true, properties, offline.gameMode(),
                offline.totpSecret(), offline.lastActive());
        players.put(premiumUuid, premium);
        premiumNameIndex.put(name.toLowerCase(), premiumUuid);
        // 迁移事务经串行写队列执行：与 flush/saveNow 的 upsert 保证落库顺序
        submitDbWrite(() -> {
            try (Connection conn = dataSource.getConnection()) {
                // 删除离线记录与写入正版记录须在同一事务，避免中途崩溃导致两记录皆失（账号丢失）
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement del = conn.prepareStatement(SQL_DELETE)) {
                        del.setString(1, offlineUuid.toString());
                        del.executeUpdate();
                    }
                    try (PreparedStatement ups = conn.prepareStatement(SQL_UPSERT)) {
                        bindPlayerData(ups, premium);
                        ups.executeUpdate();
                    }
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                plugin.getLogger().severe(I18n.get("log.migrate_failed", offlineUuid + ": " + e.getMessage()));
                if (Debug.on()) {
                    Debug.log("db", "migrate offline->premium failed (transaction): %s", e.getMessage());
                }
            }
        });
        return true;
    }

    /**
     * 目标正版 UUID 已有正版记录时的保留式合并：原正版记录的密码/2FA/退出位置/游戏模式原样保留，
     * 仅更新名字与皮肤（玩家刚完成新名的正版验证）；离线号记录删除作废。
     * 原版玩家数据（dat/成就/统计）不迁移，避免以离线号文件覆盖正版身份下的真实数据。
     */
    private void preserveExistingPremium(PlayerData offline, UUID offlineUuid, UUID premiumUuid,
                                         PlayerData premium, String name, String properties) {
        players.remove(offlineUuid);
        if (offline.name() != null) {
            premiumNameIndex.remove(offline.name().toLowerCase());
        }
        // 离线记录即将从数据库删除，脏标记不再有意义（防止残留）
        dirty.remove(offlineUuid);
        flushFailures.remove(offlineUuid);
        if (premium.name() != null) {
            premiumNameIndex.remove(premium.name().toLowerCase());
        }
        premium.properties(properties);
        premium.name(name);
        premiumNameIndex.put(name.toLowerCase(), premiumUuid);
        plugin.getLogger().info(I18n.get("log.premium_migrate_preserved", name + " (" + offlineUuid + ")"));
        // 作废离线号的原版数据文件一并删除（否则同名新玩家注册会继承遗留的背包/成就/统计）
        plugin.getAuthManager().deletePlayerDataAsync(offlineUuid);
        submitDbWrite(() -> {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement del = conn.prepareStatement(SQL_DELETE)) {
                        del.setString(1, offlineUuid.toString());
                        del.executeUpdate();
                    }
                    // 仅更新 premium/properties/name：密码/2FA/退出位置等以原正版记录为准
                    try (PreparedStatement ups = conn.prepareStatement(SQL_UPDATE_PREMIUM)) {
                        ups.setBoolean(1, true);
                        ups.setString(2, properties);
                        ups.setString(3, name);
                        ups.setString(4, premiumUuid.toString());
                        ups.executeUpdate();
                    }
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                plugin.getLogger().severe(I18n.get("log.migrate_failed", offlineUuid + ": " + e.getMessage()));
                if (Debug.on()) {
                    Debug.log("db", "merge into existing premium failed: %s", e.getMessage());
                }
            }
        });
    }

    /**
     * 将正版账号迁移回离线账号（正版降级为离线）。
     * 用离线 UUID 创建新记录，保留密码、2FA 密钥、退出位置等数据，premium=0，清除皮肤 properties。
     * 异步落库：删除正版记录 + 写入离线记录。
     * 同名离线账号不可能存在（正版记录存续期间 LoginStart 拦截同名离线连接），不做冲突检查。
     * 正版记录不存在时返回 false（注销竞态）
     */
    public boolean migrateToOffline(UUID premiumUuid, UUID offlineUuid) {
        PlayerData premium = players.remove(premiumUuid);
        if (premium == null) return false;
        if (Debug.on()) {
            Debug.log("db", "migrate premium->offline: %s",
                    premium.name() != null ? premium.name() : premiumUuid.toString().substring(0, 8));
        }
        if (premium.name() != null) {
            premiumNameIndex.remove(premium.name().toLowerCase());
        }
        // 正版记录即将从数据库删除，脏标记不再有意义（防止残留）
        dirty.remove(premiumUuid);
        flushFailures.remove(premiumUuid);
        PlayerData offline = new PlayerData(offlineUuid, null, premium.passwordHash(), premium.ip(),
                premium.lastLogin(), premium.logoutLocation(), false, null, premium.gameMode(),
                premium.totpSecret(), premium.lastActive());
        players.put(offlineUuid, offline);
        // 迁移事务经串行写队列执行：与 flush/saveNow 的 upsert 保证落库顺序
        submitDbWrite(() -> {
            try (Connection conn = dataSource.getConnection()) {
                // 删除正版记录与写入离线记录须在同一事务，避免中途崩溃导致两记录皆失（账号丢失）
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement del = conn.prepareStatement(SQL_DELETE)) {
                        del.setString(1, premiumUuid.toString());
                        del.executeUpdate();
                    }
                    try (PreparedStatement ups = conn.prepareStatement(SQL_UPSERT)) {
                        bindPlayerData(ups, offline);
                        ups.executeUpdate();
                    }
                    conn.commit();
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                }
            } catch (SQLException e) {
                plugin.getLogger().severe(I18n.get("log.migrate_failed", premiumUuid + ": " + e.getMessage()));
                if (Debug.on()) {
                    Debug.log("db", "migrate premium->offline failed: %s", e.getMessage());
                }
            }
        });
        return true;
    }

    /** 按玩家名查询（用于正版验证 LoginStart 阶段，仅返回 premium=1 的记录） */
    public PlayerData getByName(String name) {
        if (name == null) return null;
        UUID uuid = premiumNameIndex.get(name.toLowerCase());
        return uuid != null ? players.get(uuid) : null;
    }

    /**
     * 按玩家名查找账号 UUID（正版按名索引，离线由名推导）
     * 以数据库记录为准，避免 usercache 同名缓存到不同 UUID 造成误删
     */
    public UUID findUuidByName(String name) {
        if (name == null) return null;
        UUID uuid = premiumNameIndex.get(name.toLowerCase());
        if (uuid != null) return uuid;
        UUID offlineUuid = offlineUuidOf(name);
        return players.containsKey(offlineUuid) ? offlineUuid : null;
    }

    /**
     * 是否已有同名账号（含离线与正版）
     * 正版账号记录名字，按名索引判定；离线账号不记名，但离线 UUID 由用户名确定推导，按推导 UUID 判定等价
     */
    public boolean hasAccountByName(String name) {
        return findUuidByName(name) != null;
    }

    /** 原版离线模式 UUID 推导（与 DataService.offlineUuid 同式，数据层不依赖 premium 模块故内联定义） */
    private static UUID offlineUuidOf(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** 是否为正版账号（premium=1） */
    public boolean isPremium(UUID uuid) {
        PlayerData data = players.get(uuid);
        return data != null && data.premium();
    }

    /** 数据库中是否存在正版账号（premium=1）：premiumNameIndex 仅收录正版账号 */
    public boolean hasPremiumAccount() {
        return !premiumNameIndex.isEmpty();
    }

    public void removePlayer(UUID uuid) {
        if (Debug.on()) {
            Debug.log("db", "remove player: %s", uuid.toString().substring(0, 8));
        }
        PlayerData data = players.remove(uuid);
        if (data != null && data.name() != null) {
            premiumNameIndex.remove(data.name().toLowerCase());
        }
        // 清理落库相关状态：账号已删除，脏标记与重试计数不再有意义（防止残留）
        dirty.remove(uuid);
        flushFailures.remove(uuid);
        // 删除经串行写队列执行：内存已先移除，排在前面的在途 upsert 会因执行时复检被跳过
        submitDbWrite(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(SQL_DELETE)) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe(I18n.get("log.delete_player_failed", uuid, e.getMessage()));
                if (Debug.on()) {
                    Debug.log("db", "delete player failed: %s", e.getMessage());
                }
            }
        });
    }

    /**
     * 清理不活跃账号：删除超过 days 天未登录的非正版账号。
     * 活跃时间取 lastActive 与 lastLogin 的较大者；两者均为 0 时无法判断活跃度，跳过以保护数据。
     * 正版账号与在线玩家不受影响。同步执行（启动时调用，玩家尚未进入）。
     * @return 清理的账号数量
     */
    public int purgeInactive(int days) {
        long threshold = nowEpochSeconds() - days * 86400L;
        List<UUID> toDelete = new ArrayList<>();
        for (PlayerData data : players.values()) {
            if (data.premium()) continue;
            // 在线玩家跳过：/reload 重新启用时不会触发 PlayerJoinEvent 刷新活跃时间，在线期间的活跃度停留在上线时刻
            if (Bukkit.getPlayer(data.uuid()) != null) continue;
            long activity = Math.max(data.lastActive(), data.lastLogin());
            if (activity > 0 && activity < threshold) {
                toDelete.add(data.uuid());
            }
        }
        for (UUID uuid : toDelete) {
            removePlayer(uuid);
        }
        if (!toDelete.isEmpty() && Debug.on()) {
            Debug.log("db", "purge inactive: %s accounts removed", toDelete.size());
        }
        return toDelete.size();
    }

    public void updatePassword(UUID uuid, String newHash) {
        PlayerData data = players.get(uuid);
        if (data == null) return;
        data.passwordHash(newHash);
        saveNow(data);
    }

    /** 关闭数据源，释放连接池（确保串行写队列先排空，防止在途写任务打到已关闭的池） */
    @Override
    public void close() {
        dbWriteExecutor.shutdown();
        try {
            if (!dbWriteExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                // 超时未排空：丢弃剩余任务，防止关闭数据源后在途任务获取连接失败刷错误日志
                plugin.getLogger().severe(I18n.get("log.db_write_queue_timeout"));
                dbWriteExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    /**
     * 将 Location 序列化为字符串，格式: world:x:y:z:yaw:pitch
     * 用于持久化存储玩家上次退出位置。
     */
    public static String serializeLocation(Location loc) {
        return loc.getWorld().getName() + ":"
                + loc.getX() + ":" + loc.getY() + ":" + loc.getZ() + ":"
                + loc.getYaw() + ":" + loc.getPitch();
    }

    /**
     * 将序列化的字符串反序列化为 Location。
     * 如果世界不存在或格式错误，返回 null。
     */
    public static Location deserializeLocation(String str) {
        if (str == null || str.isEmpty()) return null;
        try {
            String[] parts = str.split(":");
            if (parts.length != 6) return null;
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) return null;
            return new Location(world,
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]),
                    Float.parseFloat(parts[4]),
                    Float.parseFloat(parts[5]));
        } catch (Exception e) {
            return null;
        }
    }

    public static final class PlayerData {
        private final UUID uuid;
        // volatile 保证可见性：主线程写入后，异步保存线程能读到最新值
        private volatile String name;
        private volatile String passwordHash;
        private volatile String ip;
        private volatile long lastLogin;
        // 玩家上次已登录退出时的位置（序列化字符串），用于登录后传送回原位置
        private volatile String logoutLocation;
        // 正版标记：true=正版账号（免密），false=离线账号（密码登录）
        private volatile boolean premium;
        // 正版玩家皮肤 properties（JSON 字符串，来自 Mojang hasJoined 响应）
        private volatile String properties;
        // 玩家上次已登录退出时的游戏模式（名称），用于登录后恢复
        private volatile String gameMode;
        // 双因素认证 TOTP 密钥（Base32），null 表示未启用
        private volatile String totpSecret;
        // 最后活跃时间（epoch 秒）：登录成功时更新，用于清理不活跃账号
        private volatile long lastActive;

        public PlayerData(UUID uuid, String name, String passwordHash, String ip, long lastLogin,
                          String logoutLocation, boolean premium, String properties, String gameMode,
                          String totpSecret, long lastActive) {
            this.uuid = uuid;
            this.name = name;
            this.passwordHash = passwordHash;
            this.ip = ip;
            this.lastLogin = lastLogin;
            this.logoutLocation = logoutLocation;
            this.premium = premium;
            this.properties = properties;
            this.gameMode = gameMode;
            this.totpSecret = totpSecret;
            this.lastActive = lastActive;
        }

        public UUID uuid() { return uuid; }
        public String name() { return name; }
        public void name(String name) { this.name = name; }

        public String passwordHash() { return passwordHash; }
        public void passwordHash(String hash) { this.passwordHash = hash; }

        public String ip() { return ip; }
        public void ip(String ip) { this.ip = ip; }

        public long lastLogin() { return lastLogin; }
        public void lastLogin(long lastLogin) { this.lastLogin = lastLogin; }

        public String logoutLocation() { return logoutLocation; }
        public void logoutLocation(String logoutLocation) { this.logoutLocation = logoutLocation; }

        public boolean premium() { return premium; }
        public void premium(boolean premium) { this.premium = premium; }

        public String properties() { return properties; }
        public void properties(String properties) { this.properties = properties; }

        public String gameMode() { return gameMode; }
        public void gameMode(String gameMode) { this.gameMode = gameMode; }

        public String totpSecret() { return totpSecret; }
        public void totpSecret(String totpSecret) { this.totpSecret = totpSecret; }

        public long lastActive() { return lastActive; }
        public void lastActive(long lastActive) { this.lastActive = lastActive; }
    }
}
