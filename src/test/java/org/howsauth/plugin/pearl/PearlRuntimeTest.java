package org.howsauth.plugin.pearl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EnderPearl;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.support.MockBukkitHarness;
import org.howsauth.plugin.support.MockBukkitHarness.TestEnderPearlMock;
import org.howsauth.plugin.support.MockBukkitHarness.TestPlayerMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.UUID;

/**
 * 末影珍珠保管模块的运行时行为：退出吸收、返还（物品/实体两种模式）、离线回填与开关关闭清理。
 * <p>
 * 用 {@link MockBukkitHarness} 组装真实插件/配置链路，并直接调用 {@code onQuit}（不注册监听器），
 * 依赖 harness 补齐的 Player#getEnderPearls、RegionScheduler、isOwnedByCurrentRegion 与实体调度器。
 */
class PearlRuntimeTest {

    private static final double TOLERANCE = 0.01;

    private MockBukkitHarness env;
    private PendingPearlManager manager;
    private boolean managerShutdown;

    @BeforeEach
    void setUp() throws Exception {
        env = MockBukkitHarness.start("hsauth-pearl-runtime-");
        // 珍珠处理器读 plugin.getAuthManager()（resolveOwner/isAuthenticatedOwner 路径），harness 未装配该字段
        MockBukkitHarness.inject(HowSAuth.class, "authManager", env.plugin(), env.auth());
    }

    @AfterEach
    void tearDown() {
        try {
            shutdownManager();
        } finally {
            if (env != null) {
                env.close();
            }
        }
    }

    /** 在线玩家退出后，在飞珍珠被吸收为快照并同步落盘 */
    @Test
    void absorbOnQuitRecordsSnapshotAndPersists() {
        PendingPearlManager manager = newManager("item");
        TestPlayerMock player = env.addScheduledPlayer("pearl-online");
        TestEnderPearlMock pearl = newPearl(10, 64, 10, 0.1, 0.2, 0.3);
        player.trackEnderPearl(pearl);

        manager.onQuit(quitEvent(player));

        assertEquals(1, MockBukkitHarness.mapSize(manager, "pending"),
                "one absorbed snapshot must be stored for the quitting player");
        assertFalse(pearl.isValid(), "the absorbed flying pearl must be removed from the world");
        // onQuit 的 save() 是 100ms 合并异步写，测试无 tick；shutdown 触发同步落盘以验证持久化
        shutdownManager();
        assertTrue(pearlsFile().exists(), "pearls.dat must be written after the record is absorbed");
        assertTrue(loadPearls().contains(player.getUniqueId().toString()),
                "pearls.dat must contain the owning player uuid as a top-level key");
    }

    /** item 模式返还：按快照数量发放末影珍珠物品并清空 pending */
    @Test
    void returnAsItemsGivesPearlsAndClearsPending() {
        PendingPearlManager manager = newManager("item");
        TestPlayerMock player = env.addScheduledPlayer("pearl-items");
        // 先制造 1 条记录，再补一颗使数量=2
        TestEnderPearlMock first = newPearl(1, 64, 1, 0, 0, 0);
        player.trackEnderPearl(first);
        manager.onQuit(quitEvent(player));
        TestEnderPearlMock second = newPearl(2, 64, 2, 0, 0, 0);
        player.trackEnderPearl(second);
        manager.onQuit(quitEvent(player));
        assertEquals(1, MockBukkitHarness.mapSize(manager, "pending"), "both pearls belong to the same player");

        // 记录仍在 pending 中时同步落盘，验证持久化写入了该玩家 uuid 的两条快照
        shutdownManager();
        assertEquals(2, loadPearls().getMapList(player.getUniqueId().toString()).size(),
                "pearls.dat must persist both absorbed snapshots for the player");

        manager.returnPearls(player);

        assertEquals(2, countEnderPearls(player), "item return must give back exactly the snapshot count");
        assertEquals(0, MockBukkitHarness.mapSize(manager, "pending"), "pending must be cleared after returning");
    }

    /** entity 模式返还：按快照的世界/坐标/速度重新生成珍珠，并设置发射者 */
    @Test
    void returnAsEntitiesRespawnsPearlWithShooterAndVelocity() {
        PendingPearlManager manager = newManager("entity");
        TestPlayerMock player = env.addScheduledPlayer("pearl-entity");
        double x = 12.5, y = 70.0, z = -8.25;
        double vx = 0.3, vy = 0.15, vz = -0.45;
        TestEnderPearlMock pearl = newPearl(x, y, z, vx, vy, vz);
        player.trackEnderPearl(pearl);
        manager.onQuit(quitEvent(player));
        assertEquals(1, MockBukkitHarness.mapSize(manager, "pending"), "one absorbed snapshot must be stored");

        manager.returnPearls(player);

        var spawned = player.getWorld().getEntitiesByClass(EnderPearl.class);
        assertEquals(1, spawned.size(), "exactly one pearl must be respawned in the player's world");
        EnderPearl respawned = spawned.iterator().next();
        assertEquals(x, respawned.getLocation().getX(), TOLERANCE, "respawned x must match the snapshot");
        assertEquals(y, respawned.getLocation().getY(), TOLERANCE, "respawned y must match the snapshot");
        assertEquals(z, respawned.getLocation().getZ(), TOLERANCE, "respawned z must match the snapshot");
        assertEquals(vx, respawned.getVelocity().getX(), TOLERANCE, "respawned vx must match the snapshot");
        assertEquals(vy, respawned.getVelocity().getY(), TOLERANCE, "respawned vy must match the snapshot");
        assertEquals(vz, respawned.getVelocity().getZ(), TOLERANCE, "respawned vz must match the snapshot");
        assertEquals(player, respawned.getShooter(), "the returning player must be the respawned shooter");
        assertEquals(0, MockBukkitHarness.mapSize(manager, "pending"), "pending must be cleared after returning");
    }

