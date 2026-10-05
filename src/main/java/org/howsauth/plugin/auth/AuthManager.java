package org.howsauth.plugin.auth;

import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.api.event.HSAuthLoginEvent;
import org.howsauth.plugin.api.event.HSAuthRegisterEvent;
import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.data.PlayerDataManager;
import org.howsauth.plugin.data.PlayerDataManager.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class AuthManager {

    /** 登录结果 */
    public enum LoginResult {
        /** 登录成功 */
        SUCCESS,
        /** 密码正确但需完成双因素认证 */
        NEED_2FA,
        /** 登录失败 */
        FAILED
    }

    private final HowSAuth plugin;
    private final PlayerDataManager dataManager;
    private final ConfigManager configManager;
    private final AuthEvents events;
    private final SessionStore sessions;
    private final FailProtection failProtection;
    private final TwoFactorAuth twoFactor;
    private final LogoutLocation locations;
    private final AccountLifecycle accounts;

    public AuthManager(HowSAuth plugin, PlayerDataManager dataManager, ConfigManager configManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.configManager = configManager;
        this.events = new AuthEvents(plugin);
        this.sessions = new SessionStore(configManager, dataManager);
        this.failProtection = new FailProtection(configManager);
        this.twoFactor = new TwoFactorAuth(dataManager, configManager, failProtection, events);
        this.locations = new LogoutLocation(plugin, dataManager, configManager, sessions);
        this.accounts = new AccountLifecycle(plugin, dataManager, configManager, events, sessions,
                failProtection, twoFactor, this::cleanupExpiredStates);
        // 周期清理过期的 2FA 临时密钥与登录/2FA 会话等状态（懒清理兜底，随插件关闭统一取消）
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
            twoFactor.cleanupExpiredSecrets();
            cleanupExpiredStates();
        }, 1, 30, TimeUnit.SECONDS);
    }

    /**
     * 会话状态中心：登录态、本次连接认证标记、校验中/过渡待处理标记。
     * 拆分后由本类持有组装，调用方直接使用该服务（AuthManager 不再提供转发）。
     */
    public SessionStore sessions() {
        return sessions;
    }

    /**
     * 暴力破解防护：失败计数、踢出期与过期淘汰。
     * 拆分后由本类持有组装，调用方直接使用该服务（AuthManager 不再提供转发）。
     */
    public FailProtection failProtection() {
        return failProtection;
    }

    /**
     * 双因素认证（TOTP）：待验证状态、验证码校验、绑定/解绑、2FA 会话。
     * 拆分后由本类持有组装，调用方直接使用该服务（AuthManager 不再提供转发）。
     */
    public TwoFactorAuth twoFactor() {
        return twoFactor;
    }

    /**
     * 退出位置与坐标保护：位置保存/读取/传送、安全出生点、区块预载与旁观切换。
     * 拆分后由本类持有组装，调用方直接使用该服务（AuthManager 不再提供转发）。
     */
    public LogoutLocation locations() {
        return locations;
    }

    /**
     * 账号生命周期：注册、注销、原版数据删除与迁移、正版升降级标记、账号属性查询。
     * 拆分后由本类持有组装，调用方直接使用该服务（AuthManager 不再提供转发）。
     */
    public AccountLifecycle accounts() {
        return accounts;
    }

    /** 提取玩家客户端 IP（getAddress 可能为 null，如代理协议未解析完成时） */
    public static String clientIp(Player player) {
        return SessionStore.clientIp(player);
    }

    /**
     * 异步注册：bcrypt 哈希在异步线程执行（避免阻塞玩家区域线程），建号与登录收尾回到玩家区域线程。
     * @param done 回调（玩家区域线程）：true 注册成功；false 已达 IP 上限或账号已存在（并发竞态）
     */
    public void registerAsync(Player player, String password, Consumer<Boolean> done) {
        UUID uuid = player.getUniqueId();
        String ip = clientIp(player);
        if (accounts.isIpAccountLimitReached(ip)) {
            if (Debug.on()) {
                Debug.log("auth", "register %s: failed (ip account limit reached)", player.getName());
            }
            done.accept(false);
            return;
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String hash = PasswordHash.hashPassword(password, configManager.passwordHashAlgorithm(), configManager.bcryptCost());
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
                        configManager.maxAccountsPerIp()) == null) {
                    if (Debug.on()) {
                        Debug.log("auth", "register %s: failed (ip account limit reached, atomic check)", player.getName());
                    }
                    done.accept(false);
                    return;
                }
                sessions.markLoginSession(uuid, ip);
                markLoggedIn(uuid);
                onLoginSuccess(player);
                events.register(uuid, player);
                if (Debug.on()) {
                    Debug.log("auth", "register %s: success", player.getName());
                }
                done.accept(true);
            }, null);
        });
    }

    public void loginAsync(Player player, String password, BiConsumer<LoginResult, Long> done) {
        UUID uuid = player.getUniqueId();
        // 轻量检查（主线程/调用线程）
        if (failProtection.isKicked(player)) {
            done.accept(LoginResult.FAILED, failProtection.getKickRemaining(player));
            return;
        }
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) {
            done.accept(LoginResult.FAILED, 0L);
            return;
        }
        // 重入保护：已有一次密码校验进行中时静默忽略本次，避免重复校验、重复登录事件与消息
        if (!sessions.beginVerifying(uuid)) {
            return;
        }
        final PlayerData snapshot = data;
        // bcrypt 校验与算法对齐重哈希均耗时，移到异步线程执行
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            boolean ok = PasswordHash.checkPassword(password, snapshot.passwordHash());
            // 密码算法对齐：配置算法与存储算法不一致时按配置算法重新哈希
            // 哈希计算在异步线程完成（约 200-300ms），仅结果写回区域线程
            String alignedHash = ok ? alignedPasswordHash(snapshot, password) : null;
            // 状态变更需回到玩家区域线程（Folia 线程安全）
            player.getScheduler().run(plugin, task2 -> {
                sessions.endVerifying(uuid);
                // 回调期间被强制登录或账号被注销（管理员操作与异步校验的竞态）：丢弃本次结果
                // 避免二次登录收尾（重复事件/覆盖会话时间）与注销后的幽灵登录
                if (sessions.isLoggedIn(uuid) || dataManager.getPlayer(uuid) == null) {
                    return;
                }
                if (!ok) {
                    handleLoginFailure(uuid, player);
                    done.accept(LoginResult.FAILED, failProtection.isKicked(player) ? failProtection.getKickRemaining(player) : 0L);
                } else {
                    // 密码明文仅此处可用，须在进入 2FA 等待前完成对齐
                    if (alignedHash != null) snapshot.passwordHash(alignedHash);
                    String playerIp = clientIp(player);
                    if (twoFactor.requiresAtLogin(uuid, playerIp)) {
                        // 密码正确但需双因素认证：进入待验证状态，不算已登录
                        // 开关关闭时跳过验证（密钥保留在数据库，重新开启后恢复）
                        twoFactor.markPending(uuid);
                        done.accept(LoginResult.NEED_2FA, 0L);
                    } else {
                        completeLogin(player, snapshot);
                        done.accept(LoginResult.SUCCESS, 0L);
                    }
                }
            }, null);
        });
    }

    /**
     * 配置阶段异步登录（Pre-join Dialog，无 Player 实体）：
     * bcrypt 校验在异步线程执行，回调也在异步线程（调用方仅做线程安全操作：重弹窗口/断连/闭锁）。
     * 成功不立即完成登录——登录收尾（IP/时间更新、事件）延迟到玩家进入世界时由 finishPreJoinLogin 处理。
     * @param ip 玩家 IP（用于 2FA 会话判断，null 视为无会话）
     * @param done 回调（异步线程调用）：参数 1 登录结果；参数 2 失败时的剩余踢出秒数
     */
    public void loginConfigAsync(UUID uuid, String password, String ip, BiConsumer<LoginResult, Long> done) {
        if (failProtection.isKicked(uuid)) {
            long remaining = failProtection.getKickRemaining(uuid);
            if (Debug.on()) {
                Debug.log("auth", "login config %s: %s (kick %ss remaining)",
                        uuid.toString().substring(0, 8), LoginResult.FAILED, remaining);
            }
            done.accept(LoginResult.FAILED, remaining);
            return;
        }
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) {
            if (Debug.on()) {
                Debug.log("auth", "login config %s: %s (no account)", uuid.toString().substring(0, 8), LoginResult.FAILED);
            }
            done.accept(LoginResult.FAILED, 0L);
            return;
        }
        // 注意：此处不复用 loginAsync 的 verifying 重入保护。pre-join 窗口提交后即关闭（串行），
        // 不会并发双提交；且若重入直接 return 不回调 done，会导致配置线程永久阻塞（连接卡死）。
        final PlayerData snapshot = data;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            boolean ok = PasswordHash.checkPassword(password, snapshot.passwordHash());
            if (!ok) {
                handleLoginFailure(uuid, null);
                long remaining = failProtection.isKicked(uuid) ? failProtection.getKickRemaining(uuid) : 0L;
                if (Debug.on()) {
                    if (remaining > 0) {
                        Debug.log("auth", "login config %s: %s (wrong password, kick %ss remaining)",
                                uuid.toString().substring(0, 8), LoginResult.FAILED, remaining);
                    } else {
                        Debug.log("auth", "login config %s: %s (wrong password)",
                                uuid.toString().substring(0, 8), LoginResult.FAILED);
                    }
                }
                done.accept(LoginResult.FAILED, remaining);
            } else {
                String alignedHash = alignedPasswordHash(snapshot, password);
                if (alignedHash != null) snapshot.passwordHash(alignedHash);
                if (twoFactor.requiresAtLogin(uuid, ip)) {
                    twoFactor.markPending(uuid);
                    if (Debug.on()) {
                        Debug.log("auth", "login config %s: %s", uuid.toString().substring(0, 8), LoginResult.NEED_2FA);
                    }
                    done.accept(LoginResult.NEED_2FA, 0L);
                } else {
                    if (Debug.on()) {
                        Debug.log("auth", "login config %s: %s", uuid.toString().substring(0, 8), LoginResult.SUCCESS);
                    }
                    done.accept(LoginResult.SUCCESS, 0L);
                }
            }
        });
    }

    /** 密码算法对齐：配置算法与存储算法不一致时按配置算法重新哈希（异步线程调用，返回新哈希；无需对齐返回 null） */
    private String alignedPasswordHash(PlayerData data, String password) {
        String configured = configManager.passwordHashAlgorithm();
        boolean storedIsBcrypt = PasswordHash.isBcrypt(data.passwordHash());
        boolean configIsBcrypt = "bcrypt".equalsIgnoreCase(configured);
        if (configIsBcrypt == storedIsBcrypt) return null;
        return PasswordHash.hashPassword(password, configured, configManager.bcryptCost());
    }

    /** 登录成功收尾：IP 变动提醒、更新 IP/时间/活跃时间、标记登录、恢复模式、触发事件（须在玩家区域线程调用） */
    private void completeLogin(Player player, PlayerData data) {
        UUID uuid = player.getUniqueId();
        String oldIp = data.ip();
        String ip = clientIp(player);
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
        if (configManager.ipChangeNotifyEnabled()
                && oldIp != null && !oldIp.isEmpty()
                && !oldIp.equals(data.ip())
                && notifyIpChangeFor(data)) {
            player.sendMessage(I18n.msg("login.ip_changed", player, oldIp));
        }
    }


    /** IP 变动提醒是否适用于该玩家：离线玩家提醒；正版玩家仅当正版验证回退开启时提醒（fallback 仅约束正版） */
    private boolean notifyIpChangeFor(PlayerData data) {
        return !data.premium() || configManager.premiumPasswordFallbackEnabled();
    }

    /**
     * 移除密码，转为无密码账户。
     * 离线账户已绑定 2FA 时验证码作为确认凭据（移除后即为唯一登录因素）；正版账户凭正版验证放行。
     * @return true 移除成功；false 验证码错误或账号不存在
     */
    public boolean removePassword(Player player, String code) {
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

    /**
     * 完成双因素验证：校验 TOTP 验证码，通过则完成登录。
     * @return true 验证通过且登录完成
     */
    public boolean verify2fa(Player player, String code) {
        PlayerData data = twoFactor.verifyCode(player.getUniqueId(), player, code, clientIp(player));
        if (data == null) return false;
        completeLogin(player, data);
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

    /** 登录失败处理：失败计数（可能触发踢出）+ 触发失败事件（须在玩家区域线程调用；配置阶段 player 为 null，事件转全局调度器触发） */
    private void handleLoginFailure(UUID uuid, Player player) {
        failProtection.recordFailure(uuid, player);
        events.loginFail(player);
    }

    // ===== 管理员强制操作 =====

    /** 强制登出玩家（无需玩家在线，清除登录状态，并使登录会话与 2FA 会话失效） */
    public boolean forceLogout(UUID uuid) {
        if (!sessions.invalidateLoggedIn(uuid)) return false;
        if (Debug.on()) {
            Debug.log("auth", "force logout: %s (live login state invalidated, connection flag kept)", uuid);
        }
        invalidateLoginSessions(uuid);
        events.logout(uuid);
        return true;
    }

    /** 强制修改玩家密码（无需验证旧密码，玩家无需在线） */
    public boolean forceChangePassword(UUID uuid, String newPassword) {
        if (!dataManager.hasAccount(uuid)) return false;
        String newHash = PasswordHash.hashPassword(newPassword, configManager.passwordHashAlgorithm(), configManager.bcryptCost());
        dataManager.updatePassword(uuid, newHash);
        invalidateLoginSessions(uuid);
        return true;
    }

    /** 管理员强制清空玩家密码（转为无密码账户）：无需验证旧密码或 2FA 验证码，玩家无需在线 */
    public boolean forceRemovePassword(UUID uuid) {
        if (!dataManager.hasAccount(uuid)) return false;
        dataManager.updatePassword(uuid, "");
        invalidateLoginSessions(uuid);
        return true;
    }

    /** 使登录会话失效（凭据变更、强制登出等场景）：清除 lastLogin（免密窗口）与登录/2FA 会话，下次必须重新验证 */
    private void invalidateLoginSessions(UUID uuid) {
        accounts.invalidateUnregisterConfirm(uuid);
        PlayerData data = dataManager.getPlayer(uuid);
        if (data != null) {
            data.lastLogin(0);
            dataManager.save(uuid);
        }
        sessions.clearLoginSession(uuid);
        twoFactor.clearSession(uuid);
    }

    /** 强制登录玩家（不管有没有账号，仅对在线玩家生效） */
    public void forceLogin(Player player) {
        UUID uuid = player.getUniqueId();
        if (Debug.on()) {
            Debug.log("auth", "force login: %s", player.getName());
        }
        markLoggedIn(uuid);
        onLoginSuccess(player);
        events.login(player);
    }


    // 免密登录：跳过密码验证直接完成登录（会话命中或正版验证通过后调用）
    // 登录需完成 2FA 的账号不直接放行：进入待验证状态，由 /2fa <验证码> 完成登录
    // 2FA 会话命中（同 IP 且未过期）时同样直接完成登录
    public void autoLogin(Player player) {
        PlayerData data = dataManager.getPlayer(player.getUniqueId());
        if (data == null) return;
        String ip = clientIp(player);
        if (Debug.on()) {
            Debug.log("session", "auto login for %s (2FA required=%s)", player.getName(), twoFactor.requiresAtLogin(player.getUniqueId(), ip));
        }
        if (twoFactor.requiresAtLogin(player.getUniqueId(), ip)) {
            twoFactor.markPending(player.getUniqueId());
            return;
        }
        completeLogin(player, data);
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
        completeLogin(player, data);
        return true;
    }

    /**
     * Pre-join 注册完成后玩家进入世界时的收尾：与 register() 的登录后处理一致
     * （建立免密会话、标记登录、恢复物品状态、触发注册事件；不更新登录时间/IP——createPlayer 已记录）。
     * @return false 表示账号数据已不存在（被注销的竞态），调用方应回退正常登录流程
     */
    public boolean finishPreJoinRegister(Player player) {
        if (!dataManager.hasAccount(player.getUniqueId())) return false;
        sessions.markLoginSession(player.getUniqueId(), clientIp(player));
        markLoggedIn(player.getUniqueId());
        onLoginSuccess(player);
        Bukkit.getPluginManager().callEvent(new HSAuthRegisterEvent(player.getUniqueId(), player));
        return true;
    }

    // Logout
    public void logout(Player player) {
        forceLogout(player.getUniqueId());
    }

    // Change password
    /**
     * 异步修改密码：旧密码校验与新密码哈希（bcrypt 耗时）在异步线程执行，结果回调回到玩家区域线程。
     * 正版与离线同规则，均须先验证旧密码（无密码账户不进入此路径，命令层拦截并引导 /addpassword）
     */
    public void changePasswordAsync(Player player, String oldPassword, String newPassword, Consumer<Boolean> done) {
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
            String newHash = PasswordHash.hashPassword(newPassword, configManager.passwordHashAlgorithm(), configManager.bcryptCost());
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
     * 异步为无密码账户添加密码（转为有密码账户，是解绑 2FA 的前置步骤）：bcrypt 哈希在异步线程执行，结果回调回到玩家区域线程。
     * 已有密码或账号不存在时回调 false
     */
    public void addPasswordAsync(Player player, String newPassword, Consumer<Boolean> done) {
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
            String newHash = PasswordHash.hashPassword(newPassword, configManager.passwordHashAlgorithm(), configManager.bcryptCost());
            player.getScheduler().run(plugin, task2 -> {
                dataManager.updatePassword(uuid, newHash);
                if (Debug.on()) {
                    Debug.log("auth", "add password %s: success", player.getName());
                }
                done.accept(true);
            }, null);
        });
    }

    /** 标记玩家为已登录：置位实时登录态与本次连接认证标记，清理双因素待验证、失败计数、踢出记录 */
    private void markLoggedIn(UUID uuid) {
        sessions.markLoggedIn(uuid);
        twoFactor.clearPending(uuid);
        failProtection.clear(uuid);
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

    /** 记录登录超时任务启动时间，返回当前时间戳（用于触发时判断是否为最新任务） */
    public long markLoginTimeoutStart(UUID uuid) {
        return sessions.markLoginTimeoutStart(uuid);
    }

    /** 判断指定时间戳是否为最新的超时任务启动时间（旧任务自动失效） */
    public boolean isLatestLoginTimeout(UUID uuid, long startedAt) {
        return sessions.isLatestLoginTimeout(uuid, startedAt);
    }

    /**
     * 清理已过期的踢出记录、失败计数、2FA/登录会话与注销拒绝重连记录（30 秒周期任务 + reload/unregister 时调用）。
     * 登录会话只在读取时判定过期（玩家不再上线则条目无读取机会），须依赖周期清理防止常驻内存
     */
    public void cleanupExpiredStates() {
        long now = System.currentTimeMillis();
        int removed = failProtection.cleanupExpired(now);
        removed += twoFactor.cleanupExpiredSessions(now);
        // 清理已过期的登录会话（固定窗口，命中不续期）与注销拒绝重连记录
        removed += sessions.cleanupExpiredSessions(now);
        int before = sessions.recentUnregisterCount();
        sessions.cleanupExpired(now);
        removed += before - sessions.recentUnregisterCount();
        if (removed > 0 && Debug.on()) {
            Debug.log("session", "cleanup expired states: %s entries removed", removed);
        }
    }

    /**
     * 生成认证状态快照（/hsauth debug dump），供排查工单使用：只读内存状态与开关摘要，
     * 不含密码、密钥与完整 IP 等敏感值
     */
    public List<String> diagnostics() {
        List<String> lines = new ArrayList<>(8 + Bukkit.getOnlinePlayers().size());
        lines.add("HowSAuth diagnostics: online=" + Bukkit.getOnlinePlayers().size()
                + " dbLoadFailed=" + dataManager.isLoadFailed()
                + " debug=" + configManager.debug()
                + " session=" + configManager.sessionEnabled()
                + " 2fa=" + configManager.twoFaEnabled()
                + " failProtection=" + configManager.failProtectionEnabled()
                + " spectatorProtection=" + configManager.protectionGamemodeEnabled());
        lines.add("  caches: loginSessions=" + sessions.loginSessionCount() + " twoFaSessions=" + twoFactor.sessionCount()
                + " pending2fa=" + twoFactor.pendingCount() + " pending2faSecret=" + twoFactor.pendingSecretCount()
                + " verifying=" + sessions.verifyingCount() + " used2faCounters=" + twoFactor.usedCounterCount()
                + " premiumFallback=" + accounts.premiumFallbackCount() + " recentUnregister=" + sessions.recentUnregisterCount()
                + " pendingDatDelete=" + sessions.pendingDatDeleteCount() + " failedAttempts=" + failProtection.failureCount());
        lines.add("  pending markers: invulnerable=" + sessions.invulnerablePendingCount()
                + " spectator=" + sessions.spectatorPendingCount() + " loginTimeout=" + sessions.loginTimeoutStartedCount());
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            lines.add("  " + player.getName() + "(" + uuid.toString().substring(0, 8) + ")"
                    + sessions.describe(uuid)
                    + " pending2fa=" + twoFactor.isPending(uuid)
                    + " loginSession=" + sessions.hasLoginSessionRecord(uuid)
                    + " twoFaSession=" + twoFactor.hasSessionRecord(uuid)
                    + " failed=" + failProtection.hasFailureRecord(uuid)
                    + " kicked=" + failProtection.isKicked(uuid));
        }
        return lines;
    }

    public void onLoginSuccess(Player player) {
        player.getScheduler().run(plugin, task -> {
            // 立即隐藏提醒 BossBar（不等下一个提醒周期；非 bossbar 方式时为空操作）
            plugin.getPlayerListener().hideReminderBar(player);
            // 返还退出时保管的飞行末影珍珠（无记录时为空操作）
            plugin.getPendingPearlManager().returnPearls(player);
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
}
