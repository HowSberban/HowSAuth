package org.howsauth.plugin.pearl;

import org.bukkit.configuration.file.YamlConfiguration;
import org.howsauth.plugin.pearl.SnapshotCodec.PearlSnapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 旧版 {@code pearls.dat}（YAML 文本）的一次性读取。
 * <p>
 * 只在启动迁移时使用：把旧文件解析为内存快照后写成每玩家二进制记录，再把旧文件改名备份。
 * 解析失败的单条记录会被跳过而不是让整次迁移失败——能救回的珍珠照常迁移。
 */
final class LegacyPearlImport {

    private LegacyPearlImport() {
    }

    /**
     * 解析一条旧版 YAML 快照；world 缺失/为空/过长、坐标非有限值时返回 null。
     * <p>
     * 这些记录若留在内存里，迁移会永远失败，返还时还可能把 NaN 坐标传给世界生成逻辑。
     */
    static PearlSnapshot parseSnapshot(Map<?, ?> map) {
        Object worldValue = map.get("world");
        if (!(worldValue instanceof String world)) return null;
        if (!SnapshotCodec.isValidWorld(world)) return null;
        double x = number(map.get("x"));
        double y = number(map.get("y"));
        double z = number(map.get("z"));
        double vx = number(map.get("vx"));
        double vy = number(map.get("vy"));
        double vz = number(map.get("vz"));
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(vx) || !Double.isFinite(vy) || !Double.isFinite(vz)) {
            return null;
        }
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
        return new PearlSnapshot(pearlId, legacy, world, x, y, z, vx, vy, vz);
    }

    private static double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    static Map<UUID, List<PearlSnapshot>> read(Path file, Consumer<String> warning) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        Map<UUID, List<PearlSnapshot>> imported = new LinkedHashMap<>();
        for (String key : yaml.getKeys(false)) {
            UUID owner;
            try {
                owner = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                warning.accept("invalid player uuid: " + key);
                continue;
            }
            List<PearlSnapshot> snapshots = new ArrayList<>();
            try {
                for (Map<?, ?> map : yaml.getMapList(key)) {
                    PearlSnapshot snapshot = parseSnapshot(map);
                    if (snapshot == null) {
                        warning.accept("invalid snapshot for " + key);
                        continue;
                    }
                    snapshots.add(snapshot);
                }
            } catch (RuntimeException e) {
                // 单个玩家的列表结构损坏不应让整个迁移中止：其余玩家仍应被导入，坏条目留在备份文件里。
                warning.accept("invalid snapshot list for " + key + ": " + e.getMessage());
                continue;
            }
            if (!snapshots.isEmpty()) {
                imported.put(owner, List.copyOf(snapshots));
            }
        }
        return imported;
    }
}
