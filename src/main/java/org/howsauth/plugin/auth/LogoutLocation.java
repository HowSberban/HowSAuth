package org.howsauth.plugin.auth;

import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.data.PlayerDataManager;
import org.howsauth.plugin.data.PlayerData;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 退出位置与坐标保护：保存/读取上次退出位置、登录后传送回去、安全出生点、区块预载、
 * 悬空判定，以及未登录期间的旁观切换。
 * <p>
 * 位置与游戏模式本身持久化在 {@link PlayerData}，本类不自有集合；过渡期标记
 * （无敌/旁观待恢复）由 {@link SessionStore} 持有。
 * <p>
 * <b>线程契约</b>：{@link #preloadChunk} 为异步 fire-and-forget、绝不阻塞（pre-login 阶段调用）；
 * {@link #setSpectator} 与 {@link #isBlockSolidBelow} 在玩家区域线程同步执行（仅读已加载区块，
 * 未加载则按"悬空"保守处理，不触发加载）；
 * {@code setGameMode} 一律经玩家调度器执行，保证 Folia 下在玩家区域线程调用。
 */
public final class LogoutLocation {

    private final HowSAuth plugin;
    private final PlayerDataManager dataManager;
    private final ConfigManager configManager;
    private final SessionStore sessions;

    LogoutLocation(HowSAuth plugin, PlayerDataManager dataManager, ConfigManager configManager,
                   SessionStore sessions) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.configManager = configManager;
        this.sessions = sessions;
    }

    /**
     * 保存玩家当前退出位置和游戏模式。
     * 退出流程仅对本次连接已认证的玩家调用：未认证玩家的位置是登录前的保护/出生点，写入会覆盖真实退出位置
     */
    public void save(Player player) {
        PlayerData data = dataManager.getPlayer(player.getUniqueId());
        if (data == null) return;
        capture(data, player);
        dataManager.save(player.getUniqueId());
    }

    /**
     * 仅更新内存缓存中的退出位置和游戏模式，不落库。
     * 用于 onDisable：插件禁用后无法注册异步任务，改为更新缓存后由 saveSync 统一落库。
     */
    public void updateCache(Player player) {
        PlayerData data = dataManager.getPlayer(player.getUniqueId());
        if (data != null) {
            capture(data, player);
        }
    }

    /** 把当前位置与游戏模式写入内存数据（不落库），供退出保存与关服兜底共用。
     *  登录过渡期（传送或游戏模式恢复尚未落地）跳过：此时读到的是保护出生点与临时旁观模式，写入会覆盖真实值 */
    private void capture(PlayerData data, Player player) {
        UUID uuid = player.getUniqueId();
        if (sessions.isInvulnerablePending(uuid) || sessions.isSpectatorPending(uuid)) {
            if (Debug.on()) {
                Debug.log("flow", "skip logout location for %s: login transition pending (teleport/gamemode not settled)", player.getName());
            }
            return;
        }
        data.logoutLocation(PlayerData.serializeLocation(player.getLocation()));
        data.gameMode(player.getGameMode().name());
    }

    /**
     * 登录/注册成功后，传送回上次退出位置。
     * 如果没有保存的位置（新玩家），不传送（留在世界出生点）。
     * 使用 teleportAsync 以兼容 Folia（Folia 禁止同步 teleport）。
     * 调用时机：玩家已在世界中（密码登录/注册/forcelogin），非 PlayerJoinEvent 期间。
     */
    public void teleportBack(Player player) {
        Location loc = get(player);
        if (loc == null) {
            // 新玩家没有保存的位置，留在世界出生点
            return;
        }
        // 标记传送过渡期，保持无敌
        sessions.addInvulnerablePending(player.getUniqueId());
        // 异步传送；完成与异常都要移除无敌标记——thenAccept 在 future 异常完成时不执行，
        // 标记一旦残留会让该玩家本次会话永久免伤（onDamage 依据该标记取消伤害）
        player.teleportAsync(loc).whenComplete((success, error) ->
                sessions.removeInvulnerablePending(player.getUniqueId()));
    }

    /** 获取玩家上次退出位置（无保存位置返回 null） */
    public Location get(Player player) {
        return get(player.getUniqueId());
    }

    /** 获取玩家上次退出位置（无保存位置返回 null） */
    public Location get(UUID uuid) {
        PlayerData data = dataManager.getPlayer(uuid);
        if (data == null) return null;
        return PlayerData.deserializeLocation(data.logoutLocation());
    }

    /**
     * 异步预载玩家退出位置所在区块（fire-and-forget，绝不阻塞、绝不抛异常）。
     * <p>
     * 供 pre-login 异步阶段调用：目的是让加入时的悬空判定直接命中已加载区块，
     * 避免在 tick 关键路径（区域线程/主线程）上等待区块加载。
     * 只触发加载、不等待结果，异常一律吞掉，pre-login 路径不得因此出错。
     */
    public void preloadChunk(UUID uuid) {
        try {
            // 坐标保护 / 旁观强制开启时 setSpectator 不做悬空判定；
            // advanced.dangling-check 关闭（默认）时判定整段被跳过：三种情况都无需预载
            if (configManager.protectionMisc().gamemodeEnabled() || configManager.protectionPosition().enabled()
                    || !configManager.settings().danglingCheck()) return;
            Location logoutLoc = get(uuid);
            if (logoutLoc == null) return;
            World world = logoutLoc.getWorld();
            if (world == null) return;
            int cx = logoutLoc.getBlockX() >> 4;
            int cz = logoutLoc.getBlockZ() >> 4;
            // 区块已加载则无需再触发加载票据
            if (world.isChunkLoaded(cx, cz)) return;
            // 只预载不等结果：不 join、不消费结果，异步异常仅吞掉
            world.getChunkAtAsyncUrgently(cx, cz).exceptionally(error -> null);
        } catch (Exception ignored) {
            // 预载是尽力而为：任何异常都不影响加入流程
        }
    }

    /**
     * 未登录期间切换为旁观模式。
     * 标记玩家为 spectatorPending，onLoginSuccess 时据此恢复游戏模式。
     * <p>
     * 当 gamemode.enabled=false 时，若坐标保护未开启且退出位置悬空，仍强制切换为旁观模式：
     * 退出位置悬空时玩家会在该处坠落暴露位置。
     * <p>
     * 悬空判定受 advanced.dangling-check 控制：false（默认）时不执行判定，整段处理跳过、本方法直接返回，
     * 玩家保持原游戏模式；true 时才走 {@link #isBlockSolidBelow} 判定。
     * 悬空判定只读**本服务器已加载**的区块：未加载按"悬空"保守处理，绝不触发加载或等待，
     * 因此不会在 Folia 下为读取外部区域方块而阻塞玩家区域线程。
     * 最终 setGameMode 使用玩家调度器执行，保证 Folia 下在玩家区域线程调用（非线程安全）。
     */
    public void setSpectator(Player player) {
        if (!configManager.protectionMisc().gamemodeEnabled()) {
            // 未启用悬空判定（默认）：不处理也不旁观，直接返回，玩家保持原游戏模式
            if (!configManager.settings().danglingCheck()) return;
            // 旁观模式未开启时，仅在坐标保护未开启且退出位置悬空时仍切换为旁观
            if (configManager.protectionPosition().enabled()) return;
            Location logoutLoc = get(player);
            if (logoutLoc == null || logoutLoc.getWorld() == null) return;
            if (isBlockSolidBelow(logoutLoc)) return;
        }
        sessions.addSpectatorPending(player.getUniqueId());
        player.getScheduler().run(plugin, task -> player.setGameMode(org.bukkit.GameMode.SPECTATOR), null);
    }

    /** 判断退出位置正下方方块是否固体（用于悬空检查） */
    private static boolean isBlockSolidBelow(Location loc) {
        World world = loc.getWorld();
        int y = loc.getBlockY() - 1;
        if (y <= world.getMinHeight() || y >= world.getMaxHeight()) return false;
        // 区块未加载时按"悬空"保守处理（不加载、不等待）：强制旁观，
        // 登录成功后 onLoginSuccess 照常恢复原游戏模式；
        // 等待期玩家移动被 onMove 拦下、伤害被 onDamage 拦下，无风险。
        // pre-login 的 preloadChunk 通常已把该区块加载好，故这里一般能读到真实方块。
        if (!world.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return false;
        return loc.getBlock().getRelative(BlockFace.DOWN).getType().isSolid();
    }

    /**
     * 在主世界出生点周围寻找能立足的随机位置（老玩家专用）。
     * 安全标准放宽：只需"下方固体方块"（能站立）。
     * 因为未登录期间 onDamage 取消伤害，玩家不会因悬空/水中/岩浆受伤；
     * 登录后立即传送到上次退出位置，离开临时位置。
     * 默认尝试 10 次，全部失败则回退到世界出生点（玩家无敌，出生点不安全也不会死）。
     * 此方法会阻塞等待区块加载，应在异步线程中调用。
     * 若配置为固定坐标模式，直接返回配置的固定位置。
     */
    public Location findSafeAuthSpawn(World world) {
        // 固定坐标模式：直接使用配置的坐标
        if (configManager.protectionPosition().fixedMode()) {
            return configManager.protectionPosition().fixedLocation(world);
        }

        Location spawn = world.getSpawnLocation();
        int radius = configManager.protectionPosition().spawnRadius();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // 多次重试，模仿原版 MC 寻找安全出生点的机制
        for (int attempt = 0; attempt < 10; attempt++) {
            int x = (int) (spawn.getX() + (random.nextDouble() * 2 - 1) * radius);
            int z = (int) (spawn.getZ() + (random.nextDouble() * 2 - 1) * radius);
            // 阻塞等待区块加载（调用方应在异步线程）
            org.bukkit.Chunk chunk = world.getChunkAtAsyncUrgently(x >> 4, z >> 4).join();
            int y = findSafeSpawnY(chunk.getChunkSnapshot(), x & 15, z & 15, world);
            if (y != Integer.MIN_VALUE) {
                return new Location(world, x + 0.5, y, z + 0.5);
            }
        }
        // 全部失败：回退到世界出生点（玩家无敌期间不会受伤）
        return spawn;
    }

    /**
     * 从最高方块上方开始向下找能立足的 y 坐标。
     * 安全标准：下方是固体方块（能站立）。
     * 找不到时返回 Integer.MIN_VALUE，由调用方重试或回退。
     */
    private static int findSafeSpawnY(org.bukkit.ChunkSnapshot snapshot, int x, int z, World world) {
        int highestY = snapshot.getHighestBlockYAt(x, z);
        // 最高方块上方即视为可立足（下方=最高方块，只需检查它是否固体）
        if (highestY > world.getMinHeight()) {
            BlockData below = snapshot.getBlockData(x, highestY, z);
            if (below.getMaterial().isSolid()) {
                return highestY + 1;
            }
        }
        return Integer.MIN_VALUE;
    }
}
