package org.howsauth.plugin.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.auth.LoginFlow;
import org.howsauth.plugin.auth.AccountLifecycle;
import org.howsauth.plugin.auth.FailProtection;
import org.howsauth.plugin.auth.LogoutLocation;
import org.howsauth.plugin.auth.SessionStore;
import org.howsauth.plugin.auth.TwoFactorAuth;
import org.howsauth.plugin.api.event.HSAuthLoginEvent;
import org.howsauth.plugin.api.event.HSAuthRegisterEvent;
import org.howsauth.plugin.dialog.PreJoinAuthListener;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// AsyncPlayerSpawnLocationEvent 等 Paper API 标记为 @ApiStatus.Experimental，实际已稳定可用
@SuppressWarnings("UnstableApiUsage")
public final class PlayerListener implements Listener {

    private final HowSAuth plugin;
    private final SessionStore sessions;
    private final FailProtection failProtection;
    private final TwoFactorAuth twoFactor;
    private final LogoutLocation locations;
    private final AccountLifecycle accounts;
    private final LoginFlow loginFlow;
    // 活跃的提醒 BossBar：登录成功/玩家退出时立即隐藏（不等下一个任务周期）
    private final Map<UUID, net.kyori.adventure.bossbar.BossBar> reminderBars = new ConcurrentHashMap<>();
    // 活跃的提醒任务：重新挂起（reload）时取消旧任务，避免新旧任务并行重复提醒
    private final Map<UUID, ScheduledTask> reminderTasks = new ConcurrentHashMap<>();

