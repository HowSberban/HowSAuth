package org.howsauth.plugin.pearl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.howsauth.plugin.pearl.SnapshotCodec.PearlSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 末影珍珠快照编解码：二进制记录格式与旧版 YAML 迁移解析的读写契约。
 * <p>
 * 覆盖完整往返、旧格式兼容、截断/魔数/版本/CRC 损坏以及非法字段。
 */
class SnapshotCodecTest {

    private static final String WORLD = "world";
    private static final UUID PEARL_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** 新格式快照（带 pearlId）应在二进制往返后逐字段一致 */
    @Test
    void roundTripsModernSnapshot() throws IOException {
        PearlSnapshot original = new PearlSnapshot(PEARL_ID, false, WORLD, 1.5, 64.0, -2.25, 0.1, 0.2, 0.3);

        List<PearlSnapshot> decoded = SnapshotCodec.decode(SnapshotCodec.encode(List.of(original)));

        assertEquals(List.of(original), decoded, "round-tripped snapshot must equal the original");
    }

    /** 旧格式快照（无编号、legacy 标记）同样可往返 */
    @Test
    void roundTripsLegacySnapshot() throws IOException {
        PearlSnapshot original = new PearlSnapshot(null, true, WORLD, 0, 0, 0, 0, 0, 0);

        List<PearlSnapshot> decoded = SnapshotCodec.decode(SnapshotCodec.encode(List.of(original)));

        assertEquals(List.of(original), decoded, "legacy snapshot must survive the binary round trip");
    }

    /** 多条快照保持输入顺序，每个世界独立保存 */
    @Test
    void preservesOrderAndMultipleWorlds() throws IOException {
        UUID secondId = UUID.fromString("99999999-8888-7777-6666-555555555555");
        List<PearlSnapshot> snapshots = new ArrayList<>();
        snapshots.add(new PearlSnapshot(PEARL_ID, false, WORLD, 1, 1, 1, 0, 0, 0));
        snapshots.add(new PearlSnapshot(secondId, false, "nether", 2, 2, 2, 0, 0, 0));

        List<PearlSnapshot> decoded = SnapshotCodec.decode(SnapshotCodec.encode(snapshots));

        assertEquals(snapshots, decoded, "order and world names must be preserved");
    }

    /** 空记录可编码/解码（正常流程不会写空文件，但解码端不应因此崩溃） */
    @Test
    void roundTripsEmptyRecord() throws IOException {
        assertTrue(SnapshotCodec.decode(SnapshotCodec.encode(List.of())).isEmpty(),
                "an empty record must decode to an empty list");
    }

    /** 截断的记录必须报错，而不是返回半条数据 */
    @Test
    void rejectsTruncatedRecord() {
        byte[] record = SnapshotCodec.encode(List.of(
                new PearlSnapshot(PEARL_ID, false, WORLD, 1, 2, 3, 0, 0, 0)));
        byte[] truncated = Arrays.copyOf(record, record.length - 1);

        assertThrows(IOException.class, () -> SnapshotCodec.decode(truncated),
                "a truncated record must be rejected");
    }

    /** 魔数不符必须报错 */
    @Test
    void rejectsBadMagic() {
        byte[] record = SnapshotCodec.encode(List.of(
                new PearlSnapshot(PEARL_ID, false, WORLD, 1, 2, 3, 0, 0, 0)));
        record[0] ^= 0x01;

        assertThrows(IOException.class, () -> SnapshotCodec.decode(record),
                "a record with the wrong magic must be rejected");
    }

    /** 版本号不支持必须报错（为未来升级留出明确失败点，而不是误读） */
    @Test
    void rejectsUnsupportedVersion() {
        byte[] record = SnapshotCodec.encode(List.of(
                new PearlSnapshot(PEARL_ID, false, WORLD, 1, 2, 3, 0, 0, 0)));
        record[5] = 2;

        assertThrows(IOException.class, () -> SnapshotCodec.decode(record),
                "an unknown format version must be rejected");
    }

    /** CRC 不匹配必须报错，避免磁盘位翻转被当成有效坐标 */
    @Test
    void rejectsBadChecksum() {
        byte[] record = SnapshotCodec.encode(List.of(
                new PearlSnapshot(PEARL_ID, false, WORLD, 1, 2, 3, 0, 0, 0)));
        record[record.length - 1] ^= 0x01;

        assertThrows(IOException.class, () -> SnapshotCodec.decode(record),
                "a checksum mismatch must be rejected");
    }

