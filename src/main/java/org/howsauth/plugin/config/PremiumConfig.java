package org.howsauth.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.howsauth.plugin.I18n;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * 正版验证（{@code premium.*}）配置。
 * <p>
 * 包含验证开关、HTTP 出站代理、会话验证镜像、超时与重试、缓存上限，以及升级/降级/回退开关。
 * 构造时完成全部解析与校验（非法项仅告警保留、数值越界钳制并回写），此后只读。
 */
public final class PremiumConfig {

    /** 官方会话验证端点：硬编码，恒为候选列表首项 */
    private static final String DEFAULT_SESSION_SERVER_URL = "https://sessionserver.mojang.com";

    /** 出厂配置里的代理占位示例：未修改配置时不应被当作真实代理探测 */
    private static final String EXAMPLE_PROXY = "example.com:25565";

    /** 出厂配置里的镜像占位示例：未修改配置时不应被当作真实镜像探测 */
    private static final String EXAMPLE_MIRROR = "https://mirror.example.com";

    private final boolean enabled;
    private final boolean autoVerify;
    private final List<Proxy> httpProxies;
    /** 会话验证候选列表：官方端点在首位 + 配置的镜像，构造期一次组装 */
    private final List<String> sessionServerCandidates;
    private final int timeoutSeconds;
    private final int verifyDeadlineMs;
    private final int crackerCacheSeconds;
    private final int handshakeTimeoutMs;
    private final int maxRetries;
    private final long retryIntervalMs;
    private final int httpPoolSize;
    private final int cacheCap;
    private final boolean upgradeEnabled;
    private final boolean downgradeEnabled;
    private final boolean passwordFallbackEnabled;
    private final int fallbackCacheSeconds;

