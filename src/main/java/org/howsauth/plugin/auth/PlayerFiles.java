package org.howsauth.plugin.auth;

import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.File;
import java.util.UUID;

/**
 * Minecraft 原版玩家数据文件（player.dat / advancements / stats）的删除与 UUID 迁移。
 * <p>
 * 一组互相依靠的文件系统职责，与账号业务逻辑（注册、注销标记、正版升降级）无关，
 * 故此从 {@link AccountLifecycle} 独立出来：账号逻辑的改动不牵连文件路径处理，
 * 世界目录结构（26.1+ 的 players/ 布局）也只在此处感知。
 * <p>
 * <b>线程契约</b>：本类所有公开入口都在异步调度器上执行真正的文件 IO 与重试 sleep，
 * 调用方不会被阻塞；阻塞式的重试循环 {@code retryDelete} 为私有，只由本类内部调度。
 */
public final class PlayerFiles {

    // 删除重试时序：首次等服务器把 .dat 保存完，之后密集重试，总时长封顶
    /** 首次重试前的等待（毫秒）：等服务器完成 .dat 保存 */
    private static final long DELETE_FIRST_DELAY_MS = 1000L;
    /** 首次之后的重试间隔（毫秒） */
    private static final long DELETE_RETRY_DELAY_MS = 300L;
    /** 重试总时长上限（毫秒）：超过即放弃并告警 */
    private static final long DELETE_RETRY_WINDOW_MS = 5000L;

    private final HowSAuth plugin;
    private final ConfigManager configManager;
    private final SessionStore sessions;
    // 缓存世界结构类型：26.1+ 采用新结构（players/data + dimensions/minecraft/overworld）
    private final boolean newWorldStructure;

