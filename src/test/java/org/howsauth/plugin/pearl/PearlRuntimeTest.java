package org.howsauth.plugin.pearl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EnderPearl;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.config.PearlConfig;
import org.howsauth.plugin.support.MockBukkitHarness;
import org.howsauth.plugin.support.MockBukkitHarness.TestEnderPearlMock;
import org.howsauth.plugin.support.MockBukkitHarness.TestPlayerMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 末影珍珠保管模块的运行时行为：退出吸收、返还（物品/实体两种模式）、离线回填与开关关闭清理。
 * <p>
 * 用 {@link MockBukkitHarness} 组装真实插件/配置链路，并直接调用事件处理器（不注册监听器），
 * 依赖 harness 补齐的 Player#getEnderPearls、RegionScheduler、isOwnedByCurrentRegion 与实体调度器。
 * <p>
 * {@code EntityAddToWorldEvent}、{@code EntityRemoveFromWorldEvent}、{@code PlayerTeleportEvent}
 * 与 {@code PlayerQuitEvent} 的构造器标记为 {@code @ApiStatus.Internal}；测试必须自行构造事件，
 * 故在类级别抑制该不稳定 API 警告。
 */
@SuppressWarnings("UnstableApiUsage")
class PearlRuntimeTest {

    private static final double TOLERANCE = 0.01;

    private MockBukkitHarness env;
    private PendingPearlManager manager;
    private boolean managerShutdown;

    @BeforeEach
    void setUp() throws Exception {
        env = MockBukkitHarness.start("hsauth-pearl-runtime-");
        // 珍珠处理器读 plugin.auth()（resolveOwner/isAuthenticatedOwner 路径），harness 未装配该字段
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

        assertEquals(1, storeMapSize(manager, "pending"),
                "one absorbed snapshot must be stored for the quitting player");
        assertFalse(pearl.isValid(), "the absorbed flying pearl must be removed from the world");
        assertTrue(pearlsFile(player.getUniqueId()).exists(),
                "onQuit must write the player's binary record before returning");
        assertEquals(1, loadPearls(player.getUniqueId()).size(),
                "the binary record must contain the absorbed snapshot");
        shutdownManager();
    }

