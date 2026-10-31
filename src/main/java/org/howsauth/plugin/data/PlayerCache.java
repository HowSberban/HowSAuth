package org.howsauth.plugin.data;

import org.howsauth.plugin.OfflineUuids;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家内存缓存与名字索引。
 * <p>
 * 只负责"账号数据在内存里的存放与查找"，不触发落库、不做生命周期决策。
 * 持久化队列在 {@link PlayerPersistence}，账号业务在 {@link PlayerDataManager}。
 */
final class PlayerCache {

    private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
    // 正版玩家名索引：name(小写) → uuid，用于 getByName 快速查找，避免 O(n) 遍历
    private final Map<String, UUID> premiumNameIndex = new ConcurrentHashMap<>();

    /** 共享给持久化层读取当前缓存内容的只读入口（包内使用） */
    Map<UUID, PlayerData> players() {
        return players;
    }

    void clear() {
        players.clear();
        premiumNameIndex.clear();
    }

    /** 启动加载单行：放入缓存，正版记录同时建立名字索引 */
    void load(PlayerData data) {
        players.put(data.uuid(), data);
        if (data.premium() && data.name() != null) {
            premiumNameIndex.put(data.name().toLowerCase(), data.uuid());
        }
    }

    int size() {
        return players.size();
    }

    Set<UUID> uuids() {
        return players.keySet();
    }

    boolean contains(UUID uuid) {
        return players.containsKey(uuid);
    }

    PlayerData get(UUID uuid) {
        return players.get(uuid);
    }

    /** 缓存快照：用于不修改缓存的全量遍历（如不活跃账号清理） */
    List<PlayerData> all() {
        return List.copyOf(players.values());
    }

    /** 查找指定 IP 下的所有账号（用于管理员排查多账号） */
    List<PlayerData> findByIp(String ip) {
        if (ip == null || ip.isEmpty()) return List.of();
        return players.values().stream()
                .filter(d -> ip.equals(d.ip()))
                .toList();
    }

    /** 指定 IP 下的账号数（注册名额判定专用；空 IP 也按原样计数） */
    long countByIp(String ip) {
        return players.values().stream().filter(d -> ip.equals(d.ip())).count();
    }

    PlayerData getByName(String name) {
        if (name == null) return null;
        UUID uuid = premiumNameIndex.get(name.toLowerCase());
        return uuid != null ? players.get(uuid) : null;
    }

    /**
     * 按玩家名查找账号 UUID（正版按名索引，离线由名推导）
     * 以数据库记录为准，避免 usercache 同名缓存到不同 UUID 造成误删
     */
    UUID findUuidByName(String name) {
        if (name == null) return null;
        UUID uuid = premiumNameIndex.get(name.toLowerCase());
        if (uuid != null) return uuid;
        UUID offlineUuid = OfflineUuids.of(name);
        return players.containsKey(offlineUuid) ? offlineUuid : null;
    }

    boolean hasAccountByName(String name) {
        return findUuidByName(name) != null;
    }

    boolean isPremium(UUID uuid) {
        PlayerData data = players.get(uuid);
        return data != null && data.premium();
    }

    boolean hasPremiumAccount() {
        return !premiumNameIndex.isEmpty();
    }

    void put(UUID uuid, PlayerData data) {
        players.put(uuid, data);
    }

    /** 写入并按需建立正版名字索引（旧名字索引由调用方先行摘除） */
    void putIndexed(UUID uuid, PlayerData data, String nameToIndex) {
        players.put(uuid, data);
        if (nameToIndex != null) {
            premiumNameIndex.put(nameToIndex.toLowerCase(), uuid);
        }
    }

    PlayerData remove(UUID uuid) {
        return players.remove(uuid);
    }

    void index(String name, UUID uuid) {
        if (name != null) {
            premiumNameIndex.put(name.toLowerCase(), uuid);
        }
    }

    void unindex(String name) {
        if (name != null) {
            premiumNameIndex.remove(name.toLowerCase());
        }
    }
}