    PremiumConfig(FileConfiguration config, ConfigFixer fixer) {
        this.enabled = config.getBoolean("premium.enabled", false);
        this.autoVerify = config.getBoolean("premium.auto-verify", true);

        // HTTP 出站代理列表（host:port）：借道代理访问真正的 Mojang 官方验证服务器。
        // 非法项仅告警、不删除配置——节点可能出错或临时不可用，保留便于排查
        List<Proxy> proxies = new ArrayList<>();
        for (String raw : config.getStringList("premium.http-proxies")) {
            String entry = raw == null ? "" : raw.trim();
            // 跳过默认配置自带的占位示例，未修改配置时不会误当真实代理探测
            if (entry.isEmpty() || entry.equals(EXAMPLE_PROXY)) continue;
            Proxy proxy = parseHttpProxy(entry);
            if (proxy == null) {
                fixer.warn(I18n.get("log.config_http_proxy_invalid", entry));
            } else {
                proxies.add(proxy);
            }
        }
        this.httpProxies = List.copyOf(proxies);

        // 会话验证镜像服务器列表（完整 URL）：实现 hasJoined 接口的替代服务器，作为代理的备选。
        // 非法项仅告警、不删除配置——节点可能出错或临时不可用，保留便于排查
        List<String> mirrors = new ArrayList<>();
        for (String raw : config.getStringList("premium.session-server-mirrors")) {
            String url = raw == null ? "" : raw.trim();
            // 跳过默认配置自带的占位示例，未修改配置时不会误当真实镜像探测
            if (url.isEmpty() || url.equals(EXAMPLE_MIRROR)) continue;
            if (url.startsWith("http://") || url.startsWith("https://")) {
                // 去除末尾斜杠，保证拼接路径正确
                mirrors.add(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
            } else {
                fixer.warn(I18n.get("log.config_session_mirror_invalid", url));
            }
        }
        // 候选列表在构造期组装一次：官方端点在首位，其后为配置的镜像（镜像是构造期局部数据，无需留存字段）
        List<String> candidates = new ArrayList<>(mirrors.size() + 1);
        candidates.add(DEFAULT_SESSION_SERVER_URL);
        candidates.addAll(mirrors);
        this.sessionServerCandidates = List.copyOf(candidates);

        this.timeoutSeconds = fixer.clampMin("premium.timeout-seconds",
                config.getInt("premium.timeout-seconds", 5), 1);
        // 验证总时限：默认 30 秒，不超过客户端"通讯加密中"等待上限（30 秒），避免服务端验证超时后客户端已主动断开
        this.verifyDeadlineMs = fixer.clampMin("premium.verify-deadline-ms",
                config.getInt("premium.verify-deadline-ms", 30000), 1000);
        this.crackerCacheSeconds = fixer.clampMin("premium.cracker-cache-seconds",
                config.getInt("premium.cracker-cache-seconds", 120), 0);
        this.handshakeTimeoutMs = fixer.clampMin("premium.handshake-timeout-ms",
                config.getInt("premium.handshake-timeout-ms", 30000), 0);
        this.maxRetries = fixer.clampMin("premium.max-retries", config.getInt("premium.max-retries", 2), 0);
        this.retryIntervalMs = fixer.clampMin("premium.retry-interval-ms",
                config.getInt("premium.retry-interval-ms", 500), 0);
        this.httpPoolSize = fixer.clampRange("premium.http-pool-size",
                config.getInt("premium.http-pool-size", 2), 2, 64);
        this.cacheCap = fixer.clampMin("premium.cache-cap", config.getInt("premium.cache-cap", 1000), 0);
        this.upgradeEnabled = config.getBoolean("premium.upgrade", true);
        this.downgradeEnabled = config.getBoolean("premium.downgrade", true);
        this.passwordFallbackEnabled = config.getBoolean("premium.fallback.enabled", false);
        this.fallbackCacheSeconds = fixer.clampMin("premium.fallback.cache-seconds",
                config.getInt("premium.fallback.cache-seconds", 300), 30);
    }

    /**
     * 解析 HTTP 出站代理条目（host:port），格式非法返回 null。
     * 以最后一个冒号分隔，因此主机名本身可以含冒号。
     */
    private static Proxy parseHttpProxy(String entry) {
        int colon = entry.lastIndexOf(':');
        if (colon <= 0 || colon == entry.length() - 1) return null;
        String host = entry.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(entry.substring(colon + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (port < 1 || port > 65535) return null;
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
    }

    /** 正版验证总开关 */
    public boolean enabled() {
        return enabled;
    }

    /** 新玩家进服时是否自动尝试正版验证 */
    public boolean autoVerify() {
        return autoVerify;
    }

    /** HTTP 出站代理列表（不可变） */
    public List<Proxy> httpProxies() {
        return httpProxies;
    }

    /**
     * 会话验证候选列表：官方地址（硬编码）在首位，其后为配置的镜像，代理均不可用时依次尝试。
     * <p>
     * 列表在构造期一次组装并缓存，返回不可变视图：调用方只做遍历，避免每次访问都重建列表。
     */
    public List<String> sessionServerCandidates() {
        return sessionServerCandidates;
    }

    /** 单次 hasJoined 请求超时（秒） */
    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    /** 一次完整验证（含端点切换与重试）的总时限（毫秒） */
    public int verifyDeadlineMs() {
        return verifyDeadlineMs;
    }

    /** 离线客户端标记缓存时长（秒） */
    public int crackerCacheSeconds() {
        return crackerCacheSeconds;
    }

    /** 加密握手阶段等待 EncryptionResponse 的超时（毫秒） */
    public int handshakeTimeoutMs() {
        return handshakeTimeoutMs;
    }

    /** hasJoined 可重试状态码的最大重试次数（不含首次尝试） */
    public int maxRetries() {
        return maxRetries;
    }

    /** 重试等待间隔（毫秒） */
    public long retryIntervalMs() {
        return retryIntervalMs;
    }

    /** Mojang hasJoined 专用线程池大小 */
    public int httpPoolSize() {
        return httpPoolSize;
    }

    /** 离线确认/正版回退缓存硬上限 */
    public int cacheCap() {
        return cacheCap;
    }

    /** 离线账号升级为正版 */
    public boolean upgradeEnabled() {
        return upgradeEnabled;
    }

    /** 正版账号降级为离线 */
    public boolean downgradeEnabled() {
        return downgradeEnabled;
    }

    /** 正版验证失败时允许正版玩家以密码登录（不安全，默认关闭） */
    public boolean passwordFallbackEnabled() {
        return passwordFallbackEnabled;
    }

    /** 回退标记有效期（秒） */
    public int fallbackCacheSeconds() {
        return fallbackCacheSeconds;
    }
}
