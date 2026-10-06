package org.howsauth.plugin.auth;

import org.howsauth.plugin.Debug;
import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.data.PlayerDataManager;
import org.howsauth.plugin.data.PlayerDataManager.PlayerData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 双因素认证（TOTP）：待验证状态、验证码校验与防重放、绑定/解绑、临时密钥生命周期、2FA 会话保持。
 * <p>
 * 验证失败的待遇与密码错误一致（计入暴力破解防护并回到待验证状态）：无密码账户的验证码是唯一
 * 登录因素，必须与密码同等防护，因此本类依赖 {@link FailProtection} 与 {@link AuthEvents}。
 * <p>
 * <b>线程契约</b>：全部集合为并发集合。验证码消费路径 {@code pending2fa.remove} 为入口，
 * 保证同一玩家同一时刻仅一个线程进入消费路径，故 {@code used2faCounters} 的 check-then-put 无竞态。
 */
public final class TwoFactorAuth {

    // 双因素认证：密码已通过但尚未完成 TOTP 验证的玩家（未完成前不算已登录）
    private final Set<UUID> pending2fa = ConcurrentHashMap.newKeySet();
    // 已消费的 2FA 时间片计数器：登录验证通过后记录，拒绝同周期或更旧验证码重放
    // （仅内存，重启清零后同一验证码在 ≤90 秒窗口内理论上可重放一次，风险可忽略）
    private final Map<UUID, Long> used2faCounters = new ConcurrentHashMap<>();
    // 2FA 会话保持：验证码通过后记录 (ip, 到期时间)，同 IP 短时间内重连免验证码
    // 固定窗口不滑动（命中不续期）；仅内存，重启即失效
    private final Map<UUID, TwoFaSession> twoFaSessions = new ConcurrentHashMap<>();
    // 双因素设置中的临时密钥：confirm 验证通过后才持久化
    private final Map<UUID, String> pending2faSecret = new ConcurrentHashMap<>();
    // 临时密钥的创建时间戳：用于按配置时长过期清理（与 pending2faSecret 一一对应）
    private final Map<UUID, Long> pending2faSecretCreatedAt = new ConcurrentHashMap<>();

    private final PlayerDataManager dataManager;
    private final ConfigManager configManager;
    private final FailProtection failProtection;
    private final AuthEvents events;

    TwoFactorAuth(PlayerDataManager dataManager, ConfigManager configManager,
                  FailProtection failProtection, AuthEvents events) {
        this.dataManager = dataManager;
        this.configManager = configManager;
        this.failProtection = failProtection;
        this.events = events;
    }

    /** 2FA 会话：验证码通过时的来源 IP 与到期时间戳 */
    private record TwoFaSession(String ip, long expiresAt) {}

    // ===== 状态查询 =====

    /** 账号是否处于双因素认证生效状态（已绑定密钥且全局开关开启） */
    public boolean has2fa(UUID uuid) {
        PlayerData data = dataManager.getPlayer(uuid);
        return data != null && data.totpSecret() != null && configManager.twoFaEnabled();
    }

    /** 玩家是否处于双因素待验证状态（密码已通过，TOTP 未完成） */
    // 调用方均为取反使用（!isPending 判断"无需 2FA"），方法语义保持正向便于阅读
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isPending(UUID uuid) {
        return pending2fa.contains(uuid);
    }

    /** 免密登录（正版/IP）但已绑定 2FA：配置阶段直弹验证码窗口前标记待验证（verifyCode 以此为前置状态） */
    public void markPending(UUID uuid) {
        pending2fa.add(uuid);
    }

    /** 清除 2FA 待验证标记（会话级状态，不跨连接：新连接进入配置阶段时清残留，语义与 SessionStore#clear 一致） */
    public void clearPending(UUID uuid) {
        pending2fa.remove(uuid);
    }

    /** 是否已绑定 2FA 密钥（不看全局开关，移除密码的资格判断用） */
    public boolean hasTotpSecret(UUID uuid) {
        PlayerData data = dataManager.getPlayer(uuid);
        return data != null && data.totpSecret() != null;
    }

