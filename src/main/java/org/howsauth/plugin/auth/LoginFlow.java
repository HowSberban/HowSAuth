package org.howsauth.plugin.auth;

import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.api.event.HSAuthRegisterEvent;
import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.data.PlayerDataManager;
import org.howsauth.plugin.data.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 登录/注册编排：密码校验（bcrypt 在异步线程）、2FA 转接、登录成功收尾、
 * 免密与 pre-join 收尾、密码增改删、管理员强制操作、退出清理。
 * <p>
 * 本类只做编排，不自有状态：会话/失败计数/2FA/账号数据分别由
 * {@link SessionStore}/{@link FailProtection}/{@link TwoFactorAuth}/{@link AccountLifecycle} 持有。
 * <p>
 * <b>线程契约</b>：bcrypt 校验一律在异步线程，状态变更回到玩家区域线程（Folia）；
 * {@link #loginAsync} 以 {@code SessionStore#beginVerifying} 做重入保护，
 * {@link #loginConfigAsync} 不做（pre-join 串行提交，重入直接返回会卡死配置线程）。
 */
public final class LoginFlow {

    private final HowSAuth plugin;
    private final PlayerDataManager dataManager;
    private final ConfigManager configManager;
    private final AuthEvents events;
    private final SessionStore sessions;
    private final TwoFactorAuth twoFactor;
    private final AccountLifecycle accounts;
    private final LoginSuccessHandler loginSuccess;
    private final LoginVerification verification;
    private final PasswordFlow passwordFlow;

    LoginFlow(HowSAuth plugin, PlayerDataManager dataManager, ConfigManager configManager,
              AuthEvents events, SessionStore sessions, FailProtection failProtection,
              TwoFactorAuth twoFactor, AccountLifecycle accounts) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.configManager = configManager;
        this.events = events;
        this.sessions = sessions;
        this.twoFactor = twoFactor;
        this.accounts = accounts;
        this.loginSuccess = new LoginSuccessHandler(plugin, dataManager, configManager,
                sessions, failProtection, twoFactor, accounts);
        this.verification = new LoginVerification(plugin, dataManager, configManager,
                sessions, failProtection, twoFactor, loginSuccess, events);
        this.passwordFlow = new PasswordFlow(plugin, dataManager, configManager,
                sessions, twoFactor, accounts);
    }

    /**
     * 异步注册：bcrypt 哈希在异步线程执行（避免阻塞玩家区域线程），建号与登录收尾回到玩家区域线程。
     * @param done 回调（玩家区域线程）：true 注册成功；false 已达 IP 上限或账号已存在（并发竞态）
     */
    public void registerAsync(Player player, String password, Consumer<Boolean> done) {
        UUID uuid = player.getUniqueId();
        String ip = SessionStore.clientIp(player);
        if (accounts.isIpAccountLimitReached(ip)) {
            if (Debug.on()) {
                Debug.log("auth", "register %s: failed (ip account limit reached)", player.getName());
            }
            done.accept(false);
            return;
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String hash = PasswordHash.hashPassword(password, configManager.password().hashAlgorithm(), configManager.password().bcryptCost());
            player.getScheduler().run(plugin, task2 -> {
                // 同名账号（含正版）已存在时拒绝；离线 UUID 由名推导，该判定同时覆盖账号已存在的并发竞态
                if (dataManager.hasAccountByName(player.getName())) {
                    if (Debug.on()) {
                        Debug.log("auth", "register %s: failed (account already exists)", player.getName());
                    }
                    done.accept(false);
                    return;
                }
                // 建号+登录收尾在区域线程（轻量），立即落库（关键操作防崩溃丢失，经串行写队列）
                // 名额判定与建号原子完成：并发注册同一 IP 不会全部通过检查（防 max-accounts-per-ip 被绕过）
                if (dataManager.createPlayerIfIpAllowed(uuid, hash, ip != null ? ip : "unknown",
                        configManager.register().maxAccountsPerIp()) == null) {
                    if (Debug.on()) {
                        Debug.log("auth", "register %s: failed (ip account limit reached, atomic check)", player.getName());
                    }
                    done.accept(false);
                    return;
                }
                sessions.markLoginSession(uuid, ip);
                loginSuccess.markLoggedIn(uuid);
                loginSuccess.onLoginSuccess(player);
                events.register(uuid, player);
                if (Debug.on()) {
                    Debug.log("auth", "register %s: success", player.getName());
                }
                done.accept(true);
            }, null);
        });
    }

    public void loginAsync(Player player, String password, BiConsumer<AuthManager.LoginResult, Long> done) {
        verification.loginAsync(player, password, done);
    }

    /**
     * 配置阶段异步登录（Pre-join Dialog）：校验在异步线程执行，成功后由
     * {@link #finishPreJoinLogin(Player)} 在进入世界时收尾。
     */
    public void loginConfigAsync(UUID uuid, String password, String ip, BiConsumer<AuthManager.LoginResult, Long> done) {
        verification.loginConfigAsync(uuid, password, ip, done);
    }

    /** 移除密码，转为无密码账户（验证码/正版验证规则见 PasswordFlow）。 */
    public boolean removePassword(Player player, String code) {
        return passwordFlow.removePassword(player, code);
    }

    /**
     * 完成双因素验证：校验 TOTP 验证码，通过则完成登录。
     * @return true 验证通过且登录完成
     */
    public boolean verify2fa(Player player, String code) {
        PlayerData data = twoFactor.verifyCode(player.getUniqueId(), player, code, SessionStore.clientIp(player));
        if (data == null) return false;
        loginSuccess.completeLogin(player, data);
        return true;
    }

    /** 配置阶段完成双因素验证（Pre-join Dialog）：通过则由调用方放行（登录收尾延迟到进入世界时），失败回到待验证状态 */
    public boolean verify2faConfig(UUID uuid, String code, String ip) {
        return twoFactor.verifyCode(uuid, null, code, ip) != null;
    }

    /**
     * 更新玩家活跃时间：玩家加入时调用，无论是否登录成功（活跃=进过服）。
     * 未注册玩家无账号不处理（完全不写库）。
     */
    public void touchActive(Player player) {
        PlayerData data = dataManager.getPlayer(player.getUniqueId());
        if (data == null) return;
        data.lastActive(PlayerDataManager.nowEpochSeconds());
        dataManager.save(player.getUniqueId());
    }

    // ===== 管理员强制操作 =====

    /** 强制登出玩家（无需玩家在线，清除登录状态，并使登录会话与 2FA 会话失效） */
    public boolean forceLogout(UUID uuid) {
        if (!sessions.invalidateLoggedIn(uuid)) return false;
        if (Debug.on()) {
            Debug.log("auth", "force logout: %s (live login state invalidated, connection flag kept)", uuid);
        }
        passwordFlow.invalidateLoginSessions(uuid);
        events.logout(uuid);
        return true;
    }

    /** 强制修改玩家密码（无需验证旧密码，玩家无需在线） */
    public boolean forceChangePassword(UUID uuid, String newPassword) {
        return passwordFlow.forceChangePassword(uuid, newPassword);
    }

    /** 管理员强制清空玩家密码（转为无密码账户）：无需验证旧密码或 2FA 验证码，玩家无需在线 */
    public boolean forceRemovePassword(UUID uuid) {
        return passwordFlow.forceRemovePassword(uuid);
    }

    /** 强制登录玩家（不管有没有账号，仅对在线玩家生效） */
    public void forceLogin(Player player) {
        UUID uuid = player.getUniqueId();
        if (Debug.on()) {
            Debug.log("auth", "force login: %s", player.getName());
        }
        loginSuccess.markLoggedIn(uuid);
        loginSuccess.onLoginSuccess(player);
        events.login(player);
    }

    // 免密登录：跳过密码验证直接完成登录（会话命中或正版验证通过后调用）
    // 登录需完成 2FA 的账号不直接放行：进入待验证状态，由 /2fa <验证码> 完成登录
    // 2FA 会话命中（同 IP 且未过期）时同样直接完成登录
    public void autoLogin(Player player) {
        PlayerData data = dataManager.getPlayer(player.getUniqueId());
        if (data == null) return;
        String ip = SessionStore.clientIp(player);
        if (Debug.on()) {
            Debug.log("session", "auto login for %s (2FA required=%s)", player.getName(), twoFactor.requiresAtLogin(player.getUniqueId(), ip));
        }
        if (twoFactor.requiresAtLogin(player.getUniqueId(), ip)) {
            twoFactor.markPending(player.getUniqueId());
            return;
        }
        loginSuccess.completeLogin(player, data);
    }

    // ===== Pre-join Dialog 收尾（配置阶段认证后，玩家进入世界时调用） =====

    /**
     * Pre-join 认证完成后玩家进入世界时的登录收尾：completeLogin 全流程
     * （IP/时间更新、标记登录、清理回退标记、恢复物品、触发登录事件、IP 变动提醒）。
     * @return false 表示账号数据已不存在（被注销的竞态），调用方应回退正常登录流程
     */
    public boolean finishPreJoinLogin(Player player) {
        PlayerData data = dataManager.getPlayer(player.getUniqueId());
        if (data == null) return false;
        loginSuccess.completeLogin(player, data);
        return true;
    }

    /**
     * Pre-join 注册完成后玩家进入世界时的收尾：与 register 的登录后处理一致
     * （建立免密会话、标记登录、恢复物品状态、触发注册事件；不更新登录时间/IP——createPlayer 已记录）。
     * @return false 表示账号数据已不存在（被注销的竞态），调用方应回退正常登录流程
     */
    public boolean finishPreJoinRegister(Player player) {
        if (!dataManager.hasAccount(player.getUniqueId())) return false;
        sessions.markLoginSession(player.getUniqueId(), SessionStore.clientIp(player));
        loginSuccess.markLoggedIn(player.getUniqueId());
        loginSuccess.onLoginSuccess(player);
        Bukkit.getPluginManager().callEvent(new HSAuthRegisterEvent(player.getUniqueId(), player));
        return true;
    }

    // Change password
    /**
     * 异步修改密码：旧密码校验与新密码哈希（bcrypt 耗时）在异步线程执行，结果回调回到玩家区域线程。
     * 正版与离线同规则，均须先验证旧密码（无密码账户不进入此路径，命令层拦截并引导 /addpassword）
     */
    public void changePasswordAsync(Player player, String oldPassword, String newPassword, Consumer<Boolean> done) {
        passwordFlow.changePasswordAsync(player, oldPassword, newPassword, done);
    }

    /**
     * 异步为无密码账户添加密码（转为有密码账户，是解绑 2FA 的前置步骤）：bcrypt 哈希在异步线程执行，结果回调回到玩家区域线程。
     * 已有密码或账号不存在时回调 false
     */
    public void addPasswordAsync(Player player, String newPassword, Consumer<Boolean> done) {
        passwordFlow.addPasswordAsync(player, newPassword, done);
    }

    /**
     * 玩家退出时调用（PlayerListener#onQuitCleanup，MONITOR）— 清理会话状态
     * 调用点固定在退出流程的最后阶段：实时登录态与本次连接认证标记的失效须晚于依赖它们的退出处理（如退出消息决策），不可提前
     */
    public void clearSession(Player player) {
        UUID uuid = player.getUniqueId();
        accounts.invalidateUnregisterConfirm(uuid);
        // 会话级状态（登录态/认证标记/校验中/过渡标记/超时标记）统一由 SessionStore 清理；
        // 其中认证标记随连接结束失效，须晚于加入/退出消息与退出位置保存的判定
        sessions.clear(uuid);
        // 清除双因素认证会话状态（未完成验证即退出）
        twoFactor.clearPending(uuid);
        twoFactor.clearPendingSecret(uuid);
        // 清除正版回退标记（会话级状态：本次连接要求密码登录，退出即失效，
        // 防止残留标记使下次验证成功的连接仍误走密码路径）
        accounts.clearPremiumFallback(uuid);
        // 注意：不清除失败计数与踢出记录（FailProtection 持有的两个 Map）。
        // 玩家被踢出或退出会触发 PlayerQuitEvent → 本方法；若在此清除，
        // 攻击者可通过"失败1-2次→重连"重置连续失败计数、或借被踢重连绕过踢出期，
        // 使 fail-protection 的连续失败阈值与踢出期保护失效。
        // 两者均为跨连接的暴力破解防护，须保留至达到阈值/登录成功/到期，由 FailProtection 清理。
    }

    // ===== 登录成功后的状态恢复 =====

}
