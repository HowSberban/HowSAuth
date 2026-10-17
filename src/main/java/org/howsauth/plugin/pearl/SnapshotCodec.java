package org.howsauth.plugin.pearl;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

/**
 * 末影珍珠快照的编解码与同一性判断。
 * <p>
 * 落盘格式是带版本与 CRC32 的二进制记录，而不是 YAML：
 * <pre>
 * int   magic "HSP1"
 * short formatVersion
 * int   payloadLength
 * int   payloadCrc32
 * byte[payloadLength] payload
 * </pre>
 * payload 为 {@code int count} 加若干快照记录。每个快照记录使用固定顺序的
 * flags/pearlId/world/六个 double，不依赖反射，也不会因为字段名拼写变化而静默丢数据。
 * <p>
 * 解析旧版 {@code pearls.dat}（YAML 映射）的方法保留在 {@link #parseSnapshot(Map)}，
 * 仅用于启动时的一次性迁移；运行期只读写二进制记录。
 * <p>
 * 编解码方法为包内可见：供 {@code SnapshotCodecTest} 直接单测。
 */
final class SnapshotCodec {

    /** 单玩家最多保存的快照数：超过视为损坏文件，避免恶意/异常数据无限占用内存 */
    static final int MAX_SNAPSHOTS = 1024;
    /** 单条记录允许的最大字节数（含头），用于拒绝明显损坏或异常膨胀的文件 */
    static final int MAX_RECORD_BYTES = 1024 * 1024;

    /** 文件头魔数："HSP1"（HowSAuth Pearl v1） */
    private static final int MAGIC = 0x48535031;
    private static final short FORMAT_VERSION = 1;
    private static final int HEADER_BYTES = Integer.BYTES + Short.BYTES + Integer.BYTES + Integer.BYTES;
    private static final int MAX_WORLD_BYTES = 256;
    private static final int FLAG_PEARL_ID = 1;
    private static final int FLAG_LEGACY = 1 << 1;
    private static final int FLAG_MASK = FLAG_PEARL_ID | FLAG_LEGACY;

    /** 位置/速度比较容差：浮点经历磁盘往返后允许的误差 */
    private static final double STATE_MATCH_EPSILON = 1e-6;

    private SnapshotCodec() {
    }

    /** 珍珠快照（包内可见：供单测使用） */
    record PearlSnapshot(UUID pearlId, boolean legacy, String world,
                         double x, double y, double z, double vx, double vy, double vz) {
    }

    /**
     * 编码一组快照为独立、可校验的二进制记录。
     *
     * @throws IllegalArgumentException 快照数量/世界名/坐标不合法时抛出，调用方不应写入损坏数据
     */
    static byte[] encode(List<PearlSnapshot> snapshots) {
        if (snapshots.size() > MAX_SNAPSHOTS) {
            throw new IllegalArgumentException("too many pearl snapshots: " + snapshots.size());
        }
        ByteArrayOutputStream payloadBuffer = new ByteArrayOutputStream(snapshots.size() * 64);
        try (DataOutputStream out = new DataOutputStream(payloadBuffer)) {
            out.writeInt(snapshots.size());
            for (PearlSnapshot snapshot : snapshots) {
                if (snapshot == null) {
                    throw new IllegalArgumentException("pearl snapshot must not be null");
                }
                writeSnapshot(out, snapshot);
            }
        } catch (IOException e) {
            // ByteArrayOutputStream 不会抛 IOException；保留显式处理以免未来替换底层实现时静默失败
            throw new IllegalStateException("failed to encode pearl snapshots", e);
        }
        byte[] payload = payloadBuffer.toByteArray();
        if (payload.length > MAX_RECORD_BYTES - HEADER_BYTES) {
            throw new IllegalArgumentException("pearl record too large: " + payload.length);
        }
        ByteBuffer record = ByteBuffer.allocate(HEADER_BYTES + payload.length).order(ByteOrder.BIG_ENDIAN);
        record.putInt(MAGIC);
        record.putShort(FORMAT_VERSION);
        record.putInt(payload.length);
        record.putInt(crc32(payload));
        record.put(payload);
        return record.array();
    }

