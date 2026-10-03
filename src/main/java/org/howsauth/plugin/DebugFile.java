package org.howsauth.plugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 调试日志文件：debug 输出单独写 {@code plugins/HowSAuth/debug.log}，不进入控制台与 latest.log，
 * 既便于与原版日志区分，也便于让用户直接回传该文件。
 * <p>
 * 采用无缓冲追加写：每次写入即交给系统（进程崩溃不丢最后几行，仅断电可能丢），
 * 避免为调试输出牺牲区域线程的响应性。不限制单文件大小，按两个时机轮转：① 每次开服；② 跨日（当天首次写入前）。
 * 归档名取被归档文件的最后写入时间（{@code debug-<yyyy-MM-dd_HH-mm-ss>.log}），
 * 空文件直接沿用，避免堆积空归档；debug 关闭时不产生、也不触碰任何日志文件。
 * 写入失败静默丢弃并关闭句柄：调试输出不得影响认证流程。
 */
final class DebugFile {

    private static final String FILE_NAME = "debug.log";
    private static final String ARCHIVE_PREFIX = "debug-";
    private static final String ARCHIVE_SUFFIX = ".log";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Object LOCK = new Object();

    private static File file;
    private static FileOutputStream out;
    // 当前日志文件所属日期：跨日后的首次写入先归档，避免长期运行堆积在同一文件
    private static LocalDate currentLogDate;

    private DebugFile() {
    }

    /** 指向数据目录（配置刷新时调用；此时关闭旧句柄，下次写入按新路径重开） */
    static void attach(HowSAuth plugin) {
        synchronized (LOCK) {
            closeLocked();
            file = new File(plugin.getDataFolder(), FILE_NAME);
        }
    }

    /** 开服轮转：把上一次的 debug.log 归档，本次从空文件重新开始 */
    static void rotate() {
        synchronized (LOCK) {
            currentLogDate = null;
            rotateLocked();
        }
    }

    /** 当前文件状态（供 /hsauth debug dump 展示：路径、大小、归档份数） */
    static String status() {
        synchronized (LOCK) {
            File target = file;
            if (target == null) {
                return "path=unset";
            }
            File parent = target.getParentFile();
            File[] archives = parent == null ? null
                    : parent.listFiles((d, name) -> name.startsWith(ARCHIVE_PREFIX) && name.endsWith(ARCHIVE_SUFFIX));
            return "path=" + target.getAbsolutePath() + " size=" + target.length() + "B archives="
                    + (archives == null ? 0 : archives.length);
        }
    }

    /** 追加一行（线程安全）；跨日后首次写入先归档，使单个文件不超过一天 */
    static void write(String line) {
        synchronized (LOCK) {
            try {
                LocalDateTime now = LocalDateTime.now();
                if (currentLogDate != null && !currentLogDate.equals(now.toLocalDate())) {
                    rotateLocked();
                }
                currentLogDate = now.toLocalDate();
                if (out == null) {
                    openLocked();
                    if (out == null) {
                        return;
                    }
                }
                out.write((now.format(TIME) + " " + line + System.lineSeparator())
                        .getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                // 磁盘满、文件被占用等：关闭句柄后静默丢弃后续输出，不影响认证流程
                closeLocked();
            }
        }
    }

    /** 关闭句柄（插件卸载时调用） */
    static void close() {
        synchronized (LOCK) {
            closeLocked();
        }
    }

    /** 归档当前文件（调用方持锁；文件不存在或为空时不产生归档） */
    private static void rotateLocked() {
        closeLocked();
        File target = file;
        if (target == null || !target.isFile() || target.length() == 0) {
            return;
        }
        LocalDateTime writtenAt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(target.lastModified()), ZoneId.systemDefault());
        File archive = new File(target.getParentFile(),
                ARCHIVE_PREFIX + writtenAt.format(STAMP) + ARCHIVE_SUFFIX);
        try {
            Files.move(target.toPath(), archive.toPath());
        } catch (IOException ignored) {
            // 归档失败（文件被占用等）：沿用原文件继续追加，不阻断开服或写入
        }
    }

    private static void openLocked() throws IOException {
        File target = file;
        if (target == null) {
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        out = new FileOutputStream(target, true);
    }

    private static void closeLocked() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // 关闭失败无需处理：句柄随进程释放
            }
            out = null;
        }
    }
}