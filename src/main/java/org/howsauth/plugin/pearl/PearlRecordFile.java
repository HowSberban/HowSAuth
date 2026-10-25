package org.howsauth.plugin.pearl;

import org.howsauth.plugin.pearl.SnapshotCodec.PearlSnapshot;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 每名玩家一个二进制记录文件的磁盘布局与原子读写。
 * <p>
 * 目录结构：{@code plugins/HowSAuth/pearls/<uuid>.bin}。一个玩家一份文件意味着
 * 保存时只重写该玩家的记录，不再重写全服数据；文件之间互不影响，单个文件损坏也只
 * 影响一名玩家。写入先落临时文件并 {@code fsync}，再原子替换，崩溃时旧文件仍然完整。
 */
record PearlRecordFile(Path directory) {

    private static final String RECORD_SUFFIX = ".bin";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final String CORRUPT_SUFFIX = ".corrupt";

    /**
     * Windows 不允许用 FileChannel 打开目录做 fsync；其余平台尽力把 rename/delete 的目录项也刷盘。
     */
    private static final boolean DIRECTORY_FORCE_SUPPORTED =
            !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private Path fileOf(UUID owner) {
        return directory.resolve(owner + RECORD_SUFFIX);
    }

    void createDirectory() throws IOException {
        Files.createDirectories(directory);
    }

    /**
     * 列出目录中文件名可解析为 UUID 的记录文件；无法解析的文件交给调用方告警，不在此处删除。
     */
    List<UUID> listOwners() throws IOException {
        List<UUID> owners = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + RECORD_SUFFIX)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                String rawId = name.substring(0, name.length() - RECORD_SUFFIX.length());
                try {
                    owners.add(UUID.fromString(rawId));
                } catch (IllegalArgumentException ignored) {
                    // 非本插件命名的 .bin 文件：不读、不删，避免误伤。
                }
            }
        }
        return owners;
    }

    List<PearlSnapshot> read(UUID owner) throws IOException {
        Path file = fileOf(owner);
        long size = Files.size(file);
        if (size <= 0 || size > SnapshotCodec.MAX_RECORD_BYTES) {
            throw new IOException("invalid pearl record size: " + size + " bytes");
        }
        return SnapshotCodec.decode(Files.readAllBytes(file));
    }

    void write(UUID owner, List<PearlSnapshot> snapshots) throws IOException {
        byte[] bytes = SnapshotCodec.encode(snapshots);
        Path target = fileOf(owner);
        Path temp = directory.resolve(owner + RECORD_SUFFIX + TEMP_SUFFIX);
        try (FileChannel channel = FileChannel.open(temp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            int total = 0;
            int zeroWrites = 0;
            while (buffer.hasRemaining()) {
                int written = channel.write(buffer);
                if (written == 0) {
                    if (++zeroWrites > 8) {
                        throw new IOException("file channel made no progress writing " + buffer.remaining() + " bytes");
                    }
                    // 阻塞式文件通道正常不会返回 0；防御性让出 CPU 后重试，避免空转。
                    Thread.onSpinWait();
                } else {
                    zeroWrites = 0;
                }
                total += written;
            }
            if (total != bytes.length) {
                throw new IOException("short write: " + total + "/" + bytes.length + " bytes");
            }
            channel.force(true);
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    void delete(UUID owner) throws IOException {
        Files.deleteIfExists(fileOf(owner));
    }

    /**
     * 尽力把目录项也刷到磁盘：文件内容已 {@code force}，但 rename/delete 的目录元数据
     * 还需要目录 fsync 才能在断电后确定保留。不支持的平台/文件系统静默跳过。
     */
    void forceDirectory() {
        if (!DIRECTORY_FORCE_SUPPORTED) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // 目录 fsync 是尽力而为；内容本身已经落盘，退化为依赖文件系统的元数据刷写。
        }
    }

    /**
     * 把损坏的记录移出正常命名空间，保留现场供排查，且下次保存不会静默覆盖它。
     */
    void quarantine(UUID owner) throws IOException {
        Files.move(fileOf(owner), directory.resolve(owner + RECORD_SUFFIX + CORRUPT_SUFFIX),
                StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * 清理上次崩溃残留的临时文件；这些文件从未被原子替换成正式记录，删除是安全的。
     */
    void cleanupTempFiles() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + TEMP_SUFFIX)) {
            for (Path file : stream) {
                Files.deleteIfExists(file);
            }
        }
    }

}
