package org.howsauth.plugin.listener;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.api.event.HSAuthLoginEvent;
import org.howsauth.plugin.api.event.HSAuthRegisterEvent;
import org.howsauth.plugin.auth.AccountLifecycle;
import org.howsauth.plugin.auth.FailProtection;
import org.howsauth.plugin.auth.LoginFlow;
import org.howsauth.plugin.auth.LogoutLocation;
import org.howsauth.plugin.auth.SessionStore;
import org.howsauth.plugin.auth.TwoFactorAuth;

/**
 * 玩家连接/加入/退出事件入口。
 * <p>
 * 具体逻辑已按阶段拆到 {@link PreLoginGuard}（连接准入）、{@link JoinAuthFlow}（进入世界后的认证）、
 * {@link SpawnLocationHandler}（出生位置）与 {@link QuitHandler}（退出两阶段）；本类只保留事件注册与委托。
 */
@SuppressWarnings("UnstableApiUsage")
public final class PlayerListener implements Listener {

    private final PreLoginGuard preLoginGuard;
    private final JoinAuthFlow joinAuthFlow;
    private final SpawnLocationHandler spawnLocationHandler;
    private final QuitHandler quitHandler;

    public PlayerListener(HowSAuth plugin, SessionStore sessions,
                          FailProtection failProtection, TwoFactorAuth twoFactor, LogoutLocation locations,
                          AccountLifecycle accounts, LoginFlow loginFlow, AuthReminder reminders) {
        this.preLoginGuard = new PreLoginGuard(plugin, sessions, failProtection, accounts, locations);
        this.joinAuthFlow = new JoinAuthFlow(plugin, sessions, accounts, twoFactor, locations, loginFlow, reminders);
        this.spawnLocationHandler = new SpawnLocationHandler(plugin, sessions, twoFactor, locations);
        this.quitHandler = new QuitHandler(plugin, sessions, locations, reminders, loginFlow,
                joinAuthFlow::clearLoginBlindness);
    }

    // 在玩家加入世界前拦截：踢出期玩家、同一 IP 账号数量超限
    // 使用 AsyncPlayerPreLoginEvent 替代已弃用的 PlayerLoginEvent（1.21.6+）
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        preLoginGuard.check(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        joinAuthFlow.onJoin(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLoginSuccess(HSAuthLoginEvent event) {
        joinAuthFlow.onLoginSuccess(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegisterSuccess(HSAuthRegisterEvent event) {
        joinAuthFlow.onRegisterSuccess(event);
    }

    /** 挂起玩家等待登录/2FA：旁观保护、提示、提醒与超时。 */
    public void suspend(Player player, String messageKey, boolean needsLogin) {
        joinAuthFlow.suspend(player, messageKey, needsLogin);
    }

    /** 根据账号状态开始登录/注册挂起流程。 */
    public void beginAuthFlow(Player player) {
        joinAuthFlow.beginAuthFlow(player);
    }

    /** 配置热重载后重新挂起所有未登录玩家。 */
    public void refreshPendingPlayers() {
        joinAuthFlow.refreshPendingPlayers();
    }

    /** 重启登录/注册超时任务。 */
    public void scheduleLoginTimeout(Player player, boolean needsLogin) {
        joinAuthFlow.scheduleLoginTimeout(player, needsLogin);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        spawnLocationHandler.handle(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        quitHandler.onQuit(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuitCleanup(PlayerQuitEvent event) {
        quitHandler.onQuitCleanup(event);
    }
}
