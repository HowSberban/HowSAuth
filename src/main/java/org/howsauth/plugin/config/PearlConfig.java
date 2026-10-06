package org.howsauth.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * 末影珍珠保管与返还（{@code pearl.*}）配置。
 * <p>
 * 保管开关关闭时退出不接管珍珠，维持原版行为；返还方式仅支持 item/entity。
 * 构造时完成模式校验（非法值回退为 item 并回写），此后只读。
 */
public final class PearlConfig {

    /** 支持的返还方式 */
    private static final List<String> RETURN_MODES = List.of("item", "entity");

    private final boolean enabled;
    private final String returnMode;

    PearlConfig(FileConfiguration config, ConfigFixer fixer) {
        this.enabled = config.getBoolean("pearl.enabled", true);
        // 返还方式校验：仅支持 item/entity，非法值回退为 item
        this.returnMode = fixer.enumOrDefault("pearl.return", config.getString("pearl.return", "item"),
                RETURN_MODES, "item", "log.config_mode_invalid", false);
    }

    /** 末影珍珠保管开关（关闭后退出不接管珍珠，维持原版行为） */
    public boolean enabled() {
        return enabled;
    }

    /** 返还方式是否为世界原位重生飞行珍珠（false = 物品入包） */
    public boolean returnEntity() {
        return "entity".equals(returnMode);
    }
}
