package org.howsauth.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 注册限制（{@code register.max-accounts-per-ip.*}）配置。
 * <p>
 * 包含同 IP 账号数上限与"IP 已满时是否在连接阶段拒绝进服"开关。
 * 构造时完成解析与钳制（越界值钳制并回写），此后只读。
 */
public final class RegisterConfig {

    private final int maxAccountsPerIp;
    private final boolean ipLimitRejectJoin;

    RegisterConfig(FileConfiguration config, ConfigFixer fixer) {
        this.maxAccountsPerIp = fixer.clampMin("register.max-accounts-per-ip.limit",
                config.getInt("register.max-accounts-per-ip.limit", 3), 0);
        // 连接阶段拦截未注册玩家（IP 已满时）的开关，默认开启保持严格；共享 IP 环境可关闭
        this.ipLimitRejectJoin = config.getBoolean("register.max-accounts-per-ip.reject-join", true);
    }

    /** 同一 IP 允许注册的账号数上限（0 表示不限制） */
    public int maxAccountsPerIp() {
        return maxAccountsPerIp;
    }

    /** 同 IP 已满时，是否在连接阶段直接拒绝未注册新玩家进服（false = 放行由注册动作判定） */
    public boolean ipLimitRejectJoin() {
        return ipLimitRejectJoin;
    }
}
