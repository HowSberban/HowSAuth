package org.howsauth.plugin.data;

import org.howsauth.plugin.HowSAuth;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class PlayerDataManager implements AutoCloseable {

    // 连接池与全部表级 SQL 集中在 PlayerDatabase
    private final PlayerDatabase database;
    // 脏标记消费、批量落库、写队列与关服排空
    private final PlayerPersistence persistence;
    // 账号身份迁移（离线↔正版）：需要同时操控缓存、名字索引与写队列，故独立成类
    private final AccountMigration migration;
    // 内存缓存与名字索引：本类只做门面与迁移钩子，不直接维护缓存结构
    private final PlayerCache cache = new PlayerCache();
    // 启动加载与 fail-closed 重试
    private final PlayerDataLoader loader;
    // 账号写操作（创建/标记/删除/清理/改密）
    private final PlayerAccountOperations operations;
    // 脏标记：内存数据已修改但尚未落库的玩家 UUID，由周期任务批量 flush。
    // 测试通过反射读取该字段断言 flush 后收敛，故保留为实例字段。
    @SuppressWarnings("FieldCanBeLocal")
    private final Set<UUID> dirty = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public PlayerDataManager(HowSAuth plugin) {
        this.database = new PlayerDatabase(plugin);
        this.persistence = new PlayerPersistence(plugin, database, cache.players(), dirty);
        this.loader = new PlayerDataLoader(plugin, database, cache);
        this.operations = new PlayerAccountOperations(plugin, database, cache, persistence);
        this.migration = new AccountMigration(plugin, this, database);
        loader.load();
    }

    /** 数据库是否处于加载失败状态（fail-closed：玩家数据不可见，调用方应拒绝进入） */
    public boolean isLoadFailed() {
        return loader.isLoadFailed();
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
        return cache.uuids();
    }

    public boolean hasAccount(UUID uuid) {
        return cache.contains(uuid);
    }

    public PlayerData getPlayer(UUID uuid) {
        return cache.get(uuid);
    }

    /** 查找指定 IP 下的所有账号（用于管理员排查多账号） */
    public List<PlayerData> findByIp(String ip) {
        return cache.findByIp(ip);
    }

    /** 当前 epoch 秒（PlayerData 时间戳字段的统一存储精度） */
    public static long nowEpochSeconds() {
        return System.currentTimeMillis() / 1000;
    }

    public void createPlayer(UUID uuid, String passwordHash, String ip) {
        operations.createPlayer(uuid, passwordHash, ip);
    }

    /**
     * 受同 IP 名额限制的原子建号：计数与建号在同一临界区内完成，并发注册同一 IP
     * 不会全部通过检查（防止 max-accounts-per-ip 被并发绕过）
     * @param maxAccounts 每 IP 允许的最大账号数（<=0 或 ip 为 null 时不限制）
     * @return 建号成功返回账号数据；名额已满返回 null
     */
    public PlayerData createPlayerIfIpAllowed(UUID uuid, String passwordHash, String ip, int maxAccounts) {
        return operations.createPlayerIfIpAllowed(uuid, passwordHash, ip, maxAccounts);
    }

    /**
     * 创建正版玩家记录（premium=1，无密码，带 properties 皮肤数据）。
     * 正版验证即身份凭证，无需密码；玩家可用 /addpassword 自行设置
     */
    public void createPremiumPlayer(UUID uuid, String name, String ip, String properties) {
        operations.createPremiumPlayer(uuid, name, ip, properties);
    }

    /** 标记已有账号为正版（premium=1），更新 properties 和 name */
    public void markPremium(UUID uuid, String name, String properties) {
        operations.markPremium(uuid, name, properties);
    }

    /**
     * 强制标记账号为正版（管理员 /premium）：仅改 premium 标记，
     * 其余字段（UUID/名字/皮肤等）留待玩家下次正版验证进服时由正式流程写入
     */
    public void forceMarkPremium(UUID uuid) {
        operations.forceMarkPremium(uuid);
    }

    /**
     * 撤销强制正版标记（管理员 /premium 对残留态再次切换）：仅把 premium 改回 false。
     * 该记录本是离线 UUID 且无名字，撤标记后即恢复普通离线账号
     */
    public void forceMarkOffline(UUID uuid) {
        operations.forceMarkOffline(uuid);
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
        return cache.get(uuid);
    }

    /** 移出缓存并清理落库相关状态：记录即将被删除，脏标记与重试计数不再有意义 */
    PlayerData take(UUID uuid) {
        PlayerData data = cache.remove(uuid);
        persistence.forget(uuid);
        return data;
    }

    /** 丢弃一个缓存记录（不返回），用于迁移中"旧记录作废"的场景 */
    void discard(UUID uuid, String nameToUnindex) {
        cache.remove(uuid);
        cache.unindex(nameToUnindex);
        persistence.forget(uuid);
    }

    /** 写入/替换一个缓存记录，并按需建立正版名字索引 */
    void store(UUID uuid, PlayerData data, String nameToIndex) {
        cache.putIndexed(uuid, data, nameToIndex);
    }

    /** 写入/替换一个缓存记录（不涉名字索引） */
    void put(UUID uuid, PlayerData data) {
        cache.put(uuid, data);
    }

    /** 摘除正版名字索引 */
    void unindex(String name) {
        cache.unindex(name);
    }

    /** 入队一次数据库写任务（迁移事务走这里，保证与 flush/saveNow 的落库顺序） */
    void submitWrite(Runnable job) {
        persistence.submitWrite(job);
    }

    public PlayerData getByName(String name) {
        return cache.getByName(name);
    }

    /**
     * 按玩家名查找账号 UUID（正版按名索引，离线由名推导）
     * 以数据库记录为准，避免 usercache 同名缓存到不同 UUID 造成误删
     */
    public UUID findUuidByName(String name) {
        return cache.findUuidByName(name);
    }

    /**
     * 是否已有同名账号（含离线与正版）
     * 正版账号记录名字，按名索引判定；离线账号不记名，但离线 UUID 由用户名确定推导，按推导 UUID 判定等价
     */
    public boolean hasAccountByName(String name) {
        return cache.hasAccountByName(name);
    }

    /** 是否为正版账号（premium=1） */
    public boolean isPremium(UUID uuid) {
        return cache.isPremium(uuid);
    }

    /** 数据库中是否存在正版账号（premium=1）：premiumNameIndex 仅收录正版账号 */
    public boolean hasPremiumAccount() {
        return cache.hasPremiumAccount();
    }

    public void removePlayer(UUID uuid) {
        operations.removePlayer(uuid);
    }

    /**
     * 清理不活跃账号：删除超过 days 天未登录的非正版账号。
     * 活跃时间取 lastActive 与 lastLogin 的较大者；两者均为 0 时无法判断活跃度，跳过以保护数据。
     * 正版账号与在线玩家不受影响。同步执行（启动时调用，玩家尚未进入）。
     * @return 清理的账号数量
     */
    public int purgeInactive(int days) {
        return operations.purgeInactive(days);
    }

    public void updatePassword(UUID uuid, String newHash) {
        operations.updatePassword(uuid, newHash);
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
