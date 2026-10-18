package org.howsauth.plugin.auth;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.data.PlayerData;
import org.howsauth.plugin.data.PlayerDataManager;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 密码相关操作：修改/添加/移除密码与管理员强制改密/清空密码。
 * <p>
 * 从 {@link LoginFlow} 拆出，bcrypt 校验与哈希仍严格在异步线程执行，
 * 状态变更回到玩家区域线程。凭据变更后的会话失效集中在本类。
 */
final class PasswordFlow {

    private final HowSAuth plugin;
    private final PlayerDataManager dataManager;
    private final ConfigManager configManager;
    private final SessionStore sessions;
    private final TwoFactorAuth twoFactor;
    private final AccountLifecycle accounts;

    PasswordFlow(HowSAuth plugin, PlayerDataManager dataManager, ConfigManager configManager,
                 SessionStore sessions, TwoFactorAuth twoFactor, AccountLifecycle accounts) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.configManager = configManager;
        this.sessions = sessions;
        this.twoFactor = twoFactor;
        this.accounts = accounts;
    }

    /**
     * 移除密码，转为无密码账户。
     * 离线账户已绑定 2FA 时验证码作为确认凭据（移除后即为唯一登录因素）；正版账户凭正版验证放行。
     * @return true 移除成功；false 验证码错误或账号不存在
     */
    boolean removePassword(Player player, String code) {
        UUID uuid = player.getUniqueId();
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) {
            if (Debug.on()) {
                Debug.log("auth", "remove password %s: failed (no account)", player.getName());
            }
            return false;
        }
        // 离线账户移除密码前须验证 TOTP（验证码成为唯一登录因素）；正版账户凭正版验证放行，无需验证码
        if (data.totpSecret() != null && !data.premium()) {
            if (code == null || code.isEmpty() || !Totp.verifyCode(data.totpSecret(), code)) {
                if (Debug.on()) {
                    Debug.log("auth", "remove password %s: rejected (invalid 2fa code)", player.getName());
                }
                return false;
            }
        }
        dataManager.updatePassword(uuid, "");
        if (Debug.on()) {
            Debug.log("auth", "remove password %s: success", player.getName());
        }
        return true;
    }

    /** 强制修改玩家密码（无需验证旧密码，玩家无需在线） */
    boolean forceChangePassword(UUID uuid, String newPassword) {
        if (!dataManager.hasAccount(uuid)) return false;
        String newHash = PasswordHash.hashPassword(newPassword, configManager.password().hashAlgorithm(), configManager.password().bcryptCost());
        dataManager.updatePassword(uuid, newHash);
        invalidateLoginSessions(uuid);
        return true;
    }

    /** 管理员强制清空玩家密码（转为无密码账户）：无需验证旧密码或 2FA 验证码，玩家无需在线 */
    boolean forceRemovePassword(UUID uuid) {
        if (!dataManager.hasAccount(uuid)) return false;
        dataManager.updatePassword(uuid, "");
        invalidateLoginSessions(uuid);
        return true;
    }

    /**
     * 异步修改密码：旧密码校验与新密码哈希（bcrypt 耗时）在异步线程执行，结果回调回到玩家区域线程。
     * 正版与离线同规则，均须先验证旧密码（无密码账户不进入此路径，命令层拦截并引导 /addpassword）
     */
    void changePasswordAsync(Player player, String oldPassword, String newPassword, Consumer<Boolean> done) {
        UUID uuid = player.getUniqueId();
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) {
            if (Debug.on()) {
                Debug.log("auth", "change password %s: failed (no account)", player.getName());
            }
            done.accept(false);
            return;
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            if (!PasswordHash.checkPassword(oldPassword, data.passwordHash())) {
                player.getScheduler().run(plugin, task2 -> {
                    if (Debug.on()) {
                        Debug.log("auth", "change password %s: failed (wrong old password)", player.getName());
                    }
                    done.accept(false);
                }, null);
                return;
            }
            String newHash = PasswordHash.hashPassword(newPassword, configManager.password().hashAlgorithm(), configManager.password().bcryptCost());
            player.getScheduler().run(plugin, task2 -> {
                dataManager.updatePassword(uuid, newHash);
                invalidateLoginSessions(uuid);
                if (Debug.on()) {
                    Debug.log("auth", "change password %s: success", player.getName());
                }
                done.accept(true);
            }, null);
        });
    }

    /**
     * 异步为无密码账户添加密码（转为有密码账户，是解绑 2FA 的前置步骤）：
     * bcrypt 哈希在异步线程执行，结果回调回到玩家区域线程。
     * 已有密码或账号不存在时回调 false
     */
    void addPasswordAsync(Player player, String newPassword, Consumer<Boolean> done) {
        UUID uuid = player.getUniqueId();
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null || (data.passwordHash() != null && !data.passwordHash().isEmpty())) {
            if (Debug.on()) {
                Debug.log("auth", "add password %s: failed (no account or already has password)", player.getName());
            }
            done.accept(false);
            return;
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String newHash = PasswordHash.hashPassword(newPassword, configManager.password().hashAlgorithm(), configManager.password().bcryptCost());
            player.getScheduler().run(plugin, task2 -> {
                dataManager.updatePassword(uuid, newHash);
                if (Debug.on()) {
                    Debug.log("auth", "add password %s: success", player.getName());
                }
                done.accept(true);
            }, null);
        });
    }

    /** 使登录会话失效（凭据变更、强制登出等场景）：清除 lastLogin（免密窗口）与登录/2FA 会话，下次必须重新验证 */
    void invalidateLoginSessions(UUID uuid) {
        accounts.invalidateUnregisterConfirm(uuid);
        PlayerData data = dataManager.getPlayer(uuid);
        if (data != null) {
            data.lastLogin(0);
            dataManager.save(uuid);
        }
        sessions.clearLoginSession(uuid);
        twoFactor.clearSession(uuid);
    }
}
