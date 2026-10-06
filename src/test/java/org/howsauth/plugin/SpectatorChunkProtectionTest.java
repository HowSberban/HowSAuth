package org.howsauth.plugin;

import org.bukkit.Location;
import org.bukkit.World;
import org.howsauth.plugin.config.PasswordConfig;
import org.howsauth.plugin.config.SettingsConfig;
import org.howsauth.plugin.data.PlayerData;
import org.howsauth.plugin.support.MockBukkitHarness;
import org.howsauth.plugin.support.MockBukkitHarness.TestPlayerMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退出位置区块加载与悬空判定回归：
 * 1. 区块未加载时悬空判定不得等待加载，按"悬空"保守处理 → 强制旁观；
 * 2. preloadLogoutChunk 为异步 fire-and-forget 预载，在需要判定的配置下确实把区块标记为已加载（非空操作）；
 * 3. 以下三种情况判定被跳过，预载随之跳过：坐标保护开启、旁观强制开启、advanced.dangling-check 关闭（默认）；
 * 4. advanced.dangling-check 关闭时 setSpectator 整段跳过（不切旁观、不改游戏模式、不读区块）；
 *    开启时恢复原判定：区块未加载按悬空处理 → 切旁观。
 */
class SpectatorChunkProtectionTest {

    /** 用例统一使用的退出高度（地面层），使"区块是否加载"成为唯一变量 */
    private static final int GROUND_Y = 64;

    private MockBukkitHarness env;
    private World world;

