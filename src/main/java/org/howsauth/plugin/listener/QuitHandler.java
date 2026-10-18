package org.howsauth.plugin.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.auth.LoginFlow;
import org.howsauth.plugin.auth.LogoutLocation;
import org.howsauth.plugin.auth.SessionStore;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 玩家退出两阶段处理：先保存认证相关数据，最后清理会话状态。
 * 退出事件的分层顺序是业务约束，独立成类避免与加入流程互相影响。
 */
final class QuitHandler {

    private final HowSAuth plugin;
    private final SessionStore sessions;
    private final LogoutLocation locations;
    private final AuthReminder reminders;
    private final LoginFlow loginFlow;
    private final Consumer<Player> clearLoginBlindness;

    QuitHandler(HowSAuth plugin, SessionStore sessions, LogoutLocation locations,
                AuthReminder reminders, LoginFlow loginFlow, Consumer<Player> clearLoginBlindness) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.locations = locations;
        this.reminders = reminders;
        this.loginFlow = loginFlow;
        this.clearLoginBlindness = clearLoginBlindness;
    }

    /**
     * 玩家退出阶段一（LOWEST，最先执行）：处理依赖认证结果的业务并保存退出数据。
     * 认证判定用 SessionStore#hasAuthenticatedThisConnection（与 isLoggedIn 的差异见该方法注释），
     * 会话状态清理在阶段二（onQuitCleanup），须晚于本阶段的判定
     */
    void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        boolean authenticated = sessions.hasAuthenticatedThisConnection(uuid);
        if (Debug.on()) {
            Debug.log("flow", "quit %s: authenticatedThisConnection=%s loggedIn=%s", player.getName(),
                    authenticated, sessions.isLoggedIn(player));
        }
        // 本次连接已认证的玩家退出时保存退出位置（用于下次登录后传送回来）
        // 未认证玩家不保存：其位置是登录前的保护/出生点，写入会覆盖真实退出位置
        if (authenticated) {
            locations.save(player);
        }
        // 退出时不在登录态（从未认证，或登录态被强制登出/注销提前失效）：清理可能残留的登录失明，
        // 避免效果随 .dat 存档到下次会话（与位置保存是两个独立判定，不共用条件）
        if (!sessions.isLoggedIn(player)) {
            clearLoginBlindness.accept(player);
        }
        // 立即清理提醒 BossBar：玩家调度器随退出 retired，任务内的清理分支不再执行
        reminders.hide(player);
        // 清理提醒任务引用（任务随玩家调度器 retired 不再执行，防止 Map 残留）
        reminders.cancelTask(player.getUniqueId());
        // 注销玩家退出时删除原版 .dat（服务器已保存并释放文件锁）
        plugin.playerFiles().deleteOnQuit(player.getUniqueId());
    }

    /**
     * 玩家退出阶段二（MONITOR，最后执行）：清理会话状态。
     * 退出监听按「保存数据（LOWEST）→ 消息决策（HIGH）→ 状态清理（MONITOR）」分层，
     * 本阶段须保持在最后：加入/退出消息与退出位置保存依据本次连接认证标记判定，
     * 提前清理会让本次已认证的玩家被误判为未认证
     */
    void onQuitCleanup(PlayerQuitEvent event) {
        if (Debug.on()) {
            Debug.log("flow", "quit cleanup for %s: clearing session state (MONITOR, after message decision)", event.getPlayer().getName());
        }
        loginFlow.clearSession(event.getPlayer());
    }
}