    /** 登录时是否必须完成 2FA 验证：已绑定密钥且（全局开关开启，或无密码账户——验证码是其必要登录因素，不受开关影响）。
     *  2FA 会话命中（同 IP 且未过期）时返回 false 免验证码 */
    public boolean requiresAtLogin(UUID uuid, String ip) {
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null || data.totpSecret() == null) return false;
        if (hasSession(uuid, ip)) return false;
        return configManager.twoFaEnabled()
                || data.passwordHash() == null || data.passwordHash().isEmpty();
    }

    // ===== 2FA 会话保持 =====

    /** 记录 2FA 会话：验证码通过后同 IP 短时间内重连免验证码（固定窗口，命中不续期） */
    private void markSession(UUID uuid, String ip) {
        if (!configManager.twoFaSessionEnabled() || ip == null) return;
        twoFaSessions.put(uuid, new TwoFaSession(ip,
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(configManager.twoFaSessionExpireMinutes())));
    }

    /** 2FA 会话是否命中（免验证码）：开关开启 + 同 IP 且未过期。
     *  无密码账户同样适用——风险模型与 login.session 一致（同 IP 短窗口信任），窗口内等同零凭证登录 */
    public boolean hasSession(UUID uuid, String ip) {
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
    void clearSession(UUID uuid) {
        twoFaSessions.remove(uuid);
    }

    // ===== 验证码校验 =====

    /**
     * 2FA 验证核心：校验验证码，通过则记录 2FA 会话（免验证码重连窗口）。
     * 失败时与密码错误同待遇计入暴力破解防护（无密码账户的验证码即唯一登录因素，更须防护），
     * 并回到待验证状态允许重试。
     * @param player 在线验证时的玩家（暴力破解踢出提示用），配置阶段无 Player 传 null
     * @return 验证通过返回账号数据（供调用方登录收尾），失败返回 null
     */
    PlayerData verifyCode(UUID uuid, Player player, String code, String ip) {
        if (!pending2fa.remove(uuid)) {
            if (Debug.on()) {
                Debug.log("2fa", "verify %s: rejected (not pending 2fa)",
                        who(uuid, player));
            }
            return null;
        }
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null || data.totpSecret() == null) {
            if (Debug.on()) {
                Debug.log("2fa", "verify %s: rejected (no secret)",
                        who(uuid, player));
            }
            return null;
        }
        Long counter = Totp.matchCounter(data.totpSecret(), code);
        // 防重放：同周期或更旧的验证码视为已消费拒绝（TOTP 无状态，同一码在 ±1 窗口内可重复匹配）
        // 入口 pending2fa.remove 已保证同一玩家同一时刻仅一个线程进入消费路径，check-then-put 无竞态
        if (counter == null || counter <= used2faCounters.getOrDefault(uuid, Long.MIN_VALUE)) {
            if (Debug.on()) {
                Debug.log("2fa", "verify %s: rejected (%s)",
                        who(uuid, player),
                        counter == null ? "invalid code" : "replay");
            }
            failProtection.recordFailure(uuid, player);
            events.loginFail(player);
            pending2fa.add(uuid);
            return null;
        }
        used2faCounters.put(uuid, counter);
        markSession(uuid, ip);
        if (Debug.on()) {
            Debug.log("2fa", "verify %s: passed",
                    who(uuid, player));
        }
        return data;
    }

    /** 日志用标识：在线验证用玩家名，配置阶段（无 Player 对象）用 UUID 短标识。
     *  纯函数，须在 {@code if (Debug.on())} 守卫内调用 */
    private static String who(UUID uuid, Player player) {
        return player != null ? player.getName() : Debug.shortId(uuid);
    }

    // ===== 绑定与解绑 =====

    /** 开始双因素设置：返回待绑定密钥（confirm 通过后才持久化）
     *  已有未绑定的临时密钥则复用，避免重复执行 /2fa setup 时密钥被覆盖导致旧密钥失效
     *  复用前先清理已过期的旧密钥，避免复用过期密钥后无法 confirm */
    public String setup(Player player) {
        UUID uuid = player.getUniqueId();
        if (has2fa(uuid)) return null;
        removeExpiredSecret(uuid);
        return pending2faSecret.computeIfAbsent(uuid, u -> {
            String secret = Totp.generateSecret();
            pending2faSecretCreatedAt.put(uuid, System.currentTimeMillis());
            if (Debug.on()) {
                Debug.log("2fa", "setup %s: temp secret generated (ttl %ss)",
                        player.getName(), configManager.twoFaTempSecretExpireSeconds());
            }
            return secret;
        });
    }

    /** 确认双因素绑定：验证码通过后持久化临时密钥（临时密钥已过期则作废） */
    public boolean confirm(Player player, String code) {
        UUID uuid = player.getUniqueId();
        removeExpiredSecret(uuid);
        String secret = pending2faSecret.get(uuid);
        if (secret == null) {
            if (Debug.on()) {
                Debug.log("2fa", "confirm %s: rejected (no pending secret)", player.getName());
            }
            return false;
        }
        if (!Totp.verifyCode(secret, code)) {
            if (Debug.on()) {
                Debug.log("2fa", "confirm %s: rejected (invalid code)", player.getName());
            }
            return false;
        }
        clearPendingSecret(uuid);
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) {
            if (Debug.on()) {
                Debug.log("2fa", "confirm %s: failed (no account)", player.getName());
            }
            return false;
        }
        data.totpSecret(secret);
        dataManager.save(uuid);
        if (Debug.on()) {
            Debug.log("2fa", "confirm %s: enabled", player.getName());
        }
        return true;
    }

    /** 玩家的临时密钥是否已失效：未生成或已过期（供提示"重新 setup"前判断）
     *  调用方均为取反前的直接判断，方法语义保持"已失效"便于阅读 */
    public boolean isSecretExpired(UUID uuid) {
        removeExpiredSecret(uuid);
        return !pending2faSecret.containsKey(uuid);
    }

    /** 关闭双因素认证：需验证当前 TOTP 验证码（而非密码——2FA 正是防密码泄漏，解绑也须持有验证器） */
    public boolean disable(Player player, String code) {
        UUID uuid = player.getUniqueId();
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null || data.totpSecret() == null) {
            if (Debug.on()) {
                Debug.log("2fa", "disable %s: failed (not enabled or no account)", player.getName());
            }
            return false;
        }
        if (!Totp.verifyCode(data.totpSecret(), code)) {
            if (Debug.on()) {
                Debug.log("2fa", "disable %s: rejected (invalid code)", player.getName());
            }
            return false;
        }
        data.totpSecret(null);
        // 解绑后待验证标记已无意义，清除避免残留（残留会让玩家在密码窗口卡死/状态泄漏至下次退出）
        pending2fa.remove(uuid);
        used2faCounters.remove(uuid);
        // 关键操作立即持久化，防止断电丢失
        dataManager.saveNow(uuid);
        if (Debug.on()) {
            Debug.log("2fa", "disable %s: disabled", player.getName());
        }
        return true;
    }

    /**
     * 管理员强制解除双因素认证：不校验验证码（玩家可能已丢失验证器密钥导致账号锁死的救济通道），
     * 仅清除 TOTP 密钥与会话/计数/待确认状态，保留账号其余数据（密码、正版标记、位置等）。
     * 绑定中返回 true；账号不存在或未绑定时返回 false。
     */
    public boolean reset(UUID uuid) {
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null || data.totpSecret() == null) {
            if (Debug.on()) {
                Debug.log("2fa", "reset %s: failed (not enabled or no account)", Debug.shortId(uuid));
            }
            return false;
        }
        data.totpSecret(null);
        // 管理员解除同样使待验证状态失效，一并清理（语义与 disable 一致）
        pending2fa.remove(uuid);
        used2faCounters.remove(uuid);
        clearSession(uuid);
        clearPendingSecret(uuid);
        // 关键操作立即持久化，防止断电丢失
        dataManager.saveNow(uuid);
        if (Debug.on()) {
            Debug.log("2fa", "reset %s: reset by admin", Debug.shortId(uuid));
        }
        return true;
    }

    // ===== 临时密钥生命周期 =====

    /** 临时密钥是否已超期（配置为 0 时永不过期） */
    private boolean isExpiredSecret(long created) {
        int seconds = configManager.twoFaTempSecretExpireSeconds();
        return seconds > 0 && System.currentTimeMillis() - created >= seconds * 1000L;
    }

    /** 移除过期临时密钥（setup/confirm 前调用，懒清理） */
    private void removeExpiredSecret(UUID uuid) {
        Long created = pending2faSecretCreatedAt.get(uuid);
        if (created != null && isExpiredSecret(created)) {
            clearPendingSecret(uuid);
        }
    }

    /** 清理单个玩家的临时密钥及创建时间戳 */
    void clearPendingSecret(UUID uuid) {
        pending2faSecret.remove(uuid);
        pending2faSecretCreatedAt.remove(uuid);
    }

    /** 清除该玩家的防重放计数（解绑/注销时调用：残留计数会误拒重绑定新密钥后的正确验证码） */
    void clearCounter(UUID uuid) {
        used2faCounters.remove(uuid);
    }

    /** 周期清理所有过期的临时密钥（异步调度器调用） */
    void cleanupExpiredSecrets() {
        int seconds = configManager.twoFaTempSecretExpireSeconds();
        if (seconds <= 0) return;
        long limit = seconds * 1000L;
        long now = System.currentTimeMillis();
        List<UUID> expired = new ArrayList<>();
        pending2faSecretCreatedAt.forEach((uuid, created) -> {
            if (now - created >= limit) expired.add(uuid);
        });
        for (UUID uuid : expired) {
            clearPendingSecret(uuid);
        }
        if (!expired.isEmpty() && Debug.on()) {
            Debug.log("2fa", "cleanup expired temp secrets: %s removed", expired.size());
        }
    }

    /** 周期清理已过期的 2FA 会话，返回移除条数 */
    int cleanupSessions(long now) {
        int before = twoFaSessions.size();
        twoFaSessions.entrySet().removeIf(e -> now > e.getValue().expiresAt());
        return before - twoFaSessions.size();
    }

    // ===== 诊断 =====

    int pendingCount() {
        return pending2fa.size();
    }

    int pendingSecretCount() {
        return pending2faSecret.size();
    }

    int usedCounterCount() {
        return used2faCounters.size();
    }

    int sessionCount() {
        return twoFaSessions.size();
    }

    boolean hasSessionRecord(UUID uuid) {
        return twoFaSessions.containsKey(uuid);
    }
}