    /** item 模式返还：按快照数量发放末影珍珠物品并清空 pending */
    @Test
    void returnAsItemsGivesPearlsAndClearsPending() {
        PendingPearlManager manager = newManager("item");
        TestPlayerMock player = env.addScheduledPlayer("pearl-items");
        player.trackEnderPearl(newPearl(1, 64, 1, 0, 0, 0));
        player.trackEnderPearl(newPearl(2, 64, 2, 0, 0, 0));
        manager.onQuit(quitEvent(player));
        assertEquals(1, storeMapSize(manager, "pending"), "both pearls belong to the same player");

        // 记录仍在 pending 中时同步落盘，验证持久化写入了该玩家的两条快照
        shutdownManager();
        assertEquals(2, loadPearls(player.getUniqueId()).size(),
                "the binary record must persist both absorbed snapshots for the player");

        manager.returnPearls(player);

        assertEquals(2, countEnderPearls(player), "item return must give back exactly the snapshot count");
        assertEquals(0, storeMapSize(manager, "pending"), "pending must be cleared after returning");
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
        assertEquals(1, storeMapSize(manager, "pending"), "one absorbed snapshot must be stored");

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
        assertEquals(0, storeMapSize(manager, "pending"), "pending must be cleared after returning");
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
        assertEquals(1, storeMapSize(manager, "pending"), "one absorbed snapshot must be stored");

        manager.returnPearls(offline);

        assertEquals(1, storeMapSize(manager, "pending"),
                "an offline return must requeue the snapshot instead of spawning it");
        assertEquals(1, loadPearls(offline.getUniqueId()).size(),
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
        assertEquals(1, storeMapSize(manager, "pending"), "one absorbed snapshot must be stored");

        MockBukkitHarness.inject(PearlConfig.class, "enabled", env.config().pearl(), false);
        manager.refresh();

        assertEquals(0, storeMapSize(manager, "pending"), "disabling must clear pending records");
        assertEquals(0, storeMapSize(manager, "handledPearls"), "disabling must clear handled pearls");
        assertFalse(pearlsFile(player.getUniqueId()).exists(),
                "the player's binary record must be deleted after clearing");
        assertEquals(0, recordFileCount(), "the pearls directory must contain no player records");
    }

    /** 返还开关关闭时，登录收尾仍须丢弃上次退出留下的待返还快照 */
    @Test
    void disabledPearlReturnDropsStalePendingSnapshotsOnLogin() {
        PendingPearlManager manager = newManager("item");
        TestPlayerMock player = env.addScheduledPlayer("pearl-disabled-return");
        TestEnderPearlMock pearl = newPearl(5, 64, 5, 0, 0, 0);
        player.trackEnderPearl(pearl);
        manager.onQuit(quitEvent(player));
        assertEquals(1, storeMapSize(manager, "pending"), "precondition: quit stored one pearl snapshot");

        // 模拟珍珠刚被关闭但 refresh 尚未完成/未被调用的登录竞态
        MockBukkitHarness.inject(PearlConfig.class, "enabled", env.config().pearl(), false);
        manager.returnPearls(player);

        assertEquals(0, storeMapSize(manager, "pending"),
                "disabled pearl recovery must discard stale snapshots for the logging-in player");
        assertFalse(pearlsFile(player.getUniqueId()).exists(),
                "discarded snapshots must not reappear after a later restart or re-enable");
        assertEquals(0, countEnderPearls(player), "disabled recovery must not give pearl items");
    }

    /** 未登录玩家的珍珠传送被取消，并记一条 credit 供登录后返还 */
    @Test
    void pearlTeleportForUnauthenticatedPlayerIsCancelledAndCredited() {
        PendingPearlManager manager = newManager("item");
        TestPlayerMock player = env.addScheduledPlayer("pearl-teleport");
        PlayerTeleportEvent event = pearlTeleport(player, 8, 64, 8);

        manager.onPearlTeleport(event);
        // 同一颗珍珠重复触发传送事件时，不应重复补 credit
        manager.onPearlTeleport(event);

        assertTrue(event.isCancelled(), "an unauthenticated pearl teleport must be cancelled");
        assertEquals(1, storeSnapshotCount(manager, player.getUniqueId()),
                "the cancelled teleport must leave exactly one credit snapshot");
    }

    /** 已登录玩家的珍珠传送不受干预，也不写 credit */
    @Test
    void pearlTeleportForAuthenticatedPlayerIsLeftAlone() throws Exception {
        PendingPearlManager manager = newManager("item");
        TestPlayerMock player = env.addScheduledPlayer("pearl-teleport-auth");
        markLoggedIn(player.getUniqueId());
        PlayerTeleportEvent event = pearlTeleport(player, 8, 64, 8);

        manager.onPearlTeleport(event);

        assertFalse(event.isCancelled(), "an authenticated player's pearl teleport must be allowed");
        assertEquals(0, storeMapSize(manager, "pending"),
                "no credit must be stored for an authenticated player");
    }

    /** credit 与实际珍珠移除不得重复计数：一次投掷只能对应一颗返还珍珠 */
    @Test
    void creditAndRealPearlRemovalDoNotDoubleCount() {
        PendingPearlManager manager = newManager("item");
        TestPlayerMock player = env.addScheduledPlayer("pearl-credit-dup");
        PlayerTeleportEvent teleport = pearlTeleport(player, 8, 64, 8);
        manager.onPearlTeleport(teleport);
        assertEquals(1, storeMapSize(manager, "pending"), "precondition: the teleport credit is stored");

        TestEnderPearlMock pearl = newOwnedPearl(player, 8, 64, 8, 0, 0, 0);
        manager.onEntityRemoveFromWorld(new EntityRemoveFromWorldEvent(pearl, world()));

        assertEquals(1, storeSnapshotCount(manager, player.getUniqueId()),
                "one thrown pearl must yield exactly one snapshot even if both events are observed");
    }

    /** 相反事件顺序：真实珍珠先被吸收，随后的传送事件不得再补 credit */
    @Test
    void realPearlRemovalBeforeTeleportDoesNotDoubleCount() {
        PendingPearlManager manager = newManager("item");
        TestPlayerMock player = env.addScheduledPlayer("pearl-credit-dup-reverse");
        TestEnderPearlMock pearl = newOwnedPearl(player, 8, 64, 8, 0, 0, 0);
        manager.onEntityRemoveFromWorld(new EntityRemoveFromWorldEvent(pearl, world()));
        assertEquals(1, storeSnapshotCount(manager, player.getUniqueId()),
                "precondition: the real pearl was absorbed first");

        PlayerTeleportEvent teleport = pearlTeleport(player, 8, 64, 8);
        manager.onPearlTeleport(teleport);

        assertEquals(1, storeSnapshotCount(manager, player.getUniqueId()),
                "the teleport event must not add a credit when the real pearl is already stored");
    }

    /** 未登录玩家的珍珠进入世界：被吸收并移除 */
    @Test
    void entityAddForUnauthenticatedPlayerAbsorbsPearl() {
        PendingPearlManager manager = newManager("item");
        TestPlayerMock player = env.addScheduledPlayer("pearl-add");
        TestEnderPearlMock pearl = newOwnedPearl(player, 6, 64, 6, 0, 0, 0);

        manager.onEntityAddToWorld(new EntityAddToWorldEvent(pearl, world()));

        assertFalse(pearl.isValid(), "an unauthenticated player's pearl must be removed from the world");
        assertEquals(1, storeMapSize(manager, "pending"), "the pearl must be stored for later return");
    }

    /** 已登录玩家的珍珠进入世界：不干预 */
    @Test
    void entityAddForAuthenticatedPlayerLeavesPearlAlone() throws Exception {
        PendingPearlManager manager = newManager("item");
        TestPlayerMock player = env.addScheduledPlayer("pearl-add-auth");
        markLoggedIn(player.getUniqueId());
        TestEnderPearlMock pearl = newOwnedPearl(player, 6, 64, 6, 0, 0, 0);

        manager.onEntityAddToWorld(new EntityAddToWorldEvent(pearl, world()));

        assertTrue(pearl.isValid(), "an authenticated player's pearl must be left alone");
        assertEquals(0, storeMapSize(manager, "pending"), "no snapshot must be stored for an authenticated player");
    }

    /** 关闭状态下，未登录玩家的珍珠仍被清理但不再入库 */
    @Test
    void entityAddWhenDisabledRemovesUnauthenticatedPearl() {
        PendingPearlManager manager = newManager("item");
        TestPlayerMock player = env.addScheduledPlayer("pearl-add-disabled");
        TestEnderPearlMock pearl = newOwnedPearl(player, 7, 64, 7, 0, 0, 0);
        MockBukkitHarness.inject(PearlConfig.class, "enabled", env.config().pearl(), false);

        manager.onEntityAddToWorld(new EntityAddToWorldEvent(pearl, world()));

        assertFalse(pearl.isValid(), "a disabled feature must still remove unauthenticated pearls");
        assertEquals(0, storeMapSize(manager, "pending"), "a disabled feature must not store new snapshots");
    }

    /** 已标记 handled 的珍珠移除事件不得再次入库 */
    @Test
    void handledPearlRemovalIsNotAbsorbedAgain() {
        PendingPearlManager manager = newManager("item");
        TestPlayerMock player = env.addScheduledPlayer("pearl-handled");
        TestEnderPearlMock pearl = newOwnedPearl(player, 9, 64, 9, 0, 0, 0);
        storeMarkHandled(manager, pearl.getUniqueId());

        manager.onEntityRemoveFromWorld(new EntityRemoveFromWorldEvent(pearl, world()));

        assertEquals(0, storeMapSize(manager, "pending"), "a handled removal must not be stored again");
    }

    /** 记录写入磁盘后，新实例（模拟重启）应能重新载入同一批快照 */
    @Test
    void recordSurvivesRestart() {
        PendingPearlManager first = newManager("item");
        TestPlayerMock player = env.addScheduledPlayer("pearl-restart");
        player.trackEnderPearl(newPearl(11, 64, 11, 0.1, 0.2, 0.3));
        first.onQuit(quitEvent(player));
        assertEquals(1, storeSnapshotCount(first, player.getUniqueId()), "precondition: one snapshot stored");
        shutdownManager();

        PendingPearlManager reloaded = newManager("item");

        assertEquals(1, storeSnapshotCount(reloaded, player.getUniqueId()),
                "a new store instance must reload the persisted snapshot");
    }

    /** 旧版 pearls.dat 启动时迁移为二进制记录，并把原文件改名为 .migrated */
    @Test
    void migratesLegacyYamlFile() throws Exception {
        TestPlayerMock player = env.addScheduledPlayer("pearl-legacy");
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("world", world().getName());
        snapshot.put("x", 1.5);
        snapshot.put("y", 64.0);
        snapshot.put("z", -2.5);
        snapshot.put("vx", 0.1);
        snapshot.put("vy", 0.2);
        snapshot.put("vz", 0.3);
        YamlConfiguration legacy = new YamlConfiguration();
        legacy.set(player.getUniqueId().toString(), List.of(snapshot));
        File legacyFile = new File(env.plugin().getDataFolder(), "pearls.dat");
        legacy.save(legacyFile);

        PendingPearlManager manager = newManager("item");

        assertEquals(1, storeSnapshotCount(manager, player.getUniqueId()),
                "the legacy record must be imported into memory");
        assertEquals(1, loadPearls(player.getUniqueId()).size(),
                "the legacy record must be rewritten as a binary player record");
        assertFalse(legacyFile.exists(), "the legacy YAML file must be moved aside after a successful import");
        assertTrue(new File(env.plugin().getDataFolder(), "pearls.dat.migrated").exists(),
                "the legacy YAML file must be preserved as a backup");
    }

    /** 旧版文件里的非法条目应被跳过，合法玩家的记录仍要迁移成功 */
    @Test
    void migrationSkipsInvalidLegacyEntries() throws Exception {
        TestPlayerMock validPlayer = env.addScheduledPlayer("pearl-legacy-valid");
        UUID invalidOwner = UUID.randomUUID();
        Map<String, Object> validSnapshot = new LinkedHashMap<>();
        validSnapshot.put("world", world().getName());
        validSnapshot.put("x", 1.0);
        validSnapshot.put("y", 64.0);
        validSnapshot.put("z", 1.0);
        Map<String, Object> invalidSnapshot = new LinkedHashMap<>();
        invalidSnapshot.put("world", world().getName());
        invalidSnapshot.put("x", Double.NaN);
        YamlConfiguration legacy = new YamlConfiguration();
        legacy.set(validPlayer.getUniqueId().toString(), List.of(validSnapshot));
        legacy.set(invalidOwner.toString(), List.of(invalidSnapshot));
        File legacyFile = new File(env.plugin().getDataFolder(), "pearls.dat");
        legacy.save(legacyFile);

        PendingPearlManager manager = newManager("item");

        assertEquals(1, storeSnapshotCount(manager, validPlayer.getUniqueId()),
                "the valid legacy player must be migrated");
        assertEquals(0, storeSnapshotCount(manager, invalidOwner),
                "an invalid legacy snapshot must not enter memory");
        assertTrue(pearlsFile(validPlayer.getUniqueId()).exists(),
                "the valid legacy player must have a binary record");
        assertFalse(pearlsFile(invalidOwner).exists(),
                "an invalid legacy player must not get a binary record");
    }

    /** 损坏的记录文件应被隔离，而不是让启动失败或静默当成有效坐标 */
    @Test
    void corruptRecordIsQuarantined() throws Exception {
        UUID owner = UUID.randomUUID();
        Files.createDirectories(pearlsDirectory().toPath());
        Files.write(pearlsFile(owner).toPath(), new byte[]{1, 2, 3});

        PendingPearlManager manager = newManager("item");

        assertEquals(0, storeSnapshotCount(manager, owner), "a corrupt record must not be loaded");
        assertTrue(new File(pearlsDirectory(), owner + ".bin.corrupt").exists(),
                "a corrupt record must be quarantined for inspection");
    }

    /** 注入开关与返还模式后构造管理器（构造器会读配置并 refresh） */
    private PendingPearlManager newManager(String returnMode) {
        MockBukkitHarness.inject(PearlConfig.class, "enabled", env.config().pearl(), true);
        MockBukkitHarness.inject(PearlConfig.class, "returnMode", env.config().pearl(), returnMode);
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

    /** 取管理器持有的存储层实例（store 字段为私有）。 */
    private PendingPearlStore storeOf(PendingPearlManager manager) {
        try {
            Field field = PendingPearlManager.class.getDeclaredField("store");
            field.setAccessible(true);
            return (PendingPearlStore) field.get(manager);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to read pearl store", e);
        }
    }

    /** 读存储层的私有 Map 字段大小（pending / handledPearls）。 */
    private int storeMapSize(PendingPearlManager manager, String fieldName) {
        try {
            Field mapField = PendingPearlStore.class.getDeclaredField(fieldName);
            mapField.setAccessible(true);
            return ((java.util.Map<?, ?>) mapField.get(storeOf(manager))).size();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to read pearl store field: " + fieldName, e);
        }
    }

    /** 某玩家在 pending 中的快照数量（同一玩家的多条快照只占一个键）。 */
    private int storeSnapshotCount(PendingPearlManager manager, UUID owner) {
        return storeOf(manager).pendingCount(owner);
    }

    /** 标记登录态：SessionStore.markLoggedIn 为包内方法，测试经反射调用。 */
    private void markLoggedIn(UUID uuid) throws Exception {
        var method = env.sessions().getClass().getDeclaredMethod("markLoggedIn", UUID.class);
        method.setAccessible(true);
        method.invoke(env.sessions(), uuid);
    }

    /** 标记珍珠已被处理：验证移除事件不会重复入库。 */
    private void storeMarkHandled(PendingPearlManager manager, UUID pearlId) {
        storeOf(manager).markHandled(pearlId);
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

    /** 造一颗归属指定玩家的珍珠。 */
    @SuppressWarnings("SameParameterValue")
    private TestEnderPearlMock newOwnedPearl(TestPlayerMock player,
                                             double x, double y, double z,
                                             double vx, double vy, double vz) {
        TestEnderPearlMock pearl = newPearl(x, y, z, vx, vy, vz);
        pearl.setShooter(player);
        return pearl;
    }

    @SuppressWarnings("SameParameterValue")
    private PlayerTeleportEvent pearlTeleport(TestPlayerMock player, double x, double y, double z) {
        return new PlayerTeleportEvent(player, new Location(world(), 0, 64, 0),
                new Location(world(), x, y, z), PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
    }

    private PlayerQuitEvent quitEvent(TestPlayerMock player) {
        return new PlayerQuitEvent(player,
                Component.text(player.getName() + " left the game"), PlayerQuitEvent.QuitReason.DISCONNECTED);
    }

    private File pearlsDirectory() {
        return new File(env.plugin().getDataFolder(), "pearls");
    }

    private File pearlsFile(UUID owner) {
        return new File(pearlsDirectory(), owner + ".bin");
    }

    private List<SnapshotCodec.PearlSnapshot> loadPearls(UUID owner) {
        File file = pearlsFile(owner);
        try {
            return SnapshotCodec.decode(Files.readAllBytes(file.toPath()));
        } catch (IOException e) {
            throw new AssertionError("failed to read pearl record " + file, e);
        }
    }

    private int recordFileCount() {
        File[] files = pearlsDirectory().listFiles((dir, name) -> name.endsWith(".bin"));
        return files == null ? 0 : files.length;
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
