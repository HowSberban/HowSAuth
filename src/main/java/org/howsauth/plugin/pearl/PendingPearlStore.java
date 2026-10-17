package org.howsauth.plugin.pearl;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.pearl.SnapshotCodec.PearlSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

/**
 * 暂存珍珠状态及其持久化生命周期。
 * <p>
 * 存储模型：每个待返还的玩家一份二进制记录（{@code pearls/&lt;uuid&gt;.bin}），而不是把全服
 * 数据塞进一个 YAML {@code pearls.dat}。吸收/返还时只标记该玩家为脏，异步合并窗口结束后
 * 仅重写这些玩家的文件；文件先写临时文件并 fsync，再原子替换，崩溃时旧记录仍完整。
 */
final class PendingPearlStore {

    private static final long HANDLED_PEARL_TTL_MILLIS = TimeUnit.SECONDS.toMillis(30);
    private static final long SAVE_COALESCE_DELAY_MILLIS = 100;
    private static final long SAVE_RETRY_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(5);
    private static final int CLEANUP_PERIOD_SECONDS = 30;

    /** 合成快照（取消传送时补发的 credit）的标识；与真实珍珠 UUID 区分，便于吸收时替换而非追加。 */
    static final UUID SYNTHETIC_PEARL_ID = new UUID(0L, 0L);

    private final HowSAuth plugin;
    private final PearlRecordFile records;
    private final Path legacyFile;
    private final Map<UUID, List<PearlSnapshot>> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> handledPearls = new ConcurrentHashMap<>();
    /** 等待落盘的玩家；值可能已被替换，写完后用引用相等判断是否清掉脏标记。 */
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    /** 串行化磁盘写入：异步批量保存与登录/关服同步保存可能同时发生。 */
    private final Object ioLock = new Object();
    private final AtomicBoolean saveScheduled = new AtomicBoolean();
    private volatile boolean shuttingDown;
    private volatile ScheduledTask saveTask;
    private final ScheduledTask handledCleanupTask;

