package org.howsauth.plugin.pearl;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.pearl.SnapshotCodec.PearlSnapshot;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 保管末影珍珠：玩家退出时吸收在飞珍珠，未认证玩家在世界中产生/落地的珍珠也会被吸收。
 * 成功登录后按配置以物品或实体形式返还。
 */
public final class PendingPearlManager implements Listener {

    private static final double TELEPORT_CREDIT_MATCH_RADIUS = 2.0;

    private final HowSAuth plugin;
    private final OwnerResolver ownerResolver;
    private final PendingPearlStore store;
    private volatile boolean lastPearlEnabled;
    private volatile boolean pearlStateInitialized;

    public PendingPearlManager(HowSAuth plugin) {
        this.plugin = plugin;
        this.ownerResolver = new OwnerResolver(plugin);
        this.store = new PendingPearlStore(plugin);
        refresh();
    }

    public void refresh() {
        boolean enabled = plugin.config().pearl().enabled();
        boolean wasEnabled = lastPearlEnabled;
        boolean initialized = pearlStateInitialized;
        lastPearlEnabled = enabled;
        pearlStateInitialized = true;
        if (!enabled && (!initialized || wasEnabled)) {
            int cleared = store.pendingSize();
            store.clearPending();
            store.saveSync();
            if (Debug.on()) {
                Debug.log("pearl", "pearl storage disabled: cleared %s pending records", cleared);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.config().pearl().enabled()) return;
        // 退出是珍珠恢复最关键的一次写入：只同步落盘该玩家的小文件，避免 100ms 合并窗口内崩溃丢记录。
        Player player = event.getPlayer();
        if (absorbFlyingPearls(player)) store.saveSync(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoveFromWorld(EntityRemoveFromWorldEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!plugin.config().pearl().enabled()) return;
        if (store.consumeHandled(pearl.getUniqueId())) return;
        absorbIfUnauthenticated(pearl, ownerResolver.resolve(pearl));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityAddToWorld(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        UUID owner = ownerResolver.resolve(pearl);
        if (!plugin.config().pearl().enabled()) {
            // 开关关闭时仍清理未登录玩家的恢复珍珠，但不能干扰已登录玩家的正常投掷。
            if (owner != null && !ownerResolver.isAuthenticated(owner)) removePearl(pearl);
            return;
        }
        absorbIfUnauthenticated(pearl, owner);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPearlTeleport(PlayerTeleportEvent event) {
        if (!plugin.config().pearl().enabled()) return;
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        Player player = event.getPlayer();
        if (plugin.sessions().isLoggedIn(player)) return;
        event.setCancelled(true);
        Location to = event.getTo();
        World world = to.getWorld();
        if (world == null) return;
        UUID owner = player.getUniqueId();
        // 同一次投掷的移除事件（或前一个传送事件）已经记过快照；再补 credit 会重复返还
        if (store.hasSnapshotNear(owner, world.getName(),
                to.getX(), to.getY(), to.getZ(), TELEPORT_CREDIT_MATCH_RADIUS)) {
            return;
        }
        PearlSnapshot credit = new PearlSnapshot(PendingPearlStore.SYNTHETIC_PEARL_ID, false, world.getName(),
                to.getX(), to.getY(), to.getZ(), 0, 0, 0);
        store.mergeOne(owner, credit);
        store.save();
    }

    public void returnPearls(Player player) {
        UUID owner = player.getUniqueId();
        if (!plugin.config().pearl().enabled()) {
            // 功能关闭时不返还旧快照，也不保留到下次重新开启，避免过期状态被再次恢复。
            List<PearlSnapshot> discarded = store.remove(owner);
            if (discarded != null && !discarded.isEmpty()) store.saveSync(owner);
            return;
        }
        boolean absorbed = absorbFlyingPearls(player);
        List<PearlSnapshot> snapshots = store.remove(owner);
        if (snapshots == null || snapshots.isEmpty()) {
            if (absorbed) store.saveSync(owner);
            if (absorbed && Debug.on()) {
                Debug.log("pearl", "return pearls to %s: only absorbed flying pearls", player.getName());
            }
            return;
        }
        store.saveSync(owner);
        if (!plugin.config().pearl().returnEntity()) {
            if (Debug.on()) {
                Debug.log("pearl", "return %s pearls to %s as items", snapshots.size(), player.getName());
            }
            giveItems(player, snapshots.size());
            return;
        }
        int fallbackItems = 0;
        for (PearlSnapshot snapshot : snapshots) {
            World world = Bukkit.getWorld(snapshot.world());
            if (world == null) {
                fallbackItems++;
                continue;
            }
            Location loc = new Location(world, snapshot.x(), snapshot.y(), snapshot.z());
            Bukkit.getRegionScheduler().run(plugin, loc, task -> {
                if (!player.isOnline()) {
                    store.mergeOne(owner, snapshot);
                    store.saveSync(owner);
                    return;
                }
                world.spawn(loc, EnderPearl.class, p -> {
                    p.setVelocity(new Vector(snapshot.vx(), snapshot.vy(), snapshot.vz()));
                    p.setShooter(player);
                });
            });
        }
        if (fallbackItems > 0) giveItems(player, fallbackItems);
        if (Debug.on()) {
            Debug.log("pearl", "return %s pearls to %s as entities (item fallback %s)",
                    snapshots.size(), player.getName(), fallbackItems);
        }
    }

    /** 吸收玩家当前所有在飞珍珠；返回是否至少处理了一颗。 */
    private boolean absorbFlyingPearls(Player player) {
        UUID owner = player.getUniqueId();
        boolean absorbed = false;
        for (EnderPearl pearl : copyFlyingPearls(player)) {
            absorb(pearl, owner);
            absorbed = true;
        }
        return absorbed;
    }

    /** 未认证归属者的珍珠进入/离开世界时统一吸收；已认证或无法解析时跳过。 */
    private void absorbIfUnauthenticated(EnderPearl pearl, UUID owner) {
        if (owner == null) {
            ownerResolver.warnResolution(pearl);
            return;
        }
        if (ownerResolver.isAuthenticated(owner)) return;
        absorb(pearl, owner);
        store.save();
    }

    private void absorb(EnderPearl pearl, UUID owner) {
        Location loc = pearl.getLocation();
        World world = loc.getWorld();
        if (world == null) return;
        Vector vel = pearl.getVelocity();
        String worldName = world.getName();
        UUID pearlId = pearl.getUniqueId();
        PearlSnapshot snapshot = new PearlSnapshot(pearlId, false, worldName,
                loc.getX(), loc.getY(), loc.getZ(), vel.getX(), vel.getY(), vel.getZ());
        store.update(owner, (uuid, oldList) -> {
            List<PearlSnapshot> updated = oldList == null
                    ? new ArrayList<>() : new ArrayList<>(oldList);
            int index = indexToReplace(updated, pearlId, worldName, loc, vel);
            if (index >= 0) {
                updated.set(index, snapshot);
            } else {
                updated.add(snapshot);
            }
            return updated;
        });
        removePearl(pearl);
        if (Debug.on()) {
            Debug.log("pearl", "absorb pearl for %s (stored %s)", Debug.shortId(owner),
                    store.pendingCount(owner));
        }
    }

    /**
     * 查找应被这颗真实珍珠替换的快照下标：同 UUID、legacy 同状态，或匹配半径内最近的 synthetic credit。
     * 返回 -1 表示没有可替换项。
     */
    private int indexToReplace(List<PearlSnapshot> snapshots, UUID pearlId, String worldName,
                               Location loc, Vector vel) {
        for (int i = 0; i < snapshots.size(); i++) {
            PearlSnapshot existing = snapshots.get(i);
            if (pearlId.equals(existing.pearlId())) return i;
            if (existing.legacy() && SnapshotCodec.sameState(existing, worldName, loc, vel)) return i;
        }
        return store.nearestSyntheticIndex(snapshots, worldName,
                loc.getX(), loc.getY(), loc.getZ(), TELEPORT_CREDIT_MATCH_RADIUS);
    }

    @SuppressWarnings("UnstableApiUsage")
    private List<EnderPearl> copyFlyingPearls(Player player) {
        try {
            return List.copyOf(player.getEnderPearls());
        } catch (Exception e) {
            // 读取失败按无珍珠处理：该接口在部分实现/环境下不可用，而珍珠恢复属辅助功能，不得打断退出与登录流程
            return List.of();
        }
    }

    private void removePearl(EnderPearl pearl) {
        if (!pearl.isValid()) return;
        UUID pearlId = pearl.getUniqueId();
        if (Bukkit.isOwnedByCurrentRegion(pearl.getLocation())) {
            markAndRemove(pearl, pearlId);
            clearHandledLater(pearl, pearlId);
            return;
        }
        boolean scheduled = pearl.getScheduler().runDelayed(plugin, task -> {
            if (!pearl.isValid()) return;
            markAndRemove(pearl, pearlId);
        }, () -> store.clearHandled(pearlId), 1) != null;
        if (!scheduled) store.clearHandled(pearlId);
    }

    private void markAndRemove(EnderPearl pearl, UUID pearlId) {
        store.markHandled(pearlId);
        try {
            pearl.remove();
        } catch (RuntimeException exception) {
            store.clearHandled(pearlId);
            throw exception;
        }
    }

    private void clearHandledLater(EnderPearl pearl, UUID pearlId) {
        pearl.getScheduler().runDelayed(plugin, task -> store.clearHandled(pearlId),
                () -> store.clearHandled(pearlId), 1);
    }

    private void giveItems(Player player, int count) {
        Map<Integer, ItemStack> leftover = player.getInventory()
                .addItem(new ItemStack(Material.ENDER_PEARL, count));
        for (ItemStack item : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
        player.sendMessage(I18n.msg("pearl.returned", player, count));
    }

    /** 关闭存储任务并同步落盘，供插件停用阶段调用。 */
    public void shutdown() {
        store.shutdown();
    }
}
