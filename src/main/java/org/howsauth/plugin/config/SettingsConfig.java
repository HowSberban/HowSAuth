package org.howsauth.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 通用设置（{@code settings.*} 与 {@code advanced.*}）配置。
 * <p>
 * 包含注销行为、玩家自助注销、调试模式、悬空判定与不活跃账号清理。
 * 注意：{@code settings.default-language} 与 {@code settings.i18n} 不归本类，
 * 它们由 {@link ConfigManager#load()} 内联处理并调用 {@code I18n} 设置。
 * 构造时完成解析与钳制（越界值钳制并回写），此后只读。
 */
public final class SettingsConfig {

    private final boolean realUnreg;
    private final boolean allowSelfUnregister;
    private final boolean debug;
    private final boolean danglingCheck;
    private final boolean purgeEnabled;
    private final int purgeDays;

    SettingsConfig(FileConfiguration config, ConfigFixer fixer) {
        this.realUnreg = config.getBoolean("settings.real-unreg", true);
        this.allowSelfUnregister = config.getBoolean("settings.allow-self-unregister", true);
        // 高级设置（config.yml 的 advanced 段：仅高级用户使用的开关）
        this.debug = config.getBoolean("advanced.debug", false);
        // 悬空判定：未登录挂起时判断退出位置脚下方块是否固体，悬空则切换旁观以获得坠落保护。
        // false（默认）不执行判定，整段旁观处理直接跳过，玩家保持原游戏模式
        this.danglingCheck = config.getBoolean("advanced.dangling-check", false);
        this.purgeEnabled = config.getBoolean("settings.purge.enabled", false);
        this.purgeDays = fixer.clampMin("settings.purge.days", config.getInt("settings.purge.days", 90), 1);
    }

    /** 注销时是否真实删除账号数据（false = 仅标记注销并重置密码） */
    public boolean realUnreg() {
        return realUnreg;
    }

    /** 玩家自助注销开关 */
    public boolean allowSelfUnregister() {
        return allowSelfUnregister;
    }

    /** 调试模式：认证流程详细日志（面向排查的英文硬编码输出） */
    public boolean debug() {
        return debug;
    }

    /** 悬空判定：是否在未登录挂起时判定退出位置是否悬空并切换旁观 */
    public boolean danglingCheck() {
        return danglingCheck;
    }

    /** 是否启用不活跃账号清理 */
    public boolean purgeEnabled() {
        return purgeEnabled;
    }

    /** 不活跃账号的保留天数（不小于 1） */
    public int purgeDays() {
        return purgeDays;
    }
}
