package org.howsauth.plugin.data;

import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 玩家数据的异步落库与串行写队列。
 * <p>
 * {@link PlayerDataManager} 只保留内存缓存与账号业务；本类负责脏标记消费、批量 upsert、
 * 失败重试和关服排空。缓存 Map 与 dirty Set 由管理器持有并共享，避免两套状态漂移。
 * <p>
 * 所有 INSERT/UPDATE/DELETE 都经单线程队列执行，保证注销/迁移与普通保存按入队顺序落库。
 */
final class PlayerPersistence {

    private static final int MAX_FLUSH_RETRY = 3;
    /** 关服/等待写队列排空的等待上限（秒）：超时后强制关停，避免卡住关服 */
    private static final int DB_DRAIN_TIMEOUT_SECONDS = 30;

    private final HowSAuth plugin;
    private final PlayerDatabase database;
    private final Map<UUID, PlayerData> players;
    private final Set<UUID> dirty;
    /** 批量落库失败重试计数：达到上限后放弃该玩家，防止数据库故障时无限重试刷日志 */
    private final Map<UUID, Integer> flushFailures = new ConcurrentHashMap<>();
    private final ExecutorService dbWriteExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HowSAuth-DB-Writer");
        t.setDaemon(true);
        return t;
    });

    PlayerPersistence(HowSAuth plugin, PlayerDatabase database,
                      Map<UUID, PlayerData> players, Set<UUID> dirty) {
        this.plugin = plugin;
        this.database = database;
        this.players = players;
        this.dirty = dirty;
    }

    /** 标记玩家数据为脏：由周期任务批量落库（合并写、降 DB 开销；崩溃时最多丢失一个 flush 周期内的改动） */
    void save(UUID uuid) {
        if (players.containsKey(uuid)) {
            dirty.add(uuid);
        }
    }

    /** 立即落库指定玩家；不存在时静默跳过 */
    void saveNow(UUID uuid) {
        PlayerData data = players.get(uuid);
        if (data != null) {
            if (Debug.on()) {
                Debug.log("db", "save now: %s", Debug.shortId(uuid));
            }
            saveNow(data);
        }
    }

    /** 立即落库单个玩家数据，失败时退化为脏标记由周期任务兜底重试。 */
    void saveNow(PlayerData data) {
        submitWrite(() -> {
            if (players.get(data.uuid()) != data) return;
            if (!upsertBatchSync(List.of(data))) {
                dirty.add(data.uuid());
            } else {
                flushFailures.remove(data.uuid());
            }
        });
    }

    /** 周期任务调用（已在异步调度线程）：将脏标记的玩家数据批量落库，失败按上限重试 */
    void flushDirty() {
        if (dirty.isEmpty()) return;
        final List<PlayerData> toSave = new ArrayList<>(dirty.size());
        // 逐个移除而非整体 clear：避免与主线程并发 save() 竞态（clear 可能清掉刚标记的脏数据）
        for (UUID uuid : dirty) {
            dirty.remove(uuid);
            PlayerData data = players.get(uuid);
            if (data != null) toSave.add(data);
        }
        if (toSave.isEmpty()) return;
        if (Debug.on()) {
            Debug.log("db", "flush dirty: %s records queued", toSave.size());
        }
        submitWrite(() -> {
            // 执行时复检内存状态：快照后被移除或替换的记录跳过（已注销/迁移的账号不得被 upsert 复活）
            List<PlayerData> valid = new ArrayList<>(toSave.size());
            for (PlayerData data : toSave) {
                if (players.get(data.uuid()) == data) valid.add(data);
            }
            if (valid.isEmpty()) return;
            if (upsertBatchSync(valid)) {
                for (PlayerData data : valid) {
                    flushFailures.remove(data.uuid());
                }
            } else {
                for (PlayerData data : valid) {
                    int n = flushFailures.merge(data.uuid(), 1, Integer::sum);
                    if (n < MAX_FLUSH_RETRY) {
                        dirty.add(data.uuid());
                    } else {
                        flushFailures.remove(data.uuid()); // 放弃，停止重试
                    }
                }
            }
        });
    }

    /** 同步全量保存，用于 onDisable（必须在关服前完成，覆盖全部内存数据含脏标记）。 */
    void saveSync() {
        dirty.clear();
        shutdownExecutor();
        saveAllSync();
    }

    /**
     * 等待串行写队列排空（不关闭线程池）：向队列提交哨兵任务，其完成表明此前入队的写入均已落库。
     */
    void awaitPendingWrites() {
        try {
            dbWriteExecutor.submit(() -> {
            }).get(DB_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            // 队列已关闭（saveSync/close 之后）：不存在在途写入需要等待
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            plugin.getLogger().warning(I18n.get("log.db_write_await_timeout", DB_DRAIN_TIMEOUT_SECONDS));
        }
    }

    /** 清理某 UUID 的落库相关状态：脏标记与重试计数 */
    void forget(UUID uuid) {
        dirty.remove(uuid);
        flushFailures.remove(uuid);
    }

    /** 入队一次数据库写任务（迁移事务走这里，保证与 flush/saveNow 的落库顺序） */
    void submitWrite(Runnable job) {
        try {
            dbWriteExecutor.execute(job);
        } catch (RejectedExecutionException ignored) {
            // 队列已关闭（停用中）：丢弃，saveSync 全量保存兜底
        }
    }

    /** 关闭写队列并等待在途任务结束（不关闭数据库连接池） */
    void close() {
        shutdownExecutor();
    }

    private void shutdownExecutor() {
        dbWriteExecutor.shutdown();
        try {
            if (!dbWriteExecutor.awaitTermination(DB_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                // 超时未排空（DB 严重卡顿）：丢弃剩余任务（旧快照晚于全量保存落库会回退数据），最终状态由全量保存覆盖
                plugin.getLogger().severe(I18n.get("log.db_write_queue_timeout"));
                dbWriteExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 批量 upsert，整批一个事务；成功返回 true，失败返回 false */
    private boolean upsertBatchSync(List<PlayerData> list) {
        try {
            database.upsertRows(list);
            return true;
        } catch (SQLException e) {
            plugin.getLogger().severe(I18n.get("log.save_all_failed", e.getMessage()));
            if (Debug.on()) {
                Debug.log("db", "upsert batch failed: %s", e.getMessage());
            }
            return false;
        }
    }

    private void saveAllSync() {
        if (players.isEmpty()) return;
        upsertBatchSync(new ArrayList<>(players.values()));
    }
}
