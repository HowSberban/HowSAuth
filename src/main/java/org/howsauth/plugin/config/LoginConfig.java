package org.howsauth.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * 登录与注册窗口（{@code login.*} / {@code register.timeout}）配置。
 * <p>
 * 包含登录/注册超时、超时踢出、暴力破解防护、登录会话保持、未登录提醒与 Dialog 登录界面开关。
 * 注册超时键位于 {@code register} 段但归本类，属既定决定（与登录窗口同源）。
 * 构造时完成全部解析与校验（模式非法回退、数值越界钳制并回写），此后只读。
 */
public final class LoginConfig {

    /** 未登录提醒的发送方式 */
    private static final List<String> REMIND_METHODS = List.of("chat", "title", "actionbar", "bossbar");

    private final int timeout;
    private final int registerTimeout;
    private final boolean kickOnTimeout;
    private final boolean failProtectionEnabled;
    private final int failMaxAttempts;
    private final int failKickDuration;
    private final int failProtectionResetSeconds;
    private final boolean sessionEnabled;
    private final int sessionExpireMinutes;
    private final int remindInterval;
    private final String remindMethod;
    private final boolean dialogEnabled;
    private final boolean dialogAllowRiskyVersions;
    private final boolean ipChangeNotifyEnabled;

    LoginConfig(FileConfiguration config, ConfigFixer fixer) {
        this.timeout = fixer.clampMin("login.timeout", config.getInt("login.timeout", 120), 0);
        this.registerTimeout = fixer.clampMin("register.timeout", config.getInt("register.timeout", 180), 0);
        this.kickOnTimeout = config.getBoolean("login.kick-on-timeout", true);
        this.failProtectionEnabled = config.getBoolean("login.fail-protection.enabled", true);
        this.failMaxAttempts = fixer.clampMin("login.fail-protection.max-attempts",
                config.getInt("login.fail-protection.max-attempts", 3), 1);
        this.failKickDuration = fixer.clampMin("login.fail-protection.kick-duration",
                config.getInt("login.fail-protection.kick-duration", 60), 0);
        this.failProtectionResetSeconds = fixer.clampMin("login.fail-protection.reset-seconds",
                config.getInt("login.fail-protection.reset-seconds", 300), 1);
        this.sessionEnabled = config.getBoolean("login.session.enabled", true);
        this.sessionExpireMinutes = fixer.clampMin("login.session.expire-minutes",
                config.getInt("login.session.expire-minutes", 120), 1);
        this.remindInterval = fixer.clampMin("login.remind-interval", config.getInt("login.remind-interval", 5), 0);
        this.dialogEnabled = config.getBoolean("login.dialog.enabled", true);
        this.dialogAllowRiskyVersions = config.getBoolean("login.dialog.allow-risky-versions", false);
        // 提示方式校验：仅支持 chat/title/actionbar/bossbar，非法值回退为 chat（大小写不敏感）
        this.remindMethod = fixer.enumOrDefault("login.remind-method", config.getString("login.remind-method", "chat"),
                REMIND_METHODS, "chat", "log.config_mode_invalid", true);
        this.ipChangeNotifyEnabled = config.getBoolean("login.ip-change-notify", false);
    }

    /** 登录窗口（秒），超时未登录则按 kickOnTimeout 处理 */
    public int timeout() {
        return timeout;
    }

    /** 注册窗口（秒）：与登录超时分开，供新玩家注册使用 */
    public int registerTimeout() {
        return registerTimeout;
    }

    /** 登录/注册超时后是否踢出玩家 */
    public boolean kickOnTimeout() {
        return kickOnTimeout;
    }

    /** 暴力破解防护总开关 */
    public boolean failProtectionEnabled() {
        return failProtectionEnabled;
    }

    /** 触发踢出前的最大连续失败次数 */
    public int failMaxAttempts() {
        return failMaxAttempts;
    }

    /** 达到失败阈值后的踢出时长（秒） */
    public int failKickDuration() {
        return failKickDuration;
    }

    /** 失败计数跨连接保留的过期时间（秒）：0 = 永不过期 */
    public int failProtectionResetSeconds() {
        return failProtectionResetSeconds;
    }

    /** 会话保持：上次登录 IP 一致且未过期时免输密码 */
    // 调用方一律以 ! 守卫子句使用（如"会话关闭则直接判未命中"），反转命名反而与配置键语义相反
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean sessionEnabled() {
        return sessionEnabled;
    }

    /** 登录会话有效期（分钟），固定窗口，命中不续期 */
    public int sessionExpireMinutes() {
        return sessionExpireMinutes;
    }

    /** 未登录提醒间隔（秒），0 = 不提醒 */
    public int remindInterval() {
        return remindInterval;
    }

    /** 未登录提醒的发送方式：chat / title / actionbar / bossbar */
    public String remindMethod() {
        return remindMethod;
    }

    /** Dialog 登录界面开关（Paper 1.21.11+，客户端需同版本以上） */
    public boolean dialogEnabled() {
        return dialogEnabled;
    }

    /** 1.21.6–1.21.10 冒险启用 Dialog：存在未验证的兼容问题，默认关闭 */
    public boolean dialogAllowRiskyVersions() {
        return dialogAllowRiskyVersions;
    }

    /** IP 变动提醒：登录 IP 与上次不同时提示玩家 */
    public boolean ipChangeNotifyEnabled() {
        return ipChangeNotifyEnabled;
    }
}
