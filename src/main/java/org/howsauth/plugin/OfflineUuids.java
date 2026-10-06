package org.howsauth.plugin;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 原版离线模式 UUID 推导。
 * <p>
 * 公式固定为 {@code UUID.nameUUIDFromBytes("OfflinePlayer:" + name)}（UTF-8），
 * 与 Vanilla / Bukkit 的离线 UUID 算法一致；登录、注册、正版升降级都依赖该映射。
 * <p>
 * 放在根包而非 {@code data} 或 {@code premium}：两层都要用，而 {@code data} 层刻意不依赖
 * {@code premium} 层，故由共同的上层提供唯一实现，避免同一算法出现第二份拷贝。
 */
public final class OfflineUuids {

    private OfflineUuids() {
    }

    /** 由玩家名推导离线 UUID；name 为 null 时返回 null */
    public static UUID of(String name) {
        if (name == null) return null;
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
