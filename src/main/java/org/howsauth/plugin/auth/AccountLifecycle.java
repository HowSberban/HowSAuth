package org.howsauth.plugin.auth;

import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.data.PlayerDataManager;
import org.howsauth.plugin.data.PlayerDataManager.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 账号生命周期：注册（含 IP 名额与并发原子性）、注销、原版玩家数据删除与迁移、
 * 正版升级/降级标记与迁移，以及账号属性查询（是否注册/正版/无密码/无可用登录方式）。
 * <p>
 * <b>跨服务协作</b>：注销需要清除由各服务持有的残留状态（会话、失败计数、2FA），
 * 故本类依赖 {@link SessionStore}/{@link FailProtection}/{@link TwoFactorAuth}；
 * 批量注销后的全局过期状态清理由组装根以 {@code expiredStateCleanup} 回调提供，
 * 避免本类反向依赖 {@link AuthManager}。
 * <p>
 * <b>线程契约</b>：bcrypt 校验与文件 IO 一律在异步线程执行，结果回调回到玩家区域线程；
 * 标记集合为并发 Set/Map。
 */
public final class AccountLifecycle {

    private final HowSAuth plugin;
    private final PlayerDataManager dataManager;
    private final ConfigManager configManager;
    private final AuthEvents events;
    private final SessionStore sessions;
    private final FailProtection failProtection;
    private final TwoFactorAuth twoFactor;
    // 组装根提供的"清理全部服务过期状态"回调（批量注销时顺手清理，防止累积）
    private final Runnable expiredStateCleanup;
    // 缓存世界结构类型：26.1+ 采用新结构（players/data + dimensions/minecraft/overworld）
    private final boolean newWorldStructure;

    // 待升级离线账号（离线 UUID）：玩家执行升级指令后标记，下次登录时尝试正版验证
    private final Set<UUID> pendingUpgrade = ConcurrentHashMap.newKeySet();
    // 正版账号降级标记（内存，不持久化）：下次进入时迁移数据到离线 UUID
    private final Set<UUID> pendingDowngrade = ConcurrentHashMap.newKeySet();
    // 正版验证失败回退进入的正版玩家（正版 UUID → 标记时间戳）：本次需密码登录，不自动免密。
    // 标记仅代表当前连接会话，正常路径由退出清理；未进世界即断开的连接无退出事件，靠 TTL 过期兜底，
    // TTL 复用回退确认窗口（premium.fallback.cache-seconds）
    private final Map<UUID, Long> premiumFallback = new ConcurrentHashMap<>();
    private volatile Consumer<UUID> unregisterConfirmInvalidator;

    AccountLifecycle(HowSAuth plugin, PlayerDataManager dataManager, ConfigManager configManager,
                     AuthEvents events, SessionStore sessions, FailProtection failProtection,
                     TwoFactorAuth twoFactor, Runnable expiredStateCleanup) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.configManager = configManager;
        this.events = events;
        this.sessions = sessions;
        this.failProtection = failProtection;
        this.twoFactor = twoFactor;
        this.expiredStateCleanup = expiredStateCleanup;
        this.newWorldStructure = detectNewWorldStructure();
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

    // ===== 账号属性查询 =====

    public boolean hasAccount(Player player) {
        return hasAccount(player.getUniqueId());
    }

    public boolean hasAccount(UUID uuid) {
        return dataManager.hasAccount(uuid);
    }

    /** 是否为正版账号（premium=1），用于免密登录判断 */
    public boolean isPremium(Player player) {
        return isPremium(player.getUniqueId());
    }

    public boolean isPremium(UUID uuid) {
        return dataManager.isPremium(uuid);
    }

    /** 是否为无密码账户（密码哈希为空，登录依赖验证码或正版验证） */
    public boolean isPasswordless(UUID uuid) {
        PlayerData data = dataManager.getPlayer(uuid);
        return data != null && (data.passwordHash() == null || data.passwordHash().isEmpty());
    }

    /** 是否无可用登录方式：无密码、未绑验证器且非正版（正版可免密），任何认证路径都不可行 */
    public boolean hasNoUsableLoginMethod(UUID uuid) {
        return isPasswordless(uuid) && !twoFactor.hasTotpSecret(uuid) && !isPremium(uuid);
    }

