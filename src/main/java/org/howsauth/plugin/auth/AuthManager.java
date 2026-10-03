package org.howsauth.plugin.auth;

import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.api.event.HSAuthLoginEvent;
import org.howsauth.plugin.api.event.HSAuthLoginFailEvent;
import org.howsauth.plugin.api.event.HSAuthLogoutEvent;
import org.howsauth.plugin.api.event.HSAuthRegisterEvent;
import org.howsauth.plugin.api.event.HSAuthUnregisterEvent;
import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.data.PlayerDataManager;
import org.howsauth.plugin.data.PlayerDataManager.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
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
    // 线程安全集合，用于 Folia 多线程区域化调度
    private final Set<UUID> loggedIn = ConcurrentHashMap.newKeySet();
    // 本次连接是否完成过认证（登录/注册/免密）：供加入/退出消息、退出位置保存等会话级判定使用。
    // 与 loggedIn 的职责划分：loggedIn 表示实时登录态（登录前防护、命令权限、免密判定），
    // 强制登出/注销会立即失效它；本标记只记录"本次连接是否认证过"，不随提前失效而清除，
    // 仅在退出清理（clearSession）时移除
    private final Set<UUID> authenticatedThisConnection = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingLogin = ConcurrentHashMap.newKeySet();
    // 标记密码异步校验进行中的玩家：防止快速重复提交 /login 触发重复校验、重复登录事件与消息
    private final Set<UUID> verifying = ConcurrentHashMap.newKeySet();
    // 双因素认证：密码已通过但尚未完成 TOTP 验证的玩家（未完成前不算已登录）
    private final Set<UUID> pending2fa = ConcurrentHashMap.newKeySet();
    // 已消费的 2FA 时间片计数器：登录验证通过后记录，拒绝同周期或更旧验证码重放
    // （仅内存，重启清零后同一验证码在 ≤90 秒窗口内理论上可重放一次，风险可忽略）
    private final Map<UUID, Long> used2faCounters = new ConcurrentHashMap<>();
    // 2FA 会话保持：验证码通过后记录 (ip, 到期时间)，同 IP 短时间内重连免验证码
    // 固定窗口不滑动（命中不续期）；仅内存，重启即失效
    private final Map<UUID, TwoFaSession> twoFaSessions = new ConcurrentHashMap<>();
    // 登录会话保持：密码/2FA 验证通过后记录 (ip, 建立时间戳)，同 IP 且未过期免输密码
    // 固定窗口不滑动（命中登录不刷新建立时间），避免活跃账号会话永不过期
    private final Map<UUID, LoginSession> loginSessions = new ConcurrentHashMap<>();
    // 双因素设置中的临时密钥：confirm 验证通过后才持久化
    private final Map<UUID, String> pending2faSecret = new ConcurrentHashMap<>();
    // 临时密钥的创建时间戳：用于按配置时长过期清理（与 pending2faSecret 一一对应）
    private final Map<UUID, Long> pending2faSecretCreatedAt = new ConcurrentHashMap<>();
    // 登录后传送过渡期：玩家已登录但还在传送到退出位置，期间保持无敌
    private final Set<UUID> invulnerablePending = ConcurrentHashMap.newKeySet();
    // 标记当前会话被设为旁观的玩家：onLoginSuccess 仅对这些玩家恢复游戏模式，
    // 避免对免密登录（IP/正版）的玩家做不必要的 setGameMode
    private final Set<UUID> spectatorPending = ConcurrentHashMap.newKeySet();
    // 暴力破解防护：记录失败次数[0]/最后失败时间[1]和踢出到期时间
    // failedAttempts 跨连接保留，超过 reset-seconds 未再失败则过期清空
    private final Map<UUID, long[]> failedAttempts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> kickUntil = new ConcurrentHashMap<>();
    // 标记待删除原版数据的玩家（unregister 后等待 PlayerQuitEvent 触发时删除 .dat）
    private final Set<UUID> pendingDatDelete = ConcurrentHashMap.newKeySet();
    // 记录最近注销的玩家时间戳：5 秒内拒绝重连，确保 .dat 删除完成
    private final Map<UUID, Long> recentUnregister = new ConcurrentHashMap<>();
    // 待升级离线账号（离线 UUID）：玩家执行升级指令后标记，下次登录时尝试正版验证
    private final Set<UUID> pendingUpgrade = ConcurrentHashMap.newKeySet();
    // 正版账号降级标记（内存，不持久化）：下次进入时迁移数据到离线 UUID
    private final Set<UUID> pendingDowngrade = ConcurrentHashMap.newKeySet();
    // 正版验证失败回退进入的正版玩家（正版 UUID → 标记时间戳）：本次需密码登录，不自动免密。
    // 标记仅代表当前连接会话，正常路径由退出清理；未进世界即断开的连接无退出事件，靠 TTL 过期兜底，
    // TTL 复用回退确认窗口（premium.fallback.cache-seconds）
    private final Map<UUID, Long> premiumFallback = new ConcurrentHashMap<>();
    // 登录超时任务启动时间戳：用于判断超时任务是否为最新（重启时旧任务自动失效）
    private final Map<UUID, Long> loginTimeoutStartedAt = new ConcurrentHashMap<>();
    private volatile Consumer<UUID> unregisterConfirmInvalidator;
    // 缓存世界结构类型：26.1+ 采用新结构（players/data + dimensions/minecraft/overworld）
    private final boolean newWorldStructure;
    // 注销后拒绝重连时长（毫秒）
    private static final long UNREGISTER_RECONNECT_DELAY = 5000L;
    // failedAttempts 容量阈值：超过时清理未达阈值的失败计数，防止攻击者用大量用户名
    // 各失败未达阈值导致 Map 无界增长（失败计数跨连接保留后不再随退出清理）
    private static final int FAILED_ATTEMPTS_CAP = 1000;

    public AuthManager(HowSAuth plugin, PlayerDataManager dataManager, ConfigManager configManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.configManager = configManager;
        this.newWorldStructure = detectNewWorldStructure();
        // 周期清理过期的 2FA 临时密钥与登录/2FA 会话等状态（懒清理兜底，随插件关闭统一取消）
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
            cleanupExpired2faSecrets();
            cleanupExpiredStates();
        }, 1, 30, TimeUnit.SECONDS);
    }

    /**
     * 检测服务端是否使用 26.1+ 的新世界文件结构。
     * Bukkit.getBukkitVersion() 返回如 "1.21.11-R0.1-SNAPSHOT" 或 "26.1.2-R0.1-SNAPSHOT"。
     * 26.1+ 主版本号 >= 26，旧版 1.x 主版本号始终为 1。
     */
    private static boolean detectNewWorldStructure() {
        String version = Bukkit.getBukkitVersion();
        int dash = version.indexOf('-');
        String nums = dash > 0 ? version.substring(0, dash) : version;
        String[] parts = nums.split("\\.");
        try {
            return Integer.parseInt(parts[0]) >= 26;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 同 IP 已注册账号数是否已达上限：仅按已注册账号数判定，未注册玩家不占用名额。
     * 连接阶段（拦截已满的 IP）与注册阶段（精确兜底）共用。
     * @param ip 玩家 IP（null 视为未达上限）
     * @return true 已达上限，false 仍可注册/进入
     */
    public boolean isIpAccountLimitReached(String ip) {
        int max = configManager.maxAccountsPerIp();
        if (max <= 0) return false;
        if (ip == null) return false;
        return dataManager.findByIp(ip).size() >= max;
    }

    /** 触发同步 API 事件：tick 线程直接触发，异步线程转全局区域调度器。
     *  管理命令在异步线程执行（getOfflinePlayer 防阻塞），直接 callEvent 会抛 IllegalStateException */
    private void fireEvent(Event event) {
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(event);
        } else {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> Bukkit.getPluginManager().callEvent(event));
        }
    }

    /** 提取玩家客户端 IP（getAddress 可能为 null，如代理协议未解析完成时） */
    public static String clientIp(Player player) {
        if (player == null) return null;
        var socketAddress = player.getAddress();
        if (socketAddress == null) return null;
        var address = socketAddress.getAddress();
        return address != null ? address.getHostAddress() : null;
    }

    // Registration
    /** 创建账号（哈希+写库）：强制注册专用（管理员绕过 IP 名额限制）；同名账号（含正版）已存在时拒绝，维持用户名全局唯一。name 为 null 时仅按 UUID 查重；ip 可为 null（记为 "unknown"） */
    private boolean createAccount(UUID uuid, String name, String password, String ip) {
        if (dataManager.hasAccountByName(name) || dataManager.hasAccount(uuid)) {
            return false;
        }
        String hash = PasswordHash.hashPassword(password, configManager.passwordHashAlgorithm(), configManager.bcryptCost());
        dataManager.createPlayer(uuid, hash, ip != null ? ip : "unknown");
        return true;
    }

    /**
     * 异步注册：bcrypt 哈希在异步线程执行（避免阻塞玩家区域线程），建号与登录收尾回到玩家区域线程。
     * @param done 回调（玩家区域线程）：true 注册成功；false 已达 IP 上限或账号已存在（并发竞态）
     */
    public void registerAsync(Player player, String password, Consumer<Boolean> done) {
        UUID uuid = player.getUniqueId();
        String ip = clientIp(player);
        if (isIpAccountLimitReached(ip)) {
            done.accept(false);
            return;
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String hash = PasswordHash.hashPassword(password, configManager.passwordHashAlgorithm(), configManager.bcryptCost());
            player.getScheduler().run(plugin, task2 -> {
                // 同名账号（含正版）已存在时拒绝；离线 UUID 由名推导，该判定同时覆盖账号已存在的并发竞态
                if (dataManager.hasAccountByName(player.getName())) {
                    done.accept(false);
                    return;
                }
                // 建号+登录收尾在区域线程（轻量），立即落库（关键操作防崩溃丢失，经串行写队列）
                // 名额判定与建号原子完成：并发注册同一 IP 不会全部通过检查（防 max-accounts-per-ip 被绕过）
                if (dataManager.createPlayerIfIpAllowed(uuid, hash, ip != null ? ip : "unknown",
                        configManager.maxAccountsPerIp()) == null) {
                    done.accept(false);
                    return;
                }
                markLoggedIn(uuid);
                onLoginSuccess(player);
                fireEvent(new HSAuthRegisterEvent(uuid, player));
                done.accept(true);
            }, null);
        });
    }

    /**
     * 强制注册：管理员绕过 IP 限制强制为玩家创建账号。
     * 同名账号已存在时返回 false。玩家在线时记录其当前 IP，离线时记为 "unknown"（下次登录时更新）。
     * 不会自动登录，玩家需自行 /login。
     */
    public boolean forceRegister(UUID uuid, String name, String password) {
        Player online = Bukkit.getPlayer(uuid);
        String ip = online != null ? clientIp(online) : null;
        if (!createAccount(uuid, name, password, ip)) return false;
        fireEvent(new HSAuthRegisterEvent(uuid, online));
        return true;
    }

    /** 配置阶段注册（Pre-join Dialog）：仅创建账号，登录状态与注册事件延迟到玩家进入世界时处理。IP 已满或同名账号已存在时拒绝 */
    public boolean registerConfig(UUID uuid, String name, String password, String ip) {
        // 前置快速判定（避免无谓的 bcrypt 计算）；权威判定在建号临界区内原子完成
        if (isIpAccountLimitReached(ip)) {
            return false;
        }
        if (dataManager.hasAccountByName(name) || dataManager.hasAccount(uuid)) {
            return false;
        }
        String hash = PasswordHash.hashPassword(password, configManager.passwordHashAlgorithm(), configManager.bcryptCost());
        // 名额判定与建号原子完成：并发注册同一 IP 不会全部通过检查（防 max-accounts-per-ip 被绕过）
        return dataManager.createPlayerIfIpAllowed(uuid, hash, ip != null ? ip : "unknown",
                configManager.maxAccountsPerIp()) != null;
    }

    // Login
    /**
     * 异步登录：bcrypt 密码校验在异步线程执行（约 100ms，避免阻塞服务端 tick），
     * 成功/失败后的状态变更与事件回到玩家区域线程执行（Folia 线程安全）。
     * @param done 回调（在玩家区域线程调用）：参数 1 登录结果；参数 2 失败时的剩余踢出秒数
     */
    public void loginAsync(Player player, String password, BiConsumer<LoginResult, Long> done) {
        UUID uuid = player.getUniqueId();
        // 轻量检查（主线程/调用线程）
        if (isKicked(player)) {
            done.accept(LoginResult.FAILED, getKickRemaining(player));
            return;
        }
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) {
            done.accept(LoginResult.FAILED, 0L);
            return;
        }
        // 重入保护：已有一次密码校验进行中时静默忽略本次，避免重复校验、重复登录事件与消息
        if (!verifying.add(uuid)) {
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
                verifying.remove(uuid);
                // 回调期间被强制登录或账号被注销（管理员操作与异步校验的竞态）：丢弃本次结果
                // 避免二次登录收尾（重复事件/覆盖会话时间）与注销后的幽灵登录
                if (loggedIn.contains(uuid) || dataManager.getPlayer(uuid) == null) {
                    return;
                }
                if (!ok) {
                    handleLoginFailure(uuid, player);
                    done.accept(LoginResult.FAILED, isKicked(player) ? getKickRemaining(player) : 0L);
                } else {
                    // 密码明文仅此处可用，须在进入 2FA 等待前完成对齐
                    if (alignedHash != null) snapshot.passwordHash(alignedHash);
                    String playerIp = clientIp(player);
                    if (requires2faAtLogin(uuid, playerIp)) {
                        // 密码正确但需双因素认证：进入待验证状态，不算已登录
                        // 开关关闭时跳过验证（密钥保留在数据库，重新开启后恢复）
                        pending2fa.add(uuid);
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
        if (isKicked(uuid)) {
            done.accept(LoginResult.FAILED, getKickRemaining(uuid));
            return;
        }
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) {
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
                done.accept(LoginResult.FAILED, isKicked(uuid) ? getKickRemaining(uuid) : 0L);
            } else {
                String alignedHash = alignedPasswordHash(snapshot, password);
                if (alignedHash != null) snapshot.passwordHash(alignedHash);
                if (requires2faAtLogin(uuid, ip)) {
                    pending2fa.add(uuid);
                    done.accept(LoginResult.NEED_2FA, 0L);
                } else {
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
        markLoginSession(uuid, ip);
        markLoggedIn(uuid);
        // 正版回退玩家密码登录成功，清除回退标记（下次正版验证成功即自动免密）
        clearPremiumFallback(uuid);
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

    /** 2FA 会话：验证码通过时的来源 IP 与到期时间戳 */
    private record TwoFaSession(String ip, long expiresAt) {}

    /** 登录会话：验证通过时的来源 IP 与建立时间戳（固定窗口不滑动） */
    private record LoginSession(String ip, long establishedAt) {}

    /** 记录登录会话：验证通过后同 IP 且未过期免输密码（固定窗口，命中登录不刷新） */
    private void markLoginSession(UUID uuid, String ip) {
        if (!configManager.sessionEnabled() || ip == null) return;
        loginSessions.put(uuid, new LoginSession(ip, System.currentTimeMillis()));
    }

    /** 清除登录会话（登出/强制操作/注销时调用：安全事件后不保留免密码信任） */
    private void clearLoginSession(UUID uuid) {
        loginSessions.remove(uuid);
    }

    /** 记录 2FA 会话：验证码通过后同 IP 短时间内重连免验证码（固定窗口，命中不续期） */
    private void mark2faSession(UUID uuid, String ip) {
        if (!configManager.twoFaSessionEnabled() || ip == null) return;
        twoFaSessions.put(uuid, new TwoFaSession(ip,
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(configManager.twoFaSessionExpireMinutes())));
    }

    /** 2FA 会话是否命中（免验证码）：开关开启 + 同 IP 且未过期。
     *  无密码账户同样适用——风险模型与 login.session 一致（同 IP 短窗口信任），窗口内等同零凭证登录 */
    public boolean has2faSession(UUID uuid, String ip) {
        if (!configManager.twoFaSessionEnabled() || ip == null) return false;
        TwoFaSession s = twoFaSessions.get(uuid);
        if (s == null) return false;
        if (!s.ip().equals(ip) || System.currentTimeMillis() > s.expiresAt()) {
            twoFaSessions.remove(uuid);
            return false;
        }
        return true;
    }

    /** 清除 2FA 会话（登出/强制操作/注销时调用：安全事件后不保留免验证码信任） */
    private void clear2faSession(UUID uuid) {
        twoFaSessions.remove(uuid);
    }

    /** IP 变动提醒是否适用于该玩家：离线玩家提醒；正版玩家仅当正版验证回退开启时提醒（fallback 仅约束正版） */
    private boolean notifyIpChangeFor(PlayerData data) {
        return !data.premium() || configManager.premiumPasswordFallbackEnabled();
    }

    // ===== 双因素认证（TOTP） =====

    /** 账号是否处于双因素认证生效状态（已绑定密钥且全局开关开启） */
    public boolean has2fa(UUID uuid) {
        PlayerData data = dataManager.getPlayer(uuid);
        return data != null && data.totpSecret() != null && configManager.twoFaEnabled();
    }

    /** 玩家是否处于双因素待验证状态（密码已通过，TOTP 未完成） */
    // 调用方均为取反使用（!isPending2fa 判断"无需 2FA"），方法语义保持正向便于阅读
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isPending2fa(UUID uuid) {
        return pending2fa.contains(uuid);
    }

    /** 免密登录（正版/IP）但已绑定 2FA：配置阶段直弹验证码窗口前标记待验证（verify2faConfig 以此为前置状态） */
    public void addPending2fa(UUID uuid) {
        pending2fa.add(uuid);
    }

    /** 清除 2FA 待验证标记（会话级状态，不跨连接：新连接进入配置阶段时清残留，语义与 clearSession 一致） */
    public void clearPending2fa(UUID uuid) {
        pending2fa.remove(uuid);
    }

    /** 是否为无密码账户（密码哈希为空，登录依赖验证码或正版验证） */
    public boolean isPasswordless(UUID uuid) {
        PlayerData data = dataManager.getPlayer(uuid);
        return data != null && (data.passwordHash() == null || data.passwordHash().isEmpty());
    }

    /** 是否已绑定 2FA 密钥（不看全局开关，移除密码的资格判断用） */
    public boolean hasTotpSecret(UUID uuid) {
        PlayerData data = dataManager.getPlayer(uuid);
        return data != null && data.totpSecret() != null;
    }

    /** 是否无可用登录方式：无密码、未绑验证器且非正版（正版可免密），任何认证路径都不可行 */
    public boolean hasNoUsableLoginMethod(UUID uuid) {
        return isPasswordless(uuid) && !hasTotpSecret(uuid) && !isPremium(uuid);
    }

    /** 登录时是否必须完成 2FA 验证：已绑定密钥且（全局开关开启，或无密码账户——验证码是其必要登录因素，不受开关影响）。
     *  2FA 会话命中（同 IP 且未过期）时返回 false 免验证码 */
    public boolean requires2faAtLogin(UUID uuid, String ip) {
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null || data.totpSecret() == null) return false;
        if (has2faSession(uuid, ip)) return false;
        return configManager.twoFaEnabled()
                || data.passwordHash() == null || data.passwordHash().isEmpty();
    }

    /**
     * 移除密码，转为无密码账户。
     * 离线账户已绑定 2FA 时验证码作为确认凭据（移除后即为唯一登录因素）；正版账户凭正版验证放行。
     * @return true 移除成功；false 验证码错误或账号不存在
     */
    public boolean removePassword(Player player, String code) {
        UUID uuid = player.getUniqueId();
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) return false;
        // 离线账户移除密码前须验证 TOTP（验证码成为唯一登录因素）；正版账户凭正版验证放行，无需验证码
        if (data.totpSecret() != null && !data.premium()) {
            if (code == null || code.isEmpty() || !Totp.verifyCode(data.totpSecret(), code)) return false;
        }
        dataManager.updatePassword(uuid, "");
        return true;
    }

    /**
     * 完成双因素验证：校验 TOTP 验证码，通过则完成登录。
     * @return true 验证通过且登录完成
     */
    public boolean verify2fa(Player player, String code) {
        PlayerData data = verify2faCode(player.getUniqueId(), player, code, clientIp(player));
        if (data == null) return false;
        completeLogin(player, data);
        return true;
    }

    /** 配置阶段完成双因素验证（Pre-join Dialog）：通过则由调用方放行（登录收尾延迟到进入世界时），失败回到待验证状态 */
    public boolean verify2faConfig(UUID uuid, String code, String ip) {
        return verify2faCode(uuid, null, code, ip) != null;
    }

    /**
     * 2FA 验证核心：校验验证码，通过则记录 2FA 会话（免验证码重连窗口）。
     * 失败时与密码错误同待遇计入暴力破解防护（无密码账户的验证码即唯一登录因素，更须防护），
     * 并回到待验证状态允许重试。
     * @param player 在线验证时的玩家（暴力破解踢出提示用），配置阶段无 Player 传 null
     * @return 验证通过返回账号数据（供调用方登录收尾），失败返回 null
     */
    private PlayerData verify2faCode(UUID uuid, Player player, String code, String ip) {
        if (!pending2fa.remove(uuid)) return null;
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null || data.totpSecret() == null) return null;
        Long counter = Totp.matchCounter(data.totpSecret(), code);
        // 防重放：同周期或更旧的验证码视为已消费拒绝（TOTP 无状态，同一码在 ±1 窗口内可重复匹配）
        // 入口 pending2fa.remove 已保证同一玩家同一时刻仅一个线程进入消费路径，check-then-put 无竞态
        if (counter == null || counter <= used2faCounters.getOrDefault(uuid, Long.MIN_VALUE)) {
            handleLoginFailure(uuid, player);
            pending2fa.add(uuid);
            return null;
        }
        used2faCounters.put(uuid, counter);
        mark2faSession(uuid, ip);
        return data;
    }

    /** 开始双因素设置：返回待绑定密钥（confirm 通过后才持久化）
     *  已有未绑定的临时密钥则复用，避免重复执行 /2fa setup 时密钥被覆盖导致旧密钥失效
     *  复用前先清理已过期的旧密钥，避免复用过期密钥后无法 confirm */
    public String setup2fa(Player player) {
        UUID uuid = player.getUniqueId();
        if (has2fa(uuid)) return null;
        removeExpired2faSecret(uuid);
        return pending2faSecret.computeIfAbsent(uuid, u -> {
            String secret = Totp.generateSecret();
            pending2faSecretCreatedAt.put(uuid, System.currentTimeMillis());
            return secret;
        });
    }

    /** 确认双因素绑定：验证码通过后持久化临时密钥（临时密钥已过期则作废） */
    public boolean confirm2fa(Player player, String code) {
        UUID uuid = player.getUniqueId();
        removeExpired2faSecret(uuid);
        String secret = pending2faSecret.get(uuid);
        if (secret == null) return false;
        if (!Totp.verifyCode(secret, code)) return false;
        clearPending2faSecret(uuid);
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) return false;
        data.totpSecret(secret);
        dataManager.save(uuid);
        return true;
    }

    /** 玩家的临时密钥是否已失效：未生成或已过期（供提示"重新 setup"前判断）
     *  调用方均为取反前的直接判断，方法语义保持"已失效"便于阅读 */
    public boolean isPending2faSecretExpired(UUID uuid) {
        removeExpired2faSecret(uuid);
        return !pending2faSecret.containsKey(uuid);
    }

    /** 临时密钥是否已超期（配置为 0 时永不过期） */
    private boolean isExpired2faSecret(long created) {
        int seconds = configManager.twoFaTempSecretExpireSeconds();
        return seconds > 0 && System.currentTimeMillis() - created >= seconds * 1000L;
    }

    /** 移除过期临时密钥（setup/confirm 前调用，懒清理） */
    private void removeExpired2faSecret(UUID uuid) {
        Long created = pending2faSecretCreatedAt.get(uuid);
        if (created != null && isExpired2faSecret(created)) {
            clearPending2faSecret(uuid);
        }
    }

    /** 清理单个玩家的临时密钥及创建时间戳 */
    private void clearPending2faSecret(UUID uuid) {
        pending2faSecret.remove(uuid);
        pending2faSecretCreatedAt.remove(uuid);
    }

    /** 周期清理所有过期的临时密钥（异步调度器调用） */
    private void cleanupExpired2faSecrets() {
        int seconds = configManager.twoFaTempSecretExpireSeconds();
        if (seconds <= 0) return;
        long limit = seconds * 1000L;
        long now = System.currentTimeMillis();
        List<UUID> expired = new ArrayList<>();
        pending2faSecretCreatedAt.forEach((uuid, created) -> {
            if (now - created >= limit) expired.add(uuid);
        });
        for (UUID uuid : expired) {
            clearPending2faSecret(uuid);
        }
    }

    /** 关闭双因素认证：需验证当前 TOTP 验证码（而非密码——2FA 正是防密码泄漏，解绑也须持有验证器） */
    public boolean disable2fa(Player player, String code) {
        UUID uuid = player.getUniqueId();
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null || data.totpSecret() == null) return false;
        if (!Totp.verifyCode(data.totpSecret(), code)) return false;
        data.totpSecret(null);
        // 解绑后待验证标记已无意义，清除避免残留（残留会让玩家在密码窗口卡死/状态泄漏至下次退出）
        pending2fa.remove(uuid);
        used2faCounters.remove(uuid);
        // 关键操作立即持久化，防止断电丢失
        dataManager.saveNow(uuid);
        return true;
    }

    /**
     * 管理员强制解除双因素认证：不校验验证码（玩家可能已丢失验证器密钥导致账号锁死的救济通道），
     * 仅清除 TOTP 密钥与会话/计数/待确认状态，保留账号其余数据（密码、正版标记、位置等）。
     * 绑定中返回 true；账号不存在或未绑定时返回 false。
     */
    public boolean reset2fa(UUID uuid) {
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null || data.totpSecret() == null) return false;
        data.totpSecret(null);
        // 管理员解除同样使待验证状态失效，一并清理（语义与 disable2fa 一致）
        pending2fa.remove(uuid);
        used2faCounters.remove(uuid);
        clear2faSession(uuid);
        clearPending2faSecret(uuid);
        // 关键操作立即持久化，防止断电丢失
        dataManager.saveNow(uuid);
        return true;
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
        // 增加计数（仅在启用失败保护时）
        if (configManager.failProtectionEnabled()) {
            long now = System.currentTimeMillis();
            long resetMs = configManager.failProtectionResetSeconds() * 1000L;
            // 容量守卫：失败计数跨连接保留后不再随退出清理，超限时清理可安全移除的条目
            if (failedAttempts.size() > FAILED_ATTEMPTS_CAP) {
                evictStaleFailures(now);
            }
            // 原子计数：距上次失败超过过期时长则重置为 1，否则累加（跨连接保留）
            int[] attempts = new int[1];
            failedAttempts.compute(uuid, (k, v) -> {
                if (v == null || (resetMs > 0 && now - v[1] >= resetMs)) {
                    attempts[0] = 1;
                    return new long[]{1, now};
                }
                v[0]++;
                v[1] = now;
                attempts[0] = (int) v[0];
                return v;
            });
            if (attempts[0] >= configManager.failMaxAttempts()) {
                // 达到阈值，设置踢出期
                kickUntil.put(uuid, now + configManager.failKickDuration() * 1000L);
                failedAttempts.remove(uuid);
                // 容量守卫：攻击者用大量用户名各达阈值后不再重连，踢出记录仅在被读取时懒清理，
                // 超限时清理已过期项，防止 Map 无界增长（与 failedAttempts 守卫同一威胁模型）
                if (kickUntil.size() > FAILED_ATTEMPTS_CAP) {
                    kickUntil.values().removeIf(until -> until <= now);
                }
            }
        }
        HSAuthLoginFailEvent event = new HSAuthLoginFailEvent(player, HSAuthLoginFailEvent.Reason.WRONG_PASSWORD);
        if (player != null) {
            Bukkit.getPluginManager().callEvent(event);
        } else {
            // 配置阶段无 Player：异步线程不能直接触发同步事件，转全局区域调度器
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> Bukkit.getPluginManager().callEvent(event));
        }
    }

    public void setUnregisterConfirmInvalidator(Consumer<UUID> invalidator) {
        this.unregisterConfirmInvalidator = invalidator;
    }

    private void invalidateUnregisterConfirm(UUID uuid) {
        Consumer<UUID> invalidator = unregisterConfirmInvalidator;
        if (invalidator != null) invalidator.accept(uuid);
    }

    // ===== 管理员强制操作 =====

    /** 强制登出玩家（无需玩家在线，清除登录状态，并使登录会话与 2FA 会话失效） */
    public boolean forceLogout(UUID uuid) {
        if (!loggedIn.remove(uuid)) return false;
        pendingLogin.add(uuid);
        invalidateLoginSessions(uuid);
        fireEvent(new HSAuthLogoutEvent(uuid, Bukkit.getPlayer(uuid)));
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
        invalidateUnregisterConfirm(uuid);
        PlayerData data = dataManager.getPlayer(uuid);
        if (data != null) {
            data.lastLogin(0);
            dataManager.save(uuid);
        }
        clearLoginSession(uuid);
        clear2faSession(uuid);
    }

    /** 强制登录玩家（不管有没有账号，仅对在线玩家生效） */
    public void forceLogin(Player player) {
        UUID uuid = player.getUniqueId();
        markLoggedIn(uuid);
        onLoginSuccess(player);
        fireEvent(new HSAuthLoginEvent(player));
    }

    /** 登录会话是否命中（免输密码）：上次验证 IP 与当前一致，且未超过失效时间 */
    public boolean hasSession(Player player) {
        if (player.getAddress() == null) return false;
        return hasSession(player.getUniqueId(), clientIp(player));
    }

    /** 登录会话是否命中（无需 Player 对象，用于 AsyncPlayerSpawnLocationEvent） */
    public boolean hasSession(UUID uuid, String ip) {
        if (!configManager.sessionEnabled()) return false;
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) return false;
        if (ip == null) return false;
        LoginSession s = loginSessions.get(uuid);
        if (s == null) return false;
        if (!s.ip().equals(ip)) return false;
        // 固定窗口不滑动：命中登录不刷新建立时间，到期后需重新验证
        long expireMillis = TimeUnit.MINUTES.toMillis(configManager.sessionExpireMinutes());
        return System.currentTimeMillis() - s.establishedAt() < expireMillis;
    }

    // 免密登录：跳过密码验证直接完成登录（会话命中或正版验证通过后调用）
    // 登录需完成 2FA 的账号不直接放行：进入待验证状态，由 /2fa <验证码> 完成登录
    // 2FA 会话命中（同 IP 且未过期）时同样直接完成登录
    public void autoLogin(Player player) {
        PlayerData data = dataManager.getPlayer(player.getUniqueId());
        if (data == null) return;
        String ip = clientIp(player);
        if (requires2faAtLogin(player.getUniqueId(), ip)) {
            pending2fa.add(player.getUniqueId());
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
     * （标记登录、恢复物品状态、触发注册事件；不更新登录时间/IP——createPlayer 已记录）。
     * @return false 表示账号数据已不存在（被注销的竞态），调用方应回退正常登录流程
     */
    public boolean finishPreJoinRegister(Player player) {
        if (!dataManager.hasAccount(player.getUniqueId())) return false;
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
            done.accept(false);
            return;
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            if (!PasswordHash.checkPassword(oldPassword, data.passwordHash())) {
                player.getScheduler().run(plugin, task2 -> done.accept(false), null);
                return;
            }
            String newHash = PasswordHash.hashPassword(newPassword, configManager.passwordHashAlgorithm(), configManager.bcryptCost());
            player.getScheduler().run(plugin, task2 -> {
                dataManager.updatePassword(uuid, newHash);
                invalidateLoginSessions(uuid);
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
            done.accept(false);
            return;
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            String newHash = PasswordHash.hashPassword(newPassword, configManager.passwordHashAlgorithm(), configManager.bcryptCost());
            player.getScheduler().run(plugin, task2 -> {
                dataManager.updatePassword(uuid, newHash);
                done.accept(true);
            }, null);
        });
    }

    /**
     * 异步校验自助注销凭据：按账户持有情况校验密码与 2FA 验证码（均已绑定时两项都须通过），
     * 正版账户凭正版验证直接通过。bcrypt 校验在异步线程执行，结果回调回到玩家区域线程
     */
    public void verifyUnregisterCredentialsAsync(Player player, String password, String code, Consumer<Boolean> done) {
        UUID uuid = player.getUniqueId();
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) {
            done.accept(false);
            return;
        }
        // 正版账户：正版验证即身份凭证，免凭据校验
        if (data.premium()) {
            done.accept(true);
            return;
        }
        boolean hasPassword = data.passwordHash() != null && !data.passwordHash().isEmpty();
        String secret = data.totpSecret();
        // 无密码账户：验证码为唯一凭据（HMAC 计算开销极小，同步校验）
        if (!hasPassword) {
            done.accept(secret != null && code != null && Totp.verifyCode(secret, code));
            return;
        }
        // 有密码账户：bcrypt 校验异步执行，验证码一并校验后回调
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            boolean pwOk = password != null && PasswordHash.checkPassword(password, data.passwordHash());
            boolean codeOk = secret == null || (code != null && Totp.verifyCode(secret, code));
            boolean ok = pwOk && codeOk;
            player.getScheduler().run(plugin, task2 -> done.accept(ok), null);
        });
    }

    // Unregister
    public boolean unregister(UUID uuid) {
        if (!dataManager.hasAccount(uuid)) return false;
        dataManager.removePlayer(uuid);
        loggedIn.remove(uuid);
        pendingLogin.remove(uuid);
        pending2fa.remove(uuid);
        clearPending2faSecret(uuid);
        // 防重放计数一并清理：残留计数会误拒重绑定新密钥后的正确验证码（counter 单调消费）
        used2faCounters.remove(uuid);
        failedAttempts.remove(uuid);
        kickUntil.remove(uuid);
        invulnerablePending.remove(uuid);
        spectatorPending.remove(uuid);
        pendingUpgrade.remove(uuid);
        pendingDowngrade.remove(uuid);
        premiumFallback.remove(uuid);
        clearLoginSession(uuid);
        clear2faSession(uuid);
        // 根据配置决定是否删除 Minecraft 原版玩家数据（player.dat）
        if (configManager.realUnreg()) {
            // 记录注销时间，5 秒内拒绝重连，确保 .dat 删除完成
            recentUnregister.put(uuid, System.currentTimeMillis());
            if (Bukkit.getPlayer(uuid) != null) {
                // 玩家在线：标记后由 PlayerQuitEvent 删除（避免文件锁冲突）
                pendingDatDelete.add(uuid);
            } else {
                // 玩家离线：无文件锁，直接删除
                deletePlayerDataWithRetry(uuid);
            }
            // 顺手清理已过期的踢出记录、失败计数和注销拒绝重连记录，防止批量注销时累积
            cleanupExpiredStates();
        }
        fireEvent(new HSAuthUnregisterEvent(uuid, Bukkit.getPlayer(uuid)));
        return true;
    }

    /** 检查玩家是否在注销后的拒绝重连期内（5 秒） */
    public boolean isRecentlyUnregistered(UUID uuid) {
        Long time = recentUnregister.get(uuid);
        if (time == null) return false;
        if (System.currentTimeMillis() - time >= UNREGISTER_RECONNECT_DELAY) {
            recentUnregister.remove(uuid);
            return false;
        }
        return true;
    }

    /** 获取拒绝重连剩余秒数 */
    public long getRecentUnregisterRemaining(UUID uuid) {
        Long time = recentUnregister.get(uuid);
        if (time == null) return 0;
        long remaining = UNREGISTER_RECONNECT_DELAY - (System.currentTimeMillis() - time);
        return remaining > 0 ? (remaining + 999) / 1000 : 0;
    }

    /**
     * 在 PlayerQuitEvent 中调用：异步重试删除玩家 .dat 文件。
     * 玩家被踢出后服务器仍会将其数据保存到 .dat，早于保存完成的删除会被覆盖回写。
     * 采用重试机制：首次延迟 1000ms（等保存完成）后尝试，文件仍存在则每 300ms 重试，
     * 5 秒内持续尝试，确保服务器完成保存后能可靠删除。
     */
    public void tryDeletePlayerDataOnQuit(UUID uuid) {
        if (!pendingDatDelete.remove(uuid)) return;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> deletePlayerDataWithRetry(uuid));
    }

    /**
     * 异步删除玩家原版数据（player.dat、advancements、stats），复用 unregister 的重试删除逻辑。
     * 账号合并作废场景使用：离线号记录删除后，遗留文件会被同名新注册玩家继承，必须一并清理。
     * 遵循 settings.real-unreg 配置；可在网络线程调用（内部异步调度，不阻塞调用线程）。
     */
    public void deletePlayerDataAsync(UUID uuid) {
        if (!configManager.realUnreg()) return;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> deletePlayerDataWithRetry(uuid));
    }

    /** 重试删除玩家数据，5 秒内持续尝试（首次 1000ms，后续每 300ms） */
    @SuppressWarnings("BusyWait")
    private void deletePlayerDataWithRetry(UUID uuid) {
        long elapsed = 0;
        while (true) {
            try {
                Thread.sleep(elapsed == 0 ? 1000 : 300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            elapsed += elapsed == 0 ? 1000 : 300;
            if (deletePlayerData(uuid)) return;
            if (elapsed >= 5000) {
                plugin.getLogger().warning(I18n.get("log.delete_player_data_failed", uuid));
                return;
            }
        }
    }

    /**
     * 删除 Minecraft 原版玩家数据（player.dat、advancements、stats）。
     * 目录结构兼容（通过服务端版本判断，构造时缓存）：
     *   - 旧版（< 26.1）：world/playerdata、world/advancements、world/stats
     *   - 26.1+：world/players/data、world/players/advancements、world/players/stats
     *     （worldDir 是维度目录 world/dimensions/minecraft/overworld，玩家数据在其上级 3 层的世界根目录下）
     * 由 tryDeletePlayerDataOnQuit 异步重试调用（服务器保存 .dat 后再删除）。
     * @return true 表示文件已删除或不存在（成功）；false 表示文件仍存在（需重试）
     */
    private boolean deletePlayerData(UUID uuid) {
        World world = Bukkit.getWorlds().getFirst();
        File worldRoot = worldRoot(world);
        if (worldRoot == null) {
            plugin.getLogger().warning(I18n.get("log.player_data_dir_not_found", world.getWorldFolder().getAbsolutePath()));
            return true; // 目录不存在视为无需删除，停止重试
        }

        String[] dirs = playerDataDirs();
        // 删除 .dat_old（备份文件，失败仅告警，不影响重试）
        File datOldFile = new File(worldRoot, dirs[0] + "/" + uuid + ".dat_old");
        if (datOldFile.exists() && !datOldFile.delete()) {
            plugin.getLogger().warning(I18n.get("log.delete_player_data_backup_failed", datOldFile.getAbsolutePath()));
        }

        // 删除 .dat、advancements/.json、stats/.json，任一失败则重试
        return deletePlayerFile(new File(worldRoot, dirs[0]), uuid, ".dat")
                && deletePlayerFile(new File(worldRoot, dirs[1]), uuid, ".json")
                && deletePlayerFile(new File(worldRoot, dirs[2]), uuid, ".json");
    }

    /**
     * 将离线账号的原版玩家数据（player.dat、advancements、stats）迁移到正版 UUID。
     * 升级后 UUID 变化，若不迁移这些文件，玩家的背包/成就/统计会丢失。
     * 异步执行文件重命名（阻塞文件 IO，调用方无需关心线程）。
     */
    public void migratePlayerDataAsync(UUID fromUuid, UUID toUuid) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            World world = Bukkit.getWorlds().getFirst();
            File worldRoot = worldRoot(world);
            if (worldRoot == null) {
                plugin.getLogger().warning(I18n.get("log.player_data_dir_not_found", world.getWorldFolder().getAbsolutePath()));
                return;
            }
            String[] dirs = playerDataDirs();
            renamePlayerFile(new File(worldRoot, dirs[0]), fromUuid, toUuid, ".dat");
            renamePlayerFile(new File(worldRoot, dirs[1]), fromUuid, toUuid, ".json");
            renamePlayerFile(new File(worldRoot, dirs[2]), fromUuid, toUuid, ".json");
        });
    }

    /** 将玩家文件 &lt;from&gt;.&lt;ext&gt; 重命名为 &lt;to&gt;.&lt;ext&gt;（目标已存在则先删除旧目标） */
    private void renamePlayerFile(File dir, UUID from, UUID to, String ext) {
        if (!dir.isDirectory()) return;
        File src = new File(dir, from + ext);
        if (!src.exists()) return;
        File dst = new File(dir, to + ext);
        if (dst.exists() && !dst.delete()) {
            plugin.getLogger().warning(I18n.get("log.migrate_failed", src.getAbsolutePath()));
            return;
        }
        if (!src.renameTo(dst)) {
            plugin.getLogger().warning(I18n.get("log.migrate_failed", src.getAbsolutePath()));
        }
    }

    /**
     * 计算世界根目录（玩家数据所在目录）。
     * 26.1+：世界文件夹是维度目录 world/dimensions/minecraft/overworld，
     *   玩家数据在其上级 3 层的世界根目录下；旧版：世界文件夹即根目录。
     */
    private File worldRoot(World world) {
        // Paper 的 getWorldFolder() @NotNull，无需判空；26.1+ 向上 3 层到世界根目录
        File worldDir = world.getWorldFolder();
        if (!newWorldStructure) return worldDir;
        // 26.1+：向上 3 层到世界根目录
        File root = worldDir.getParentFile(); // minecraft
        if (root != null) root = root.getParentFile(); // dimensions
        if (root != null) root = root.getParentFile(); // world 根
        return root;
    }

    /**
     * 玩家数据三个子目录（data/advancements/stats），兼容新旧世界结构。
     * 26.1+ 在 players/ 下，旧版在根目录下（playerdata 名称也不同）。
     */
    private String[] playerDataDirs() {
        if (newWorldStructure) {
            return new String[]{"players/data", "players/advancements", "players/stats"};
        }
        return new String[]{"playerdata", "advancements", "stats"};
    }

    /**
     * 删除指定目录下的玩家文件（<uuid>.<ext>）。
     * @return true 表示文件已删除或目录/文件不存在；false 表示文件仍存在（需重试）
     */
    private boolean deletePlayerFile(File dir, UUID uuid, String ext) {
        if (!dir.isDirectory()) return true;
        File file = new File(dir, uuid + ext);
        if (!file.exists()) return true;
        return file.delete();
    }

    /** 标记玩家为已登录：置位实时登录态与本次连接认证标记，清理待登录、双因素待验证、失败计数、踢出记录 */
    private void markLoggedIn(UUID uuid) {
        loggedIn.add(uuid);
        authenticatedThisConnection.add(uuid);
        pendingLogin.remove(uuid);
        pending2fa.remove(uuid);
        failedAttempts.remove(uuid);
        kickUntil.remove(uuid);
    }

    /**
     * 玩家退出时调用（PlayerListener#onQuitCleanup，MONITOR）— 清理会话状态
     * 调用点固定在退出流程的最后阶段：实时登录态与本次连接认证标记的失效须晚于依赖它们的退出处理（如退出消息决策），不可提前
     */
    public void clearSession(Player player) {
        UUID uuid = player.getUniqueId();
        invalidateUnregisterConfirm(uuid);
        loggedIn.remove(uuid);
        // 本次连接认证标记随连接结束失效（晚于加入/退出消息与退出位置保存的判定）
        authenticatedThisConnection.remove(uuid);
        pendingLogin.remove(uuid);
        // 清除密码校验进行中标记（玩家在校验完成前退出时，异步回调的 player 调度不会执行，需在此兜底清理）
        verifying.remove(uuid);
        // 清除双因素认证会话状态（未完成验证即退出）
        pending2fa.remove(uuid);
        clearPending2faSecret(uuid);
        // 清除传送过渡期标记，防止下次登录时错误无敌
        invulnerablePending.remove(uuid);
        // 清除旁观标记（未登录退出时防止下次登录误恢复游戏模式）
        spectatorPending.remove(uuid);
        // 清除正版回退标记（会话级状态：本次连接要求密码登录，退出即失效，
        // 防止残留标记使下次验证成功的连接仍误走密码路径）
        premiumFallback.remove(uuid);
        // 清除超时任务标记（玩家已下线，旧任务无意义）
        loginTimeoutStartedAt.remove(uuid);
        // 注意：不清除失败计数与踢出记录（failedAttempts / kickUntil）。
        // 玩家被踢出或退出会触发 PlayerQuitEvent → 本方法；若在此清除，
        // 攻击者可通过"失败1-2次→重连"重置连续失败计数、或借被踢重连绕过踢出期，
        // 使 fail-protection 的连续失败阈值与踢出期保护失效。
        // 两者均为跨连接的暴力破解防护，须保留至达到阈值/登录成功/到期，由对应逻辑清理。
    }

    // Status checks
    public boolean isLoggedIn(Player player) {
        return loggedIn.contains(player.getUniqueId());
    }

    public boolean isLoggedIn(UUID uuid) {
        return loggedIn.contains(uuid);
    }

    /**
     * 本次连接是否完成过认证（登录/注册/免密）
     * 与 isLoggedIn 的区别：后者是实时登录态，强制登出/注销会提前失效；
     * 本方法描述"本次连接认证过"这一历史事实，供加入/退出消息、退出位置保存等会话级判定使用
     */
    public boolean hasAuthenticatedThisConnection(UUID uuid) {
        return authenticatedThisConnection.contains(uuid);
    }

    public boolean hasAccount(Player player) {
        return hasAccount(player.getUniqueId());
    }

    public boolean hasAccount(UUID uuid) {
        return dataManager.hasAccount(uuid);
    }

    public void addPendingLogin(Player player) {
        pendingLogin.add(player.getUniqueId());
    }

    /** 记录登录超时任务启动时间，返回当前时间戳（用于触发时判断是否为最新任务） */
    public long markLoginTimeoutStart(UUID uuid) {
        long now = System.currentTimeMillis();
        loginTimeoutStartedAt.put(uuid, now);
        return now;
    }

    /** 判断指定时间戳是否为最新的超时任务启动时间（旧任务自动失效） */
    public boolean isLatestLoginTimeout(UUID uuid, long startedAt) {
        Long latest = loginTimeoutStartedAt.get(uuid);
        return latest != null && latest == startedAt;
    }

    // 暴力破解防护：检查是否处于踢出期
    public boolean isKicked(Player player) {
        return isKicked(player.getUniqueId());
    }

    public boolean isKicked(UUID uuid) {
        if (!configManager.failProtectionEnabled()) return false;
        Long until = kickUntil.get(uuid);
        if (until == null) return false;
        if (until <= System.currentTimeMillis()) {
            // 懒清理已过期的踢出记录（踢出记录不再随 clearSession 清理，需在此避免无界累积）
            kickUntil.remove(uuid);
            return false;
        }
        return true;
    }

    // 获取剩余踢出时间（秒）
    public long getKickRemaining(Player player) {
        return getKickRemaining(player.getUniqueId());
    }

    public long getKickRemaining(UUID uuid) {
        Long until = kickUntil.get(uuid);
        if (until == null) return 0;
        long remaining = until - System.currentTimeMillis();
        return remaining > 0 ? remaining / 1000 : 0;
    }

    /**
     * 清理已过期的踢出记录、失败计数、2FA/登录会话与注销拒绝重连记录（30 秒周期任务 + reload/unregister 时调用）。
     * 登录会话只在读取时判定过期（玩家不再上线则条目无读取机会），须依赖周期清理防止常驻内存
     */
    public void cleanupExpiredStates() {
        long now = System.currentTimeMillis();
        kickUntil.values().removeIf(until -> until <= now);
        evictStaleFailures(now);
        twoFaSessions.entrySet().removeIf(e -> now > e.getValue().expiresAt());
        // 清理已过期的登录会话（固定窗口，命中不续期）
        long sessionMs = TimeUnit.MINUTES.toMillis(configManager.sessionExpireMinutes());
        loginSessions.entrySet().removeIf(e -> now - e.getValue().establishedAt() >= sessionMs);
        // 清理已过期的注销拒绝重连记录
        recentUnregister.entrySet().removeIf(entry -> now - entry.getValue() >= UNREGISTER_RECONNECT_DELAY);
    }

    /** 清理可安全移除的失败计数：超过过期时长未再失败（玩家可能已离线/已放弃尝试）。
     *  不能按"未达阈值"清理——达阈值的条目在 handleLoginFailure 中已被 remove，Map 中不存在 ≥max 的条目，
     *  按阈值清理恒真等于全清，攻击者可用大量假名洪水抹掉自己针对目标账号的累计进度 */
    private void evictStaleFailures(long now) {
        long resetMs = configManager.failProtectionResetSeconds() * 1000L;
        if (resetMs <= 0) return;
        failedAttempts.entrySet().removeIf(entry -> now - entry.getValue()[1] >= resetMs);
    }

    // ===== 坐标保护相关 =====

    /**
     * 保存玩家当前退出位置和游戏模式。
     * 退出流程仅对本次连接已认证的玩家调用：未认证玩家的位置是登录前的保护/出生点，写入会覆盖真实退出位置
     */
    public void saveLogoutLocation(Player player) {
        PlayerData data = dataManager.getPlayer(player.getUniqueId());
        if (data == null) return;
        captureLogoutLocation(data, player);
        dataManager.save(player.getUniqueId());
    }

    /**
     * 仅更新内存缓存中的退出位置和游戏模式，不落库。
     * 用于 onDisable：插件禁用后无法注册异步任务，改为更新缓存后由 saveSync 统一落库。
     */
    public void updateLogoutLocationCache(Player player) {
        PlayerData data = dataManager.getPlayer(player.getUniqueId());
        if (data != null) {
            captureLogoutLocation(data, player);
        }
    }

    /** 把当前位置与游戏模式写入内存数据（不落库），供退出保存与关服兜底共用 */
    private static void captureLogoutLocation(PlayerData data, Player player) {
        data.logoutLocation(PlayerDataManager.serializeLocation(player.getLocation()));
        data.gameMode(player.getGameMode().name());
    }

    /**
     * 登录/注册成功后，传送回上次退出位置。
     * 如果没有保存的位置（新玩家），不传送（留在世界出生点）。
     * 使用 teleportAsync 以兼容 Folia（Folia 禁止同步 teleport）。
     * 调用时机：玩家已在世界中（密码登录/注册/forcelogin），非 PlayerJoinEvent 期间。
     */
    public void returnToLogoutLocation(Player player) {
        Location loc = getLogoutLocation(player);
        if (loc == null) {
            // 新玩家没有保存的位置，留在世界出生点
            return;
        }
        // 标记传送过渡期，保持无敌
        invulnerablePending.add(player.getUniqueId());
        // 直接异步传送，传送完成后移除无敌状态
        player.teleportAsync(loc).thenAccept(success ->
                invulnerablePending.remove(player.getUniqueId()));
    }

    /** 玩家是否处于传送过渡期（已登录但还在传送，应保持无敌） */
    public boolean isInvulnerablePending(Player player) {
        return invulnerablePending.contains(player.getUniqueId());
    }

    /** 是否为正版账号（premium=1），用于免密登录判断 */
    public boolean isPremium(Player player) {
        return isPremium(player.getUniqueId());
    }

    public boolean isPremium(UUID uuid) {
        return dataManager.isPremium(uuid);
    }

    // ===== 离线账号升级为正版 =====

    /**
     * 切换升级标记：无标记则打上（返回 true），已有标记则取消（返回 false）。
     * 重复执行 /upgrade 即取消已提交的升级请求
     */
    public boolean toggleUpgrade(UUID offlineUuid) {
        if (!pendingUpgrade.add(offlineUuid)) {
            pendingUpgrade.remove(offlineUuid);
            return false;
        }
        return true;
    }

    /** 检查离线账号是否有升级标记 */
    public boolean hasPendingUpgrade(UUID offlineUuid) {
        return pendingUpgrade.contains(offlineUuid);
    }

    /** 清除升级标记（验证成功或失败回退时调用） */
    public void clearUpgradePending(UUID offlineUuid) {
        pendingUpgrade.remove(offlineUuid);
    }

    // ===== 正版账号降级为离线 =====

    /**
     * 切换降级标记：无标记则打上（返回 true），已有标记则取消（返回 false）。
     * 重复执行 /downgrade 即取消已提交的降级请求
     */
    public boolean toggleDowngrade(UUID premiumUuid) {
        if (!pendingDowngrade.add(premiumUuid)) {
            pendingDowngrade.remove(premiumUuid);
            return false;
        }
        return true;
    }

    /** 检查正版账号是否有降级标记 */
    public boolean hasPendingDowngrade(UUID premiumUuid) {
        return pendingDowngrade.contains(premiumUuid);
    }

    /**
     * 执行降级迁移（正版 UUID → 离线 UUID）：账号数据与原版玩家数据一并迁移，
     * 此后以密码或 2FA 登录。正版记录不存在时跳过（注销竞态，标记已由注销清理）
     */
    public void executeDowngrade(UUID premiumUuid, UUID offlineUuid, String name) {
        if (!dataManager.migrateToOffline(premiumUuid, offlineUuid)) return;
        migratePlayerDataAsync(premiumUuid, offlineUuid);
        pendingDowngrade.remove(premiumUuid);
        premiumFallback.remove(premiumUuid);
        plugin.getLogger().info(I18n.get("log.downgrade_migrated", name));
    }

    /** 标记正版玩家本次为正版验证失败回退进入（需密码登录） */
    public void markPremiumFallback(UUID premiumUuid) {
        premiumFallback.put(premiumUuid, System.currentTimeMillis());
    }

    /** 清除正版回退标记（密码登录成功或下次正版验证成功时调用） */
    public void clearPremiumFallback(UUID premiumUuid) {
        premiumFallback.remove(premiumUuid);
    }

    /** 正版玩家是否为验证失败回退进入（本次需密码登录）。
     *  超过回退确认窗口的残留标记视为过期（回退后未进世界即断开的连接无退出事件清理），按正常正版流程处理 */
    public boolean isPremiumFallback(UUID premiumUuid) {
        Long markedAt = premiumFallback.get(premiumUuid);
        if (markedAt == null) return false;
        long ttl = configManager.premiumFallbackCacheSeconds() * 1000L;
        if (System.currentTimeMillis() - markedAt >= ttl) {
            premiumFallback.remove(premiumUuid);
            return false;
        }
        return true;
    }

    /**
     * 在主世界出生点周围寻找能立足的随机位置（老玩家专用）。
     * 安全标准放宽：只需"下方固体方块"（能站立）。
     * 因为未登录期间 onDamage 取消伤害，玩家不会因悬空/水中/岩浆受伤；
     * 登录后立即传送到上次退出位置，离开临时位置。
     * 默认尝试 10 次，全部失败则回退到世界出生点（玩家无敌，出生点不安全也不会死）。
     * 此方法会阻塞等待区块加载，应在异步线程中调用。
     * 若配置为固定坐标模式，直接返回配置的固定位置。
     */
    public Location findSafeAuthSpawn(World world) {
        // 固定坐标模式：直接使用配置的坐标
        if ("fixed".equals(configManager.protectionPosMode())) {
            return new Location(world,
                    configManager.protectionPosFixedX(),
                    configManager.protectionPosFixedY(),
                    configManager.protectionPosFixedZ(),
                    configManager.protectionPosFixedYaw(),
                    configManager.protectionPosFixedPitch());
        }

        Location spawn = world.getSpawnLocation();
        int radius = configManager.protectionPosSpawnRadius();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // 多次重试，模仿原版 MC 寻找安全出生点的机制
        for (int attempt = 0; attempt < 10; attempt++) {
            int x = (int) (spawn.getX() + (random.nextDouble() * 2 - 1) * radius);
            int z = (int) (spawn.getZ() + (random.nextDouble() * 2 - 1) * radius);
            // 阻塞等待区块加载（调用方应在异步线程）
            org.bukkit.Chunk chunk = world.getChunkAtAsyncUrgently(x >> 4, z >> 4).join();
            int y = findSafeSpawnY(chunk.getChunkSnapshot(), x & 15, z & 15, world);
            if (y != Integer.MIN_VALUE) {
                return new Location(world, x + 0.5, y, z + 0.5);
            }
        }
        // 全部失败：回退到世界出生点（玩家无敌期间不会受伤）
        return spawn;
    }

    /**
     * 从最高方块上方开始向下找能立足的 y 坐标。
     * 安全标准：下方是固体方块（能站立）。
     * 找不到时返回 Integer.MIN_VALUE，由调用方重试或回退。
     */
    private static int findSafeSpawnY(org.bukkit.ChunkSnapshot snapshot, int x, int z, World world) {
        int highestY = snapshot.getHighestBlockYAt(x, z);
        // 最高方块上方即视为可立足（下方=最高方块，只需检查它是否固体）
        if (highestY > world.getMinHeight()) {
            BlockData below = snapshot.getBlockData(x, highestY, z);
            if (below.getMaterial().isSolid()) {
                return highestY + 1;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** 获取玩家上次退出位置（无保存位置返回 null） */
    public Location getLogoutLocation(Player player) {
        return getLogoutLocation(player.getUniqueId());
    }

    /** 获取玩家上次退出位置（无保存位置返回 null） */
    public Location getLogoutLocation(UUID uuid) {
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) return null;
        return PlayerDataManager.deserializeLocation(data.logoutLocation());
    }

    /**
     * 未登录期间切换为旁观模式。
     * 标记玩家为 spectatorPending，onLoginSuccess 时据此恢复游戏模式。
     * <p>
     * 当 gamemode.enabled=false 时，若坐标保护未开启且退出位置悬空，仍强制切换为旁观模式：
     * 退出位置悬空时玩家会在该处坠落暴露位置。
     * 悬空检查通过 ChunkSnapshot 读取（快照线程安全），任意线程可安全访问，
     * 避免 Folia 下在非所属区域线程读取退出位置所在世界（可能为其它世界或其它区域）的方块。
     * 最终 setGameMode 使用玩家调度器执行，保证 Folia 下在玩家区域线程调用（非线程安全）。
     */
    public void setSpectator(Player player) {
        if (!configManager.protectionGamemodeEnabled()) {
            // 旁观模式未开启时，仅在坐标保护未开启且退出位置悬空时仍切换为旁观
            if (configManager.protectionPosEnabled()) return;
            Location logoutLoc = getLogoutLocation(player);
            if (logoutLoc == null || logoutLoc.getWorld() == null) return;
            if (isBlockSolidBelow(logoutLoc)) return;
        }
        spectatorPending.add(player.getUniqueId());
        player.getScheduler().run(plugin, task -> player.setGameMode(org.bukkit.GameMode.SPECTATOR), null);
    }

    /** 判断退出位置正下方方块是否固体（用于悬空检查）。快照读取线程安全，可在任意线程调用 */
    private static boolean isBlockSolidBelow(Location loc) {
        World world = loc.getWorld();
        int y = loc.getBlockY() - 1;
        if (y <= world.getMinHeight() || y >= world.getMaxHeight()) return false;
        org.bukkit.ChunkSnapshot snap = world.getChunkAtAsyncUrgently(
                loc.getBlockX() >> 4, loc.getBlockZ() >> 4).join().getChunkSnapshot();
        return snap.getBlockData(loc.getBlockX() & 15, y, loc.getBlockZ() & 15).getMaterial().isSolid();
    }

    /**
     * 登录/注册成功后的状态恢复：
     * 1. 恢复游戏模式：仅对被设为旁观的玩家恢复，有保存的游戏模式则恢复，否则使用服务器默认游戏模式（新玩家）
     * 2. 物品状态恢复：未登录期间数据包监听器清空了该玩家的背包和装备（仅本人视角，他人不受影响），
     *    登录后调用 updateInventory 让服务器重发真实背包内容（含装备槽）。
     * <p>
     * 使用玩家调度器执行，保证 Folia 下在玩家区域线程调用（非线程安全操作）。
     */
    public void onLoginSuccess(Player player) {
        player.getScheduler().run(plugin, task -> {
            // 立即隐藏提醒 BossBar（不等下一个提醒周期；非 bossbar 方式时为空操作）
            plugin.getPlayerListener().hideReminderBar(player);
            // 返还退出时保管的飞行末影珍珠（无记录时为空操作）
            plugin.getPendingPearlManager().returnPearls(player);
            // 仅对被设为旁观的玩家恢复游戏模式
            if (spectatorPending.remove(player.getUniqueId())) {
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
