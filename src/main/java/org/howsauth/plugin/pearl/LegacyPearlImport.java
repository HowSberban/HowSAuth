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
                    PearlSnapshot snapshot = SnapshotCodec.parseSnapshot(map);
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
