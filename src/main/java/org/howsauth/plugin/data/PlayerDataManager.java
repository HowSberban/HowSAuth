package org.howsauth.plugin.data;

import org.howsauth.plugin.OfflineUuids;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.I18n;
import org.bukkit.Bukkit;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

// 表 players 在运行时由 PlayerDatabase 创建，静态分析无法解析
@SuppressWarnings("SqlResolve")
public final class PlayerDataManager implements AutoCloseable {

    private final HowSAuth plugin;
    // 连接池与全部表级 SQL 集中在 PlayerDatabase
    private final PlayerDatabase database;
    // 脏标记消费、批量落库、写队列与关服排空
    private final PlayerPersistence persistence;
    // 账号身份迁移（离线↔正版）：需要同时操控缓存、名字索引与写队列，故独立成类
    private final AccountMigration migration;
    // 内存缓存：启动时全量加载，运行时读操作走缓存，写操作标记脏后由周期任务批量落库
    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    // 脏标记：内存数据已修改但尚未落库的玩家 UUID，由周期任务批量 flush
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    // 数据库加载失败后的自动重试周期（秒）
    private static final int LOAD_RETRY_PERIOD_SECONDS = 10;
    // 正版玩家名索引：name(小写) → uuid，用于 getByName 快速查找，避免 O(n) 遍历
    private final Map<String, UUID> premiumNameIndex = new ConcurrentHashMap<>();
    // 数据库全量加载是否失败：失败期间 fail-closed，拒绝新玩家进入（空缓存会把所有玩家误判为未注册）
    private volatile boolean loadFailed;
    // 加载失败自动重试任务是否已挂起，防止周期任务叠加
    private volatile boolean retryScheduled;
    // IP 注册名额锁：同 IP 计数与建号须在同一临界区内完成，防止并发注册同时通过检查突破上限
    private final Object ipLimitLock = new Object();

    public PlayerDataManager(HowSAuth plugin) {
        this.plugin = plugin;
        this.database = new PlayerDatabase(plugin);
        this.persistence = new PlayerPersistence(plugin, database, players, dirty);
        this.migration = new AccountMigration(plugin, this, database);
        load();
    }