    /** 离线玩家 entity 模式返还：无法生成实体，快照被回填 pending 并落盘 */
    @Test
    void offlineReturnRequeuesAndPersists() {
        PendingPearlManager manager = newManager("entity");
        // 不加入服务器：isOnline() 为 false，QuitFlowRegressionTest 用的同一手法
        TestPlayerMock offline = new TestPlayerMock(env.server(), "pearl-offline");
        TestEnderPearlMock pearl = newPearl(3, 64, 3, 0, 0, 0);
        offline.trackEnderPearl(pearl);
        manager.onQuit(quitEvent(offline));
        assertEquals(1, MockBukkitHarness.mapSize(manager, "pending"), "one absorbed snapshot must be stored");

        manager.returnPearls(offline);

        assertEquals(1, MockBukkitHarness.mapSize(manager, "pending"),
                "an offline return must requeue the snapshot instead of spawning it");
        assertTrue(loadPearls().contains(offline.getUniqueId().toString()),
                "the requeued snapshot must be persisted for the offline player");
    }

    /** 开关由开转关：refresh 清空内存状态并落盘为空 */
    @Test
    void refreshClearsStateWhenDisabled() {
        PendingPearlManager manager = newManager("item");
        TestPlayerMock player = env.addScheduledPlayer("pearl-disable");
        TestEnderPearlMock pearl = newPearl(4, 64, 4, 0, 0, 0);
        player.trackEnderPearl(pearl);
        manager.onQuit(quitEvent(player));
        assertEquals(1, MockBukkitHarness.mapSize(manager, "pending"), "one absorbed snapshot must be stored");

        MockBukkitHarness.inject(ConfigManager.class, "pearlEnabled", env.config(), false);
        manager.refresh();

        assertEquals(0, MockBukkitHarness.mapSize(manager, "pending"), "disabling must clear pending records");
        assertEquals(0, MockBukkitHarness.mapSize(manager, "handledPearls"), "disabling must clear handled pearls");
        assertFalse(loadPearls().contains(player.getUniqueId().toString()),
                "pearls.dat must no longer contain the player uuid after clearing");
        assertTrue(loadPearls().getKeys(false).isEmpty(), "pearls.dat must contain no player records");
    }

    /** 注入开关与返还模式后构造管理器（构造器会读配置并 refresh） */
    private PendingPearlManager newManager(String returnMode) {
        MockBukkitHarness.inject(ConfigManager.class, "pearlEnabled", env.config(), true);
        MockBukkitHarness.inject(ConfigManager.class, "pearlReturnMode", env.config(), returnMode);
        manager = new PendingPearlManager(env.plugin());
        managerShutdown = false;
        return manager;
    }

    /** 只结束一次：测试内为验证异步落盘可能提前 shutdown，tearDown 不重复调用 */
    private void shutdownManager() {
        if (manager != null && !managerShutdown) {
            managerShutdown = true;
            manager.shutdown();
        }
    }

    private World world() {
        return env.server().getWorlds().getFirst();
    }

    /** 造一颗位于世界内、带给定位置与速度的珍珠（测试自行登记到玩家的在飞列表） */
    private TestEnderPearlMock newPearl(double x, double y, double z, double vx, double vy, double vz) {
        TestEnderPearlMock pearl = new TestEnderPearlMock(env.server(), UUID.randomUUID());
        pearl.setLocation(new Location(world(), x, y, z));
        pearl.setVelocity(new Vector(vx, vy, vz));
        return pearl;
    }

    // PlayerQuitEvent 构造器全部标记为 @ApiStatus.Internal（测试需自行构造事件并直接调用 onQuit），
    // 3 参 Component 版是唯一未被标记待删除的
    @SuppressWarnings("UnstableApiUsage")
    private PlayerQuitEvent quitEvent(TestPlayerMock player) {
        return new PlayerQuitEvent(player,
                Component.text(player.getName() + " left the game"), PlayerQuitEvent.QuitReason.DISCONNECTED);
    }

    private File pearlsFile() {
        return new File(env.plugin().getDataFolder(), "pearls.dat");
    }

    private YamlConfiguration loadPearls() {
        return YamlConfiguration.loadConfiguration(pearlsFile());
    }

    private int countEnderPearls(TestPlayerMock player) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == Material.ENDER_PEARL) {
                total += stack.getAmount();
            }
        }
        return total;
    }
}