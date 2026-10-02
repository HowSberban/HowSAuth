package org.howsauth.plugin.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.howsauth.plugin.api.event.HSAuthLoginEvent;
import org.howsauth.plugin.api.event.HSAuthRegisterEvent;
import org.howsauth.plugin.auth.AuthManager;
import org.howsauth.plugin.config.ConfigManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 加入/退出消息的自定义模板与延迟补发。
 * 相比 AuthMe 同功能的实现做了三点优化：模板替换与隐藏/补发两个关注点正交解耦
 * （AuthMe 的 remove 命中会 return 跳过 custom，开关相互纠缠）；待补发消息用 UUID 做键
 * （AuthMe 用名字小写，改名/撞名会错发）；补发挂在登录/注册事件上，所有验证成功路径
 * 必经这两个事件，无需在各成功点插桩，join 与 quit 对称支持模板。
 */
public class JoinQuitMessageService implements Listener {

    private final AuthManager authManager;
    private final ConfigManager configManager;

    // 待补发的加入消息（UUID 做键）：未登录加入时消息被隐藏并暂存，登录成功后原子取出广播；
    // 玩家未登录即退出则移除丢弃，不会产生幽灵加入消息。值在暂存时已应用模板（模板为空存原版消息），
    // 补发时直接广播，无二次替换
    private final Map<UUID, Component> pendingJoinMessages = new ConcurrentHashMap<>();

    public JoinQuitMessageService(AuthManager authManager, ConfigManager configManager) {
        this.authManager = authManager;
        this.configManager = configManager;
    }

    /**
     * 加入消息处理（HIGH：晚于 LOWEST 的认证监听——同步完成的自动登录此时已登录，
     * 只有挂起等待输入的玩家才会被隐藏/暂存补发）。
     * 语义正交：模板永远替换最终广播的消息；隐藏仅针对未登录玩家；
     * 补发仅与隐藏搭配有意义（消息没藏就无需补发）。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onJoinMessage(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // 完全禁用：无条件清空，不暂存补发
        if (configManager.joinDisabled()) {
            event.joinMessage(null);
            return;
        }
        Component template = formatTemplate(configManager.joinMessageTemplate(), player);
        if (template != null) {
            event.joinMessage(template);
        }
        if (authManager.isLoggedIn(player) || !configManager.joinHideUnauthenticated()) {
            return;
        }
        Component hidden = template != null ? template : event.joinMessage();
        event.joinMessage(null);
        // 前序监听器已清空消息且无模板时无消息可补发，不入表
        if (configManager.joinDelayUntilAuthenticated() && hidden != null) {
            pendingJoinMessages.put(player.getUniqueId(), hidden);
        }
    }

    /**
     * 退出消息处理：先移除待补发项（未登录退出即丢弃补发，同时防 map 泄漏），
     * 再按配置隐藏未登录退出消息或应用退出模板。
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onQuitMessage(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        pendingJoinMessages.remove(player.getUniqueId());
        // 完全禁用：无条件清空（暂存项已在上面移除，不会补发）
        if (configManager.quitDisabled()) {
            event.quitMessage(null);
            return;
        }
        if (!authManager.isLoggedIn(player) && configManager.quitHideUnauthenticated()) {
            event.quitMessage(null);
            return;
        }
        Component template = formatTemplate(configManager.quitMessageTemplate(), player);
        if (template != null) {
            event.quitMessage(template);
        }
    }

    /**
     * 登录成功补发：/login 密码、2FA 验证完成、会话/正版免密 autoLogin、pre-join 登录收尾、
     * 管理员强制登录全部经 HSAuthLoginEvent；注册成功（pre-join 注册、游戏内注册）经 HSAuthRegisterEvent。
     * 两事件均在玩家区域线程触发（Folia 线程安全）；MONITOR 只读，不修改事件。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoginSuccess(HSAuthLoginEvent event) {
        broadcastPending(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegisterSuccess(HSAuthRegisterEvent event) {
        // 强制注册离线玩家时 getPlayer() 为 null，此时也无可补发的消息
        if (event.getPlayer() != null) {
            broadcastPending(event.getPlayer());
        }
    }

    private void broadcastPending(Player player) {
        Component message = pendingJoinMessages.remove(player.getUniqueId());
        // 补发前复查禁用开关：暂存可能发生在 reload 开启禁用之前
        if (message != null && !configManager.joinDisabled() && player.isOnline()) {
            Bukkit.getServer().sendMessage(message);
        }
    }

    /** 模板应用：支持 & 颜色代码与 {player}（玩家名）、{displayname}（显示名）占位符；模板空白返回 null 表示保留原版消息 */
    private Component formatTemplate(String template, Player player) {
        if (template == null || template.isBlank()) return null;
        String text = template
                .replace("{player}", player.getName())
                .replace("{displayname}", LegacyComponentSerializer.legacyAmpersand().serialize(player.displayName()));
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
