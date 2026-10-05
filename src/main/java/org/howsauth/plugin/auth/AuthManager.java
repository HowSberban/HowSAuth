package org.howsauth.plugin.auth;

import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.data.PlayerDataManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 认证模块的组装根：按依赖顺序构造各协作服务、持有周期清理任务、暴露服务访问器。
 * <p>
 * 职责边界（拆分完成后的形态）：
 * <ul>
 *   <li>{@link SessionStore} — 登录态、认证标记、登录会话、过渡/待处理标记</li>
 *   <li>{@link FailProtection} — 失败计数与踢出期</li>
 *   <li>{@link TwoFactorAuth} — 双因素认证（TOTP）与会话</li>
 *   <li>{@link LogoutLocation} — 退出位置、安全出生点、旁观切换</li>
 *   <li>{@link AccountLifecycle} — 注册/注销/数据删除/正版升降级</li>
 *   <li>{@link LoginFlow} — 登录/注册编排与密码操作</li>
 *   <li>{@link AuthEvents} — 认证 API 事件发布</li>
 * </ul>
 * 调用方直接使用上述服务，本类不提供转发方法；仅保留组装、周期清理与诊断聚合。
 * <p>
 * 本类不再承载业务状态：原先的裸集合与业务方法已按职责搬入对应服务（行为零变化）。
 */
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

    private final PlayerDataManager dataManager;
    private final ConfigManager configManager;
    private final SessionStore sessions;
    private final FailProtection failProtection;
    private final TwoFactorAuth twoFactor;
    private final LogoutLocation locations;
    private final AccountLifecycle accounts;
    private final LoginFlow loginFlow;

    public AuthManager(HowSAuth plugin, PlayerDataManager dataManager, ConfigManager configManager) {
        this.dataManager = dataManager;
        this.configManager = configManager;
        // 事件总线仅在装配期使用，无需持有为字段
        AuthEvents events = new AuthEvents(plugin);
        this.sessions = new SessionStore(configManager, dataManager);
        this.failProtection = new FailProtection(configManager);
        this.twoFactor = new TwoFactorAuth(dataManager, configManager, failProtection, events);
        this.locations = new LogoutLocation(plugin, dataManager, configManager, sessions);
        this.accounts = new AccountLifecycle(plugin, dataManager, configManager, events, sessions,
                failProtection, twoFactor, this::cleanupExpiredStates);
        this.loginFlow = new LoginFlow(plugin, dataManager, configManager, events, sessions,
                failProtection, twoFactor, accounts);
        // 周期清理过期的 2FA 临时密钥与登录/2FA 会话等状态（懒清理兜底，随插件关闭统一取消）
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
            twoFactor.cleanupExpiredSecrets();
            cleanupExpiredStates();
        }, 1, 30, TimeUnit.SECONDS);
    }

    // ===== 协作服务访问器 =====

    /**
     * 会话状态中心：登录态、本次连接认证标记、登录会话保持、校验中/过渡待处理标记。
     */
    public SessionStore sessions() {
        return sessions;
    }

    /**
     * 暴力破解防护：失败计数、踢出期与过期淘汰。
     */
    public FailProtection failProtection() {
        return failProtection;
    }

    /**
     * 双因素认证（TOTP）：待验证状态、验证码校验、绑定/解绑、2FA 会话。
     */
    public TwoFactorAuth twoFactor() {
        return twoFactor;
    }

    /**
     * 退出位置与坐标保护：位置保存/读取/传送、安全出生点、区块预载与旁观切换。
     */
    public LogoutLocation locations() {
        return locations;
    }

    /**
     * 账号生命周期：注册、注销、原版数据删除与迁移、正版升降级标记、账号属性查询。
     */
    public AccountLifecycle accounts() {
        return accounts;
    }

    /**
     * 登录/注册编排：密码校验、2FA 转接、登录收尾、密码增改删、管理员强制操作。
     */
    public LoginFlow loginFlow() {
        return loginFlow;
    }

    // ===== 周期清理与诊断 =====

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
}
