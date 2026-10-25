package org.howsauth.plugin.listener;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.api.event.HSAuthLoginEvent;
import org.howsauth.plugin.api.event.HSAuthRegisterEvent;
import org.howsauth.plugin.auth.AccountLifecycle;
import org.howsauth.plugin.auth.LoginFlow;
import org.howsauth.plugin.auth.LogoutLocation;
import org.howsauth.plugin.auth.SessionStore;
import org.howsauth.plugin.auth.TwoFactorAuth;
import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.dialog.PreJoinAuthListener;

import java.util.UUID;

/**
 * 玩家进入世界后的认证流程：pre-join 收尾、正版/会话免密、挂起、登录失明、超时与重新挂起。
 * <p>
 * 从 {@link PlayerListener} 拆出，事件注册仍在监听器；本类只处理“玩家已经在世界里”的认证决策。
 */
final class JoinAuthFlow {

    private final HowSAuth plugin;
    private final SessionStore sessions;
    private final AccountLifecycle accounts;
    private final TwoFactorAuth twoFactor;
    private final LogoutLocation locations;
    private final LoginFlow loginFlow;
    private final AuthReminder reminders;

    JoinAuthFlow(HowSAuth plugin, SessionStore sessions, AccountLifecycle accounts,
                 TwoFactorAuth twoFactor, LogoutLocation locations,
                 LoginFlow loginFlow, AuthReminder reminders) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.accounts = accounts;
        this.twoFactor = twoFactor;
        this.locations = locations;
        this.loginFlow = loginFlow;
        this.reminders = reminders;
    }

    void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 玩家已进入世界：服务端的同名单会话检查自 play 阶段起生效，
        // 配置阶段的名字认领到此释放（不释放会让该名字在 TTL 内无法被重新连接使用）
        PreJoinAuthListener preJoin = plugin.preJoinAuth();
        if (preJoin != null) {
            preJoin.releaseClaim(player.getName());
        }

        // 清理上一会话残留的登录失明（药水效果随 .dat 保存，崩溃重连后仍在）；
        // 本监听器最先注册，先于其它插件的 join 处理执行，不会误删后者施加的效果；
        // 本次需要挂起时 suspend 会在同一 tick 内重新施加，客户端无感知
        clearLoginBlindness(player);

        // 更新活跃时间（有账号即更新，用于不活跃清理；未注册玩家不写库）
        loginFlow.touchActive(player);

        // Pre-join Dialog 已在配置阶段完成登录/注册：收尾后直接进入世界（无需挂起）
        if (preJoin != null) {
            PreJoinAuthListener.AuthOutcome outcome = preJoin.consume(player);
            if (outcome != null) {
                boolean login = outcome == PreJoinAuthListener.AuthOutcome.LOGIN;
                if (login ? loginFlow.finishPreJoinLogin(player) : loginFlow.finishPreJoinRegister(player)) {
                    player.sendMessage(I18n.msg(login ? "login.success" : "register.success", player));
                    return;
                }
                // 账号在配置阶段认证后被删除（竞态）：走正常挂起流程
            }
        }

        // 正版玩家免密登录：跳过密码验证，直接标记为已登录
        if (accounts.isPremium(player)) {
            // 正版验证失败回退进入的玩家：本次需密码登录，不自动免密
            if (accounts.isPremiumFallback(player.getUniqueId())) {
                beginAuthFlow(player);
                return;
            }
            // 先检查会话是否命中（决定是否需要传送）
            // 命中时 onSpawnLocation 已将出生点设为退出位置，无需传送
            // 未命中时需传送到退出位置
            boolean sessionHit = sessions.hasSession(player);
            if (Debug.on()) {
                Debug.log("flow", "join %s: premium branch (sessionHit=%s)", player.getName(), sessionHit);
            }
            loginFlow.autoLogin(player);
            // 已绑定 2FA（pre-join 弹窗未覆盖时的回退）：等待验证码，登录收尾与传送延迟到 /2fa 验证完成
            boolean pending2fa = twoFactor.isPending(player.getUniqueId());
            if (pending2fa) {
                // 未通过 2FA 不算登录成功：与挂起流程一致（旁观保护 + 周期提醒 + 超时）
                suspend(player, "login.need_2fa", true);
            } else {
                player.sendMessage(I18n.msg("login.premium_auto_login", player));
            }
            if (!sessionHit && !pending2fa) {
                teleportBackAfterJoin(player);
            }
            return;
        }

        if (accounts.hasAccount(player)) {
            // 会话命中：上次登录 IP 与当前一致且未过期，免输密码直接登录
            if (sessions.hasSession(player)) {
                if (Debug.on()) {
                    Debug.log("flow", "join %s: account branch, login session hit", player.getName());
                }
                loginFlow.autoLogin(player);
                if (twoFactor.isPending(player.getUniqueId())) {
                    // 已绑定 2FA（pre-join 弹窗未覆盖时的回退）：等待验证码，传送由 /2fa 验证完成流程处理
                    suspend(player, "login.need_2fa", true);
                } else {
                    // 退出位置已在 onSpawnLocation 中设置为出生点，无需传送
                    player.sendMessage(I18n.msg("login.ip_auto_login", player));
                }
                return;
            }
        }
        beginAuthFlow(player);
    }

    /**
     * 加入流程中的传送回退出位置：延后一个 tick 在玩家区域线程执行。
     * <p>
     * 不能在 {@code PlayerJoinEvent} 处理期间直接传送：该事件由服务端在"把玩家加入世界"的过程中触发，
     * 此刻玩家尚未注册进区块加载器（RegionizedPlayerChunkLoader）。Folia 系服务端（含 Canvas）
     * 的 {@code teleportAsync} 会立即走 {@code transformForAsyncTeleport} → 移除并重建实体，
     * 于是抛出 {@code IllegalStateException: Player is already removed from player chunk loader}
     * 并导致本次加入事件报错。
     * <p>
     * {@link LogoutLocation#teleportBack} 的契约本就要求"玩家已在世界中、非 PlayerJoinEvent 期间"调用，
     * 命令与 2FA 验证等路径满足该前提，可继续直接调用；只有加入流程这几条路径需要延后。
     */
    private void teleportBackAfterJoin(Player player) {
        player.getScheduler().run(plugin, task -> locations.teleportBack(player), null);
    }

    /**
     * 通用挂起：旁观者保护 + 消息提示 + 周期提醒 + 超时踢出。
     * 所有等待登录/2FA 的挂起路径强制复用本方法，防止各分支手工复制漏项。
     * @param messageKey 挂起时发送的聊天提示 key
     * @param needsLogin true = 登录流程提示（含 2FA），false = 注册流程提示
     */
    void suspend(Player player, String messageKey, boolean needsLogin) {
        if (Debug.on()) {
            Debug.log("flow", "suspend %s: %s (needsLogin=%s, spectatorProtection=%s)", player.getName(), messageKey,
                    needsLogin, plugin.config().protectionMisc().gamemodeEnabled());
        }
        locations.setSpectator(player);
        applyLoginBlindness(player);
        player.sendMessage(I18n.msg(messageKey, player));
        reminders.schedule(player, needsLogin);
        scheduleLoginTimeout(player, needsLogin);
    }

    /**
     * 登录前失明：开关开启则施加无限时长失明（无颗粒无图标，重复挂起幂等），
     * 关闭则移除——reload 经 refreshPendingPlayers 重新挂起时按新配置双向同步，
     * 已失明的玩家可被解除，无需额外的 reload 同步逻辑。
     */
    private void applyLoginBlindness(Player player) {
        if (plugin.config().protectionMisc().blindnessEnabled()) {
            if (Debug.on()) {
                Debug.log("flow", "apply login blindness for %s", player.getName());
            }
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                    PotionEffect.INFINITE_DURATION, 0, false, false, false));
        } else {
            clearLoginBlindness(player);
        }
    }

    /**
     * 移除登录失明：登录/注册成功、reload 关闭失明、加入时残留清理、
     * 未登录退出四个场景共用此出口，效果类型改动只需修改一处。
     */
    void clearLoginBlindness(Player player) {
        if (Debug.on()) {
            Debug.log("flow", "clear login blindness for %s", player.getName());
        }
        player.removePotionEffect(PotionEffectType.BLINDNESS);
    }

    /**
     * 登录成功移除失明：/login 密码、2FA 验证完成、会话/正版免密 autoLogin、
     * pre-join 登录收尾、管理员强制登录全部经 HSAuthLoginEvent（Folia 下在玩家区域线程触发）。
     */
    void onLoginSuccess(HSAuthLoginEvent event) {
        if (Debug.on()) {
            Debug.log("flow", "login success for %s: finishing (clear blindness)", event.getPlayer().getName());
        }
        clearLoginBlindness(event.getPlayer());
    }

    void onRegisterSuccess(HSAuthRegisterEvent event) {
        // 强制注册离线玩家时 getPlayer() 为 null，此时也无可移除的失明
        if (Debug.on()) {
            Debug.log("flow", "register success for %s: finishing (clear blindness)",
                    event.getPlayer() != null ? event.getPlayer().getName()
                            : Debug.shortId(event.getUuid()));
        }
        if (event.getPlayer() != null) {
            clearLoginBlindness(event.getPlayer());
        }
    }

    /**
     * 挂起玩家等待登录/注册：待登录状态、旁观模式、聊天提示、超时与周期提醒。
     * join 与 /reload 重挂起共用；有账号走登录流程，无账号走注册流程。
     * 配置阶段 Dialog（pre-join）未能覆盖的玩家（旧客户端/旧服务端/超时放行）在此以聊天栏提示挂起。
     */
    void beginAuthFlow(Player player) {
        boolean hasAccount = accounts.hasAccount(player);
        UUID uuid = player.getUniqueId();
        // 无密码账户：密码不是登录因素，已绑定验证器时验证码成为唯一登录方式
        boolean passwordless = hasAccount && accounts.isPasswordless(uuid);
        if (passwordless) {
            String ip = SessionStore.clientIp(player);
            // 仅 2FA 会话命中（同 IP 且未过期）时免验证码登录：无密钥账户否则会因
            // requires2faAtLogin 判空短路被 autoLogin 免密直入（认证绕过），必须显式判定
            if (twoFactor.hasSession(uuid, ip)) {
                // 走到这里说明 login.session 未命中，出生点在保护位置，登录后须传送回退出位置
                if (Debug.on()) {
                    Debug.log("flow", "auth flow %s: passwordless 2FA session hit, auto login", player.getName());
                }
                loginFlow.autoLogin(player);
                player.sendMessage(I18n.msg("login.success", player));
                teleportBackAfterJoin(player);
                return;
            }
            // 仅已绑定验证器的账户进入待验证码状态：无凭据账户（无密码+无2FA+非正版）无码可验，
            // 不设待验证状态，提示自然落到 authPromptKey 的 passwordless_no_auth（联系管理员）
            if (twoFactor.hasTotpSecret(uuid)) {
                twoFactor.markPending(uuid);
            }
        }
        // 无可用登录方式（reject-no-auth-account=false 放行进入的兜底场景，无凭据永远验不过）：
        // WARN 记录供管理员排查，提示玩家联系管理员而非空输验证码（hasNoUsableLoginMethod 已含无密码判定）
        if (accounts.hasNoUsableLoginMethod(uuid)) {
            if (Debug.on()) {
                Debug.log("flow", "auth flow %s: no usable login method (suspend to timeout)", player.getName());
            }
            plugin.getLogger().warning(I18n.get("log.passwordless_no_auth_account",
                    player.getName(), SessionStore.clientIp(player)));
        }
        if (Debug.on()) {
            Debug.log("flow", "auth flow %s: suspend with prompt %s (hasAccount=%s)",
                    player.getName(), reminders.promptKey(uuid, hasAccount), hasAccount);
        }
        suspend(player, reminders.promptKey(uuid, hasAccount), hasAccount);
    }

    /**
     * 配置热重载后重新挂起未登录玩家：清理旧提醒，按新配置重新展示。
     * 逐玩家切回其区域线程执行（Folia：管理员与目标玩家可能不在同一区域线程）。
     */
    void refreshPendingPlayers() {
        reminders.clearAll();
        int suspended = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (sessions.isLoggedIn(player)) continue;
            suspended++;
            player.getScheduler().run(plugin, task -> beginAuthFlow(player), null);
        }
        if (Debug.on()) {
            Debug.log("flow", "refresh pending: %s players re-suspended", suspended);
        }
    }

    /** 启动登录/注册超时踢出任务（onJoin 和 forceRegister 共用，重复调用会自动作废旧任务）
     *  @param needsLogin true = 登录超时（login.timeout），false = 注册超时（register.timeout） */
    void scheduleLoginTimeout(Player player, boolean needsLogin) {
        // 取一次配置快照：两个分支读同一领域，避免读到新旧混合的超时值
        ConfigManager config = plugin.config();
        int timeout = config.login().timeout();
        if (!needsLogin) timeout = config.login().registerTimeout();
        if (timeout <= 0) return;

        // 记录启动时间，触发时校验是否为最新任务（forceRegister 重启超时后旧任务自动失效）
        long startedAt = sessions.markLoginTimeoutStart(player.getUniqueId());
        // 20 tick = 1 秒
        long delayTicks = timeout * 20L;
        // Paper 1.20+ 统一调度器 API，兼容 Folia（在实体所在区域调度）
        player.getScheduler().runDelayed(plugin, scheduledTask -> {
            // 非最新任务直接放弃（forceRegister 已重启超时计时）
            if (!sessions.isLatestLoginTimeout(player.getUniqueId(), startedAt)) return;
            if (!sessions.isLoggedIn(player) && player.isOnline()) {
                if (plugin.config().login().kickOnTimeout()) {
                    player.kick(I18n.msg("listener.login_timeout", player));
                }
            }
        }, null, delayTicks);
    }
}
