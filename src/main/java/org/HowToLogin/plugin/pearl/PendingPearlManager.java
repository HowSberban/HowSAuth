package org.howtologin.plugin.pearl;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
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
import org.howtologin.plugin.HTLogin;
import org.howtologin.plugin.I18n;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 暂存离线或尚未通过认证玩家的末影珍珠。 */
public final class PendingPearlManager implements Listener {

    private record PearlSnapshot(UUID pearlId, boolean legacy, String world,
                                 double x, double y, double z, double vx, double vy, double vz) {}

    private final HTLogin plugin;
    private final File file;
    private final Map<UUID, List<PearlSnapshot>> pending = new ConcurrentHashMap<>();
    private final Set<UUID> handledPearls = ConcurrentHashMap.newKeySet();
    private static final double STATE_MATCH_EPSILON = 1e-6;
    private static Method ownerUuidMethod;

    public PendingPearlManager(HTLogin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pearls.dat");
        load();
        refresh();
    }

    public void refresh() {
        if (plugin.getConfigManager().pearlEnabled()) return;
        pending.clear();
        saveSync();
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
        if (handledPearls.remove(pearl.getUniqueId())) return;
        UUID owner = resolveOwner(pearl);
        if (owner == null) return;
        Player player = Bukkit.getPlayer(owner);
        if (player != null && plugin.getAuthManager().isLoggedIn(player)) return;
        absorb(pearl, owner);
        save();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityAddToWorld(EntityAddToWorldEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl)) return;
        if (!plugin.getConfigManager().pearlEnabled()) return;
        UUID owner = resolveOwner(pearl);
        if (owner == null) return;
        Player player = Bukkit.getPlayer(owner);
        if (player != null && plugin.getAuthManager().isLoggedIn(player)) return;
        absorb(pearl, owner, true);
        save();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPearlTeleport(PlayerTeleportEvent event) {
        if (!plugin.getConfigManager().pearlEnabled()) return;
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        Player player = event.getPlayer();
        if (plugin.getAuthManager().isLoggedIn(player)) return;
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
            return;
        }
        saveSync();
        if (!plugin.getConfigManager().pearlReturnEntity()) {
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
    }

    private void absorb(EnderPearl pearl, UUID owner) {
        absorb(pearl, owner, false);
    }

    private void absorb(EnderPearl pearl, UUID owner, boolean fromAddToWorld) {
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
                if (existing.legacy() && sameState(existing, worldName, loc, vel)) {
                    updated.set(i, snapshot);
                    return List.copyOf(updated);
                }
            }
            updated.add(snapshot);
            return List.copyOf(updated);
        });
        removePearl(pearl, fromAddToWorld);
    }

    private UUID resolveOwner(EnderPearl pearl) {
        if (pearl.getShooter() instanceof Player player) {
            return player.getUniqueId();
        }
        return readOwnerUuid(pearl);
    }

    private static UUID readOwnerUuid(EnderPearl pearl) {
        try {
            Object handle = pearl.getClass().getMethod("getHandle").invoke(pearl);
            if (ownerUuidMethod == null) {
                ownerUuidMethod = handle.getClass().getMethod("getOwnerUUID");
            }
            return (UUID) ownerUuidMethod.invoke(handle);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("UnstableApiUsage")
    private List<EnderPearl> copyFlyingPearls(Player player) {
        try {
            return List.copyOf(player.getEnderPearls());
        } catch (Exception e) {
            return List.of();
        }
    }

    private static boolean sameState(PearlSnapshot snapshot, String world, Location loc, Vector vel) {
        return snapshot.world().equals(world)
                && Math.abs(snapshot.x() - loc.getX()) < STATE_MATCH_EPSILON
                && Math.abs(snapshot.y() - loc.getY()) < STATE_MATCH_EPSILON
                && Math.abs(snapshot.z() - loc.getZ()) < STATE_MATCH_EPSILON
                && Math.abs(snapshot.vx() - vel.getX()) < STATE_MATCH_EPSILON
                && Math.abs(snapshot.vy() - vel.getY()) < STATE_MATCH_EPSILON
                && Math.abs(snapshot.vz() - vel.getZ()) < STATE_MATCH_EPSILON;
    }

    private void removePearl(EnderPearl pearl, boolean fromAddToWorld) {
        if (!pearl.isValid()) return;
        UUID pearlId = pearl.getUniqueId();
        if (!fromAddToWorld && Bukkit.isOwnedByCurrentRegion(pearl.getLocation())) {
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

    private void markAndRemove(EnderPearl pearl, UUID pearlId) {
        handledPearls.add(pearlId);
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
                        Object worldValue = map.get("world");
                        if (!(worldValue instanceof String world)) continue;
                        Object pearlIdValue = map.get("pearlId");
                        UUID pearlId = null;
                        boolean legacy = pearlIdValue == null || Boolean.TRUE.equals(map.get("legacy"));
                        if (pearlIdValue instanceof String id) {
                            try {
                                pearlId = UUID.fromString(id);
                            } catch (IllegalArgumentException ignored) {
                                // 损坏的珍珠编号按旧格式记录处理。
                                legacy = true;
                            }
                        }
                        snapshots.add(new PearlSnapshot(pearlId, legacy, world,
                                number(map.get("x")), number(map.get("y")), number(map.get("z")),
                                number(map.get("vx")), number(map.get("vy")), number(map.get("vz"))));
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

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    private void save() {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> saveSync());
    }

    public void shutdown() {
        saveSync();
    }

    private synchronized void saveSync() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, List<PearlSnapshot>> entry : pending.entrySet()) {
            List<Map<String, Object>> maps = new ArrayList<>();
            for (PearlSnapshot s : entry.getValue()) {
                Map<String, Object> map = new LinkedHashMap<>();
                if (s.pearlId() != null) {
                    map.put("pearlId", s.pearlId().toString());
                } else if (s.legacy()) {
                    map.put("legacy", true);
                }
                map.put("world", s.world());
                map.put("x", s.x());
                map.put("y", s.y());
                map.put("z", s.z());
                map.put("vx", s.vx());
                map.put("vy", s.vy());
                map.put("vz", s.vz());
                maps.add(map);
            }
            yaml.set(entry.getKey().toString(), maps);
        }
        try {
            yaml.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("暂存末影珍珠保存失败：" + e.getMessage());
        }
    }
}
