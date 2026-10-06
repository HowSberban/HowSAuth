package org.howsauth.plugin.data;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * 单个玩家的持久化数据载体（内存缓存中的值对象类型）。
 * <p>
 * 字段全部 {@code volatile}：主线程写入后，异步落库线程需要读到最新值。
 * 除 {@code uuid} 外均可变——账号迁移时会就地改写 {@code premium}/{@code properties}/{@code name}。
 * <p>
 * 退出位置的序列化与反序列化放在本类：格式（{@code world:x:y:z:yaw:pitch}）与
 * {@link #logoutLocation} 字段强耦合，放在一起可避免两处各自演化。
 */
public final class PlayerData {

    private final UUID uuid;
    // volatile 保证可见性：主线程写入后，异步保存线程能读到最新值
    private volatile String name;
    private volatile String passwordHash;
    private volatile String ip;
    private volatile long lastLogin;
    // 玩家上次已登录退出时的位置（序列化字符串），用于登录后传送回原位置
    private volatile String logoutLocation;
    // 正版标记：true=正版账号（免密），false=离线账号（密码登录）
    private volatile boolean premium;
    // 正版玩家皮肤 properties（JSON 字符串，来自 Mojang hasJoined 响应）
    private volatile String properties;
    // 玩家上次已登录退出时的游戏模式（名称），用于登录后恢复
    private volatile String gameMode;
    // 双因素认证 TOTP 密钥（Base32），null 表示未启用
    private volatile String totpSecret;
    // 最后活跃时间（epoch 秒）：登录成功时更新，用于清理不活跃账号
    private volatile long lastActive;

    public PlayerData(UUID uuid, String name, String passwordHash, String ip, long lastLogin,
                      String logoutLocation, boolean premium, String properties, String gameMode,
                      String totpSecret, long lastActive) {
        this.uuid = uuid;
        this.name = name;
        this.passwordHash = passwordHash;
        this.ip = ip;
        this.lastLogin = lastLogin;
        this.logoutLocation = logoutLocation;
        this.premium = premium;
        this.properties = properties;
        this.gameMode = gameMode;
        this.totpSecret = totpSecret;
        this.lastActive = lastActive;
    }

    /**
     * 将 Location 序列化为字符串，格式: world:x:y:z:yaw:pitch
     * 用于持久化存储玩家上次退出位置。
     */
    public static String serializeLocation(Location loc) {
        return loc.getWorld().getName() + ":"
                + loc.getX() + ":" + loc.getY() + ":" + loc.getZ() + ":"
                + loc.getYaw() + ":" + loc.getPitch();
    }

    /**
     * 将序列化的字符串反序列化为 Location。
     * 如果世界不存在或格式错误，返回 null。
     */
    public static Location deserializeLocation(String str) {
        if (str == null || str.isEmpty()) return null;
        try {
            String[] parts = str.split(":");
            if (parts.length != 6) return null;
            World world = Bukkit.getWorld(parts[0]);
            if (world == null) return null;
            return new Location(world,
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]),
                    Float.parseFloat(parts[4]),
                    Float.parseFloat(parts[5]));
        } catch (Exception e) {
            return null;
        }
    }

    public UUID uuid() { return uuid; }
    public String name() { return name; }
    public void name(String name) { this.name = name; }

    public String passwordHash() { return passwordHash; }
    public void passwordHash(String hash) { this.passwordHash = hash; }

    public String ip() { return ip; }
    public void ip(String ip) { this.ip = ip; }

    public long lastLogin() { return lastLogin; }
    public void lastLogin(long lastLogin) { this.lastLogin = lastLogin; }

    public String logoutLocation() { return logoutLocation; }
    public void logoutLocation(String logoutLocation) { this.logoutLocation = logoutLocation; }

    public boolean premium() { return premium; }
    public void premium(boolean premium) { this.premium = premium; }

    public String properties() { return properties; }
    public void properties(String properties) { this.properties = properties; }

    public String gameMode() { return gameMode; }
    public void gameMode(String gameMode) { this.gameMode = gameMode; }

    public String totpSecret() { return totpSecret; }
    public void totpSecret(String totpSecret) { this.totpSecret = totpSecret; }

    public long lastActive() { return lastActive; }
    public void lastActive(long lastActive) { this.lastActive = lastActive; }
}
