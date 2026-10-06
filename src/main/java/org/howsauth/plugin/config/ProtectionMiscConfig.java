package org.howsauth.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 登录保护杂项（{@code protection.*}）配置。
 * <p>
 * 坐标保护与行为限制分别在 {@link ProtectionPosition} / {@link PreventConfig}，
 * 本类只承载未登录旁观、背包数据包保护、登录前失明与无凭据账号拒入四个开关。此后只读。
 */
public final class ProtectionMiscConfig {

    private final boolean gamemodeEnabled;
    private final boolean inventoryEnabled;
    private final boolean blindnessEnabled;
    private final boolean rejectNoAuthAccount;

    ProtectionMiscConfig(FileConfiguration config) {
        // 未登录旁观模式：未登录期间切换为旁观，登录后恢复上次游戏模式
        this.gamemodeEnabled = config.getBoolean("protection.gamemode", false);
        // 背包保护：未登录期间通过 PacketEvents 拦截物品数据包，防止 mod 窥视
        this.inventoryEnabled = config.getBoolean("protection.inventory", false);
        // 登录前失明：未登录（含等待登录/注册/2FA）期间施加失明效果，登录/注册成功后移除
        this.blindnessEnabled = config.getBoolean("protection.blindness", true);
        // 无凭据账号（无密码、未绑 2FA、非正版）是否在连接阶段拒绝进入；关闭则放行进服挂起（无法完成登录，超时踢出）
        this.rejectNoAuthAccount = config.getBoolean("protection.reject-no-auth-account", true);
    }

    /** 未登录期间切换为旁观，登录后恢复上次游戏模式 */
    public boolean gamemodeEnabled() {
        return gamemodeEnabled;
    }

    /** 背包保护：未登录期间拦截物品数据包，防止 mod 窥视 */
    public boolean inventoryEnabled() {
        return inventoryEnabled;
    }

    /** 登录前失明：未登录期间施加失明效果，登录/注册成功后移除 */
    public boolean blindnessEnabled() {
        return blindnessEnabled;
    }

    /** 无凭据账号是否在连接阶段拒绝进入 */
    public boolean rejectNoAuthAccount() {
        return rejectNoAuthAccount;
    }
}
