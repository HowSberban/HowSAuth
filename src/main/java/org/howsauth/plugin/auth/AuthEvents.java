package org.howsauth.plugin.auth;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.api.event.HSAuthLoginEvent;
import org.howsauth.plugin.api.event.HSAuthLoginFailEvent;
import org.howsauth.plugin.api.event.HSAuthLogoutEvent;
import org.howsauth.plugin.api.event.HSAuthRegisterEvent;
import org.howsauth.plugin.api.event.HSAuthUnregisterEvent;

import java.util.UUID;

/**
 * 认证相关 API 事件的统一发布出口。
 * <p>
 * 同步事件必须在主线程触发：管理命令在异步线程执行（getOfflinePlayer 防阻塞），
 * 直接 callEvent 会抛 IllegalStateException，因此非主线程一律转全局区域调度器。
 * 配置阶段（尚无 Player）的登录失败事件同理。
 */
final class AuthEvents {

    private final HowSAuth plugin;

    AuthEvents(HowSAuth plugin) {
        this.plugin = plugin;
    }

    /** 注册成功；player 可为 null（异步注册时在线状态不确定） */
    void register(UUID uuid, Player player) {
        fire(new HSAuthRegisterEvent(uuid, player));
    }

    void login(Player player) {
        fire(new HSAuthLoginEvent(player));
    }

    void logout(UUID uuid) {
        fire(new HSAuthLogoutEvent(uuid, Bukkit.getPlayer(uuid)));
    }

    void unregister(UUID uuid) {
        fire(new HSAuthUnregisterEvent(uuid, Bukkit.getPlayer(uuid)));
    }

    /** 登录失败；配置阶段没有 Player 对象，只能转全局区域调度器触发 */
    void loginFail(Player player) {
        HSAuthLoginFailEvent event = new HSAuthLoginFailEvent(player, HSAuthLoginFailEvent.Reason.WRONG_PASSWORD);
        if (player != null) {
            Bukkit.getPluginManager().callEvent(event);
        } else {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> Bukkit.getPluginManager().callEvent(event));
        }
    }

    /** 触发同步 API 事件：tick 线程直接触发，异步线程转全局区域调度器 */
    private void fire(Event event) {
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getPluginManager().callEvent(event);
        } else {
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> Bukkit.getPluginManager().callEvent(event));
        }
    }
}