    /** 非有限坐标不允许写入 */
    @Test
    void rejectsNonFiniteCoordinate() {
        PearlSnapshot snapshot = new PearlSnapshot(PEARL_ID, false, WORLD,
                Double.NaN, 2, 3, 0, 0, 0);

        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.encode(List.of(snapshot)),
                "NaN coordinates must be rejected before writing");
    }

    /** 缺少世界名不允许写入 */
    @Test
    void rejectsSnapshotWithoutWorld() {
        PearlSnapshot snapshot = new PearlSnapshot(PEARL_ID, false, null, 1, 2, 3, 0, 0, 0);

        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.encode(List.of(snapshot)),
                "a snapshot without a world must be rejected");
    }

    /** 旧格式记录（无 pearlId 字段）解析为 legacy 快照 */
    @Test
    void parsesLegacyEntryWithoutPearlId() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("world", WORLD);
        map.put("x", 1.0);
        map.put("y", 2.0);
        map.put("z", 3.0);

        PearlSnapshot parsed = LegacyPearlImport.parseSnapshot(map);

        assertNotNull(parsed, "a legacy entry with a world must be parsed");
        assertNull(parsed.pearlId(), "a legacy entry has no id");
        assertTrue(parsed.legacy(), "an entry without an id must be flagged as legacy");
        assertEquals(1.0, parsed.x(), "coordinates must be parsed");
    }

    /** 损坏的 pearlId（非法 UUID）降级为旧格式，不抛异常 */
    @Test
    void corruptPearlIdFallsBackToLegacy() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("pearlId", "not-a-uuid");
        map.put("world", WORLD);

        PearlSnapshot parsed = LegacyPearlImport.parseSnapshot(map);

        assertNotNull(parsed, "an entry with a world and a corrupt id must be parsed as legacy");
        assertNull(parsed.pearlId(), "an invalid id must be discarded");
        assertTrue(parsed.legacy(), "an invalid id must fall back to legacy (deduplicated by value)");
    }

    /** 缺少 world（或类型不对）的记录整条丢弃，返回 null */
    @Test
    void rejectsEntryWithoutUsableWorld() {
        assertNull(LegacyPearlImport.parseSnapshot(new LinkedHashMap<>()), "a missing world field must be discarded");
        Map<String, Object> wrongType = new LinkedHashMap<>();
        wrongType.put("world", 123);
        assertNull(LegacyPearlImport.parseSnapshot(wrongType), "a non-string world must be discarded");
    }

    /** 数值字段缺失时按 0 处理，不应抛异常 */
    @Test
    void defaultsMissingNumbersToZero() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("world", WORLD);
        map.put("legacy", true);

        PearlSnapshot parsed = LegacyPearlImport.parseSnapshot(map);

        assertNotNull(parsed, "an entry with only a world must be parsed");
        assertEquals(0.0, parsed.x(), "missing x must default to 0");
        assertEquals(0.0, parsed.y(), "missing y must default to 0");
        assertEquals(0.0, parsed.z(), "missing z must default to 0");
        assertEquals(0.0, parsed.vx(), "missing vx must default to 0");
        assertEquals(0.0, parsed.vy(), "missing vy must default to 0");
        assertEquals(0.0, parsed.vz(), "missing vz must default to 0");
    }

    /** 无编号且无 legacy 标记的记录读回时按旧格式处理（防御性：不丢失珍珠记录） */
    @Test
    void entryWithoutPearlIdOrLegacyFlagIsTreatedAsLegacy() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("world", WORLD);

        PearlSnapshot parsed = LegacyPearlImport.parseSnapshot(map);

        assertNotNull(parsed, "an entry without id or flag must still be parsed");
        assertNull(parsed.pearlId(), "no id");
        assertTrue(parsed.legacy(), "an entry with neither id nor flag must be treated as legacy");
    }

    /** 旧 YAML 中的 NaN/Infinity 不得进入内存：否则迁移永远写不出去，返还时还会污染世界坐标 */
    @Test
    void rejectsLegacyEntryWithNonFiniteCoordinate() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("world", WORLD);
        map.put("x", Double.NaN);

        assertNull(LegacyPearlImport.parseSnapshot(map), "NaN coordinates must be discarded during migration");
    }

    /** 空世界名或超出编码上限的世界名同样丢弃，避免迁移卡在永远失败的记录上 */
    @Test
    void rejectsLegacyEntryWithEmptyOrOversizedWorld() {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("world", "");
        assertNull(LegacyPearlImport.parseSnapshot(empty), "an empty world must be discarded");

        Map<String, Object> oversized = new LinkedHashMap<>();
        oversized.put("world", "w".repeat(300));
        assertNull(LegacyPearlImport.parseSnapshot(oversized), "an oversized world must be discarded");
    }

    /** 编码入口显式拒绝 null，避免只在写入深层抛 NPE */
    @Test
    void rejectsNullSnapshot() {
        List<PearlSnapshot> snapshots = new ArrayList<>();
        snapshots.add(null);

        assertThrows(IllegalArgumentException.class, () -> SnapshotCodec.encode(snapshots),
                "a null snapshot must be rejected");
    }
}
