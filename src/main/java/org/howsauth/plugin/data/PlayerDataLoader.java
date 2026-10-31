package org.howsauth.plugin.data;

import org.bukkit.Bukkit;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;

import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

/**
 * 启动时玩家数据加载与数据库故障重试。
 * <p>
 * 只负责把数据库行装进 {@link PlayerCache}，以及 fail-closed 状态与自动重试；
 * 查询、写入、账号生命周期分别在缓存/持久化/账号操作中。
 */
final class PlayerDataLoader {

    private static final int LOAD_RETRY_PERIOD_SECONDS = 10;

    private final HowSAuth plugin;
    private final PlayerDatabase database;
    private final PlayerCache cache;
    // 数据库全量加载是否失败：失败期间 fail-closed，拒绝新玩家进入（空缓存会把所有玩家误判为未注册）
    private volatile boolean loadFailed;
    // 加载失败自动重试任务是否已挂起，防止周期任务叠加
    private volatile boolean retryScheduled;

    PlayerDataLoader(HowSAuth plugin, PlayerDatabase database, PlayerCache cache) {
        this.plugin = plugin;
        this.database = database;
        this.cache = cache;
    }

    /** 启动时全量加载玩家数据到内存缓存；失败时置 fail-closed 标记并自动重试直至成功 */
    void load() {
        cache.clear();
        try {
            for (PlayerData data : database.loadAllRows()) {
                cache.load(data);
            }
            loadFailed = false;
            if (Debug.on()) {
                Debug.log("db", "load complete: %s accounts loaded", cache.size());
            }
        } catch (SQLException e) {
            loadFailed = true;
            plugin.getLogger().severe(I18n.get("log.load_players_failed", e.getMessage()));
            scheduleRetry();
        }
    }

    /** 数据库恢复前的自动重试（10 秒周期，成功即停）；期间 isLoadFailed=true 拒绝新玩家进入 */
    private void scheduleRetry() {
        if (retryScheduled) return;
        retryScheduled = true;
        Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
            if (!loadFailed) {
                retryScheduled = false;
                task.cancel();
                return;
            }
            plugin.getLogger().warning(I18n.get("log.load_retry"));
            load();
        }, LOAD_RETRY_PERIOD_SECONDS, LOAD_RETRY_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    boolean isLoadFailed() {
        return loadFailed;
    }
}
