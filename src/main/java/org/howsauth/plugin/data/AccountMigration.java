package org.howsauth.plugin.data;

import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;

import java.sql.SQLException;
import java.util.UUID;

/**
 * 账号身份迁移：离线↔正版之间的记录搬运。
 * <p>
 * 三种路径共用同一套"内存先改、再经串行写队列做单事务落库"的流程：
 * <ul>
 *   <li>{@link #migrateToPremium}：离线号升级为正版（常规路径）</li>
 *   <li>{@link #preserveExistingPremium}：目标正版 UUID 已有记录时的保留式合并（仅换名字与皮肤）</li>
 *   <li>{@link #migrateToOffline}：正版降级为离线</li>
 * </ul>
 * 三条路径的落库都是"删除旧行 + 写入新行"，必须同一事务，否则中途崩溃会两条记录皆失（账号丢失）。
 * 该事务由 {@link PlayerDatabase#transactionalDeleteThenWrite} 统一提供，本类只负责决定写什么。
 * <p>
 * 与 {@link PlayerDataManager} 的分工：本类处理"迁移这一刻"的一致性（缓存、名字索引、脏标记、落库顺序），
 * 常规增删改查仍留在管理器上。缓存与写队列的访问通过管理器提供的包内钩子完成，见各组注释。
 */
final class AccountMigration {

    private final HowSAuth plugin;
    private final PlayerDataManager manager;
    private final PlayerDatabase database;

    AccountMigration(HowSAuth plugin, PlayerDataManager manager, PlayerDatabase database) {
        this.plugin = plugin;
        this.manager = manager;
        this.database = database;
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
    boolean migrateToPremium(UUID offlineUuid, UUID premiumUuid, String name, String ip, String properties) {
        PlayerData offline = manager.cached(offlineUuid);
        if (offline == null) return false;
        if (Debug.on()) {
            Debug.log("db", "migrate offline->premium: %s", name);
        }
        // 目标已有正版记录：保留原账号数据，仅换绑名字与皮肤，离线号作废（返回 false 让调用方跳过原版数据迁移）
        PlayerData existing = manager.cached(premiumUuid);
        if (existing != null && existing.premium()) {
            preserveExistingPremium(offline, offlineUuid, premiumUuid, existing, name, properties);
            return false;
        }
        manager.discard(offlineUuid, offline.name());
        PlayerData premium = new PlayerData(premiumUuid, name, "",
                ip != null && !ip.isEmpty() ? ip : offline.ip(),
                PlayerDataManager.nowEpochSeconds(), offline.logoutLocation(), true, properties, offline.gameMode(),
                offline.totpSecret(), offline.lastActive());
        manager.store(premiumUuid, premium, name);
        // 迁移事务经串行写队列执行：与 flush/saveNow 的 upsert 保证落库顺序
        submitMigration(offlineUuid, "migrate offline->premium failed (transaction)",
                conn -> database.upsertRow(conn, premium));
        return true;
    }

    /**
     * 目标正版 UUID 已有正版记录时的保留式合并：原正版记录的密码/2FA/退出位置/游戏模式原样保留，
     * 仅更新名字与皮肤（玩家刚完成新名的正版验证）；离线号记录删除作废。
     * 原版玩家数据（dat/成就/统计）不迁移，避免以离线号文件覆盖正版身份下的真实数据。
     */
    private void preserveExistingPremium(PlayerData offline, UUID offlineUuid, UUID premiumUuid,
                                         PlayerData premium, String name, String properties) {
        manager.discard(offlineUuid, offline.name());
        // 目标记录换了名字：旧名字索引一并摘除，避免按旧名反查到该正版号
        String previousName = premium.name();
        if (previousName != null) {
            manager.unindex(previousName);
        }
        premium.properties(properties);
        premium.name(name);
        manager.index(name, premiumUuid);
        plugin.getLogger().info(I18n.get("log.premium_migrate_preserved", name + " (" + offlineUuid + ")"));
        // 作废离线号的原版数据文件一并删除（否则同名新玩家注册会继承遗留的背包/成就/统计）
        plugin.playerFiles().deleteAsync(offlineUuid);
        submitMigration(offlineUuid, "merge into existing premium failed",
                conn -> database.updatePremiumRow(conn, premiumUuid, name, properties));
    }

    /**
     * 将正版账号迁移回离线账号（正版降级为离线）。
     * 用离线 UUID 创建新记录，保留密码、2FA 密钥、退出位置等数据，premium=0，清除皮肤 properties。
     * 异步落库：删除正版记录 + 写入离线记录。
     * 同名离线账号不可能存在（正版记录存续期间 LoginStart 拦截同名离线连接），不做冲突检查。
     * 正版记录不存在时返回 false（注销竞态）。
     */
    boolean migrateToOffline(UUID premiumUuid, UUID offlineUuid) {
        PlayerData premium = manager.take(premiumUuid);
        if (premium == null) return false;
        if (Debug.on()) {
            Debug.log("db", "migrate premium->offline: %s",
                    premium.name() != null ? premium.name() : Debug.shortId(premiumUuid));
        }
        if (premium.name() != null) {
            manager.unindex(premium.name());
        }
        PlayerData offline = new PlayerData(offlineUuid, null, premium.passwordHash(), premium.ip(),
                premium.lastLogin(), premium.logoutLocation(), false, null, premium.gameMode(),
                premium.totpSecret(), premium.lastActive());
        manager.put(offlineUuid, offline);
        // 迁移事务经串行写队列执行：与 flush/saveNow 的 upsert 保证落库顺序
        submitMigration(premiumUuid, "migrate premium->offline failed",
                conn -> database.upsertRow(conn, offline));
        return true;
    }

    /**
     * 迁移落库的统一出口：经串行写队列执行"删除旧行 + 同一事务内写入新行"。
     * <p>
     * 三条迁移路径此前各自复制了一遍事务骨架，此处收敛为一处；写入内容由 {@code write} 决定。
     *
     * @param removedUuid 被删除（作废）的旧记录 UUID
     * @param debugLabel  失败时写入 debug 日志的标签
     * @param write       事务内的写入动作
     */
    private void submitMigration(UUID removedUuid, String debugLabel, PlayerDatabase.TransactionalWrite write) {
        manager.submitWrite(() -> {
            try {
                database.transactionalDeleteThenWrite(removedUuid, write);
            } catch (SQLException e) {
                plugin.getLogger().severe(I18n.get("log.migrate_failed", removedUuid + ": " + e.getMessage()));
                if (Debug.on()) {
                    Debug.log("db", "%s: %s", debugLabel, e.getMessage());
                }
            }
        });
    }
}