    @BeforeEach
    void setUp() throws Exception {
        env = MockBukkitHarness.start("hsauth-spectator-chunk-", config ->
                MockBukkitHarness.inject(PasswordConfig.class, "bcryptCost", config.password(), 4));
        world = env.server().getWorld("world");
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    /** 区块未加载 → 视为悬空 → 切旁观，且判定过程不加载区块（无等待） */
    @Test
    void unloadedChunkIsTreatedAsDanglingAndForcesSpectator() {
        TestPlayerMock player = newPlayer("danglinguser");
        enableDanglingCheck();
        Location logout = registerAtUnloadedChunk(player, 100, 200);
        int cx = logout.getBlockX() >> 4;
        int cz = logout.getBlockZ() >> 4;

        assertFalse(world.isChunkLoaded(cx, cz), "the chosen chunk must start unloaded");
        assertEquals(0, MockBukkitHarness.collectionSize(env.sessions(), "spectatorPending"),
                "no spectator flag before the decision");

        env.locations().setSpectator(player);

        assertEquals(1, MockBukkitHarness.collectionSize(env.sessions(), "spectatorPending"),
                "an unloaded chunk must be treated as dangling and force spectator");
        assertFalse(world.isChunkLoaded(cx, cz),
                "the dangling decision must not load the chunk (no waiting)");
    }

    /** 预载可观测：开启悬空判定时 preloadLogoutChunk 之后区块由未加载翻为已加载，证明不是空操作 */
    @Test
    void preloadLogoutChunkActuallyLoadsTheChunk() {
        TestPlayerMock player = newPlayer("preloaduser");
        enableDanglingCheck();
        Location logout = registerAtUnloadedChunk(player, 300, 400);
        int cx = logout.getBlockX() >> 4;
        int cz = logout.getBlockZ() >> 4;

        assertFalse(world.isChunkLoaded(cx, cz), "the chosen chunk must start unloaded");
        env.locations().preloadChunk(player.getUniqueId());
        assertTrue(world.isChunkLoaded(cx, cz), "preload must actually mark the chunk loaded");
    }

    /** 坐标保护开启时预载跳过：该配置下不做悬空判定，无需触发加载 */
    @Test
    void preloadIsSkippedWhenPositionProtectionEnabled() {
        TestPlayerMock player = newPlayer("protecteduser");
        Location logout = registerAtUnloadedChunk(player, 500, 600);
        int cx = logout.getBlockX() >> 4;
        int cz = logout.getBlockZ() >> 4;

        enablePositionProtection();
        env.locations().preloadChunk(player.getUniqueId());
        assertFalse(world.isChunkLoaded(cx, cz), "position protection must skip preloading");
    }

    /** advanced.dangling-check 关闭（默认）：整段判定跳过，不切旁观、不读取区块、不注册待恢复标记 */
    @Test
    void danglingCheckDisabledSkipsSpectatorHandlingEntirely() {
        TestPlayerMock player = newPlayer("skipuser");
        Location logout = registerAtUnloadedChunk(player, 700, 800);
        int cx = logout.getBlockX() >> 4;
        int cz = logout.getBlockZ() >> 4;
        org.bukkit.GameMode before = player.getGameMode();

        assertFalse(env.config().settings().danglingCheck(), "dangling-check must default to false");
        assertFalse(world.isChunkLoaded(cx, cz), "the chosen chunk must start unloaded");

        env.locations().setSpectator(player);

        assertEquals(0, MockBukkitHarness.collectionSize(env.sessions(), "spectatorPending"),
                "a disabled dangling check must not force spectator");
        assertEquals(before, player.getGameMode(), "game mode must stay untouched");
        assertFalse(world.isChunkLoaded(cx, cz), "a disabled dangling check must not load the chunk");
    }

    /** advanced.dangling-check 开启：恢复判定逻辑，区块未加载按悬空处理 → 切旁观 */
    @Test
    void enablingDanglingCheckRestoresTheDecision() {
        TestPlayerMock player = newPlayer("checkuser");
        enableDanglingCheck();
        Location logout = registerAtUnloadedChunk(player, 1100, 1200);
        int cx = logout.getBlockX() >> 4;
        int cz = logout.getBlockZ() >> 4;

        assertFalse(world.isChunkLoaded(cx, cz), "the chosen chunk must start unloaded");
        env.locations().setSpectator(player);

        assertEquals(1, MockBukkitHarness.collectionSize(env.sessions(), "spectatorPending"),
                "with the check enabled, a dangling logout position forces spectator");
        assertFalse(world.isChunkLoaded(cx, cz),
                "the dangling decision must not load the chunk (no waiting)");
    }

    /** advanced.dangling-check 关闭时预载无意义：判定不执行，不得触发区块加载 */
    @Test
    void preloadIsSkippedWhenDanglingCheckDisabled() {
        TestPlayerMock player = newPlayer("nopreloaduser");
        Location logout = registerAtUnloadedChunk(player, 900, 1000);
        int cx = logout.getBlockX() >> 4;
        int cz = logout.getBlockZ() >> 4;

        assertFalse(world.isChunkLoaded(cx, cz), "the chosen chunk must start unloaded");
        env.locations().preloadChunk(player.getUniqueId());
        assertFalse(world.isChunkLoaded(cx, cz),
                "no dangling check at join means preloading the logout chunk is pointless");
    }

    /** 构造未加入服务器的玩家：断言不依赖在线列表，避免 addPlayer 卡在异步前置登录事件 */
    private TestPlayerMock newPlayer(String name) {
        return new TestPlayerMock(env.server(), name);
    }

    /** 开启 advanced.dangling-check（恢复判定逻辑）：断言必须在建号与写库之前完成 */
    private void enableDanglingCheck() {
        MockBukkitHarness.inject(SettingsConfig.class, "danglingCheck", env.config().settings(), true);
    }

    /**
     * 开启 {@code protection.pos.enabled}：该键已迁入 ProtectionPosition 配置对象，
     * 故注入目标是 ConfigManager 持有的子对象，而不是 ConfigManager 自身字段。
     */
    private void enablePositionProtection() {
        Object position = env.config().protectionPosition();
        MockBukkitHarness.inject(position.getClass(), "enabled", position, true);
    }

    /** 建号并写入位于测试世界内、指定区块的退出位置，返回该位置 */
    private Location registerAtUnloadedChunk(TestPlayerMock player, int chunkX, int chunkZ) {
        UUID uuid = player.getUniqueId();
        assertTrue(env.accounts().forceRegister(uuid, player.getName(), "test-pw"),
                "force register must create the account");
        Location logout = new Location(world, chunkX * 16 + 8, GROUND_Y, chunkZ * 16 + 8);
        env.data().getPlayer(uuid).logoutLocation(PlayerData.serializeLocation(logout));
        return logout;
    }
}