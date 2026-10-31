package org.howsauth.plugin.data;

import org.bukkit.Bukkit;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 玩家账号的内存写操作：创建、正版标记、删除、不活跃清理、改密。
 * <p>
 * 负责"改内存 + 触发落库"，不做加载、查询或迁移事务；迁移仍由 {@link AccountMigration} 处理。
 */
final class PlayerAccountOperations {

    private final HowSAuth plugin;
    private final PlayerDatabase database;
    private final PlayerCache cache;
    private final PlayerPersistence persistence;
    // IP 注册名额锁：同 IP 计数与建号须在同一临界区内完成，防止并发注册同时通过检查突破上限
    private final Object ipLimitLock = new Object();

    PlayerAccountOperations(HowSAuth plugin, PlayerDatabase database,
                            PlayerCache cache, PlayerPersistence persistence) {
        this.plugin = plugin;
        this.database = database;
        this.cache = cache;
        this.persistence = persistence;
    }

    void createPlayer(UUID uuid, String passwordHash, String ip) {
        PlayerData data = new PlayerData(uuid, null, passwordHash, ip, PlayerDataManager.nowEpochSeconds(),
                null, false, null, null, null, 0);
        cache.put(uuid, data);
        // 创建账号为关键操作：立即落库，避免崩溃丢新账号（区别于登录/退出等的周期批量 flush）
        persistence.saveNow(data);
    }

    /**
     * 受同 IP 名额限制的原子建号：计数与建号在同一临界区内完成，并发注册同一 IP
     * 不会全部通过检查（防止 max-accounts-per-ip 被并发绕过）
     */
    PlayerData createPlayerIfIpAllowed(UUID uuid, String passwordHash, String ip, int maxAccounts) {
        synchronized (ipLimitLock) {
            if (maxAccounts > 0 && ip != null && cache.countByIp(ip) >= maxAccounts) {
                if (Debug.on()) {
                    Debug.log("db", "create player %s rejected: ip account limit reached", Debug.shortId(uuid));
                }
                return null;
            }
            PlayerData data = new PlayerData(uuid, null, passwordHash, ip, PlayerDataManager.nowEpochSeconds(),
                    null, false, null, null, null, 0);
            cache.put(uuid, data);
            persistence.saveNow(data);
            return data;
        }
    }

    void createPremiumPlayer(UUID uuid, String name, String ip, String properties) {
        PlayerData data = new PlayerData(uuid, name, "", ip, PlayerDataManager.nowEpochSeconds(),
                null, true, properties, null, null, 0);
        cache.putIndexed(uuid, data, name);
        persistence.saveNow(data);
    }

    /** 标记已有账号为正版（premium=1），更新 properties 和 name */
    void markPremium(UUID uuid, String name, String properties) {
        PlayerData data = cache.get(uuid);
        if (data == null) return;
        cache.unindex(data.name());
        data.premium(true);
        data.properties(properties);
        data.name(name);
        cache.index(name, uuid);
        persistence.saveNow(data);
    }

    void forceMarkPremium(UUID uuid) {
        PlayerData data = cache.get(uuid);
        if (data == null) return;
        data.premium(true);
        persistence.saveNow(data);
    }

    void forceMarkOffline(UUID uuid) {
        PlayerData data = cache.get(uuid);
        if (data == null) return;
        data.premium(false);
        persistence.saveNow(data);
    }

    void removePlayer(UUID uuid) {
        if (Debug.on()) {
            Debug.log("db", "remove player: %s", Debug.shortId(uuid));
        }
        PlayerData data = cache.remove(uuid);
        if (data != null && data.name() != null) {
            cache.unindex(data.name());
        }
        // 清理落库相关状态：账号已删除，脏标记与重试计数不再有意义（防止残留）
        persistence.forget(uuid);
        // 删除经串行写队列执行：内存已先移除，排在前面的在途 upsert 会因执行时复检被跳过
        persistence.submitWrite(() -> {
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
     */
    int purgeInactive(int days) {
        long threshold = PlayerDataManager.nowEpochSeconds() - days * 86400L;
        List<UUID> toDelete = new ArrayList<>();
        for (PlayerData data : cache.all()) {
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

    void updatePassword(UUID uuid, String newHash) {
        PlayerData data = cache.get(uuid);
        if (data == null) return;
        data.passwordHash(newHash);
        persistence.saveNow(data);
    }
}