    /** 启动时全量加载玩家数据到内存缓存；失败时置 fail-closed 标记并自动重试直至成功 */
    public void load() {
        players.clear();
        premiumNameIndex.clear();
        try {
            for (PlayerData data : database.loadAllRows()) {
                players.put(data.uuid(), data);
                if (data.premium() && data.name() != null) {
                    premiumNameIndex.put(data.name().toLowerCase(), data.uuid());
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
        }, LOAD_RETRY_PERIOD_SECONDS, LOAD_RETRY_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    /** 数据库是否处于加载失败状态（fail-closed：玩家数据不可见，调用方应拒绝进入） */
    public boolean isLoadFailed() {
        return loadFailed;
    }

    /** 标记玩家数据为脏：由周期任务批量落库（合并写、降 DB 开销；崩溃时最多丢失一个 flush 周期内的改动） */
    public void save(UUID uuid) {
        persistence.save(uuid);
    }

    /**
     * 立即落库指定玩家（关键操作专用：注册、改密、2FA 绑定状态变更等防断电丢失）。
     * 经串行写队列直写，失败时兜底转交周期 flush 重试
     */
    public void saveNow(UUID uuid) {
        persistence.saveNow(uuid);
    }

    /** 周期任务调用（已在异步调度线程）：将脏标记的玩家数据批量落库，失败按上限重试 */
    public void flushDirty() {
        persistence.flushDirty();
    }

    /** 同步全量保存，用于 onDisable（必须在关服前完成，覆盖全部内存数据含脏标记）。 */
    public void saveSync() {
        persistence.saveSync();
    }

    /**
     * 等待串行写队列排空（不关闭线程池）：向队列提交哨兵任务，其完成表明此前入队的写入均已落库。
     * 供需要"写入确定"的调用方与测试使用；关服保存请用 {@link #saveSync()}。
     */
    public void awaitPendingWrites() {
        persistence.awaitPendingWrites();
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
        persistence.saveNow(data);
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
                        Debug.log("db", "create player %s rejected: ip account limit reached", Debug.shortId(uuid));
                    }
                    return null;
                }
            }
            PlayerData data = new PlayerData(uuid, null, passwordHash, ip, nowEpochSeconds(), null, false, null, null, null, 0);
            players.put(uuid, data);
            // 创建账号为关键操作：立即落库，避免崩溃丢新账号
            persistence.saveNow(data);
            return data;
        }
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
        persistence.saveNow(data);
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
        persistence.saveNow(data);
    }

    /**
     * 强制标记账号为正版（管理员 /premium）：仅改 premium 标记，
     * 其余字段（UUID/名字/皮肤等）留待玩家下次正版验证进服时由正式流程写入
     */
    public void forceMarkPremium(UUID uuid) {
        PlayerData data = players.get(uuid);
        if (data == null) return;
        data.premium(true);
        persistence.saveNow(data);
    }

    /**
     * 撤销强制正版标记（管理员 /premium 对残留态再次切换）：仅把 premium 改回 false。
     * 该记录本是离线 UUID 且无名字，撤标记后即恢复普通离线账号
     */
    public void forceMarkOffline(UUID uuid) {
        PlayerData data = players.get(uuid);
        if (data == null) return;
        data.premium(false);
        persistence.saveNow(data);
    }

    /**
     * 将离线账号迁移到正版账号（离线账号升级为正版）。
     * 实现见 {@link AccountMigration}——本方法只做委托，保持对外 API 与返回值语义不变。
     *
     * @return true 迁移完成，调用方应随迁原版玩家数据文件；false 离线号不存在
     */
    public boolean migrateToPremium(UUID offlineUuid, UUID premiumUuid, String name, String ip, String properties) {
        return migration.migrateToPremium(offlineUuid, premiumUuid, name, ip, properties);
    }

    /**
     * 将正版账号迁移回离线账号（正版降级为离线）。
     * 实现见 {@link AccountMigration}——本方法只做委托，保持对外 API 与返回值语义不变。
     */
    public boolean migrateToOffline(UUID premiumUuid, UUID offlineUuid) {
        return migration.migrateToOffline(premiumUuid, offlineUuid);
    }

    // ===== 供 AccountMigration 使用的缓存钩子 =====
    // 迁移需要在"同一时刻"同时改动缓存、名字索引、脏标记与写队列；这些动作集中在此，
    // 让迁移逻辑不必了解缓存的数据结构，也让脏标记/重试计数的清理只写一遍。

    /** 读取缓存中的账号（迁移判定的入口） */
    PlayerData cached(UUID uuid) {
        return players.get(uuid);
    }

    /** 移出缓存并清理落库相关状态：记录即将被删除，脏标记与重试计数不再有意义 */
    PlayerData take(UUID uuid) {
        PlayerData data = players.remove(uuid);
        persistence.forget(uuid);
        return data;
    }

    /** 丢弃一个缓存记录（不返回），用于迁移中"旧记录作废"的场景 */
    void discard(UUID uuid, String nameToUnindex) {
        players.remove(uuid);
        if (nameToUnindex != null) {
            premiumNameIndex.remove(nameToUnindex.toLowerCase());
        }
        persistence.forget(uuid);
    }

    /** 写入/替换一个缓存记录，并按需建立正版名字索引 */
    void store(UUID uuid, PlayerData data, String nameToIndex) {
        players.put(uuid, data);
        if (nameToIndex != null) {
            premiumNameIndex.put(nameToIndex.toLowerCase(), uuid);
        }
    }

    /** 写入/替换一个缓存记录（不涉名字索引） */
    void put(UUID uuid, PlayerData data) {
        players.put(uuid, data);
    }

    /** 摘除正版名字索引 */
    void unindex(String name) {
        premiumNameIndex.remove(name.toLowerCase());
    }

    /** 入队一次数据库写任务（迁移事务走这里，保证与 flush/saveNow 的落库顺序） */
    void submitWrite(Runnable job) {
        persistence.submitWrite(job);
    }

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
        UUID offlineUuid = OfflineUuids.of(name);
        return players.containsKey(offlineUuid) ? offlineUuid : null;
    }

    /**
     * 是否已有同名账号（含离线与正版）
     * 正版账号记录名字，按名索引判定；离线账号不记名，但离线 UUID 由用户名确定推导，按推导 UUID 判定等价
     */
    public boolean hasAccountByName(String name) {
        return findUuidByName(name) != null;
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
            Debug.log("db", "remove player: %s", Debug.shortId(uuid));
        }
        PlayerData data = players.remove(uuid);
        if (data != null && data.name() != null) {
            premiumNameIndex.remove(data.name().toLowerCase());
        }
        // 清理落库相关状态：账号已删除，脏标记与重试计数不再有意义（防止残留）
        persistence.forget(uuid);
        // 删除经串行写队列执行：内存已先移除，排在前面的在途 upsert 会因执行时复检被跳过
        submitWrite(() -> {
            try {
                database.deleteRow(uuid);
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
        persistence.saveNow(data);
    }

    /** 关闭数据源，释放连接池（确保串行写队列先排空，防止在途写任务打到已关闭的池） */
    @Override
    public void close() {
        persistence.close();
        if (database != null) {
            database.close();
        }
    }
}
