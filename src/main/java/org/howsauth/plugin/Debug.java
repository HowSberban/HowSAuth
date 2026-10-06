package org.howsauth.plugin;

import org.howsauth.plugin.config.ConfigManager;

import java.util.UUID;

/**
 * 调试日志：由 {@code advanced.debug} 控制的条件输出，用于排查认证流程问题。
 * <p>
 * 面向开发者与故障排查，文案固定为英文硬编码、不进入语言文件（与其它日志走 i18n 的惯例是有意例外）；
 * 输出单独写 {@code plugins/HowSAuth/debug.log}，不进入控制台与 latest.log，
 * 且不记录密码、哈希、密钥与完整 IP 等敏感值。
 * <p>
 * 约定：每个调用点都必须用 {@code if (Debug.on())} 守卫，使关闭时不发生实参求值与分配；
 * 禁止把 debug 日志加进高频路径（移动、数据包等逐事件路径），这类路径只记录状态供 /hsauth debug dump 查看。
 */
public final class Debug {

    private static volatile boolean enabled;

    private Debug() {
    }

    /** 启动时：同步开关，并按"每次开服轮转"归档上一次的调试日志（关闭时不产生、也不触碰日志文件） */
    public static void init(HowSAuth plugin) {
        refresh(plugin);
        if (enabled) {
            DebugFile.rotate();
        }
    }

    /** 启动时与 /hsauth reload 后同步开关与输出文件（配置可运行时变更） */
    public static void refresh(HowSAuth plugin) {
        ConfigManager config = plugin.config();
        enabled = config != null && config.settings().debug();
        DebugFile.attach(plugin);
    }

    /** 当前是否开启调试输出 */
    public static boolean on() {
        return enabled;
    }

    /** 调试文件状态（供 /hsauth debug dump 展示） */
    public static String fileStatus() {
        return "debug log: enabled=" + enabled + " " + DebugFile.status();
    }

    /** 输出一条调试日志，area 为固定分类标签（auth/session/flow/premium/db/dialog/pearl/cmd/api 等）便于 grep */
    public static void log(String area, String format, Object... args) {
        if (!enabled) {
            return;
        }
        DebugFile.write("[" + area + "] " + (args.length == 0 ? format : String.format(format, args)));
    }

    /** 日志用的短 UUID 标识（前 8 位）；null（连接尚未解析出 UUID）返回 "unknown"。
     *  纯函数，须在 {@code if (Debug.on())} 守卫内调用以避免关闭时的多余求值 */
    public static String shortId(UUID uuid) {
        return uuid == null ? "unknown" : uuid.toString().substring(0, 8);
    }

    /** 插件卸载时关闭日志文件句柄 */
    public static void close() {
        enabled = false;
        DebugFile.close();
    }
}