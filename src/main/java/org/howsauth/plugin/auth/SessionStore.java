package org.howsauth.plugin.auth;

import org.howsauth.plugin.Debug;
import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.data.PlayerDataManager;
import org.howsauth.plugin.data.PlayerDataManager.PlayerData;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 会话状态中心：实时登录态、本次连接认证标记、登录会话保持与各类会话级待处理标记。
 * <p>
 * 本类只持有"随连接生命周期失效"的状态，不含任何业务编排：失败保护计数、2FA 临时密钥、
 * 正版回退标记等有各自归属的状态仍在各自服务。
 * <p>
 * <b>线程契约</b>：全部集合为并发集合（Folia 多线程区域化调度下跨线程读写）。
 * {@link #clear(UUID)} 的调用点固定在退出流程的最后阶段（PlayerListener#onQuitCleanup，MONITOR），
 * 实时登录态与本次连接认证标记的失效须晚于依赖它们的退出处理（如退出消息决策），不可提前。
 */
public final class SessionStore {

    // 线程安全集合，用于 Folia 多线程区域化调度
    private final Set<UUID> loggedIn = ConcurrentHashMap.newKeySet();
    // 本次连接是否完成过认证（登录/注册/免密）：供加入/退出消息、退出位置保存等会话级判定使用。
    // 与 loggedIn 的职责划分：loggedIn 表示实时登录态（登录前防护、命令权限、免密判定），
    // 强制登出/注销会立即失效它；本标记只记录"本次连接是否认证过"，不随提前失效而清除，
    // 仅在退出清理（clear）时移除
    private final Set<UUID> authenticatedThisConnection = ConcurrentHashMap.newKeySet();
    // 标记密码异步校验进行中的玩家：防止快速重复提交 /login 触发重复校验、重复登录事件与消息
    private final Set<UUID> verifying = ConcurrentHashMap.newKeySet();
    // 登录会话保持：密码/2FA 验证通过后记录 (ip, 建立时间戳)，同 IP 且未过期免输密码
    // 固定窗口不滑动（命中登录不刷新建立时间），避免活跃账号会话永不过期
    private final Map<UUID, LoginSession> loginSessions = new ConcurrentHashMap<>();
    // 登录后传送过渡期：玩家已登录但还在传送到退出位置，期间保持无敌
    private final Set<UUID> invulnerablePending = ConcurrentHashMap.newKeySet();
    // 标记当前会话被设为旁观的玩家：onLoginSuccess 仅对这些玩家恢复游戏模式，
    // 避免对免密登录（IP/正版）的玩家做不必要的 setGameMode
    private final Set<UUID> spectatorPending = ConcurrentHashMap.newKeySet();
    // 注销后等待 PlayerQuitEvent 触发时删除 .dat 的玩家
    private final Set<UUID> pendingDatDelete = ConcurrentHashMap.newKeySet();
    // 最近注销的玩家时间戳：5 秒内拒绝重连，确保 .dat 删除完成
    private final Map<UUID, Long> recentUnregister = new ConcurrentHashMap<>();
    // 登录超时任务启动时间戳：用于判断超时任务是否为最新（重启时旧任务自动失效）
    private final Map<UUID, Long> loginTimeoutStartedAt = new ConcurrentHashMap<>();

    private final ConfigManager configManager;
    private final PlayerDataManager dataManager;

    SessionStore(ConfigManager configManager, PlayerDataManager dataManager) {
        this.configManager = configManager;
        this.dataManager = dataManager;
    }

    /** 登录会话：验证通过时的来源 IP 与建立时间戳（固定窗口不滑动） */
    private record LoginSession(String ip, long establishedAt) {}

    /** 提取玩家客户端 IP（getAddress 可能为 null，如代理协议未解析完成时） */
    public static String clientIp(Player player) {
        if (player == null) return null;
        var socketAddress = player.getAddress();
        if (socketAddress == null) return null;
        var address = socketAddress.getAddress();
        return address != null ? address.getHostAddress() : null;
    }

    // ===== 实时登录态 =====

    /** 标记玩家为已登录：置位实时登录态与本次连接认证标记 */
    void markLoggedIn(UUID uuid) {
        loggedIn.add(uuid);
        authenticatedThisConnection.add(uuid);
        if (Debug.on()) {
            Debug.log("auth", "authenticated: %s (live login state + connection flag set)", uuid);
        }
    }

    public boolean isLoggedIn(UUID uuid) {
        return loggedIn.contains(uuid);
    }

    public boolean isLoggedIn(Player player) {
        return loggedIn.contains(player.getUniqueId());
    }

    /** 使实时登录态立即失效（强制登出/注销）：本次连接认证标记保留，由 {@link #clear} 移除 */
    boolean invalidateLoggedIn(UUID uuid) {
        return loggedIn.remove(uuid);
    }

    /**
     * 本次连接是否完成过认证（登录/注册/免密）
     * 与 isLoggedIn 的区别：后者是实时登录态，强制登出/注销会提前失效；
     * 本方法描述"本次连接认证过"这一历史事实，供加入/退出消息、退出位置保存等会话级判定使用
     */
    public boolean hasAuthenticatedThisConnection(UUID uuid) {
        return authenticatedThisConnection.contains(uuid);
    }

    // ===== 登录会话保持 =====

    /** 记录登录会话：登录/注册成功后同 IP 且未过期免输密码（固定窗口，命中不刷新） */
    void markLoginSession(UUID uuid, String ip) {
        if (!configManager.sessionEnabled() || ip == null) return;
        loginSessions.put(uuid, new LoginSession(ip, System.currentTimeMillis()));
        if (Debug.on()) {
            Debug.log("session", "login session established for %s (ttl %s min)",
                    uuid.toString().substring(0, 8), configManager.sessionExpireMinutes());
        }
    }

    /** 清除登录会话（登出/强制操作/注销时调用：安全事件后不保留免密码信任） */
    void clearLoginSession(UUID uuid) {
        loginSessions.remove(uuid);
    }

    /** 登录会话是否命中（免输密码）：上次验证 IP 与当前一致，且未超过失效时间 */
    public boolean hasSession(Player player) {
        if (player.getAddress() == null) return false;
        return hasSession(player.getUniqueId(), clientIp(player));
    }

    /** 登录会话是否命中（无需 Player 对象，用于 AsyncPlayerSpawnLocationEvent） */
    public boolean hasSession(UUID uuid, String ip) {
        if (!configManager.sessionEnabled()) {
            if (Debug.on()) {
                Debug.log("session", "login session miss for %s: session disabled", uuid.toString().substring(0, 8));
            }
            return false;
        }
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) return false;
        if (ip == null) return false;
        LoginSession s = loginSessions.get(uuid);
        if (s == null) return false;
        if (!s.ip().equals(ip)) {
            if (Debug.on()) {
                Debug.log("session", "login session miss for %s: ip mismatch", uuid.toString().substring(0, 8));
            }
            return false;
        }
        // 固定窗口不滑动：命中登录不刷新建立时间，到期后需重新验证
        long expireMillis = TimeUnit.MINUTES.toMillis(configManager.sessionExpireMinutes());
        if (Debug.on()) {
            Debug.log("session", "login session for %s: %s", uuid.toString().substring(0, 8),
                    System.currentTimeMillis() - s.establishedAt() < expireMillis ? "hit" : "expired");
        }
        return System.currentTimeMillis() - s.establishedAt() < expireMillis;
    }

    /** 周期清理已过期的登录会话（固定窗口，命中不续期），返回移除条数 */
    int cleanupExpiredSessions(long now) {
        long sessionMs = TimeUnit.MINUTES.toMillis(configManager.sessionExpireMinutes());
        int before = loginSessions.size();
        loginSessions.entrySet().removeIf(e -> now - e.getValue().establishedAt() >= sessionMs);
        return before - loginSessions.size();
    }

    // ===== 密码校验重入保护 =====

    /** 开始一次密码校验：已在校验中返回 false（调用方据此静默忽略本次提交） */
    boolean beginVerifying(UUID uuid) {
        return verifying.add(uuid);
    }

    void endVerifying(UUID uuid) {
        verifying.remove(uuid);
    }

    // ===== 传送过渡期无敌 =====

    void addInvulnerablePending(UUID uuid) {
        invulnerablePending.add(uuid);
    }

    void removeInvulnerablePending(UUID uuid) {
        invulnerablePending.remove(uuid);
    }

    public boolean isInvulnerablePending(UUID uuid) {
        return invulnerablePending.contains(uuid);
    }

    public boolean isInvulnerablePending(Player player) {
        return invulnerablePending.contains(player.getUniqueId());
    }

    // ===== 旁观过渡 =====

    void addSpectatorPending(UUID uuid) {
        spectatorPending.add(uuid);
    }

    boolean isSpectatorPending(UUID uuid) {
        return spectatorPending.contains(uuid);
    }

    /** 取出并消费旁观标记：仅命中时返回 true（onLoginSuccess 据此决定是否恢复游戏模式） */
    boolean consumeSpectatorPending(UUID uuid) {
        return spectatorPending.remove(uuid);
    }

    // ===== 注销后拒绝重连 =====

    void markRecentUnregister(UUID uuid) {
        recentUnregister.put(uuid, System.currentTimeMillis());
    }

    /** 是否在注销后的拒绝重连期内；过期条目顺带懒清理 */
    public boolean isRecentlyUnregistered(UUID uuid) {
        Long time = recentUnregister.get(uuid);
        if (time == null) return false;
        if (System.currentTimeMillis() - time >= UNREGISTER_RECONNECT_DELAY) {
            recentUnregister.remove(uuid);
            return false;
        }
        return true;
    }

    /** 拒绝重连剩余秒数（向上取整），无记录返回 0 */
    public long getRecentUnregisterRemaining(UUID uuid) {
        Long time = recentUnregister.get(uuid);
        if (time == null) return 0;
        long remaining = UNREGISTER_RECONNECT_DELAY - (System.currentTimeMillis() - time);
        return remaining > 0 ? (remaining + 999) / 1000 : 0;
    }

    // ===== 待删除原版数据 =====

    void addPendingDatDelete(UUID uuid) {
        pendingDatDelete.add(uuid);
    }

    /** 取出并消费待删除标记：仅命中时返回 true */
    boolean consumePendingDatDelete(UUID uuid) {
        return pendingDatDelete.remove(uuid);
    }

    // ===== 登录超时任务 =====

    /** 记录登录超时任务启动时间并返回（调用方据此判断超时任务是否为最新） */
    long markLoginTimeoutStart(UUID uuid) {
        long now = System.currentTimeMillis();
        loginTimeoutStartedAt.put(uuid, now);
        return now;
    }

    /** 判断指定时间戳是否为最新的超时任务启动时间（旧任务自动失效） */
    public boolean isLatestLoginTimeout(UUID uuid, long startedAt) {
        Long latest = loginTimeoutStartedAt.get(uuid);
        return latest != null && latest == startedAt;
    }

    // ===== 退出清理与周期清理 =====

    /**
     * 玩家退出时的会话状态清理。
     * 调用点固定在退出流程的最后阶段（MONITOR）：实时登录态与本次连接认证标记的失效
     * 须晚于依赖它们的退出处理（如退出消息决策），不可提前。
     * <p>
     * 不含失败计数与踢出记录：两者为跨连接的暴力破解防护，须保留至达到阈值/登录成功/到期。
     */
    void clear(UUID uuid) {
        if (Debug.on()) {
            Debug.log("auth", "clear session: %s (authenticatedThisConnection was %s)", uuid,
                    authenticatedThisConnection.contains(uuid));
        }
        loggedIn.remove(uuid);
        authenticatedThisConnection.remove(uuid);
        // 清除密码校验进行中标记（玩家在校验完成前退出时，异步回调的 player 调度不会执行，需在此兜底清理）
        verifying.remove(uuid);
        // 清除传送过渡期标记，防止下次登录时错误无敌
        invulnerablePending.remove(uuid);
        // 清除旁观标记（未登录退出时防止下次登录误恢复游戏模式）
        spectatorPending.remove(uuid);
        // 清除超时任务标记（玩家已下线，旧任务无意义）
        loginTimeoutStartedAt.remove(uuid);
    }

    /** 清理已过期的注销拒绝重连记录（周期任务 + unregister 时调用） */
    void cleanupExpired(long now) {
        recentUnregister.entrySet().removeIf(entry -> now - entry.getValue() >= UNREGISTER_RECONNECT_DELAY);
    }

    // ===== 诊断 =====

    int verifyingCount() {
        return verifying.size();
    }

    int invulnerablePendingCount() {
        return invulnerablePending.size();
    }

    int spectatorPendingCount() {
        return spectatorPending.size();
    }

    int pendingDatDeleteCount() {
        return pendingDatDelete.size();
    }

    int recentUnregisterCount() {
        return recentUnregister.size();
    }

    int loginTimeoutStartedCount() {
        return loginTimeoutStartedAt.size();
    }

    int loginSessionCount() {
        return loginSessions.size();
    }

    boolean hasLoginSessionRecord(UUID uuid) {
        return loginSessions.containsKey(uuid);
    }

    /** 诊断：该玩家的会话状态摘要（供 /hsauth diag 输出，逐行拼接） */
    String describe(UUID uuid) {
        return " loggedIn=" + loggedIn.contains(uuid)
                + " authenticatedConnection=" + authenticatedThisConnection.contains(uuid)
                + " verifying=" + verifying.contains(uuid)
                + " invulnerable=" + invulnerablePending.contains(uuid)
                + " spectator=" + spectatorPending.contains(uuid);
    }

    /** 注销后拒绝重连时长（毫秒）：确保原版 .dat 删除完成 */
    private static final long UNREGISTER_RECONNECT_DELAY = 5000L;
}
