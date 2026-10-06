package org.howsauth.plugin;

import net.kyori.adventure.text.Component;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.howsauth.plugin.auth.SessionStore;
import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.listener.JoinQuitMessageService;
import org.howsauth.plugin.listener.PlayerListener;
import org.howsauth.plugin.pearl.PendingPearlManager;
import org.howsauth.plugin.support.MockBukkitHarness;
import org.howsauth.plugin.support.MockBukkitHarness.TestPlayerMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退出流程回归测试：退出消息与退出位置保存依据"本次连接是否认证过"，
 * 而非"退出瞬间是否仍处于登录态"——强制登出/注销会在连接结束前失效后者。
 * <p>
 * 事件分发走 Bukkit 原生 HandlerList（MockBukkit 的注册与分发均委托 Paper 实现），
 * 因此退出流程 LOWEST（存位置/清失明）→ HIGH（消息决策）→ MONITOR（会话清理）的顺序与真实服务端一致。
 */
class QuitFlowRegressionTest {

    private MockBukkitHarness env;
    private HowSAuth plugin;
    private SessionStore sessions;

    @BeforeEach
    void setUp() throws Exception {
        env = MockBukkitHarness.start("hsauth-quit-flow-", config ->
                MockBukkitHarness.inject(ConfigManager.class, "bcryptCost", config, 4));
        plugin = env.plugin();
        sessions = env.sessions();
        // 监听器与登录收尾依赖的插件字段由 onEnable 装配，测试不触发 onEnable 需按同样次序注入
        // （退出阶段经 plugin.playerFiles() 删原版数据，故 authManager 字段必须先就位）
        MockBukkitHarness.inject(HowSAuth.class, "authManager", plugin, env.auth());
        PlayerListener playerListener = new PlayerListener(plugin, sessions, env.failProtection(), env.twoFactor(), env.locations(), env.accounts(), env.loginFlow(), env.authReminder());
        MockBukkitHarness.inject(HowSAuth.class, "playerListener", plugin, playerListener);
        MockBukkitHarness.inject(HowSAuth.class, "pendingPearlManager", plugin, new PendingPearlManager(plugin));
        // MockBukkit 分发时会跳过未启用插件的监听器：只置启用标记，不触发 onEnable
        MockBukkitHarness.inject(JavaPlugin.class, "isEnabled", plugin, true);
        env.server().getPluginManager().registerEvents(playerListener, plugin);
        env.server().getPluginManager().registerEvents(new JoinQuitMessageService(sessions, env.config()), plugin);
    }

    @AfterEach
    void tearDown() {
        // 复位启用标记：避免关闭环境时 MockBukkit 触发真实 onDisable（其外部依赖在 mock 下不可用）
        MockBukkitHarness.inject(JavaPlugin.class, "isEnabled", plugin, false);
        env.close();
    }

    /** 会话认证标记只在退出清理时失效：强制登出与注销都不得清除它 */
    @Test
    void sessionAuthFlagSurvivesForcedInvalidation() {
        TestPlayerMock player = newPlayer("flaguser");
        UUID uuid = player.getUniqueId();
        assertTrue(env.accounts().forceRegister(uuid, player.getName(), "test-pw"), "force register must create the account");

        env.loginFlow().forceLogin(player);
        assertTrue(sessions.isLoggedIn(uuid), "force login must set the live login state");
        assertTrue(sessions.hasAuthenticatedThisConnection(uuid), "force login must mark the connection authenticated");

        assertTrue(env.loginFlow().forceLogout(uuid), "force logout must invalidate the live login state");
        assertFalse(sessions.isLoggedIn(uuid), "force logout must clear the live login state");
        assertTrue(sessions.hasAuthenticatedThisConnection(uuid), "force logout must keep the session authentication flag");

        assertTrue(env.accounts().unregister(uuid), "unregister must remove the account");
        assertTrue(sessions.hasAuthenticatedThisConnection(uuid), "unregister must keep the session authentication flag");

        env.loginFlow().clearSession(player);
        assertFalse(sessions.isLoggedIn(uuid), "session cleanup must clear the live login state");
        assertFalse(sessions.hasAuthenticatedThisConnection(uuid), "session cleanup must clear the session authentication flag");
    }

    /** 退出消息：仅"本次连接未认证"按配置隐藏，认证过的会话即使登录态被提前失效也要保留消息 */
    @Test
    void quitMessageFollowsSessionAuthentication() {
        TestPlayerMock player = newPlayer("quituser");
        UUID uuid = player.getUniqueId();
        assertTrue(env.accounts().forceRegister(uuid, player.getName(), "test-pw"), "force register must create the account");

        // 未认证退出：消息隐藏（不暴露卡在登录界面的玩家）
        assertNull(fireQuit(player).quitMessage(), "an unauthenticated quit must have its message hidden");

        // 已认证退出：消息保留（回归：会话清理先于消息决策执行时，已认证玩家会被误判为未认证）
        env.loginFlow().forceLogin(player);
        assertNotNull(fireQuit(player).quitMessage(), "an authenticated quit must keep its message");

        // 已认证但实时登录态被强制登出提前失效（管理员/注销踢出前后的退出事件）：仍按已认证处理
        env.loginFlow().forceLogin(player);
        assertTrue(env.loginFlow().forceLogout(uuid), "force logout must invalidate the live login state");
        assertNotNull(fireQuit(player).quitMessage(), "a force-logged-out quit must still count as authenticated");
    }

    /** 退出位置：实时登录态被强制失效的退出同样要保存，不能被当作未认证而跳过 */
    @Test
    void logoutLocationIsSavedForForcedLogoutQuit() {
        TestPlayerMock player = newPlayer("kickeduser");
        UUID uuid = player.getUniqueId();
        assertTrue(env.accounts().forceRegister(uuid, player.getName(), "test-pw"), "force register must create the account");
        env.loginFlow().forceLogin(player);
        assertTrue(env.loginFlow().forceLogout(uuid), "force logout must invalidate the live login state");

        fireQuit(player);
        assertNotNull(env.data().getPlayer(uuid).logoutLocation(),
                "a force-logged-out quit must still save the logout location");
    }

    /**
     * 构造玩家但不加入服务器：退出流程的断言不依赖在线列表，
     * 且 MockBukkit 的 addPlayer 会异步派发前置登录事件并同步等锁，注册监听器后在该环境下等不到回调
     */
    private TestPlayerMock newPlayer(String name) {
        return new TestPlayerMock(env.server(), name);
    }

    /** 抛出一个带原版消息的退出事件，返回事件供断言最终消息 */
    // PlayerQuitEvent 的构造器全部标记为 @ApiStatus.Internal（测试需自行构造事件），
    // 3 参 Component 版是唯一未被标记待删除的，另两个 2 参版本已 @Deprecated(forRemoval = true)
    @SuppressWarnings("UnstableApiUsage")
    private PlayerQuitEvent fireQuit(TestPlayerMock player) {
        PlayerQuitEvent event = new PlayerQuitEvent(player,
                Component.text(player.getName() + " left the game"), PlayerQuitEvent.QuitReason.DISCONNECTED);
        env.server().getPluginManager().callEvent(event);
        return event;
    }
}