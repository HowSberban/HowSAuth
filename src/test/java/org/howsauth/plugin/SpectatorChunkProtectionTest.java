package org.howsauth.plugin;

import org.bukkit.Location;
import org.bukkit.World;
import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.data.PlayerDataManager;
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
 * 2. preloadLogoutChunk 为异步 fire-and-forget 预载，确实把区块标记为已加载（非空操作）；
 * 3. 坐标保护开启时预载直接跳过（该配置下 setSpectator 不做悬空判定）。
 */
class SpectatorChunkProtectionTest {

    private MockBukkitHarness env;
    private World world;

    @BeforeEach
    void setUp() throws Exception {
        env = MockBukkitHarness.start("hsauth-spectator-chunk-", config ->
                MockBukkitHarness.inject(ConfigManager.class, "bcryptCost", config, 4));
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
        Location logout = registerAtUnloadedChunk(player, 100, 200, 64);
        int cx = logout.getBlockX() >> 4;
        int cz = logout.getBlockZ() >> 4;

        assertFalse(world.isChunkLoaded(cx, cz), "the chosen chunk must start unloaded");
        assertEquals(0, MockBukkitHarness.collectionSize(env.auth(), "spectatorPending"),
                "no spectator flag before the decision");

        env.auth().setSpectator(player);

        assertEquals(1, MockBukkitHarness.collectionSize(env.auth(), "spectatorPending"),
                "an unloaded chunk must be treated as dangling and force spectator");
        assertFalse(world.isChunkLoaded(cx, cz),
                "the dangling decision must not load the chunk (no waiting)");
    }

    /** 预载可观测：preloadLogoutChunk 之后区块由未加载翻为已加载，证明不是空操作 */
    @Test
    void preloadLogoutChunkActuallyLoadsTheChunk() {
        TestPlayerMock player = newPlayer("preloaduser");
        Location logout = registerAtUnloadedChunk(player, 300, 400, 64);
        int cx = logout.getBlockX() >> 4;
        int cz = logout.getBlockZ() >> 4;

        assertFalse(world.isChunkLoaded(cx, cz), "the chosen chunk must start unloaded");
        env.auth().preloadLogoutChunk(player.getUniqueId());
        assertTrue(world.isChunkLoaded(cx, cz), "preload must actually mark the chunk loaded");
    }

    /** 坐标保护开启时预载跳过：该配置下不做悬空判定，无需触发加载 */
    @Test
    void preloadIsSkippedWhenPositionProtectionEnabled() {
        TestPlayerMock player = newPlayer("protecteduser");
        Location logout = registerAtUnloadedChunk(player, 500, 600, 64);
        int cx = logout.getBlockX() >> 4;
        int cz = logout.getBlockZ() >> 4;

        MockBukkitHarness.inject(ConfigManager.class, "protectionPosEnabled", env.config(), true);
        env.auth().preloadLogoutChunk(player.getUniqueId());
        assertFalse(world.isChunkLoaded(cx, cz), "position protection must skip preloading");
    }

    /** 构造未加入服务器的玩家：断言不依赖在线列表，避免 addPlayer 卡在异步前置登录事件 */
    private TestPlayerMock newPlayer(String name) {
        return new TestPlayerMock(env.server(), name);
    }

    /** 建号并写入位于测试世界内、指定区块的退出位置，返回该位置 */
    private Location registerAtUnloadedChunk(TestPlayerMock player, int chunkX, int chunkZ, int y) {
        UUID uuid = player.getUniqueId();
        assertTrue(env.auth().forceRegister(uuid, player.getName(), "test-pw"),
                "force register must create the account");
        Location logout = new Location(world, chunkX * 16 + 8, y, chunkZ * 16 + 8);
        env.data().getPlayer(uuid).logoutLocation(PlayerDataManager.serializeLocation(logout));
        return logout;
    }
}