    // ===== 注册 =====

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
     * 强制注册：管理员绕过 IP 限制强制为玩家创建账号。
     * 同名账号已存在时返回 false。玩家在线时记录其当前 IP，离线时记为 "unknown"（下次登录时更新）。
     * 不会自动登录，玩家需自行 /login。
     */
    public boolean forceRegister(UUID uuid, String name, String password) {
        Player online = Bukkit.getPlayer(uuid);
        String ip = online != null ? SessionStore.clientIp(online) : null;
        if (!createAccount(uuid, name, password, ip)) return false;
        events.register(uuid, online);
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

    // ===== 注销 =====

    public void setUnregisterConfirmInvalidator(Consumer<UUID> invalidator) {
        this.unregisterConfirmInvalidator = invalidator;
    }

    /** 使注销确认失效（退出清理与凭据变更时调用） */
    void invalidateUnregisterConfirm(UUID uuid) {
        Consumer<UUID> invalidator = unregisterConfirmInvalidator;
        if (invalidator != null) invalidator.accept(uuid);
    }

    /** 诊断：当前处于正版回退标记状态的玩家数 */
    int premiumFallbackCount() {
        return premiumFallback.size();
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

    public boolean unregister(UUID uuid) {
        if (!dataManager.hasAccount(uuid)) {
            if (Debug.on()) {
                Debug.log("auth", "unregister %s: failed (no account)", Debug.shortId(uuid));
            }
            return false;
        }
        dataManager.removePlayer(uuid);
        sessions.invalidateLoggedIn(uuid);
        twoFactor.clearPending(uuid);
        twoFactor.clearPendingSecret(uuid);
        // 防重放计数一并清理：残留计数会误拒重绑定新密钥后的正确验证码（counter 单调消费）
        twoFactor.clearCounter(uuid);
        failProtection.clear(uuid);
        sessions.removeInvulnerablePending(uuid);
        sessions.consumeSpectatorPending(uuid);
        pendingUpgrade.remove(uuid);
        pendingDowngrade.remove(uuid);
        premiumFallback.remove(uuid);
        sessions.clearLoginSession(uuid);
        twoFactor.clearSession(uuid);
        // 根据配置决定是否删除 Minecraft 原版玩家数据（player.dat）
        if (configManager.realUnreg()) {
            // 记录注销时间，5 秒内拒绝重连，确保 .dat 删除完成
            sessions.markRecentUnregister(uuid);
            if (Bukkit.getPlayer(uuid) != null) {
                // 玩家在线：标记后由 PlayerQuitEvent 删除（避免文件锁冲突）
                if (Debug.on()) {
                    Debug.log("auth", "unregister %s: account removed, vanilla data delete deferred to quit",
                            Debug.shortId(uuid));
                }
                sessions.addPendingDatDelete(uuid);
            } else {
                // 玩家离线：无文件锁，直接删除
                if (Debug.on()) {
                    Debug.log("auth", "unregister %s: account removed, vanilla data deleted immediately",
                            Debug.shortId(uuid));
                }
                deletePlayerDataWithRetry(uuid);
            }
            // 顺手清理已过期的踢出记录、失败计数和注销拒绝重连记录，防止批量注销时累积
            expiredStateCleanup.run();
        } else {
            if (Debug.on()) {
                Debug.log("auth", "unregister %s: account removed, vanilla data kept (real-unreg off)",
                        Debug.shortId(uuid));
            }
        }
        events.unregister(uuid);
        return true;
    }

    // ===== 原版玩家数据删除与迁移 =====

    /**
     * 在 PlayerQuitEvent 中调用：异步重试删除玩家 .dat 文件。
     * 玩家被踢出后服务器仍会将其数据保存到 .dat，早于保存完成的删除会被覆盖回写。
     * 采用重试机制：首次延迟 1000ms（等保存完成）后尝试，文件仍存在则每 300ms 重试，
     * 5 秒内持续尝试，确保服务器完成保存后能可靠删除。
     */
    public void tryDeletePlayerDataOnQuit(UUID uuid) {
        if (!sessions.consumePendingDatDelete(uuid)) return;
        if (Debug.on()) {
            Debug.log("db", "delete vanilla data on quit: %s queued", Debug.shortId(uuid));
        }
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
                if (Debug.on()) {
                    Debug.log("db", "delete vanilla data for %s: failed after retries", Debug.shortId(uuid));
                }
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
}
