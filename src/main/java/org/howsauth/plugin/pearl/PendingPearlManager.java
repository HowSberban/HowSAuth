package org.howsauth.plugin.pearl;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
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
import org.howsauth.plugin.pearl.PearlSnapshotCodec.PearlSnapshot;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.I18n;

import java.io.File;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 暂存离线或尚未通过认证玩家的末影珍珠。 */
public final class PendingPearlManager implements Listener {

    private final HowSAuth plugin;
    private final PearlOwnerResolver ownerResolver;
    private final File file;
    private final File tempFile;
    private final Map<UUID, List<PearlSnapshot>> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> handledPearls = new ConcurrentHashMap<>();
    private final AtomicBoolean saveScheduled = new AtomicBoolean();
    private final AtomicBoolean saveRequested = new AtomicBoolean();
    private static final long HANDLED_PEARL_TTL_MILLIS = TimeUnit.SECONDS.toMillis(30);
    private static final long SAVE_COALESCE_DELAY_MILLIS = 100;
    private volatile boolean lastPearlEnabled;
    private volatile boolean pearlStateInitialized;
    private volatile boolean shuttingDown;
    private volatile ScheduledTask saveTask;
    private final ScheduledTask handledCleanupTask;

    public PendingPearlManager(HowSAuth plugin) {
        this.plugin = plugin;
        this.ownerResolver = new PearlOwnerResolver(plugin);
        this.file = new File(plugin.getDataFolder(), "pearls.dat");
        this.tempFile = new File(plugin.getDataFolder(), "pearls.dat.tmp");
        load();
        handledCleanupTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin,
                task -> cleanupHandledPearls(), 30, 30, TimeUnit.SECONDS);
        refresh();
    }

    public void refresh() {
        boolean enabled = plugin.getConfigManager().pearlEnabled();
        boolean wasEnabled = lastPearlEnabled;
        boolean initialized = pearlStateInitialized;
        lastPearlEnabled = enabled;
        pearlStateInitialized = true;
        if (!enabled && (!initialized || wasEnabled)) {
            int cleared = pending.size();
            pending.clear();
            saveSync();
            if (Debug.on()) {
                Debug.log("pearl", "pearl storage disabled: cleared %s pending records", cleared);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!plugin.getConfigManager().pearlEnabled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        boolean changed = false;
        for (EnderPearl pearl : copyFlyingPearls(event.getPlayer())) {
            absorb(pearl, uuid);
            changed = true;
        }
        if (changed) save();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoveFromWorld(EntityRemoveFromWorldEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!plugin.getConfigManager().pearlEnabled()) return;
        if (handledPearls.remove(pearl.getUniqueId()) != null) return;
        UUID owner = ownerResolver.resolve(pearl);
        if (owner == null) {
            ownerResolver.warnResolutionFailure(pearl);
            return;
        }
        if (ownerResolver.isAuthenticated(owner)) return;
        absorb(pearl, owner);
        save();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityAddToWorld(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        UUID owner = ownerResolver.resolve(pearl);
        if (!plugin.getConfigManager().pearlEnabled()) {
            // 开关关闭时仍清理未登录玩家的恢复珍珠，但不能干扰已登录玩家的正常投掷。
            if (owner == null) return;
            if (ownerResolver.isAuthenticated(owner)) return;
            removePearl(pearl);
            return;
        }
        if (owner == null) {
            ownerResolver.warnResolutionFailure(pearl);
            return;
        }
        if (ownerResolver.isAuthenticated(owner)) return;
        absorb(pearl, owner);
        save();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPearlTeleport(PlayerTeleportEvent event) {
        if (!plugin.getConfigManager().pearlEnabled()) return;
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        Player player = event.getPlayer();
        if (plugin.sessions().isLoggedIn(player)) return;
        event.setCancelled(true);
        Location to = event.getTo();
        World world = to.getWorld();
        if (world == null) return;
        PearlSnapshot credit = new PearlSnapshot(UUID.randomUUID(), false, world.getName(),
                to.getX(), to.getY(), to.getZ(), 0, 0, 0);
        pending.merge(player.getUniqueId(), List.of(credit), (oldList, newList) -> {
            List<PearlSnapshot> merged = new ArrayList<>(oldList);
            merged.addAll(newList);
            return List.copyOf(merged);
        });
        save();
    }

    public void returnPearls(Player player) {
        if (!plugin.getConfigManager().pearlEnabled()) return;
        boolean absorbed = false;
        for (EnderPearl pearl : copyFlyingPearls(player)) {
            absorb(pearl, player.getUniqueId());
            absorbed = true;
        }
        List<PearlSnapshot> snapshots = pending.remove(player.getUniqueId());
        if (snapshots == null || snapshots.isEmpty()) {
            if (absorbed) saveSync();
            if (absorbed && Debug.on()) {
                Debug.log("pearl", "return pearls to %s: only absorbed flying pearls", player.getName());
            }
            return;
        }
        saveSync();
        if (!plugin.getConfigManager().pearlReturnEntity()) {
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
                    pending.merge(player.getUniqueId(), List.of(snapshot), (oldList, newList) -> {
                        List<PearlSnapshot> merged = new ArrayList<>(oldList);
                        merged.addAll(newList);
                        return List.copyOf(merged);
                    });
                    saveSync();
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
        pending.compute(owner, (uuid, oldList) -> {
            List<PearlSnapshot> updated = oldList == null
                    ? new ArrayList<>() : new ArrayList<>(oldList);
            for (int i = 0; i < updated.size(); i++) {
                PearlSnapshot existing = updated.get(i);
                if (pearlId.equals(existing.pearlId())) {
                    updated.set(i, snapshot);
                    return List.copyOf(updated);
                }
                if (existing.legacy() && PearlSnapshotCodec.sameState(existing, worldName, loc, vel)) {
                    updated.set(i, snapshot);
                    return List.copyOf(updated);
                }
            }
            updated.add(snapshot);
            return List.copyOf(updated);
        });
        removePearl(pearl);
        if (Debug.on()) {
            List<PearlSnapshot> stored = pending.get(owner);
            Debug.log("pearl", "absorb pearl for %s (stored %s)", Debug.shortId(owner),
                    stored == null ? 0 : stored.size());
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
        }, () -> handledPearls.remove(pearlId), 1) != null;
        if (!scheduled) handledPearls.remove(pearlId);
    }

    private void cleanupHandledPearls() {
        long cutoff = System.currentTimeMillis() - HANDLED_PEARL_TTL_MILLIS;
        handledPearls.forEach((pearlId, handledAt) -> {
            if (handledAt <= cutoff) {
                handledPearls.remove(pearlId, handledAt);
            }
        });
    }

    private void markAndRemove(EnderPearl pearl, UUID pearlId) {
        handledPearls.put(pearlId, System.currentTimeMillis());
        try {
            pearl.remove();
        } catch (RuntimeException exception) {
            handledPearls.remove(pearlId);
            throw exception;
        }
    }

    private void clearHandledLater(EnderPearl pearl, UUID pearlId) {
        pearl.getScheduler().runDelayed(plugin, task -> handledPearls.remove(pearlId),
                () -> handledPearls.remove(pearlId), 1);
    }

    private void giveItems(Player player, int count) {
        Map<Integer, ItemStack> leftover = player.getInventory()
                .addItem(new ItemStack(Material.ENDER_PEARL, count));
        for (ItemStack item : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), item);
        }
        player.sendMessage(I18n.msg("pearl.returned", player, count));
    }

    private void load() {
        if (!file.exists()) return;
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String key : yaml.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    List<PearlSnapshot> snapshots = new ArrayList<>();
                    for (Map<?, ?> map : yaml.getMapList(key)) {
                        PearlSnapshot snapshot = PearlSnapshotCodec.parseSnapshot(map);
                        if (snapshot != null) snapshots.add(snapshot);
                    }
                    if (!snapshots.isEmpty()) pending.put(uuid, List.copyOf(snapshots));
                } catch (IllegalArgumentException ignored) {
                    // 忽略格式错误的玩家记录。
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe(I18n.get("log.pearl_persist_failed", e.getMessage()));
        }
    }

    /** 解析快照（包内可见：供单测使用）；world 缺失或非字符串时返回 null */
    /** 快照列表转 YAML 映射（包内可见：供单测使用） */
    private void save() {
        if (shuttingDown) return;
        saveRequested.set(true);
        if (!saveScheduled.compareAndSet(false, true)) return;
        saveTask = Bukkit.getAsyncScheduler().runDelayed(plugin,
                task -> flushScheduledSaves(), SAVE_COALESCE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void flushScheduledSaves() {
        try {
            do {
                saveRequested.set(false);
                saveSync();
            } while (!shuttingDown && saveRequested.get());
        } finally {
            saveTask = null;
            saveScheduled.set(false);
            if (!shuttingDown && saveRequested.get()) save();
        }
    }

    public void shutdown() {
        shuttingDown = true;
        ScheduledTask pendingSave = saveTask;
        if (pendingSave != null) pendingSave.cancel();
        handledCleanupTask.cancel();
        saveRequested.set(false);
        saveScheduled.set(false);
        saveSync();
    }

    private synchronized void saveSync() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, List<PearlSnapshot>> entry : pending.entrySet()) {
            yaml.set(entry.getKey().toString(), PearlSnapshotCodec.serializeSnapshots(entry.getValue()));
        }
        try {
            yaml.save(tempFile);
            try {
                Files.move(tempFile.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.get("log.pearl_persist_failed", e.getMessage()));
        }
    }
}
