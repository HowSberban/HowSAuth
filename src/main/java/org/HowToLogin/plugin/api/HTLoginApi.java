package org.howtologin.plugin.api;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.auth.AuthManager;
import org.howtologin.plugin.auth.PasswordHash;
import org.howtologin.plugin.data.PlayerDataManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HTLogin 插件 API 入口。
 * <p>
 * 使用 {@link #getInstance()} 获取单例，插件未加载时返回 null。
 * <p>
 * 示例：
 * <pre>{@code
 * HTLoginApi api = HTLoginApi.getInstance();
 * if (api != null && api.isAuthenticated(player)) {
 *     // 玩家已登录
 * }
 * }</pre>
 */
@SuppressWarnings("unused")
public final class HTLoginApi {

    private static volatile HTLoginApi instance;

    private final HTLogin plugin;
    private final AuthManager authManager;
    private final PlayerDataManager dataManager;

    private HTLoginApi(HTLogin plugin, AuthManager authManager, PlayerDataManager dataManager) {
        this.plugin = plugin;
        this.authManager = authManager;
        this.dataManager = dataManager;
    }

    /** 插件启用时注册 API 单例（内部调用，不要直接使用） */
    public static void initialize(HTLogin plugin, AuthManager authManager, PlayerDataManager dataManager) {
        instance = new HTLoginApi(plugin, authManager, dataManager);
    }

    /** 插件禁用时清理 API 单例（内部调用，不要直接使用） */
    public static void shutdown() {
        instance = null;
        RATE_LIMITER.clear();
    }

    // 变更操作限流表：UUID → [窗口起始毫秒, 窗口内计数]，对槽 synchronized 保证原子性
    private static final Map<UUID, long[]> RATE_LIMITER = new ConcurrentHashMap<>();
    // 每个 UUID 每秒最多 10 次变更操作（bcrypt/DB 均为较重操作，防外部插件循环调用）
    private static final int RATE_LIMIT_PER_SECOND = 10;
    // 触发清理的容量阈值：正常使用条目数=在线玩家数，远低于此；仅异常膨胀（随机 UUID 洪水）时清扫
    private static final int RATE_LIMITER_EVICT_THRESHOLD = 4096;
    // 限流槽空闲超时（毫秒）：超过即视为废弃，清理时移除
    private static final long RATE_LIMITER_IDLE_MS = 2 * 60 * 1000L;
    // 清理进行中标记：防止并发触发重复清扫
    private static final AtomicBoolean RATE_LIMITER_EVICTING = new AtomicBoolean();

    /**
     * 变更操作限流：每 UUID 每秒最多 {@value RATE_LIMIT_PER_SECOND} 次，超限拒绝并返回 false。
     * 覆盖改密码/改登录态/删号等重操作，防止外部插件 bug 循环调用拖垮数据库或阻塞线程。
     */
    // 调用方均以 ! 守卫子句形式使用（if (!tryAcquire(...)) return false;），IDE 误报"始终反转"，
    // 但反转语义改名（如 isRateLimited）会与"获取成功"的方法名直觉相反
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean tryAcquire(UUID uuid) {
        long now = System.currentTimeMillis();
        // 懒触发清理：仅当表膨胀到阈值才清扫空闲槽，常态下零额外开销（无周期任务空转）
        if (RATE_LIMITER.size() >= RATE_LIMITER_EVICT_THRESHOLD
                && RATE_LIMITER_EVICTING.compareAndSet(false, true)) {
            try {
                sweepRateLimiter();
            } finally {
                RATE_LIMITER_EVICTING.set(false);
            }
        }
        long[] slot = RATE_LIMITER.computeIfAbsent(uuid, k -> new long[2]);
        synchronized (slot) {
            if (now - slot[0] >= 1000L) {
                slot[0] = now;
                slot[1] = 1;
                return true;
            }
            return ++slot[1] <= RATE_LIMIT_PER_SECOND;
        }
    }

    /**
     * 清理限流槽：移除空闲超过 2 分钟的条目，防止外部插件用随机 UUID 洪水导致表无界增长。
     * 仅在表膨胀到阈值时触发（懒清理，不做周期任务）；被移除的 UUID 再次调用时按新窗口重建槽，
     * 配额语义与全新调用者一致，限流目的不受影响（活跃的循环调用者从未空闲，不会被移除）
     */
    private static void sweepRateLimiter() {
        long now = System.currentTimeMillis();
        RATE_LIMITER.entrySet().removeIf(e -> {
            long[] slot = e.getValue();
            synchronized (slot) {
                return now - slot[0] >= RATE_LIMITER_IDLE_MS;
            }
        });
    }

    /**
     * 获取 API 实例。
     * @return API 实例，插件未加载时返回 null
     */
    public static HTLoginApi getInstance() {
        return instance;
    }

    // ===== 插件信息 =====

    /** 获取插件版本号 */
    @NotNull
    public String getPluginVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    // ===== 状态查询 =====

    /** 玩家是否已登录 */
    public boolean isAuthenticated(@NotNull Player player) {
        return authManager.isLoggedIn(player);
    }

    /** 玩家是否已登录（按 UUID 查询，玩家离线时返回 false） */
    public boolean isAuthenticated(@NotNull UUID uuid) {
        return authManager.isLoggedIn(uuid);
    }

    /** 玩家是否已注册 */
    public boolean isRegistered(@NotNull UUID uuid) {
        return dataManager.hasAccount(uuid);
    }

    /** 玩家是否已注册（便捷重载） */
    public boolean isRegistered(@NotNull Player player) {
        return isRegistered(player.getUniqueId());
    }

    /** 玩家是否为正版账号 */
    public boolean isPremium(@NotNull UUID uuid) {
        return dataManager.isPremium(uuid);
    }

    // ===== 强制操作 =====

    /**
     * 强制登录在线玩家（无需密码）。
     * @return 玩家不在线或已登录时返回 false
     */
    public boolean forceLogin(@NotNull Player player) {
        if (!player.isOnline()) return false;
        if (!tryAcquire(player.getUniqueId())) return false;
        if (!player.isOnline() || authManager.isLoggedIn(player)) return false;
        authManager.forceLogin(player);
        return true;
    }

    /**
     * 强制登出玩家。
     * 玩家在线时会被标记为待登录状态，登录会话失效。
     * @return 玩家未登录时返回 false
     */
    public boolean forceLogout(@NotNull UUID uuid) {
        if (!tryAcquire(uuid)) return false;
        return authManager.forceLogout(uuid);
    }

    /**
     * 强制注册在线玩家并自动登录。
     * 含 bcrypt 哈希（同步阻塞，约数百毫秒），请在异步线程调用，勿在主线程/区域线程调用
     * @return 玩家已有账号或同名账号（含正版）已存在时返回 false
     */
    public boolean forceRegister(@NotNull Player player, @NotNull String password) {
        if (!player.isOnline()) return false;
        if (!tryAcquire(player.getUniqueId())) return false;
        if (!authManager.forceRegister(player.getUniqueId(), player.getName(), password)) return false;
        authManager.forceLogin(player);
        return true;
    }

    /**
     * 强制注册玩家（不自动登录，适用于离线玩家）。
     * 含 bcrypt 哈希（同步阻塞，约数百毫秒），请在异步线程调用，勿在主线程/区域线程调用
     * @return 玩家已有账号或同名账号（含正版）已存在时返回 false
     */
    public boolean forceRegister(@NotNull UUID uuid, @NotNull String password) {
        if (!tryAcquire(uuid)) return false;
        // UUID 反推名字：上过服务器的离线玩家有名字记录，从未上过则返回 null（跳过同名检查）
        return authManager.forceRegister(uuid, Bukkit.getOfflinePlayer(uuid).getName(), password);
    }

    /**
     * 注销玩家账号（管理员操作，无需密码）。
     * 会删除数据库记录，根据配置可能同时删除原版玩家数据（player.dat）。
     * @return 玩家无账号时返回 false
     */
    public boolean unregister(@NotNull UUID uuid) {
        if (!tryAcquire(uuid)) return false;
        return authManager.unregister(uuid);
    }

    // ===== 玩家信息 =====

    /** 获取玩家上次退出位置，无记录时返回 null */
    @Nullable
    public Location getLastLocation(@NotNull UUID uuid) {
        return authManager.getLogoutLocation(uuid);
    }

    /** 获取玩家上次登录 IP，无记录时返回 null */
    @Nullable
    public String getLastIp(@NotNull UUID uuid) {
        PlayerDataManager.PlayerData data = dataManager.getPlayer(uuid);
        return data != null ? data.ip() : null;
    }

    /**
     * 获取玩家上次登录时间（epoch 秒）。
     * @return 时间戳，0 表示从未登录或登录已失效
     */
    public long getLastLoginTime(@NotNull UUID uuid) {
        PlayerDataManager.PlayerData data = dataManager.getPlayer(uuid);
        return data != null ? data.lastLogin() : 0;
    }

    /** 获取所有已注册玩家的 UUID 集合（不可变副本） */
    @NotNull
    public Set<UUID> getRegisteredUuids() {
        return new HashSet<>(dataManager.getAllUuids());
    }

    // ===== 密码操作 =====

    /**
     * 校验密码是否正确。
     * bcrypt 校验同步阻塞（约数百毫秒），请在异步线程调用，勿在主线程/区域线程调用
     * @return 密码正确返回 true，玩家无账号或密码错误返回 false
     */
    public boolean checkPassword(@NotNull UUID uuid, @NotNull String password) {
        if (!tryAcquire(uuid)) return false;
        PlayerDataManager.PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) return false;
        return PasswordHash.checkPassword(password, data.passwordHash());
    }

    /**
     * 修改玩家密码（无需旧密码，管理员操作）。
     * 修改后登录会话失效，玩家下次需用新密码登录。
     * 含 bcrypt 哈希（同步阻塞，约数百毫秒），请在异步线程调用，勿在主线程/区域线程调用
     * @return 玩家无账号时返回 false
     */
    public boolean changePassword(@NotNull UUID uuid, @NotNull String newPassword) {
        if (!tryAcquire(uuid)) return false;
        return authManager.forceChangePassword(uuid, newPassword);
    }
}
