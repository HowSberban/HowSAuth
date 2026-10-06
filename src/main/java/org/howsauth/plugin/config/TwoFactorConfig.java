package org.howsauth.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 双因素认证（{@code login.2fa.*}）配置。
 * <p>
 * 包含全局开关、绑定临时密钥有效期、验证会话保持、扫码网页入口与 otpauth issuer。
 * 构造时完成全部解析与钳制（越界值钳制并回写），此后只读。
 */
public final class TwoFactorConfig {

    /** 2FA 二维码服务默认地址模板（{data} 替换为 URL 编码后的 otpauth 资料） */
    private static final String DEFAULT_QR_URL = "https://api.qrserver.com/v1/create-qr-code/?data={data}&size=200x200&ecc=M&margin=10";

    private final boolean enabled;
    private final int tempSecretExpireSeconds;
    private final boolean sessionEnabled;
    private final int sessionExpireMinutes;
    private final boolean qrEnabled;
    private final String qrUrl;
    private final String serverName;

    TwoFactorConfig(FileConfiguration config, ConfigFixer fixer) {
        this.enabled = config.getBoolean("login.2fa.enabled", true);
        this.tempSecretExpireSeconds = fixer.clampMin("login.2fa.expire-seconds",
                config.getInt("login.2fa.expire-seconds", 300), 0);
        this.sessionEnabled = config.getBoolean("login.2fa.session.enabled", false);
        this.sessionExpireMinutes = fixer.clampMin("login.2fa.session.expire-minutes",
                config.getInt("login.2fa.session.expire-minutes", 5), 1);
        this.qrEnabled = config.getBoolean("login.2fa.qr", true);
        this.qrUrl = config.getString("login.2fa.qr-url", DEFAULT_QR_URL);
        this.serverName = config.getString("login.2fa.server-name", "");
    }

    /** 双因素认证总开关（关闭后已绑定玩家跳过验证，密钥保留） */
    public boolean enabled() {
        return enabled;
    }

    /** 绑定时临时密钥的有效期（秒），0 = 永不过期 */
    public int tempSecretExpireSeconds() {
        return tempSecretExpireSeconds;
    }

    /** 2FA 会话保持：验证码通过后同 IP 短时间内重连免验证码 */
    // 注意与 LoginConfig.sessionEnabled() 同名但读的是 login.2fa.session.enabled，勿混用
    // 调用方一律以 ! 守卫子句使用，反转命名反而与配置键语义相反
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean sessionEnabled() {
        return sessionEnabled;
    }

    /** 2FA 会话有效期（分钟），固定窗口，命中不续期 */
    public int sessionExpireMinutes() {
        return sessionExpireMinutes;
    }

    /** 是否提供扫码网页入口（关闭后不向二维码服务发送密钥） */
    public boolean qrEnabled() {
        return qrEnabled;
    }

    /** 生成二维码的服务地址模板，{data} 占位符会被替换为 URL 编码后的 otpauth 资料 */
    public String qrUrl() {
        return qrUrl;
    }

    /** otpauth 标签的服务器名（issuer）：非空时条目显示为 "服务器名:玩家名"，玩家名作账户详情 */
    public String serverName() {
        return serverName == null ? "" : serverName.trim();
    }
}