    PendingPearlStore(HowSAuth plugin) {
        this.plugin = plugin;
        this.records = new PearlRecordFile(plugin.getDataFolder().toPath().resolve("pearls"));
        this.legacyFile = plugin.getDataFolder().toPath().resolve("pearls.dat");
        prepareDirectory();
        loadRecords();
        importLegacyRecords();
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

    /**
     * 该玩家是否已有快照落在给定位置附近（真实珍珠或合成 credit 均算）。
     * <p>
     * 用于控制合成 credit：同一次投掷的移除事件或前一个传送事件已经记过，就不应再补一条。
     */
    boolean hasSnapshotNear(UUID owner, String world, double x, double y, double z, double tolerance) {
        List<PearlSnapshot> snapshots = pending.get(owner);
        if (snapshots == null) return false;
        double maxDistanceSq = tolerance * tolerance;
        for (PearlSnapshot snapshot : snapshots) {
            if (snapshot.world() == null || !snapshot.world().equals(world)) continue;
            if (distanceSq(snapshot, x, y, z) <= maxDistanceSq) return true;
        }
        return false;
    }

    /** 同一世界、给定半径内最近的合成 credit 下标；没有时返回 -1。 */
    int nearestSyntheticIndex(List<PearlSnapshot> snapshots, String world,
                              double x, double y, double z, double tolerance) {
        int nearest = -1;
        double nearestDistance = tolerance * tolerance;
        for (int i = 0; i < snapshots.size(); i++) {
            PearlSnapshot snapshot = snapshots.get(i);
            if (snapshot.world() == null || !snapshot.world().equals(world)) continue;
            if (!SYNTHETIC_PEARL_ID.equals(snapshot.pearlId())) continue;
            double distance = distanceSq(snapshot, x, y, z);
            if (distance <= nearestDistance) {
                nearestDistance = distance;
                nearest = i;
            }
        }
        return nearest;
    }

    private static double distanceSq(PearlSnapshot snapshot, double x, double y, double z) {
        double dx = snapshot.x() - x;
        double dy = snapshot.y() - y;
        double dz = snapshot.z() - z;
        return dx * dx + dy * dy + dz * dz;
    }

    void clearPending() {
        // 先记下要删除的玩家再清内存：saveSync 据此删除对应文件，而不是留下下次启动又复活的旧记录。
        dirty.addAll(pending.keySet());
        pending.clear();
    }

    List<PearlSnapshot> remove(UUID owner) {
        List<PearlSnapshot> removed = pending.remove(owner);
        if (removed != null) dirty.add(owner);
        return removed;
    }

    void mergeOne(UUID owner, PearlSnapshot snapshot) {
        pending.merge(owner, List.of(snapshot), (oldList, newList) -> {
            List<PearlSnapshot> merged = new ArrayList<>(oldList);
            merged.addAll(newList);
            return List.copyOf(merged);
        });
        dirty.add(owner);
    }

    void update(UUID owner, BiFunction<UUID, List<PearlSnapshot>, List<PearlSnapshot>> updater) {
        pending.compute(owner, (uuid, oldList) -> {
            List<PearlSnapshot> updated = updater.apply(uuid, oldList);
            dirty.add(owner);
            if (updated == null || updated.isEmpty()) return null;
            return List.copyOf(updated);
        });
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

    /** 请求一次合并后的异步保存；实际写入由 {@link #flushDirty()} 完成。 */
    void save() {
        if (shuttingDown || dirty.isEmpty()) return;
        scheduleFlush(SAVE_COALESCE_DELAY_MILLIS);
    }

    /** 同步写入全部脏记录（关服兜底）——包括此前写失败的记录，避免只在内存里留到最后。 */
    void saveSync() {
        Set<UUID> dirtyOwners = new HashSet<>(dirty);
        for (UUID owner : dirtyOwners) {
            writeOwner(owner);
        }
        if (!dirtyOwners.isEmpty()) {
            records.forceDirectory();
        }
    }

    /** 同步写入/删除单个玩家记录；写失败时交给异步队列重试。 */
    void saveSync(UUID owner) {
        if (writeOwner(owner)) {
            records.forceDirectory();
        } else {
            save();
        }
    }

    void shutdown() {
        shuttingDown = true;
        ScheduledTask pendingSave = saveTask;
        if (pendingSave != null) pendingSave.cancel();
        handledCleanupTask.cancel();
        saveScheduled.set(false);
        saveSync();
    }

    private void scheduleFlush(long delayMillis) {
        if (shuttingDown || dirty.isEmpty()) return;
        if (!saveScheduled.compareAndSet(false, true)) return;
        try {
            saveTask = Bukkit.getAsyncScheduler().runDelayed(plugin,
                    task -> flushDirty(), delayMillis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            saveScheduled.set(false);
            logPersistFailed(e);
        }
    }

    private void flushDirty() {
        boolean failed = false;
        List<UUID> batch = new ArrayList<>(dirty);
        try {
            for (UUID owner : batch) {
                if (shuttingDown) break;
                if (!writeOwner(owner)) {
                    failed = true;
                    break;
                }
            }
        } finally {
            saveTask = null;
            saveScheduled.set(false);
            if (!batch.isEmpty()) {
                records.forceDirectory();
            }
            if (!shuttingDown && !dirty.isEmpty()) {
                scheduleFlush(failed ? SAVE_RETRY_DELAY_MILLIS : SAVE_COALESCE_DELAY_MILLIS);
            }
        }
    }

    /**
     * 落盘单个玩家。返回 false 表示这次没写成（IO 失败会保留脏标记重试；数据本身非法只告警一次，
     * 不再让重试队列空转）。写完后仅当内存值仍是本次读取的引用时才清除脏标记，避免吞掉并发更新。
     */
    private boolean writeOwner(UUID owner) {
        synchronized (ioLock) {
            // 在锁内读取当前值：同步保存与异步保存交错时，后拿到锁的一方总能写下更新的版本。
            List<PearlSnapshot> snapshots = pending.get(owner);
            try {
                if (snapshots == null || snapshots.isEmpty()) {
                    records.delete(owner);
                } else {
                    records.write(owner, snapshots);
                }
            } catch (IOException e) {
                dirty.add(owner);
                logPersistFailed(e);
                return false;
            } catch (RuntimeException e) {
                dirty.remove(owner);
                plugin.getLogger().warning(I18n.get("log.pearl_persist_failed", e.getMessage()));
                return false;
            }
            if (snapshots == pending.get(owner)) {
                dirty.remove(owner);
            }
            return true;
        }
    }

    private void cleanupHandledPearls() {
        long cutoff = System.currentTimeMillis() - HANDLED_PEARL_TTL_MILLIS;
        handledPearls.forEach((pearlId, handledAt) -> {
            if (handledAt <= cutoff) handledPearls.remove(pearlId, handledAt);
        });
    }

    private void prepareDirectory() {
        try {
            records.createDirectory();
            records.cleanupTempFiles();
        } catch (IOException e) {
            logPersistFailed(e);
        }
    }

    private void loadRecords() {
        if (!Files.isDirectory(records.directory())) return;
        List<UUID> owners;
        try {
            owners = records.listOwners();
        } catch (IOException e) {
            logPersistFailed(e);
            return;
        }
        for (UUID owner : owners) {
            try {
                List<PearlSnapshot> snapshots = records.read(owner);
                if (snapshots.isEmpty()) {
                    records.delete(owner);
                } else {
                    pending.put(owner, snapshots);
                }
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().warning(I18n.get("log.pearl_record_corrupt",
                        owner + ": " + e.getMessage()));
                try {
                    records.quarantine(owner);
                } catch (IOException quarantineFailure) {
                    logPersistFailed(quarantineFailure);
                }
            }
        }
    }

    private void importLegacyRecords() {
        if (!Files.isRegularFile(legacyFile)) return;
        Map<UUID, List<PearlSnapshot>> legacy;
        try {
            legacy = LegacyPearlImport.read(legacyFile,
                    message -> plugin.getLogger().warning(I18n.get("log.pearl_legacy_entry_invalid", message)));
        } catch (RuntimeException e) {
            logPersistFailed(e);
            return;
        }
        int imported = 0;
        int skipped = 0;
        boolean failed = false;
        for (Map.Entry<UUID, List<PearlSnapshot>> entry : legacy.entrySet()) {
            if (pending.containsKey(entry.getKey())) {
                // 二进制记录已存在时以它为准：迁移重试不会把已返还的记录再补回来。
                skipped++;
                continue;
            }
            pending.put(entry.getKey(), entry.getValue());
            if (writeOwner(entry.getKey())) {
                imported++;
            } else {
                failed = true;
            }
        }
        records.forceDirectory();
        if (failed) {
            plugin.getLogger().warning(I18n.get("log.pearl_migration_failed",
                    legacyFile.getFileName().toString()));
            save();
            return;
        }
        Path backup = legacyFile.resolveSibling(legacyFile.getFileName() + ".migrated");
        try {
            Files.move(legacyFile, backup, StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info(I18n.get("log.pearl_migrated", backup.getFileName().toString()));
        } catch (IOException e) {
            logPersistFailed(e);
        }
        if (Debug.on()) {
            Debug.log("pearl", "legacy pearls.dat imported: %s record(s), %s skipped", imported, skipped);
        }
    }

    private void logPersistFailed(Exception e) {
        plugin.getLogger().warning(I18n.get("log.pearl_persist_failed", e.getMessage()));
    }
}
