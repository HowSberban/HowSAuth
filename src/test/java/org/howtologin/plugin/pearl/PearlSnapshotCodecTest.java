package org.howtologin.plugin.pearl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.howtologin.plugin.pearl.PendingPearlManager.PearlSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 末影珍珠快照编解码：pearls.dat 的读写契约。
 * <p>
 * 覆盖新格式（带 pearlId）、旧格式（legacy 标记、无 pearlId）、损坏数据（非法 UUID、缺 world）
 * 与缺省数值，保证升级/降级与人工改坏文件后不会抛异常或产生错误快照。
 */
class PearlSnapshotCodecTest {

    private static final String WORLD = "world";
    private static final UUID PEARL_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** 新格式快照：写出 pearlId，不写 legacy 标记 */
    @Test
    void serializesModernSnapshotWithPearlId() {
        PearlSnapshot snapshot = new PearlSnapshot(PEARL_ID, false, WORLD, 1.5, 64.0, -2.25, 0.1, 0.2, 0.3);

        List<Map<String, Object>> maps = PendingPearlManager.serializeSnapshots(List.of(snapshot));

        assertEquals(1, maps.size(), "exactly 1 snapshot record must be written");
        Map<String, Object> map = maps.getFirst();
        assertEquals(PEARL_ID.toString(), map.get("pearlId"), "the pearl id must be written");
        assertEquals(WORLD, map.get("world"), "the world name must be written");
        assertEquals(1.5, map.get("x"), "x must be written");
        assertEquals(64.0, map.get("y"), "y must be written");
        assertEquals(-2.25, map.get("z"), "z must be written");
        assertEquals(0.1, map.get("vx"), "vx must be written");
        assertEquals(0.2, map.get("vy"), "vy must be written");
        assertEquals(0.3, map.get("vz"), "vz must be written");
        assertFalse(map.containsKey("legacy"), "a modern snapshot must not write the legacy flag");
    }

    /** 旧格式快照（无编号）：写出 legacy 标记，不写 pearlId */
    @Test
    void serializesLegacySnapshotWithLegacyFlag() {
        PearlSnapshot snapshot = new PearlSnapshot(null, true, WORLD, 0, 0, 0, 0, 0, 0);

        Map<String, Object> map = PendingPearlManager.serializeSnapshots(List.of(snapshot)).getFirst();

        assertFalse(map.containsKey("pearlId"), "a legacy entry without an id must not write pearlId");
        assertEquals(Boolean.TRUE, map.get("legacy"), "a legacy entry must write the legacy flag");
    }

    /** 序列化再解析应还原全部字段（往返一致） */
    @Test
    void roundTripPreservesEveryField() {
        PearlSnapshot original = new PearlSnapshot(PEARL_ID, false, WORLD, 12.5, 70.25, -30.75, -0.5, 0.25, 1.5);

        PearlSnapshot parsed = PendingPearlManager.parseSnapshot(
                PendingPearlManager.serializeSnapshots(List.of(original)).getFirst());

        assertEquals(original, parsed, "round-tripped snapshot must equal the original (record equality)");
    }

    /** 旧格式记录（无 pearlId 字段）解析为 legacy 快照 */
    @Test
    void parsesLegacyEntryWithoutPearlId() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("world", WORLD);
        map.put("x", 1.0);
        map.put("y", 2.0);
        map.put("z", 3.0);

        PearlSnapshot parsed = PendingPearlManager.parseSnapshot(map);

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

        PearlSnapshot parsed = PendingPearlManager.parseSnapshot(map);

        assertNotNull(parsed, "an entry with a world and a corrupt id must be parsed as legacy");
        assertNull(parsed.pearlId(), "an invalid id must be discarded");
        assertTrue(parsed.legacy(), "an invalid id must fall back to legacy (deduplicated by value)");
    }

    /** 缺少 world（或类型不对）的记录整条丢弃，返回 null */
    @Test
    void rejectsEntryWithoutUsableWorld() {
        assertNull(PendingPearlManager.parseSnapshot(new LinkedHashMap<>()), "a missing world field must be discarded");
        Map<String, Object> wrongType = new LinkedHashMap<>();
        wrongType.put("world", 123);
        assertNull(PendingPearlManager.parseSnapshot(wrongType), "a non-string world must be discarded");
    }

    /** 数值字段缺失时按 0 处理，不应抛异常 */
    @Test
    void defaultsMissingNumbersToZero() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("world", WORLD);
        map.put("legacy", true);

        PearlSnapshot parsed = PendingPearlManager.parseSnapshot(map);

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

        PearlSnapshot parsed = PendingPearlManager.parseSnapshot(map);

        assertNotNull(parsed, "an entry without id or flag must still be parsed");
        assertNull(parsed.pearlId(), "no id");
        assertTrue(parsed.legacy(), "an entry with neither id nor flag must be treated as legacy");
    }

    /** 多条快照按输入顺序逐一写出 */
    @Test
    void serializesMultipleSnapshotsInOrder() {
        UUID secondId = UUID.fromString("99999999-8888-7777-6666-555555555555");
        List<PearlSnapshot> snapshots = new ArrayList<>();
        snapshots.add(new PearlSnapshot(PEARL_ID, false, WORLD, 1, 1, 1, 0, 0, 0));
        snapshots.add(new PearlSnapshot(secondId, false, "nether", 2, 2, 2, 0, 0, 0));

        List<Map<String, Object>> maps = PendingPearlManager.serializeSnapshots(snapshots);

        assertEquals(2, maps.size(), "exactly 2 records must be written");
        assertEquals(PEARL_ID.toString(), maps.getFirst().get("pearlId"), "order must match the input order");
        assertEquals(secondId.toString(), maps.get(1).get("pearlId"), "order must match the input order");
        assertEquals("nether", maps.get(1).get("world"), "each world must be preserved");
    }
}
