package org.howsauth.plugin.pearl;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 末影珍珠快照的编解码与同一性判断。
 * <p>
 * 一组互相依靠的纯静态职责：快照数据类型、YAML 映射的解析与序列化、以及按世界与
 * 位置/速度的（带容差的）同一性比较。零实例状态，故从 {@link PendingPearlManager}
 * 独立出来——持久化格式的改动不再触碰珍珠生命周期逻辑，反之亦然。
 * <p>
 * 编解码方法为包内可见：供 {@code PearlSnapshotCodecTest} 直接单测。
 */
final class PearlSnapshotCodec {

    /** 位置/速度比较容差：浮点经历 YAML 往返后允许的误差 */
    private static final double STATE_MATCH_EPSILON = 1e-6;

    private PearlSnapshotCodec() {
    }

    /** 珍珠快照（包内可见：供单测使用） */
    record PearlSnapshot(UUID pearlId, boolean legacy, String world,
                         double x, double y, double z, double vx, double vy, double vz) {}

    /** 解析快照（包内可见：供单测使用）；world 缺失或非字符串时返回 null */
    static PearlSnapshot parseSnapshot(Map<?, ?> map) {
        Object worldValue = map.get("world");
        if (!(worldValue instanceof String world)) return null;
        Object pearlIdValue = map.get("pearlId");
        UUID pearlId = null;
        boolean legacy = pearlIdValue == null || Boolean.TRUE.equals(map.get("legacy"));
        if (pearlIdValue instanceof String id) {
            try {
                pearlId = UUID.fromString(id);
            } catch (IllegalArgumentException ignored) {
                legacy = true;
            }
        }
        return new PearlSnapshot(pearlId, legacy, world,
                number(map.get("x")), number(map.get("y")), number(map.get("z")),
                number(map.get("vx")), number(map.get("vy")), number(map.get("vz")));
    }

    /** 快照列表转 YAML 映射（包内可见：供单测使用） */
    static List<Map<String, Object>> serializeSnapshots(List<PearlSnapshot> snapshots) {
        List<Map<String, Object>> maps = new ArrayList<>(snapshots.size());
        for (PearlSnapshot snapshot : snapshots) {
            maps.add(serializeSnapshot(snapshot));
        }
        return maps;
    }

    private static Map<String, Object> serializeSnapshot(PearlSnapshot snapshot) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (snapshot.pearlId() != null) {
            map.put("pearlId", snapshot.pearlId().toString());
        } else if (snapshot.legacy()) {
            map.put("legacy", true);
        }
        map.put("world", snapshot.world());
        map.put("x", snapshot.x());
        map.put("y", snapshot.y());
        map.put("z", snapshot.z());
        map.put("vx", snapshot.vx());
        map.put("vy", snapshot.vy());
        map.put("vz", snapshot.vz());
        return map;
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    /** 快照是否描述同一世界下的同一位置与速度（带 {@link #STATE_MATCH_EPSILON} 容差） */
    static boolean sameState(PearlSnapshot snapshot, String world, Location loc, Vector vel) {
        return snapshot.world().equals(world)
                && Math.abs(snapshot.x() - loc.getX()) < STATE_MATCH_EPSILON
                && Math.abs(snapshot.y() - loc.getY()) < STATE_MATCH_EPSILON
                && Math.abs(snapshot.z() - loc.getZ()) < STATE_MATCH_EPSILON
                && Math.abs(snapshot.vx() - vel.getX()) < STATE_MATCH_EPSILON
                && Math.abs(snapshot.vy() - vel.getY()) < STATE_MATCH_EPSILON
                && Math.abs(snapshot.vz() - vel.getZ()) < STATE_MATCH_EPSILON;
    }
}
