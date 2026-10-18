package org.howsauth.plugin.auth;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.api.event.HSAuthLoginEvent;
import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.data.PlayerData;
import org.howsauth.plugin.data.PlayerDataManager;

/**
 * 登录成功后的状态收尾：IP/时间更新、会话标记、事件触发、珍珠/背包/游戏模式恢复。
 * <p>
 * 从 {@link LoginFlow} 拆出，让"验证是否成功"与"成功后做了什么"分开维护。
 */
final class LoginSuccessHandler {

    private final HowSAuth plugin;
    private final PlayerDataManager dataManager;
    private final ConfigManager configManager;
    private final SessionStore sessions;
    private final FailProtection failProtection;
    private final TwoFactorAuth twoFactor;
    private final AccountLifecycle accounts;

    LoginSuccessHandler(HowSAuth plugin, PlayerDataManager dataManager, ConfigManager configManager,
                        SessionStore sessions, FailProtection failProtection,
                        TwoFactorAuth twoFactor, AccountLifecycle accounts) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.configManager = configManager;
        this.sessions = sessions;
        this.failProtection = failProtection;
        this.twoFactor = twoFactor;
        this.accounts = accounts;
    }

    /** 登录成功收尾：IP 变动提醒、更新 IP/时间/活跃时间、标记登录、恢复模式、触发事件（须在玩家区域线程调用） */
    void completeLogin(Player player, PlayerData data) {
        var uuid = player.getUniqueId();
        String oldIp = data.ip();
        String ip = SessionStore.clientIp(player);
        long now = PlayerDataManager.nowEpochSeconds();
        data.lastLogin(now);
        if (ip != null) {
            data.ip(ip);
        }
        data.lastActive(now);
        dataManager.save(uuid);

        // 建立登录会话：同 IP 短时间内重连免输密码（固定窗口，命中不续期）
        sessions.markLoginSession(uuid, ip);
        markLoggedIn(uuid);
        // 正版回退玩家密码登录成功，清除回退标记（下次正版验证成功即自动免密）
        accounts.clearPremiumFallback(uuid);
        onLoginSuccess(player);
        Bukkit.getPluginManager().callEvent(new HSAuthLoginEvent(player));
        // IP 变动提醒：上次登录 IP 存在且与本次不同（首次登录无旧 IP 可比，不提醒）。
        // 正版玩家身份经 Mojang 验证，仅在开启正版验证回退（正版可能转密码登录）时才提醒；
        // 离线（非正版）玩家始终提醒。
        if (configManager.login().ipChangeNotifyEnabled()
                && oldIp != null && !oldIp.isEmpty()
                && !oldIp.equals(data.ip())
                && notifyIpChangeFor(data)) {
            player.sendMessage(I18n.msg("login.ip_changed", player, oldIp));
        }
    }

    /** 标记玩家为已登录：置位实时登录态与本次连接认证标记，清理双因素待验证、失败计数、踢出记录 */
    void markLoggedIn(java.util.UUID uuid) {
        sessions.markLoggedIn(uuid);
        twoFactor.clearPending(uuid);
        failProtection.clear(uuid);
    }

    /**
     * 登录/注册成功后的状态恢复：
     * 1. 恢复游戏模式：仅对被设为旁观的玩家恢复，有保存的游戏模式则恢复，否则使用服务器默认游戏模式（新玩家）
     * 2. 物品状态恢复：未登录期间数据包监听器清空了该玩家的背包和装备（仅本人视角，他人不受影响），
     *    登录后调用 updateInventory 让服务器重发真实背包内容（含装备槽）。
     * <p>
     * 使用玩家调度器执行，保证 Folia 下在玩家区域线程调用（非线程安全操作）。
     */
    void onLoginSuccess(Player player) {
        player.getScheduler().run(plugin, task -> {
            // 立即隐藏提醒 BossBar（不等下一个提醒周期；非 bossbar 方式时为空操作）
            plugin.authReminder().hide(player);
            // 返还退出时保管的飞行末影珍珠（无记录时为空操作）
            plugin.pendingPearls().returnPearls(player);
            // 仅对被设为旁观的玩家恢复游戏模式
            if (sessions.consumeSpectatorPending(player.getUniqueId())) {
                PlayerData data = dataManager.getPlayer(player.getUniqueId());
                // 默认使用服务器默认游戏模式（server.properties 中的 level-default-gamemode）
                org.bukkit.GameMode mode = org.bukkit.Bukkit.getDefaultGameMode();
                if (data != null && data.gameMode() != null) {
                    try {
                        mode = org.bukkit.GameMode.valueOf(data.gameMode());
                    } catch (IllegalArgumentException ignored) {
                        // 存储的游戏模式无效，使用服务器默认值
                    }
                }
                player.setGameMode(mode);
                // 新玩家首次注册时保存默认游戏模式，下次登录可恢复
                if (data != null && data.gameMode() == null) {
                    data.gameMode(mode.name());
                    dataManager.save(player.getUniqueId());
                }
            }
            player.updateInventory();
        }, null);
    }

    /** IP 变动提醒是否适用于该玩家：离线玩家提醒；正版玩家仅当正版验证回退开启时提醒（fallback 仅约束正版） */
    private boolean notifyIpChangeFor(PlayerData data) {
        return !data.premium() || configManager.premium().passwordFallbackEnabled();
    }
}