    /**
     * 解码一条二进制记录；任何截断、魔数/版本不符、CRC 不匹配或字段越界都抛 {@link IOException}，
     * 由调用方隔离损坏文件，而不是把半条记录当成有效数据。
     */
    static List<PearlSnapshot> decode(byte[] record) throws IOException {
        if (record.length < HEADER_BYTES) {
            throw new IOException("pearl record too short: " + record.length + " bytes");
        }
        ByteBuffer header = ByteBuffer.wrap(record).order(ByteOrder.BIG_ENDIAN);
        int magic = header.getInt();
        short version = header.getShort();
        int payloadLength = header.getInt();
        int expectedCrc = header.getInt();
        if (magic != MAGIC) {
            throw new IOException("bad pearl record magic: 0x" + Integer.toHexString(magic));
        }
        if (version != FORMAT_VERSION) {
            throw new IOException("unsupported pearl record version: " + version);
        }
        if (payloadLength < 0 || payloadLength > MAX_RECORD_BYTES - HEADER_BYTES
                || record.length != HEADER_BYTES + payloadLength) {
            throw new IOException("bad pearl record length: " + payloadLength
                    + " (file " + record.length + " bytes)");
        }
        byte[] payload = new byte[payloadLength];
        header.get(payload);
        if (crc32(payload) != expectedCrc) {
            throw new IOException("pearl record CRC mismatch");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            int count = in.readInt();
            if (count < 0 || count > MAX_SNAPSHOTS) {
                throw new IOException("bad pearl snapshot count: " + count);
            }
            List<PearlSnapshot> snapshots = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                snapshots.add(readSnapshot(in));
            }
            if (in.available() != 0) {
                throw new IOException("trailing bytes in pearl record payload");
            }
            return List.copyOf(snapshots);
        }
    }

    private static void writeSnapshot(DataOutputStream out, PearlSnapshot snapshot) throws IOException {
        String world = snapshot.world();
        if (world == null) {
            throw new IllegalArgumentException("pearl snapshot world must not be null");
        }
        byte[] worldBytes = world.getBytes(StandardCharsets.UTF_8);
        if (worldBytes.length == 0 || worldBytes.length > MAX_WORLD_BYTES) {
            throw new IllegalArgumentException("invalid pearl snapshot world length: " + worldBytes.length);
        }
        int flags = (snapshot.pearlId() != null ? FLAG_PEARL_ID : 0)
                | (snapshot.legacy() || snapshot.pearlId() == null ? FLAG_LEGACY : 0);
        out.writeByte(flags);
        if (snapshot.pearlId() != null) {
            out.writeLong(snapshot.pearlId().getMostSignificantBits());
            out.writeLong(snapshot.pearlId().getLeastSignificantBits());
        }
        out.writeShort(worldBytes.length);
        out.write(worldBytes);
        writeFinite(out, snapshot.x());
        writeFinite(out, snapshot.y());
        writeFinite(out, snapshot.z());
        writeFinite(out, snapshot.vx());
        writeFinite(out, snapshot.vy());
        writeFinite(out, snapshot.vz());
    }

    private static PearlSnapshot readSnapshot(DataInputStream in) throws IOException {
        int flags = in.readUnsignedByte();
        if ((flags & ~FLAG_MASK) != 0) {
            throw new IOException("unknown pearl snapshot flags: " + flags);
        }
        UUID pearlId = null;
        if ((flags & FLAG_PEARL_ID) != 0) {
            pearlId = new UUID(in.readLong(), in.readLong());
        }
        boolean legacy = (flags & FLAG_LEGACY) != 0 || pearlId == null;
        int worldLength = in.readUnsignedShort();
        if (worldLength == 0 || worldLength > MAX_WORLD_BYTES) {
            throw new IOException("bad pearl world length: " + worldLength);
        }
        byte[] worldBytes = new byte[worldLength];
        in.readFully(worldBytes);
        String world = new String(worldBytes, StandardCharsets.UTF_8);
        double x = readFinite(in);
        double y = readFinite(in);
        double z = readFinite(in);
        double vx = readFinite(in);
        double vy = readFinite(in);
        double vz = readFinite(in);
        return new PearlSnapshot(pearlId, legacy, world, x, y, z, vx, vy, vz);
    }

    private static void writeFinite(DataOutputStream out, double value) throws IOException {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("pearl snapshot coordinate must be finite: " + value);
        }
        out.writeDouble(value);
    }

    private static double readFinite(DataInputStream in) throws IOException {
        double value = in.readDouble();
        if (!Double.isFinite(value)) {
            throw new IOException("non-finite pearl snapshot coordinate");
        }
        return value;
    }

    private static int crc32(byte[] payload) {
        CRC32 crc = new CRC32();
        crc.update(payload, 0, payload.length);
        return (int) crc.getValue();
    }

    /**
     * 解析旧版 YAML 快照（包内可见：仅迁移与单测使用）。
     * <p>
     * 与新的二进制解码一样拒绝无法落盘的数据：world 缺失/为空/过长、坐标非有限值时返回 null。
     * 这些记录若留在内存里，迁移会永远失败，返还时还可能把 NaN 坐标传给世界生成逻辑。
     */
    static PearlSnapshot parseSnapshot(Map<?, ?> map) {
        Object worldValue = map.get("world");
        if (!(worldValue instanceof String world)) return null;
        if (world.isEmpty() || world.getBytes(StandardCharsets.UTF_8).length > MAX_WORLD_BYTES) return null;
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
