package org.howsauth.plugin.auth;

import org.howsauth.plugin.Debug;
import org.howsauth.plugin.config.ConfigManager;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 暴力破解防护：连续失败计数、踢出期与过期淘汰。
 * <p>
 * 两个 Map 均为跨连接保留（退出/被踢都不清除，见 {@link LoginFlow#clearSession} 的说明）：
 * 只有达到阈值踢出、登录成功或超过重置窗口才失效，否则攻击者可用"失败几次→重连"重置进度。
 * <p>
 * <b>线程契约</b>：并发 Map，可被任意区域线程读写；容量守卫依赖 {@code ConcurrentHashMap} 的
 * 弱一致遍历，不做全局锁。{@link #recordFailure} 须在玩家区域线程调用（与原
 * {@code LoginFlow#handleLoginFailure} 一致）。
 */
public final class FailProtection {

    // 失败次数[0] / 最后失败时间[1]，跨连接保留，超过 reset-seconds 未再失败则过期清空
    private final Map<UUID, long[]> failedAttempts = new ConcurrentHashMap<>();
    // 踢出到期时间戳
    private final Map<UUID, Long> kickUntil = new ConcurrentHashMap<>();
    private final ConfigManager configManager;

    public FailProtection(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * 记录一次登录失败：计数（可能触发踢出）。
     * 事件发布不在此处——由调用方经 {@link AuthEvents} 触发，使本类不依赖事件总线。
     */
    void recordFailure(UUID uuid, Player player) {
        if (Debug.on()) {
            Debug.log("auth", "login failure for %s", player != null ? player.getName() : uuid);
        }
        // 增加计数（仅在启用失败保护时）
        if (!configManager.login().failProtectionEnabled()) return;
        long now = System.currentTimeMillis();
        long resetMs = configManager.login().failProtectionResetSeconds() * 1000L;
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
        if (attempts[0] >= configManager.login().failMaxAttempts()) {
            // 达到阈值，设置踢出期
            if (Debug.on()) {
                Debug.log("auth", "kick %s: %s consecutive failures, banned %ss",
                        player != null ? player.getName() : Debug.shortId(uuid),
                        attempts[0], configManager.login().failKickDuration());
            }
            kickUntil.put(uuid, now + configManager.login().failKickDuration() * 1000L);
            failedAttempts.remove(uuid);
            // 容量守卫：攻击者用大量用户名各达阈值后不再重连，踢出记录仅在被读取时懒清理，
            // 超限时清理已过期项，防止 Map 无界增长（与 failedAttempts 守卫同一威胁模型）
            if (kickUntil.size() > FAILED_ATTEMPTS_CAP) {
                kickUntil.values().removeIf(until -> until <= now);
            }
        }
    }

    /** 清除该玩家的失败计数与踢出记录（登录成功、账号注销时调用） */
    void clear(UUID uuid) {
        failedAttempts.remove(uuid);
        kickUntil.remove(uuid);
    }

    // 暴力破解防护：检查是否处于踢出期
    public boolean isKicked(Player player) {
        return isKicked(player.getUniqueId());
    }

    public boolean isKicked(UUID uuid) {
        if (!configManager.login().failProtectionEnabled()) return false;
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

    /** 淘汰过期项（踢出记录到期、失败计数超过重置窗口），返回移除条数（周期任务调用） */
    int cleanupExpired(long now) {
        int before = failedAttempts.size() + kickUntil.size();
        kickUntil.values().removeIf(until -> until <= now);
        evictStaleFailures(now);
        return before - (failedAttempts.size() + kickUntil.size());
    }

    /** 清理可安全移除的失败计数：超过过期时长未再失败（玩家可能已离线/已放弃尝试）。
     *  不能按"未达阈值"清理——达阈值的条目在 recordFailure 中已被 remove，Map 中不存在 ≥max 的条目，
     *  按阈值清理恒真等于全清，攻击者可用大量假名洪水抹掉自己针对目标账号的累计进度 */
    private void evictStaleFailures(long now) {
        long resetMs = configManager.login().failProtectionResetSeconds() * 1000L;
        if (resetMs <= 0) return;
        failedAttempts.entrySet().removeIf(entry -> now - entry.getValue()[1] >= resetMs);
    }

    // ===== 诊断 =====

    int failureCount() {
        return failedAttempts.size();
    }

    boolean hasFailureRecord(UUID uuid) {
        return failedAttempts.containsKey(uuid);
    }

    /** failedAttempts 容量阈值：超过时清理未达阈值的失败计数，防止攻击者用大量用户名
     *  各失败未达阈值导致 Map 无界增长（失败计数跨连接保留后不再随退出清理） */
    private static final int FAILED_ATTEMPTS_CAP = 1000;
}
