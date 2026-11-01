package org.howsauth.plugin.auth;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.data.PlayerData;
import org.howsauth.plugin.data.PlayerDataManager;

import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * 密码登录校验：玩家会话登录与配置阶段登录。
 * <p>
 * 只负责 bcrypt 校验、密码算法对齐、2FA 分流与登录失败计数；成功后调用
 * {@link LoginSuccessHandler}，注册/密码管理等仍在 {@link LoginFlow}。
 */
final class LoginVerification {

    private final HowSAuth plugin;
    private final PlayerDataManager dataManager;
    private final ConfigManager configManager;
    private final SessionStore sessions;
    private final FailProtection failProtection;
    private final TwoFactorAuth twoFactor;
    private final LoginSuccessHandler loginSuccess;
    private final AuthEvents events;

    LoginVerification(HowSAuth plugin, PlayerDataManager dataManager, ConfigManager configManager,
                      SessionStore sessions, FailProtection failProtection,
                      TwoFactorAuth twoFactor, LoginSuccessHandler loginSuccess, AuthEvents events) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.configManager = configManager;
        this.sessions = sessions;
        this.failProtection = failProtection;
        this.twoFactor = twoFactor;
        this.loginSuccess = loginSuccess;
        this.events = events;
    }

    /**
     * 玩家会话登录：bcrypt 校验在异步线程，状态变更回到玩家区域线程。
     */
    void loginAsync(Player player, String password, BiConsumer<AuthManager.LoginResult, Long> done) {
        UUID uuid = player.getUniqueId();
        // 轻量检查（主线程/调用线程）
        if (failProtection.isKicked(player)) {
            done.accept(AuthManager.LoginResult.FAILED, failProtection.getKickRemaining(player));
            return;
        }
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) {
            done.accept(AuthManager.LoginResult.FAILED, 0L);
            return;
        }
        // 重入保护：已有一次密码校验进行中时静默忽略本次，避免重复校验、重复登录事件与消息
        if (!sessions.beginVerifying(uuid)) {
            return;
        }
        final PlayerData snapshot = data;
        String playerIp = SessionStore.clientIp(player);
        // bcrypt 校验与算法对齐重哈希均耗时，移到异步线程执行
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            PasswordVerification verification = verifyPassword(snapshot, uuid, password, playerIp);
            // 状态变更需回到玩家区域线程（Folia 线程安全）
            player.getScheduler().run(plugin, task2 -> {
                sessions.endVerifying(uuid);
                // 回调期间被强制登录或账号被注销（管理员操作与异步校验的竞态）：丢弃本次结果
                // 避免二次登录收尾（重复事件/覆盖会话时间）与注销后的幽灵登录
                if (sessions.isLoggedIn(uuid) || dataManager.getPlayer(uuid) == null) {
                    return;
                }
                if (!verification.ok()) {
                    handleLoginFailure(uuid, player);
                    done.accept(AuthManager.LoginResult.FAILED, failProtection.isKicked(player) ? failProtection.getKickRemaining(player) : 0L);
                } else if (verification.needTwoFactor()) {
                    twoFactor.markPending(uuid);
                    done.accept(AuthManager.LoginResult.NEED_2FA, 0L);
                } else {
                    // 密码明文仅此处可用，须在进入 2FA 等待前完成对齐
                    if (verification.alignedHash() != null) snapshot.passwordHash(verification.alignedHash());
                    loginSuccess.completeLogin(player, snapshot);
                    done.accept(AuthManager.LoginResult.SUCCESS, 0L);
                }
            }, null);
        });
    }

    /**
     * 配置阶段异步登录（Pre-join Dialog，无 Player 实体）：
     * bcrypt 校验在异步线程执行，回调也在异步线程（调用方仅做线程安全操作：重弹窗口/断连/闭锁）。
     * 成功不立即完成登录——登录收尾（IP/时间更新、事件）延迟到玩家进入世界时由 LoginFlow 的
     * finishPreJoinLogin 处理。
     *
     * @param ip 玩家 IP（用于 2FA 会话判断，null 视为无会话）
     * @param done 回调（异步线程调用）：参数 1 登录结果；参数 2 失败时的剩余踢出秒数
     */
    void loginConfigAsync(UUID uuid, String password, String ip, BiConsumer<AuthManager.LoginResult, Long> done) {
        if (failProtection.isKicked(uuid)) {
            long remaining = failProtection.getKickRemaining(uuid);
            if (Debug.on()) {
                Debug.log("auth", "login config %s: %s (kick %ss remaining)",
                        Debug.shortId(uuid), AuthManager.LoginResult.FAILED, remaining);
            }
            done.accept(AuthManager.LoginResult.FAILED, remaining);
            return;
        }
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) {
            if (Debug.on()) {
                Debug.log("auth", "login config %s: %s (no account)", Debug.shortId(uuid), AuthManager.LoginResult.FAILED);
            }
            done.accept(AuthManager.LoginResult.FAILED, 0L);
            return;
        }
        // 注意：此处不复用 loginAsync 的 verifying 重入保护。pre-join 窗口提交后即关闭（串行），
        // 不会并发双提交；且若重入直接 return 不回调 done，会导致配置线程永久阻塞（连接卡死）。
        final PlayerData snapshot = data;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            PasswordVerification verification = verifyPassword(snapshot, uuid, password, ip);
            if (!verification.ok()) {
                handleLoginFailure(uuid, null);
                long remaining = failProtection.isKicked(uuid) ? failProtection.getKickRemaining(uuid) : 0L;
                if (Debug.on()) {
                    if (remaining > 0) {
                        Debug.log("auth", "login config %s: %s (wrong password, kick %ss remaining)",
                                Debug.shortId(uuid), AuthManager.LoginResult.FAILED, remaining);
                    } else {
                        Debug.log("auth", "login config %s: %s (wrong password)",
                                Debug.shortId(uuid), AuthManager.LoginResult.FAILED);
                    }
                }
                done.accept(AuthManager.LoginResult.FAILED, remaining);
            } else if (verification.needTwoFactor()) {
                twoFactor.markPending(uuid);
                if (Debug.on()) {
                    Debug.log("auth", "login config %s: %s", Debug.shortId(uuid), AuthManager.LoginResult.NEED_2FA);
                }
                done.accept(AuthManager.LoginResult.NEED_2FA, 0L);
            } else {
                if (verification.alignedHash() != null) snapshot.passwordHash(verification.alignedHash());
                if (Debug.on()) {
                    Debug.log("auth", "login config %s: %s", Debug.shortId(uuid), AuthManager.LoginResult.SUCCESS);
                }
                done.accept(AuthManager.LoginResult.SUCCESS, 0L);
            }
        });
    }

    /** 同一次密码校验的共享结果：密码是否正确、是否需要重哈希、是否需要 2FA。 */
    private record PasswordVerification(boolean ok, String alignedHash, boolean needTwoFactor) {}

    /** 在异步线程执行密码校验、算法对齐判断与 2FA 需求判断，避免两条登录路径重复。 */
    private PasswordVerification verifyPassword(PlayerData data, UUID uuid, String password, String ip) {
        boolean ok = PasswordHash.checkPassword(password, data.passwordHash());
        String alignedHash = ok ? alignedPasswordHash(data, password) : null;
        boolean needTwoFactor = ok && twoFactor.requiresAtLogin(uuid, ip);
        return new PasswordVerification(ok, alignedHash, needTwoFactor);
    }

    /** 密码算法对齐：配置算法与存储算法不一致时按配置算法重新哈希（异步线程调用，返回新哈希；无需对齐返回 null） */
    private String alignedPasswordHash(PlayerData data, String password) {
        String configured = configManager.password().hashAlgorithm();
        boolean storedIsBcrypt = PasswordHash.isBcrypt(data.passwordHash());
        boolean configIsBcrypt = "bcrypt".equalsIgnoreCase(configured);
        if (configIsBcrypt == storedIsBcrypt) return null;
        return PasswordHash.hashPassword(password, configured, configManager.password().bcryptCost());
    }

    /** 登录失败处理：失败计数（可能触发踢出）+ 触发失败事件（须在玩家区域线程调用；配置阶段 player 为 null，事件转全局调度器触发） */
    private void handleLoginFailure(UUID uuid, Player player) {
        failProtection.recordFailure(uuid, player);
        events.loginFail(player);
    }
}
