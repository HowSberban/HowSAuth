package org.howsauth.plugin.premium;

import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.data.PlayerDataManager;
import org.howsauth.plugin.data.PlayerDataManager.PlayerData;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据服务（模块2）—— 纯内存仓储，包装 PlayerDataManager。
 * <p>
 * 全量加载策略：PlayerDataManager 启动时已全量加载至内存，
 * 本模块直接复用其内存缓存，运行时所有查询为 O(1) 内存操作。
 * <p>
 * 线程模型：查询操作为纯内存读取，可在任何线程（含 IO 线程）直接调用。
 * 持久化写入由 PlayerDataManager 异步落盘。
 */
public final class DataService {

    private final PlayerDataManager dataManager;
    private final ConfigManager configManager;

    // 离线确认缓存：ip + "|" + name(小写) -> 到期时间戳（毫秒）
    // 命中后跳过正版验证，直接走离线登录流程
    private final Map<String, Long> offlineConfirmed = new ConcurrentHashMap<>();
    // 正版密码回退标记：ip + "|" + name(小写) -> 到期时间戳（毫秒）
    // 正版验证失败后记录，玩家重连时命中则跳过正版验证、以正版 UUID 进入并用密码登录
    private final Map<String, Long> premiumFallbackConfirmed = new ConcurrentHashMap<>();

    public DataService(PlayerDataManager dataManager, ConfigManager configManager) {
        this.dataManager = dataManager;
        this.configManager = configManager;
    }

    /**
     * 按玩家名查询档案（纯内存，O(1)~O(n)）。
     * 先查离线 UUID（确定性计算），再查正版玩家名匹配。
     *
     * @param name 玩家名称（不区分大小写）
     * @return ProfileResult，exists=false 表示无记录
     */
    public ProfileResult getProfile(String name) {
        // 1. 计算离线 UUID 并查询
        UUID offlineUuid = offlineUuid(name);
        PlayerData offlineData = dataManager.getPlayer(offlineUuid);
        if (offlineData != null) {
            return new ProfileResult(true, offlineData.premium(), offlineUuid, offlineData.properties());
        }

        // 2. 按名查询正版玩家（premium=1 且 name 匹配）
        PlayerData premiumData = dataManager.getByName(name);
        if (premiumData != null) {
            return new ProfileResult(true, true, premiumData.uuid(), premiumData.properties());
        }

        // 3. 无记录
        return new ProfileResult(false, false, null, null);
    }

    /**
     * 保存正版玩家数据（同时更新内存 + 异步落盘）。
     * 若已有账号则标记为 premium，否则创建新记录（无密码，正版默认）。
     */
    public void savePremium(UUID uuid, String name, String ip, String propertiesJson) {
        if (dataManager.hasAccount(uuid)) {
            dataManager.markPremium(uuid, name, propertiesJson);
            return;
        }
        dataManager.createPremiumPlayer(uuid, name, ip, propertiesJson);
    }

    /**
     * 将离线账号迁移到正版账号（离线升级为正版）。
     * 透传 PlayerDataManager，保留退出位置等数据并标记 premium=1，密码置空（正版默认无密码）。
     * 目标正版 UUID 已有正版记录时保留原记录全部玩家数据（密码/2FA/退出位置等），仅更新名字与皮肤，离线号作废。
     *
     * @return true 常规迁移完成，调用方应随迁原版玩家数据文件；false 未迁移，调用方应跳过原版数据迁移
     */
    public boolean migrateToPremium(UUID offlineUuid, UUID premiumUuid, String name, String ip, String propertiesJson) {
        return dataManager.migrateToPremium(offlineUuid, premiumUuid, name, ip, propertiesJson);
    }

    /** 按玩家名查询正版账号（premium=1），用于正版验证失败时回退密码登录的 UUID 定位 */
    public PlayerData getByName(String name) {
        return dataManager.getByName(name);
    }

    /**
     * 标记离线确认：该 IP + 名 在有效期内重连时跳过正版验证。
     */
    public void markOfflineConfirmed(String ip, String name) {
        offlineConfirmed.put(cacheKey(ip, name),
                System.currentTimeMillis() + configManager.premiumCrackerCacheSeconds() * 1000L);
        enforceCap(offlineConfirmed);
    }

    /**
     * 检查离线确认标记是否有效。
     */
    public boolean isOfflineConfirmed(String ip, String name) {
        return isCacheValid(offlineConfirmed, ip, name);
    }

    /**
     * 标记正版验证回退：该 IP + 名 在有效期内重连时跳过正版验证，
     * 以正版 UUID 身份进入并用密码登录（复用离线标记机制）。
     * 用于正版玩家使用离线启动器（无法完成加密握手）或正版验证失败放行的场景。
     */
    public void markPremiumFallbackConfirmed(String ip, String name) {
        premiumFallbackConfirmed.put(cacheKey(ip, name),
                System.currentTimeMillis() + configManager.premiumFallbackCacheSeconds() * 1000L);
        enforceCap(premiumFallbackConfirmed);
    }

    /**
     * 检查正版验证回退标记是否有效。
     */
    public boolean isPremiumFallbackConfirmed(String ip, String name) {
        return isCacheValid(premiumFallbackConfirmed, ip, name);
    }

    /**
     * 清除正版验证回退标记（正版验证成功后调用，确保下次优先走正常正版验证免密登录）。
     */
    public void clearPremiumFallbackConfirmed(String ip, String name) {
        premiumFallbackConfirmed.remove(cacheKey(ip, name));
    }

    /** 计算离线 UUID（原版离线模式：UUID.nameUUIDFromBytes("OfflinePlayer:" + name)） */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String cacheKey(String ip, String name) {
        return ip + "|" + name.toLowerCase();
    }

    /** 检查缓存标记是否有效：未命中或已过期返回 false，过期时顺带清理 */
    private static boolean isCacheValid(Map<String, Long> map, String ip, String name) {
        String key = cacheKey(ip, name);
        Long expire = map.get(key);
        if (expire == null) return false;
        if (System.currentTimeMillis() > expire) {
            map.remove(key);
            return false;
        }
        return true;
    }

    /**
     * 强制缓存容量不超过配置的硬上限（premium.cache-cap）。
     * 攻击者可用不同 name+IP 组合高频触发标记并持续刷新，使过期清理永不到达，
     * 导致 Map 无限增长。故超限时先清理已过期项，仍超限则按到期时间升序逐出多余项
     * （最早到期 = 最不必需保留）。仅在标记写入时调用，超限场景下 O(n log n)，正常路径零开销。
     */
    private void enforceCap(Map<String, Long> map) {
        int cap = configManager.premiumCacheCap();
        if (cap <= 0 || map.size() <= cap) return;
        long now = System.currentTimeMillis();
        // 先清已过期项
        map.entrySet().removeIf(e -> e.getValue() <= now);
        // 仍超限：按到期时间排序，逐出最早到期的多余项
        if (map.size() > cap) {
            List<Map.Entry<String, Long>> byExpiry = map.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .toList();
            for (int i = 0; i < byExpiry.size() - cap; i++) {
                map.remove(byExpiry.get(i).getKey());
            }
        }
    }

    /**
     * 玩家档案查询结果（不可变）。
     *
     * @param exists     数据库中是否存在记录
     * @param premium    是否为正版账号（premium=1）
     * @param uuid       玩家 UUID（正版 UUID 或离线 UUID）
     * @param properties 皮肤 properties JSON（可能为 null）
     */
    public record ProfileResult(boolean exists, boolean premium, UUID uuid, String properties) {}
}
