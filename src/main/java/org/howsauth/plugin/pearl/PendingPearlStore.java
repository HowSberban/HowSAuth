package org.howsauth.plugin.pearl;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.pearl.SnapshotCodec.PearlSnapshot;

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
import java.util.function.BiFunction;

/** 暂存珍珠状态及其持久化生命周期。 */
final class PendingPearlStore {

    private static final long HANDLED_PEARL_TTL_MILLIS = TimeUnit.SECONDS.toMillis(30);
    private static final long SAVE_COALESCE_DELAY_MILLIS = 100;
    private static final int CLEANUP_PERIOD_SECONDS = 30;

    private final HowSAuth plugin;
    private final File file;
    private final File tempFile;
    private final Map<UUID, List<PearlSnapshot>> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> handledPearls = new ConcurrentHashMap<>();
    private final AtomicBoolean saveScheduled = new AtomicBoolean();
    private final AtomicBoolean saveRequested = new AtomicBoolean();
    private volatile boolean shuttingDown;
    private volatile ScheduledTask saveTask;
    private final ScheduledTask handledCleanupTask;

    PendingPearlStore(HowSAuth plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "pearls.dat");
        this.tempFile = new File(plugin.getDataFolder(), "pearls.dat.tmp");
        load();
        handledCleanupTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin,
                task -> cleanupHandledPearls(), CLEANUP_PERIOD_SECONDS, CLEANUP_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    int pendingSize() {
        return pending.size();
    }

    int pendingCount(UUID owner) {
        List<PearlSnapshot> snapshots = pending.get(owner);
        return snapshots == null ? 0 : snapshots.size();
    }

    void clearPending() {
        pending.clear();
    }

    List<PearlSnapshot> remove(UUID owner) {
        return pending.remove(owner);
    }

    void merge(UUID owner, List<PearlSnapshot> snapshots) {
        pending.merge(owner, snapshots, (oldList, newList) -> {
            List<PearlSnapshot> merged = new ArrayList<>(oldList);
            merged.addAll(newList);
            return List.copyOf(merged);
        });
    }

    void mergeOne(UUID owner, PearlSnapshot snapshot) {
        merge(owner, List.of(snapshot));
    }

    void update(UUID owner, BiFunction<UUID, List<PearlSnapshot>, List<PearlSnapshot>> updater) {
        pending.compute(owner, updater);
    }

    boolean consumeHandled(UUID pearlId) {
        return handledPearls.remove(pearlId) != null;
    }

    void markHandled(UUID pearlId) {
        handledPearls.put(pearlId, System.currentTimeMillis());
    }

    void clearHandled(UUID pearlId) {
        handledPearls.remove(pearlId);
    }

    void save() {
        if (shuttingDown) return;
        saveRequested.set(true);
        if (!saveScheduled.compareAndSet(false, true)) return;
        saveTask = Bukkit.getAsyncScheduler().runDelayed(plugin,
                task -> flushScheduledSaves(), SAVE_COALESCE_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    void saveSync() {
        synchronized (this) {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<UUID, List<PearlSnapshot>> entry : pending.entrySet()) {
                yaml.set(entry.getKey().toString(), SnapshotCodec.serializeSnapshots(entry.getValue()));
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

    void shutdown() {
        shuttingDown = true;
        ScheduledTask pendingSave = saveTask;
        if (pendingSave != null) pendingSave.cancel();
        handledCleanupTask.cancel();
        saveRequested.set(false);
        saveScheduled.set(false);
        saveSync();
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

    private void cleanupHandledPearls() {
        long cutoff = System.currentTimeMillis() - HANDLED_PEARL_TTL_MILLIS;
        handledPearls.forEach((pearlId, handledAt) -> {
            if (handledAt <= cutoff) handledPearls.remove(pearlId, handledAt);
        });
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
                        PearlSnapshot snapshot = SnapshotCodec.parseSnapshot(map);
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
}