    public PlayerListener(HowSAuth plugin, SessionStore sessions,
                          FailProtection failProtection, TwoFactorAuth twoFactor, LogoutLocation locations, AccountLifecycle accounts, LoginFlow loginFlow) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.failProtection = failProtection;
        this.twoFactor = twoFactor;
        this.locations = locations;
        this.accounts = accounts;
        this.loginFlow = loginFlow;
    }

    // 在玩家加入世界前拦截：踢出期玩家、同一 IP 账号数量超限
    // 使用 AsyncPlayerPreLoginEvent 替代已弃用的 PlayerLoginEvent（1.21.6+）
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        var uuid = event.getUniqueId();

        // 数据库加载失败（fail-closed）：缓存为空会把所有玩家误判为未注册，拒绝进入直至恢复
        if (plugin.getPlayerDataManager().isLoadFailed()) {
            if (Debug.on()) {
                Debug.log("flow", "prelogin reject %s: database load failed", event.getName());
            }
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    I18n.msg("login.db_unavailable"));
            return;
        }

        // 踢出期内拒绝进入
        if (failProtection.isKicked(uuid)) {
            long remaining = failProtection.getKickRemaining(uuid);
            if (Debug.on()) {
                Debug.log("flow", "prelogin reject %s: kick period active (%s ms left)", event.getName(), remaining);
            }
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    I18n.msg("login.kicked", remaining));
            return;
        }

        // 有账号但无任何可用登录方式（无密码/未绑 2FA/非正版）。
        // 配置开启时在连接阶段直接拦截；关闭时放行，由 beginAuthFlow 挂起（永远无法通过，超时踢出）
        if (plugin.getConfigManager().rejectNoAuthAccount()
                && accounts.hasNoUsableLoginMethod(uuid)) {
            if (Debug.on()) {
                Debug.log("flow", "prelogin reject %s: no usable login method", event.getName());
            }
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    I18n.msg("prelogin.account_locked"));
            return;
        }

        // 注销后 5 秒内拒绝重连，确保 .dat 删除完成
        if (sessions.isRecentlyUnregistered(uuid)) {
            long remaining = sessions.getRecentUnregisterRemaining(uuid);
            if (Debug.on()) {
                Debug.log("flow", "prelogin reject %s: recently unregistered (%s ms left)", event.getName(), remaining);
            }
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    I18n.msg("unregister.recently_deleted", remaining));
            return;
        }

        // 同 IP 已达上限时仅在连接层拦截无账号新玩家，避免名额已满的 IP 涌入未注册玩家；
        // max-accounts-per-ip.reject-join 关闭时放行进服，由注册动作精确判定（共享 IP 环境友好）
        // 已达上限判定内部已处理 max<=0，无需在此重复判断
        if (plugin.getConfigManager().ipLimitRejectJoin()
                && !accounts.hasAccount(uuid)
                && accounts.isIpAccountLimitReached(event.getAddress().getHostAddress())) {
            if (Debug.on()) {
                Debug.log("flow", "prelogin reject %s: ip account limit reached (max %s)",
                        event.getName(), plugin.getConfigManager().maxAccountsPerIp());
            }
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    I18n.msg("register.ip_limit",
                            plugin.getConfigManager().maxAccountsPerIp()));
        }

        // 放行玩家异步预载退出位置区块：fire-and-forget，不阻塞、不影响放行判定
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            locations.preloadChunk(uuid);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // 清理上一会话残留的登录失明（药水效果随 .dat 保存，崩溃重连后仍在）；
        // 本监听器最先注册，先于其它插件的 join 处理执行，不会误删后者施加的效果；
        // 本次需要挂起时 suspend 会在同一 tick 内重新施加，客户端无感知
        clearLoginBlindness(player);

        // 更新活跃时间（有账号即更新，用于不活跃清理；未注册玩家不写库）
        loginFlow.touchActive(player);

        // Pre-join Dialog 已在配置阶段完成登录/注册：收尾后直接进入世界（无需挂起）
        PreJoinAuthListener preJoin = plugin.getPreJoinAuthListener();
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
                locations.teleportBack(player);
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
     * 通用挂起：旁观者保护 + 消息提示 + 周期提醒 + 超时踢出。
     * 所有等待登录/2FA 的挂起路径强制复用本方法，防止各分支手工复制漏项。
     * @param messageKey 挂起时发送的聊天提示 key
     * @param needsLogin true = 登录流程提示（含 2FA），false = 注册流程提示
     */
    public void suspend(Player player, String messageKey, boolean needsLogin) {
        if (Debug.on()) {
            Debug.log("flow", "suspend %s: %s (needsLogin=%s, spectatorProtection=%s)", player.getName(), messageKey,
                    needsLogin, plugin.getConfigManager().protectionGamemodeEnabled());
        }
        locations.setSpectator(player);
        applyLoginBlindness(player);
        player.sendMessage(I18n.msg(messageKey, player));
        scheduleReminder(player, needsLogin);
        scheduleLoginTimeout(player, needsLogin);
    }

    /**
     * 登录前失明：开关开启则施加无限时长失明（无颗粒无图标，重复挂起幂等），
     * 关闭则移除——reload 经 refreshPendingPlayers 重新挂起时按新配置双向同步，
     * 已失明的玩家可被解除，无需额外的 reload 同步逻辑。
     */
    private void applyLoginBlindness(Player player) {
        if (plugin.getConfigManager().protectionBlindnessEnabled()) {
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
    private void clearLoginBlindness(Player player) {
        if (Debug.on()) {
            Debug.log("flow", "clear login blindness for %s", player.getName());
        }
        player.removePotionEffect(PotionEffectType.BLINDNESS);
    }

    /**
     * 登录成功移除失明：/login 密码、2FA 验证完成、会话/正版免密 autoLogin、
     * pre-join 登录收尾、管理员强制登录全部经 HSAuthLoginEvent（Folia 下在玩家区域线程触发）。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoginSuccess(HSAuthLoginEvent event) {
        if (Debug.on()) {
            Debug.log("flow", "login success for %s: finishing (clear blindness)", event.getPlayer().getName());
        }
        clearLoginBlindness(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegisterSuccess(HSAuthRegisterEvent event) {
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
    public void beginAuthFlow(Player player) {
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
                locations.teleportBack(player);
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
                    player.getName(), authPromptKey(uuid, hasAccount), hasAccount);
        }
        suspend(player, authPromptKey(uuid, hasAccount), hasAccount);
    }

    /**
     * 挂起提示文案选择：首次挂起与周期提醒共用，防止两类提示口径不一致。
     * 注册 → 待 2FA → 无密码（无可用登录方式细分联系管理员）→ 密码登录。
     */
    private String authPromptKey(UUID uuid, boolean needsLogin) {
        if (!needsLogin) return "listener.please_register";
        if (twoFactor.isPending(uuid)) return "login.need_2fa";
        return accounts.isPasswordless(uuid)
                ? (accounts.hasNoUsableLoginMethod(uuid)
                        ? "login.passwordless_no_auth" : "login.passwordless_prompt")
                : "listener.please_login";
    }

    /**
     * 周期性重发登录/注册提示，防止玩家没看到。
     * 任务自管理：玩家登录/注册成功或下线后自动取消。
     * 提示方式由 login.remind-method 配置：chat / title / actionbar / bossbar。
     * @param needsLogin true = 发送登录提示，false = 发送注册提示
     */
    public void scheduleReminder(Player player, boolean needsLogin) {
        int interval = plugin.getConfigManager().loginRemindInterval();
        if (interval <= 0) return;
        long periodTicks = interval * 20L;
        UUID uuid = player.getUniqueId();
        // 取消旧提醒任务（refreshPendingPlayers 重新挂起时避免新旧任务并行重复提醒）
        ScheduledTask old = reminderTasks.remove(uuid);
        if (old != null) old.cancel();
        // Paper 1.20+ 统一调度器 API，兼容 Folia
        // 玩家调度器已退休（退出瞬间与 reload 挂起竞态）时返回 null，null 不允许入 Map
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, scheduledTask -> {
            if (!player.isOnline()) {
                reminderTasks.remove(uuid);
                hideReminderBar(player);
                scheduledTask.cancel();
                return;
            }
            boolean done = needsLogin ? sessions.isLoggedIn(player) : accounts.hasAccount(player);
            if (done) {
                reminderTasks.remove(uuid);
                hideReminderBar(player);
                scheduledTask.cancel();
                return;
            }
            sendReminder(player, needsLogin);
        }, null, periodTicks, periodTicks);
        if (task != null) {
            reminderTasks.put(uuid, task);
        }
    }

    /** 按配置方式发送登录/注册提醒（bossbar 引用统一由 reminderBars 持有） */
    private void sendReminder(Player player, boolean needsLogin) {
        // 提示文案与首次挂起共用同一选择逻辑（authPromptKey），保证周期提醒口径一致
        String key = authPromptKey(player.getUniqueId(), needsLogin);
        String method = plugin.getConfigManager().loginRemindMethod();
        switch (method) {
            case "title" -> player.showTitle(net.kyori.adventure.title.Title.title(
                    I18n.msg(key, player),
                    net.kyori.adventure.text.Component.empty(),
                    net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(500),
                            java.time.Duration.ofMillis(2000),
                            java.time.Duration.ofMillis(500))));
            case "actionbar" -> player.sendActionBar(I18n.msg(key, player));
            case "bossbar" -> {
                net.kyori.adventure.bossbar.BossBar bar = reminderBars.get(player.getUniqueId());
                if (bar == null) {
                    net.kyori.adventure.text.Component text = I18n.msg(key, player);
                    bar = net.kyori.adventure.bossbar.BossBar.bossBar(
                            text, 1.0f,
                            net.kyori.adventure.bossbar.BossBar.Color.YELLOW,
                            net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);
                    player.showBossBar(bar);
                    reminderBars.put(player.getUniqueId(), bar);
                } else {
                    bar.name(I18n.msg(key, player));
                }
            }
            default -> player.sendMessage(I18n.msg(key, player));
        }
    }

    /** 隐藏并移除提醒 BossBar（登录成功、玩家退出、任务自检清理时调用；非 bossbar 方式时为空操作） */
    public void hideReminderBar(Player player) {
        net.kyori.adventure.bossbar.BossBar bar = reminderBars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    /** 清理所有提醒 BossBar（refreshPendingPlayers 重新挂起前调用，防止旧 BossBar 悬挂到玩家登录才消失） */
    private void clearReminderBars() {
        reminderBars.forEach((uuid, bar) -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) player.hideBossBar(bar);
        });
        reminderBars.clear();
    }

    /**
     * 配置热重载后重新挂起未登录玩家：清理旧提醒，按新配置重新展示。
     * 逐玩家切回其区域线程执行（Folia：管理员与目标玩家可能不在同一区域线程）。
     */
    public void refreshPendingPlayers() {
        clearReminderBars();
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

    /**
     * 在 JoinGamePacket 发送前调整老玩家 spawn 位置。
     * - 会话命中的玩家：直接在退出位置出生，避免后续传送。
     * - 启用坐标保护：强制主世界随机位置，防止坐标泄露（F3、小地图 mod 等）。
     * 新玩家不干预，保留原版出生机制。
     * 此事件在 configuration phase 触发（异步线程），玩家尚未真正加入世界。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        // 新玩家不干预，保留原版出生机制
        if (event.isNewPlayer()) return;

        var conn = event.getConnection();
        java.util.UUID uuid = conn.getProfile().getId();
        // 代理协议下地址可能未解析（getAddress() 返回 null），判空避免 NPE
        var clientAddr = conn.getClientAddress().getAddress();
        String ip = clientAddr != null ? clientAddr.getHostAddress() : null;

        // 会话命中（免输密码）的玩家直接在退出位置出生，避免后续传送
        // 登录需 2FA 的除外：验证完成前不放行到退出位置（/2fa 验证后再传送）；
        // 2FA 会话命中（同 IP 且未过期）视同已完成验证
        if (uuid != null && sessions.hasSession(uuid, ip) && !twoFactor.requiresAtLogin(uuid, ip)) {
            Location logoutLoc = locations.get(uuid);
            if (logoutLoc != null) {
                if (Debug.on()) {
                    Debug.log("flow", "spawn %s: logout location (login session hit)", Debug.shortId(uuid));
                }
                event.setSpawnLocation(logoutLoc);
                return;
            }
        }

        // Pre-join 已认证玩家：与会话命中一致，直接在退出位置出生，避免随机出生后再传送
        PreJoinAuthListener preJoin = plugin.getPreJoinAuthListener();
        if (uuid != null && preJoin != null && preJoin.hasCompleted(uuid)) {
            Location logoutLoc = locations.get(uuid);
            if (logoutLoc != null) {
                if (Debug.on()) {
                    Debug.log("flow", "spawn %s: logout location (pre-join completed)", Debug.shortId(uuid));
                }
                event.setSpawnLocation(logoutLoc);
            } else if (Debug.on()) {
                Debug.log("flow", "spawn %s: pre-join completed, no logout location (default spawn)",
                        Debug.shortId(uuid));
            }
            // 已认证：登录前未接收任何世界信息，无需坐标保护
            return;
        }

        // 启用坐标保护：强制主世界随机位置，防止坐标泄露
        if (plugin.getConfigManager().protectionPosEnabled()) {
            org.bukkit.World world = org.bukkit.Bukkit.getWorlds().getFirst();
            Location safeSpawn = locations.findSafeAuthSpawn(world);
            event.setSpawnLocation(safeSpawn);
            if (Debug.on()) {
                Debug.log("flow", "spawn %s: coordinate protection location",
                        Debug.shortId(uuid));
            }
        } else if (Debug.on()) {
            Debug.log("flow", "spawn %s: default spawn (no intervention)",
                    Debug.shortId(uuid));
        }
    }

    /** 启动登录/注册超时踢出任务（onJoin 和 forceRegister 共用，重复调用会自动作废旧任务）
     *  @param needsLogin true = 登录超时（login.timeout），false = 注册超时（register.timeout） */
    public void scheduleLoginTimeout(Player player, boolean needsLogin) {
        int timeout = plugin.getConfigManager().loginTimeout();
        if (!needsLogin) timeout = plugin.getConfigManager().registerTimeout();
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
                if (plugin.getConfigManager().kickOnTimeout()) {
                    player.kick(I18n.msg("listener.login_timeout", player));
                }
            }
        }, null, delayTicks);
    }

    /**
     * 玩家退出阶段一（LOWEST，最先执行）：处理依赖认证结果的业务并保存退出数据。
     * 认证判定用 SessionStore#hasAuthenticatedThisConnection（与 isLoggedIn 的差异见该方法注释），
     * 会话状态清理在阶段二（onQuitCleanup），须晚于本阶段的判定
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        boolean authenticated = sessions.hasAuthenticatedThisConnection(uuid);
        if (Debug.on()) {
            Debug.log("flow", "quit %s: authenticatedThisConnection=%s loggedIn=%s", player.getName(),
                    authenticated, sessions.isLoggedIn(player));
        }
        // 本次连接已认证的玩家退出时保存退出位置（用于下次登录后传送回来）
        // 未认证玩家不保存：其位置是登录前的保护/出生点，写入会覆盖真实退出位置
        if (authenticated) {
            locations.save(player);
        }
        // 退出时不在登录态（从未认证，或登录态被强制登出/注销提前失效）：清理可能残留的登录失明，
        // 避免效果随 .dat 存档到下次会话（与位置保存是两个独立判定，不共用条件）
        if (!sessions.isLoggedIn(player)) {
            clearLoginBlindness(player);
        }
        // 立即清理提醒 BossBar：玩家调度器随退出 retired，任务内的清理分支不再执行
        hideReminderBar(player);
        // 清理提醒任务引用（任务随玩家调度器 retired 不再执行，防止 Map 残留）
        reminderTasks.remove(player.getUniqueId());
        // 注销玩家退出时删除原版 .dat（服务器已保存并释放文件锁）
        accounts.tryDeletePlayerDataOnQuit(player.getUniqueId());
    }

    /**
     * 玩家退出阶段二（MONITOR，最后执行）：清理会话状态。
     * 退出监听按「保存数据（LOWEST）→ 消息决策（HIGH）→ 状态清理（MONITOR）」分层，
     * 本阶段须保持在最后：加入/退出消息与退出位置保存依据本次连接认证标记判定，
     * 提前清理会让本次已认证的玩家被误判为未认证
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuitCleanup(PlayerQuitEvent event) {
        if (Debug.on()) {
            Debug.log("flow", "quit cleanup for %s: clearing session state (MONITOR, after message decision)", event.getPlayer().getName());
        }
        loginFlow.clearSession(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfigManager().preventMove()) return;

        Player player = event.getPlayer();
        if (sessions.isLoggedIn(player)) return;

        // Paper API 保证 getTo() 非 null（@NullMarked）
        // 使用 setTo() 而非 setCancelled(true)：
        //   1. 避免客户端与服务端位置不同步导致的画面卡顿
        //   2. 使用精确坐标比较（getX/Y/Z），避免方块坐标精度不足被绕过
        Location from = event.getFrom();
        Location to = event.getTo();
        boolean positionChanged = from.getX() != to.getX()
                || from.getY() != to.getY()
                || from.getZ() != to.getZ();
        boolean lookChanged = from.getYaw() != to.getYaw()
                || from.getPitch() != to.getPitch();

        boolean preventLook = plugin.getConfigManager().preventLook();
        if (preventLook) {
            // 禁止位置和视角变化：全部回滚到 from
            if (positionChanged || lookChanged) {
                event.setTo(from);
            }
        } else {
            // 仅禁止位置移动：直接改 to 的坐标（复用对象，避免热路径逐次分配），保留其视角（yaw/pitch）
            if (positionChanged) {
                to.setX(from.getX());
                to.setY(from.getY());
                to.setZ(from.getZ());
                event.setTo(to);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.getConfigManager().preventChat()) return;

        Player player = event.getPlayer();
        if (!sessions.isLoggedIn(player)) {
            if (Debug.on()) {
                Debug.log("flow", "blocked chat for %s (not logged in)", player.getName());
            }
            event.setCancelled(true);
            player.sendMessage(I18n.msg("listener.must_login", player));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfigManager().preventCommand()) return;

        Player player = event.getPlayer();
        if (sessions.isLoggedIn(player)) return;

        // 提取命令名（去掉前导 / 和参数），统一小写匹配
        String message = event.getMessage();
        if (message.startsWith("/")) message = message.substring(1);
        int space = message.indexOf(' ');
        String commandName = (space > 0 ? message.substring(0, space) : message).toLowerCase(Locale.ROOT);
        // 剥离命令命名空间前缀（/hsauth:login 与 /login 是同一命令，否则无法经命名空间形式登录）
        int colon = commandName.indexOf(':');
        if (colon >= 0) commandName = commandName.substring(colon + 1);

        // 白名单内的命令允许执行
        if (plugin.getConfigManager().commandWhitelist().contains(commandName)) {
            return;
        }

        if (Debug.on()) {
            Debug.log("cmd", "blocked command for %s: /%s", player.getName(), commandName);
        }
        event.setCancelled(true);
        player.sendMessage(I18n.msg("listener.must_login", player));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (plugin.getConfigManager().preventWorldInteraction()
                && !sessions.isLoggedIn(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (plugin.getConfigManager().preventWorldInteraction()
                && !sessions.isLoggedIn(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.getConfigManager().preventWorldInteraction()) return;
        if (event.getEntity() instanceof Player player) {
            // 未登录玩家或传送过渡期玩家不受伤害
            if (!sessions.isLoggedIn(player) || sessions.isInvulnerablePending(player)) {
                event.setCancelled(true);
                return;
            }
        }
        // 未登录玩家不可伤害任何实体（左键攻击不经过交互事件，须拦攻击者一侧）
        if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent byEntity) {
            // 直接近战：damager 为玩家，拦未登录者
            if (byEntity.getDamager() instanceof Player damager && !sessions.isLoggedIn(damager)) {
                event.setCancelled(true);
                return;
            }
            // 投射物：damager 为投射物实体，归因到射击者（箭离弦后射击者注销时仍可命中）
            if (byEntity.getDamager() instanceof Projectile projectile
                    && projectile.getShooter() instanceof Player shooter
                    && !sessions.isLoggedIn(shooter)) {
                event.setCancelled(true);
            }
        }
    }

    // 阻止怪物锁定未登录玩家（怪物不会朝玩家移动或试图攻击）
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityTarget(EntityTargetEvent event) {
        if (!plugin.getConfigManager().preventWorldInteraction()) return;
        if (event.getTarget() instanceof Player player
                && !sessions.isLoggedIn(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (plugin.getConfigManager().preventWorldInteraction()
                && event.getEntity() instanceof Player player
                && !sessions.isLoggedIn(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDropItem(PlayerDropItemEvent event) {
        if (plugin.getConfigManager().preventWorldInteraction()
                && !sessions.isLoggedIn(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickupItem(PlayerAttemptPickupItemEvent event) {
        if (plugin.getConfigManager().preventWorldInteraction()
                && !sessions.isLoggedIn(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (plugin.getConfigManager().preventWorldInteraction()
                && !sessions.isLoggedIn(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // 实体交互（右键实体：村民交易、上马、喂食等）：未登录玩家保持原游戏模式时可打开交易界面窥视
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (plugin.getConfigManager().preventWorldInteraction()
                && !sessions.isLoggedIn(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // 禁止未登录的旁观玩家附身实体：附身后镜头跟随目标实体移动，可窥视他人位置（绕过坐标保护）。
    // Paper 1.21.11 已移除 PlayerSpectateEntityEvent，附身改由 cause=SPECTATE 的传送事件表达
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpectateTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE) return;
        if (plugin.getConfigManager().preventWorldInteraction()
                && !sessions.isLoggedIn(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // 容器点击（含创造模式）
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!plugin.getConfigManager().preventInventory()) return;
        if (event.getWhoClicked() instanceof Player player
                && !sessions.isLoggedIn(player)) {
            event.setCancelled(true);
        }
    }

    // 容器拖拽
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!plugin.getConfigManager().preventInventory()) return;
        if (event.getWhoClicked() instanceof Player player
                && !sessions.isLoggedIn(player)) {
            event.setCancelled(true);
        }
    }

    // 传送门
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPortal(PlayerPortalEvent event) {
        if (!plugin.getConfigManager().preventWorldInteraction()) return;
        if (!sessions.isLoggedIn(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // 物品消耗（进食、喝药水等）
    @EventHandler(priority = EventPriority.LOWEST)
    public void onItemConsume(PlayerItemConsumeEvent event) {
        if (!plugin.getConfigManager().preventInventory()) return;
        if (!sessions.isLoggedIn(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // 副手切换
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        if (!plugin.getConfigManager().preventInventory()) return;
        if (!sessions.isLoggedIn(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
}
