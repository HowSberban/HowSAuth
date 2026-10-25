package org.howsauth.plugin.command;

import org.bukkit.Bukkit;
import org.howsauth.plugin.data.PlayerData;
import org.howsauth.plugin.data.PlayerDataManager;

import java.util.UUID;

/**
 * 管理员命令目标解析：优先离线 UUID，无账号时按名字回溯正版账号。
 * <p>
 * 解析规则被检查类与账号操作类共用，独立后避免复制或让两类互相依赖。
 */
final class AdminTargetResolver {

    private AdminTargetResolver() {
    }

    /**
     * 解析命令目标账号：优先离线 UUID，无账号时按名字匹配正版账号（premium=1 存储正版 UUID）。
     */
    static UUID resolve(PlayerDataManager dataManager, String targetName) {
        UUID uuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
        // 离线模式下 getOfflinePlayer 返回离线 UUID，与正版账号存储的正版 UUID 不匹配，
        // 不回溯会对正版玩家误报"账号不存在"
        if (!dataManager.hasAccount(uuid)) {
            PlayerData premium = dataManager.getByName(targetName);
            if (premium != null) uuid = premium.uuid();
        }
        return uuid;
    }
}
