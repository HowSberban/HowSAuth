package org.howsauth.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;

/**
 * 登录前坐标保护（{@code protection.pos.*}）配置。
 * <p>
 * 未登录期间把玩家出生点改为随机安全位置（或配置的固定坐标），避免泄露真实退出坐标。
 * 构造时完成模式校验与半径钳制，此后只读。
 */
public final class ProtectionPosition {

    /** 支持的坐标模式 */
    private static final List<String> MODES = List.of("random", "fixed");

    private final boolean enabled;
    private final String mode;
    private final int spawnRadius;
    private final double fixedX;
    private final double fixedY;
    private final double fixedZ;
    private final float fixedYaw;
    private final float fixedPitch;

    ProtectionPosition(FileConfiguration config, ConfigFixer fixer) {
        this.enabled = config.getBoolean("protection.pos.enabled", false);
        // 坐标模式校验：仅支持 random/fixed，非法值回退为 random
        this.mode = fixer.enumOrDefault("protection.pos.mode", config.getString("protection.pos.mode", "random"),
                MODES, "random", "log.config_mode_invalid", false);
        this.spawnRadius = fixer.clampMin("protection.pos.spawn-radius",
                config.getInt("protection.pos.spawn-radius", 10), 1);
        this.fixedX = config.getDouble("protection.pos.fixed.x", 0);
        this.fixedY = config.getDouble("protection.pos.fixed.y", 64);
        this.fixedZ = config.getDouble("protection.pos.fixed.z", 0);
        this.fixedYaw = (float) config.getDouble("protection.pos.fixed.yaw", 0);
        this.fixedPitch = (float) config.getDouble("protection.pos.fixed.pitch", 0);
    }

    /** 坐标保护是否开启 */
    public boolean enabled() {
        return enabled;
    }

    /** 坐标模式：random（出生点附近随机）或 fixed（配置的固定坐标） */
    public String mode() {
        return mode;
    }

    /** 固定坐标模式是否为当前模式 */
    public boolean fixedMode() {
        return "fixed".equals(mode);
    }

    /** 随机模式的出生点半径 */
    public int spawnRadius() {
        return spawnRadius;
    }

    /**
     * 配置的固定出生位置。
     * 把「取 7 个坐标字段拼 Location」收敛到这里，调用方无需了解配置键布局。
     */
    public Location fixedLocation(World world) {
        return new Location(world, fixedX, fixedY, fixedZ, fixedYaw, fixedPitch);
    }
}
