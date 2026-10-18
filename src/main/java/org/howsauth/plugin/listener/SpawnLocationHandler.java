package org.howsauth.plugin.listener;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.Location;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.auth.LogoutLocation;
import org.howsauth.plugin.auth.SessionStore;
import org.howsauth.plugin.auth.TwoFactorAuth;
import org.howsauth.plugin.dialog.PreJoinAuthListener;

/**
 * 配置阶段 spawn 位置决策：会话命中/Pre-join 完成直接使用退出位置，否则按坐标保护取安全点。
 * 从 {@link PlayerListener} 拆出，保持连接前的地图信息决策独立可读。
 */
final class SpawnLocationHandler {

    private final HowSAuth plugin;
    private final SessionStore sessions;
    private final TwoFactorAuth twoFactor;
    private final LogoutLocation locations;

    SpawnLocationHandler(HowSAuth plugin, SessionStore sessions,
                         TwoFactorAuth twoFactor, LogoutLocation locations) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.twoFactor = twoFactor;
        this.locations = locations;
    }

    /**
     * 在 JoinGamePacket 发送前调整老玩家 spawn 位置。
     * - 会话命中的玩家：直接在退出位置出生，避免后续传送。
     * - 启用坐标保护：强制主世界随机位置，防止坐标泄露（F3、小地图 mod 等）。
     * 新玩家不干预，保留原版出生机制。
     * 此事件在 configuration phase 触发（异步线程），玩家尚未真正加入世界。
     */
    void handle(AsyncPlayerSpawnLocationEvent event) {
        // 新玩家不干预，保留原版出生机制
        if (event.isNewPlayer()) return;

        var conn = event.getConnection();
        java.util.UUID uuid = conn.getProfile().getId();
        // 代理协议下地址可能未解析（getAddress() 返回 null），统一走 SessionStore.ipOf 判空
        String ip = SessionStore.ipOf(conn.getClientAddress());

        // 会话命中（免输密码）的玩家直接在退出位置出生，避免后续传送
        // 登录需 2FA 的除外：验证完成前不放行到退出位置（/2fa 验证后再传送）；
        // 2FA 会话命中（同 IP 且未过期）视同已完成验证
        if (uuid != null && sessions.hasSession(uuid, ip) && !twoFactor.requiresAtLogin(uuid, ip)) {
            Location logoutLoc = locations.get(uuid);
            if (logoutLoc != null) {
                if (Debug.on()) {
                    Debug.log("flow", "spawn %s: logout location (login session hit)", Debug.shortId(uuid));
                }
                event.setSpawnLocation(logoutLoc);
                return;
            }
        }

        // Pre-join 已认证玩家：与会话命中一致，直接在退出位置出生，避免随机出生后再传送
        PreJoinAuthListener preJoin = plugin.preJoinAuth();
        if (uuid != null && preJoin != null && preJoin.hasCompleted(uuid)) {
            Location logoutLoc = locations.get(uuid);
            if (logoutLoc != null) {
                if (Debug.on()) {
                    Debug.log("flow", "spawn %s: logout location (pre-join completed)", Debug.shortId(uuid));
                }
                event.setSpawnLocation(logoutLoc);
            } else if (Debug.on()) {
                Debug.log("flow", "spawn %s: pre-join completed, no logout location (default spawn)",
                        Debug.shortId(uuid));
            }
            // 已认证：登录前未接收任何世界信息，无需坐标保护
            return;
        }

        // 启用坐标保护：强制主世界随机位置，防止坐标泄露
        if (plugin.config().protectionPosition().enabled()) {
            org.bukkit.World world = org.bukkit.Bukkit.getWorlds().getFirst();
            Location safeSpawn = locations.findSafeAuthSpawn(world);
            event.setSpawnLocation(safeSpawn);
            if (Debug.on()) {
                Debug.log("flow", "spawn %s: coordinate protection location",
                        Debug.shortId(uuid));
            }
        } else if (Debug.on()) {
            Debug.log("flow", "spawn %s: default spawn (no intervention)",
                    Debug.shortId(uuid));
        }
    }
}
