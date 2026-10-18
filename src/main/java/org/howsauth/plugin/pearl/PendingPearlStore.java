package org.howsauth.plugin.pearl;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.pearl.SnapshotCodec.PearlSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * 暂存珍珠的内存状态及其生命周期。
 * <p>
 * 本类只负责缓存与查询/变更；脏标记消费、二进制文件写入、旧数据迁移和关服兜底在
 * {@link PearlPersistence}。两者共享 pending 与 dirty，避免拆出两套状态。
 */
final class PendingPearlStore {

    private static final long HANDLED_PEARL_TTL_MILLIS = TimeUnit.SECONDS.toMillis(30);
    private static final int CLEANUP_PERIOD_SECONDS = 30;

    /** 合成快照（取消传送时补发的 credit）的标识；与真实珍珠 UUID 区分，便于吸收时替换而非追加。 */
    static final UUID SYNTHETIC_PEARL_ID = new UUID(0L, 0L);

    private final Map<UUID, List<PearlSnapshot>> pending = new ConcurrentHashMap<>();
    private final Map<UUID, Long> handledPearls = new ConcurrentHashMap<>();
    /** 等待落盘的玩家；值可能已被替换，写完后用引用相等判断是否清掉脏标记。 */
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final PearlPersistence persistence;
    private final ScheduledTask handledCleanupTask;

    PendingPearlStore(HowSAuth plugin) {
        this.persistence = new PearlPersistence(plugin,
                new PearlRecordFile(plugin.getDataFolder().toPath().resolve("pearls")),
                plugin.getDataFolder().toPath().resolve("pearls.dat"),
                pending, dirty);
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
        return snapshots != null
                && nearestIndex(snapshots, world, x, y, z, tolerance, snapshot -> true) >= 0;
    }

    /** 同一世界、给定半径内最近的合成 credit 下标；没有时返回 -1。 */
    int nearestSyntheticIndex(List<PearlSnapshot> snapshots, String world,
                              double x, double y, double z, double tolerance) {
        return nearestIndex(snapshots, world, x, y, z, tolerance,
                snapshot -> SYNTHETIC_PEARL_ID.equals(snapshot.pearlId()));
    }

    /** 同一世界、半径内满足条件且距离最近的快照下标；没有时返回 -1。 */
    private static int nearestIndex(List<PearlSnapshot> snapshots, String world,
                                    double x, double y, double z, double tolerance,
                                    Predicate<PearlSnapshot> filter) {
        int nearest = -1;
        double nearestDistance = tolerance * tolerance;
        for (int i = 0; i < snapshots.size(); i++) {
            PearlSnapshot snapshot = snapshots.get(i);
            if (snapshot.world() == null || !snapshot.world().equals(world)) continue;
            if (!filter.test(snapshot)) continue;
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
        update(owner, (uuid, oldList) -> {
            if (oldList == null) return List.of(snapshot);
            List<PearlSnapshot> merged = new ArrayList<>(oldList);
            merged.add(snapshot);
            return merged;
        });
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

    void save() {
        persistence.save();
    }

    void saveSync() {
        persistence.saveSync();
    }

    void saveSync(UUID owner) {
        persistence.saveSync(owner);
    }

    void shutdown() {
        handledCleanupTask.cancel();
        persistence.shutdown();
    }

    private void cleanupHandledPearls() {
        long cutoff = System.currentTimeMillis() - HANDLED_PEARL_TTL_MILLIS;
        handledPearls.forEach((pearlId, handledAt) -> {
            if (handledAt <= cutoff) handledPearls.remove(pearlId, handledAt);
        });
    }
}
