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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 珍珠记录的异步落盘与启动加载。
 * <p>
 * 缓存 Map 与 dirty Set 由 {@link PendingPearlStore} 持有并共享；本类只负责脏标记消费、
 * 每玩家二进制文件写入、旧 pearls.dat 迁移与关服兜底。缓存本身的增删查改留在 Store。
 */
final class PearlPersistence {

    private static final long SAVE_COALESCE_DELAY_MILLIS = 100;
    private static final long SAVE_RETRY_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(5);

    private final HowSAuth plugin;
    private final PearlRecordFile records;
    private final Path legacyFile;
    private final Map<UUID, List<PearlSnapshot>> pending;
    private final Set<UUID> dirty;
    /** 串行化磁盘写入：异步批量保存与登录/关服同步保存可能同时发生。 */
    private final Object ioLock = new Object();
    private final AtomicBoolean saveScheduled = new AtomicBoolean();
    private volatile boolean shuttingDown;
    private volatile ScheduledTask saveTask;

    PearlPersistence(HowSAuth plugin, PearlRecordFile records, Path legacyFile,
                     Map<UUID, List<PearlSnapshot>> pending, Set<UUID> dirty) {
        this.plugin = plugin;
        this.records = records;
        this.legacyFile = legacyFile;
        this.pending = pending;
        this.dirty = dirty;
        prepareDirectory();
        loadRecords();
        importLegacyRecords();
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
                logPersistFailed(e);
                return false;
            }
            if (snapshots == pending.get(owner)) {
                dirty.remove(owner);
            }
            return true;
        }
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
