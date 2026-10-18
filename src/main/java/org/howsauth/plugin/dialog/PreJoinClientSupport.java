package org.howsauth.plugin.dialog;

import com.destroystokyo.paper.ClientOption;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import org.bukkit.Bukkit;
import org.howsauth.plugin.auth.SessionStore;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.UUID;

/**
 * 配置阶段客户端能力与语言解析（ViaVersion 反射、locale 规范化、IP 提取）。
 * <p>
 * 这些逻辑与登录状态机无关，独立后状态机不必再关心协议版本探测细节。
 */
final class PreJoinClientSupport {

    // ViaVersion 反射惰性缓存：每个连接都会查询客户端协议版本，反射解析一次后复用
    // ViaVersion 未在依赖中声明（加载顺序不保证），无法静态初始化，只能运行时反射
    private static volatile Method viaGetPlayerVersion;
    private static volatile Object viaApiInstance;

    private PreJoinClientSupport() {
    }

    /**
     * 客户端是否支持配置阶段 Dialog（协议 >= 1.21.6 / 771）。
     * 无 ViaVersion 时视为支持：能连上本插件的服务端版本必然 >= 1.21.6
     * （默认 1.21.11+，冒险模式可放宽到 1.21.6，见 HowSAuth#preJoinSupported），
     * 客户端协议匹配该服务端时必然 >= 1.21.6，一定支持 Dialog。
     * 仅在有 ViaVersion（允许旧客户端连新服务端）时，才需用 ViaAPI 按 UUID 查询客户端真实协议版本。
     */
    static boolean supportsDialogs(UUID playerId) {
        try {
            if (Bukkit.getPluginManager().getPlugin("ViaVersion") == null) {
                return true;
            }
            Method getPlayerVersion = viaGetPlayerVersion;
            Object api = viaApiInstance;
            if (getPlayerVersion == null || api == null) {
                Class<?> viaApiClass = Class.forName("com.viaversion.viaversion.api.ViaAPI");
                Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via");
                api = viaClass.getMethod("getAPI").invoke(null);
                getPlayerVersion = viaApiClass.getMethod("getPlayerVersion", UUID.class);
                viaApiInstance = api;
                viaGetPlayerVersion = getPlayerVersion;
            }
            int version = (int) getPlayerVersion.invoke(api, playerId);
            return version >= 771;
        } catch (Exception e) {
            return true; // 查询失败保守放行（不阻塞正常玩家），回退到聊天栏在 onJoin 处理
        }
    }

    /** 客户端语言（读取失败回退默认）。
     *  客户端发送的原始值是全小写格式（如 en_us），语言文件名为 en_US 形式（语言小写 + 地区大写），
     *  需规范化后再匹配，否则非默认语言玩家会看到默认语言 */
    static String resolveLocale(PlayerConfigurationConnection conn) {
        try {
            // getClientOption 保证非空，仅需判断是否空白
            String locale = conn.getClientOption(ClientOption.LOCALE);
            if (locale.isBlank()) return null;
            int underscore = locale.indexOf('_');
            if (underscore > 0 && underscore < locale.length() - 1) {
                return locale.substring(0, underscore).toLowerCase(Locale.ROOT)
                        + "_" + locale.substring(underscore + 1).toUpperCase(Locale.ROOT);
            }
            return locale;
        } catch (Exception e) {
            return null;
        }
    }

    static String clientIp(PlayerConfigurationConnection conn) {
        return SessionStore.ipOf(conn.getClientAddress());
    }
}
