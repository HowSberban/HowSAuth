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

/** 暂存离线或尚未通过认证玩家的末影珍珠。 */
public final class PendingPearlManager implements Listener {

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
        UUID uuid = event.getPlayer().getUniqueId();
        boolean changed = false;
        for (EnderPearl pearl : copyFlyingPearls(event.getPlayer())) {
            absorb(pearl, uuid);
            changed = true;
        }
        if (changed) store.save();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoveFromWorld(EntityRemoveFromWorldEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!plugin.config().pearl().enabled()) return;
        if (store.consumeHandled(pearl.getUniqueId())) return;
        UUID owner = ownerResolver.resolve(pearl);
        if (owner == null) {
            ownerResolver.warnResolution(pearl);
            return;
        }
        if (ownerResolver.isAuthenticated(owner)) return;
        absorb(pearl, owner);
        store.save();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityAddToWorld(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        UUID owner = ownerResolver.resolve(pearl);
        if (!plugin.config().pearl().enabled()) {
            // 开关关闭时仍清理未登录玩家的恢复珍珠，但不能干扰已登录玩家的正常投掷。
            if (owner == null) return;
            if (ownerResolver.isAuthenticated(owner)) return;
            removePearl(pearl);
            return;
        }
        if (owner == null) {
            ownerResolver.warnResolution(pearl);
            return;
        }
        if (ownerResolver.isAuthenticated(owner)) return;
        absorb(pearl, owner);
        store.save();
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
        PearlSnapshot credit = new PearlSnapshot(UUID.randomUUID(), false, world.getName(),
                to.getX(), to.getY(), to.getZ(), 0, 0, 0);
        store.mergeOne(player.getUniqueId(), credit);
        store.save();
    }

    public void returnPearls(Player player) {
        if (!plugin.config().pearl().enabled()) {
            // 功能关闭时不返还旧快照，也不保留到下次重新开启，避免过期状态被再次恢复。
            List<PearlSnapshot> discarded = store.remove(player.getUniqueId());
            if (discarded != null && !discarded.isEmpty()) store.saveSync();
            return;
        }
        boolean absorbed = false;
        for (EnderPearl pearl : copyFlyingPearls(player)) {
            absorb(pearl, player.getUniqueId());
            absorbed = true;
        }
        List<PearlSnapshot> snapshots = store.remove(player.getUniqueId());
        if (snapshots == null || snapshots.isEmpty()) {
            if (absorbed) store.saveSync();
            if (absorbed && Debug.on()) {
                Debug.log("pearl", "return pearls to %s: only absorbed flying pearls", player.getName());
            }
            return;
        }
        store.saveSync();
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
                    store.mergeOne(player.getUniqueId(), snapshot);
                    store.saveSync();
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
            for (int i = 0; i < updated.size(); i++) {
                PearlSnapshot existing = updated.get(i);
                if (pearlId.equals(existing.pearlId())) {
                    updated.set(i, snapshot);
                    return List.copyOf(updated);
                }
                if (existing.legacy() && SnapshotCodec.sameState(existing, worldName, loc, vel)) {
                    updated.set(i, snapshot);
                    return List.copyOf(updated);
                }
            }
            updated.add(snapshot);
            return List.copyOf(updated);
        });
        removePearl(pearl);
        if (Debug.on()) {
            Debug.log("pearl", "absorb pearl for %s (stored %s)", Debug.shortId(owner),
                    store.pendingCount(owner));
        }
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