    PlayerFiles(HowSAuth plugin, ConfigManager configManager, SessionStore sessions) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.sessions = sessions;
        this.newWorldStructure = detectNewWorldStructure();
    }

    /**
     * 检测服务端是否使用 26.1+ 的新世界文件结构。
     * Bukkit.getBukkitVersion() 返回如 "1.21.11-R0.1-SNAPSHOT" 或 "26.1.2-R0.1-SNAPSHOT"。
     * 26.1+ 主版本号 >= 26，旧版 1.x 主版本号始终为 1。
     */
    private static boolean detectNewWorldStructure() {
        String version = Bukkit.getBukkitVersion();
        int dash = version.indexOf('-');
        String nums = dash > 0 ? version.substring(0, dash) : version;
        String[] parts = nums.split("\\.");
        try {
            return Integer.parseInt(parts[0]) >= 26;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 在 PlayerQuitEvent 中调用：异步重试删除玩家 .dat 文件。
     * 玩家被踢出后服务器仍会将其数据保存到 .dat，早于保存完成的删除会被覆盖回写。
     * 采用重试机制：首次延迟 {@link #DELETE_FIRST_DELAY_MS}（等保存完成）后尝试，
     * 文件仍存在则每 {@link #DELETE_RETRY_DELAY_MS} 重试，
     * {@link #DELETE_RETRY_WINDOW_MS} 内持续尝试，确保服务器完成保存后能可靠删除。
     */
    public void deleteOnQuit(UUID uuid) {
        if (!sessions.consumePendingDatDelete(uuid)) return;
        if (Debug.on()) {
            Debug.log("db", "delete vanilla data on quit: %s queued", Debug.shortId(uuid));
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> retryDelete(uuid));
    }

    /**
     * 异步删除玩家原版数据（player.dat、advancements、stats），复用 unregister 的重试删除逻辑。
     * 账号合并作废场景使用：离线号记录删除后，遗留文件会被同名新注册玩家继承，必须一并清理。
     * 遵循 settings.real-unreg 配置；可在网络线程调用（内部异步调度，不阻塞调用线程）。
     */
    public void deleteAsync(UUID uuid) {
        if (!configManager.settings().realUnreg()) return;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> retryDelete(uuid));
    }

    /**
     * 重试删除玩家原版数据，{@link #DELETE_RETRY_WINDOW_MS} 内持续尝试。
     * <p>
     * <b>会阻塞调用线程（最坏 {@link #DELETE_RETRY_WINDOW_MS}）</b>，因此只允许从异步线程调用。
     * 本类的公开入口 {@link #deleteOnQuit}/{@link #deleteAsync} 已自行调度到异步线程，
     * 调用方无需也不得直接调用本方法。
     */
    @SuppressWarnings("BusyWait")
    private void retryDelete(UUID uuid) {
        long elapsed = 0;
        while (true) {
            long delay = elapsed == 0 ? DELETE_FIRST_DELAY_MS : DELETE_RETRY_DELAY_MS;
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            elapsed += delay;
            if (delete(uuid)) return;
            if (elapsed >= DELETE_RETRY_WINDOW_MS) {
                plugin.getLogger().warning(I18n.get("log.delete_player_data_failed", uuid));
                if (Debug.on()) {
                    Debug.log("db", "delete vanilla data for %s: failed after retries", Debug.shortId(uuid));
                }
                return;
            }
        }
    }

    /**
     * 删除 Minecraft 原版玩家数据（player.dat、advancements、stats）。
     * 目录结构兼容（通过服务端版本判断，构造时缓存）：
     *   - 旧版（&lt; 26.1）：world/playerdata、world/advancements、world/stats
     *   - 26.1+：world/players/data、world/players/advancements、world/players/stats
     *     （worldDir 是维度目录 world/dimensions/minecraft/overworld，玩家数据在其上级 3 层的世界根目录下）
     * 由 deleteOnQuit 异步重试调用（服务器保存 .dat 后再删除）。
     * @return true 表示文件已删除或不存在（成功）；false 表示文件仍存在（需重试）
     */
    private boolean delete(UUID uuid) {
        World world = Bukkit.getWorlds().getFirst();
        File worldRoot = worldRoot(world);
        if (worldRoot == null) {
            plugin.getLogger().warning(I18n.get("log.player_data_dir_not_found", world.getWorldFolder().getAbsolutePath()));
            return true; // 目录不存在视为无需删除，停止重试
        }

        String[] dirs = playerDataDirs();
        // 删除 .dat_old（备份文件，失败仅告警，不影响重试）
        File datOldFile = new File(worldRoot, dirs[0] + "/" + uuid + ".dat_old");
        if (datOldFile.exists() && !datOldFile.delete()) {
            plugin.getLogger().warning(I18n.get("log.delete_player_data_backup_failed", datOldFile.getAbsolutePath()));
        }

        // 删除 .dat、advancements/.json、stats/.json，任一失败则重试
        return deletePlayerFile(new File(worldRoot, dirs[0]), uuid, ".dat")
                && deletePlayerFile(new File(worldRoot, dirs[1]), uuid, ".json")
                && deletePlayerFile(new File(worldRoot, dirs[2]), uuid, ".json");
    }

    /**
     * 将离线账号的原版玩家数据（player.dat、advancements、stats）迁移到正版 UUID。
     * 升级后 UUID 变化，若不迁移这些文件，玩家的背包/成就/统计会丢失。
     * 异步执行文件重命名（阻塞文件 IO，调用方无需关心线程）。
     */
    public void migrateAsync(UUID fromUuid, UUID toUuid) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            World world = Bukkit.getWorlds().getFirst();
            File worldRoot = worldRoot(world);
            if (worldRoot == null) {
                plugin.getLogger().warning(I18n.get("log.player_data_dir_not_found", world.getWorldFolder().getAbsolutePath()));
                return;
            }
            String[] dirs = playerDataDirs();
            renamePlayerFile(new File(worldRoot, dirs[0]), fromUuid, toUuid, ".dat");
            renamePlayerFile(new File(worldRoot, dirs[1]), fromUuid, toUuid, ".json");
            renamePlayerFile(new File(worldRoot, dirs[2]), fromUuid, toUuid, ".json");
        });
    }

    /** 将玩家文件 &lt;from&gt;.&lt;ext&gt; 重命名为 &lt;to&gt;.&lt;ext&gt;（目标已存在则先删除旧目标） */
    private void renamePlayerFile(File dir, UUID from, UUID to, String ext) {
        if (!dir.isDirectory()) return;
        File src = new File(dir, from + ext);
        if (!src.exists()) return;
        File dst = new File(dir, to + ext);
        if (dst.exists() && !dst.delete()) {
            plugin.getLogger().warning(I18n.get("log.migrate_failed", src.getAbsolutePath()));
            return;
        }
        if (!src.renameTo(dst)) {
            plugin.getLogger().warning(I18n.get("log.migrate_failed", src.getAbsolutePath()));
        }
    }

    /**
     * 计算世界根目录（玩家数据所在目录）。
     * 26.1+：世界文件夹是维度目录 world/dimensions/minecraft/overworld，
     *   玩家数据在其上级 3 层的世界根目录下；旧版：世界文件夹即根目录。
     */
    private File worldRoot(World world) {
        // Paper 的 getWorldFolder() @NotNull，无需判空；26.1+ 向上 3 层到世界根目录
        File worldDir = world.getWorldFolder();
        if (!newWorldStructure) return worldDir;
        // 26.1+：向上 3 层到世界根目录
        File root = worldDir.getParentFile(); // minecraft
        if (root != null) root = root.getParentFile(); // dimensions
        if (root != null) root = root.getParentFile(); // world 根
        return root;
    }

    /**
     * 玩家数据三个子目录（data/advancements/stats），兼容新旧世界结构。
     * 26.1+ 在 players/ 下，旧版在根目录下（playerdata 名称也不同）。
     */
    private String[] playerDataDirs() {
        if (newWorldStructure) {
            return new String[]{"players/data", "players/advancements", "players/stats"};
        }
        return new String[]{"playerdata", "advancements", "stats"};
    }

    /**
     * 删除指定目录下的玩家文件（&lt;uuid&gt;.&lt;ext&gt;）。
     * @return true 表示文件已删除或目录/文件不存在；false 表示文件仍存在（需重试）
     */
    private boolean deletePlayerFile(File dir, UUID uuid, String ext) {
        if (!dir.isDirectory()) return true;
        File file = new File(dir, uuid + ext);
        if (!file.exists()) return true;
        return file.delete();
    }
}
