package org.howsauth.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// 保持 preventXxx 正向命名：调用方均以 ! 守卫子句使用，反转逻辑会与方法名语义相反
@SuppressWarnings("BooleanMethodIsAlwaysInverted")
public final class ConfigManager {

    private final HowSAuth plugin;
    // 配置被钳制/回退修正标记，load() 末尾统一写回 config.yml
    private boolean configDirty;

    // 数据库设置
    private String databaseType;
    private String mysqlHost;
    private int mysqlPort;
    private String mysqlDatabase;
    private String mysqlUsername;
    private String mysqlPassword;
    private Map<String, String> mysqlParams;
    private int poolSize;
    // 数据库配置指纹：用于 reload 时检测数据库配置是否变化（变化需重启而非热重载）
    private String databaseFingerprint;

    // 登录设置
    private int loginTimeout;
    // 注册超时（秒）：与登录超时分开，供新玩家注册窗口使用
    private int registerTimeout;
    private boolean kickOnTimeout;
    private boolean failProtectionEnabled;
    private int failMaxAttempts;
    private int failKickDuration;
    // 失败计数跨连接保留的过期时间（秒）：玩家最后一次失败超过此时长未再失败则清空计数，0 = 永不过期
    private int failProtectionResetSeconds;
    // 会话保持：上次登录 IP 一致且未过期时免输密码
    private boolean sessionEnabled;
    private int sessionExpireMinutes;
    private int loginRemindInterval;
    // 提示消息发送方式：chat / title / actionbar / bossbar
    private String loginRemindMethod;
    // Dialog 登录界面：弹出图形化窗口登录/注册（Paper 1.21.11+，客户端需同版本以上）
    private boolean loginDialogEnabled;
    // 1.21.6–1.21.10 冒险启用 Dialog：存在未验证的兼容问题，默认关闭
    private boolean dialogAllowRiskyVersions;
    // IP 变动提醒：登录 IP 与上次不同时提示玩家
    private boolean ipChangeNotifyEnabled;
    // 双因素认证：全局开关（关闭后已绑定玩家跳过验证，密钥保留）
    private boolean twoFaEnabled;
    // 绑定时临时密钥的有效期（秒），0 = 永不过期
    private int twoFaTempSecretExpireSeconds;
    // 2FA 会话保持：验证码通过后同 IP 短时间内重连免验证码
    private boolean twoFaSessionEnabled;
    // 2FA 会话有效期（分钟），固定窗口，命中不续期
    private int twoFaSessionExpireMinutes;
    // 是否提供扫码网页入口（关闭后不向二维码服务发送密钥）
    private boolean twoFaQrEnabled;
    // 生成二维码的服务地址模板，{data} 占位符会被替换为 URL 编码后的 otpauth 资料
    private String twoFaQrUrl;
    // otpauth 标签的服务器名（issuer）：非空时条目显示为 "服务器名:玩家名"，玩家名作账户详情
    private String twoFaServerName;

    // 密码规则
    private int minPasswordLength;
    private int maxPasswordLength;
    private String passwordHashAlgorithm;
    private int bcryptCost;
    private Pattern passwordPattern;

    // 注册限制
    private int maxAccountsPerIp;
    // 同 IP 已满时，是否在连接阶段直接拒绝未注册新玩家进服（false=放行由注册动作判定）
    private boolean ipLimitRejectJoin;

    // 行为限制（protection.prevent 段）
    private boolean preventMove;
    private boolean preventLook;
    private boolean preventChat;
    private boolean preventCommand;
    private List<String> commandWhitelist;
    private boolean preventWorldInteraction;
    private boolean preventInventory;

    // 登录前保护
    private boolean protectionPosEnabled;
    private String protectionPosMode;
    private int protectionPosSpawnRadius;
    private double protectionPosFixedX;
    private double protectionPosFixedY;
    private double protectionPosFixedZ;
    private float protectionPosFixedYaw;
    private float protectionPosFixedPitch;
    // 未登录旁观模式：未登录期间切换为旁观，登录后恢复上次游戏模式
    private boolean protectionGamemodeEnabled;
    // 背包保护：未登录期间通过 PacketEvents 拦截物品数据包，防止 mod 窥视
    private boolean protectionInventoryEnabled;
    // 登录前失明：未登录（含等待登录/注册/2FA）期间施加失明效果，登录/注册成功后移除
    private boolean protectionBlindnessEnabled;
    // 无凭据账号（无密码、未绑 2FA、非正版）是否在连接阶段拒绝进入；关闭则放行进服挂起（无法完成登录，超时踢出）
    private boolean rejectNoAuthAccount;
    // 末影珍珠返还方式：item = 物品入包；entity = 世界原位重生飞行珍珠
    private String pearlReturnMode;
    // 末影珍珠保管开关（关闭后退出不接管珍珠，维持原版行为）
    private boolean pearlEnabled;

    // 通用设置
    private boolean realUnreg;
    // 玩家自助注销开关
    private boolean allowSelfUnregister;
    // 清理不活跃账号
    private boolean purgeEnabled;
    private int purgeDays;

    // 正版验证
    private boolean premiumEnabled;
    // 新玩家自动正版验证：关闭时新玩家直接离线进入，仅 /upgrade 标记的玩家验证
    private boolean premiumAutoVerify;
    // HTTP 出站代理列表（host:port）：借道代理访问真正的 Mojang 官方验证服务器
    private List<Proxy> premiumHttpProxies;
    // 会话验证镜像服务器列表（完整 URL）：实现 hasJoined 接口的替代服务器，作为代理的备选
    private List<String> premiumSessionServerMirrors;
    // Mojang 官方会话验证服务器地址（首选，硬编码）
    private static final String DEFAULT_SESSION_SERVER_URL = "https://sessionserver.mojang.com";
    // 2FA 二维码服务默认地址模板（{data} 替换为 URL 编码后的 otpauth 资料）
    private static final String DEFAULT_QR_URL = "https://api.qrserver.com/v1/create-qr-code/?data={data}&size=200x200&ecc=M&margin=10";
    private int premiumTimeoutSeconds;
    // hasJoined 验证总时限（毫秒）：含候选服务器切换，超时即终止验证
    private int premiumVerifyDeadlineMs;
    private int premiumCrackerCacheSeconds;
    // 加密握手阶段等待 EncryptionResponse 的超时（毫秒），防止恶意客户端滞留会话
    private int premiumHandshakeTimeoutMs;
    // hasJoined 可重试状态码的最大重试次数（不含首次尝试）
    private int premiumMaxRetries;
    // 重试等待间隔（毫秒），每次重试前等待的固定时长
    private long premiumRetryIntervalMs;
    // Mojang hasJoined 专用线程池大小（请求含 sleep/重试等待，阻塞式任务需独立线程）
    private int premiumHttpPoolSize;
    // 离线确认/正版回退缓存硬上限（超过则逐出最早过期项，防止攻击者无限刷新导致内存膨胀）
    private int premiumCacheCap;
    // 离线账号升级为正版
    private boolean premiumUpgradeEnabled;
    // 正版账号降级为离线
    private boolean premiumDowngradeEnabled;
    // 正版验证失败时允许正版玩家以密码登录（默认关闭，不安全）
    private boolean premiumPasswordFallbackEnabled;
    // 回退标记有效期（秒），过期后重连重新尝试正版验证
    private int premiumFallbackCacheSeconds;
    // 加入/退出消息：完全禁用所有玩家的加入消息（开启后忽略其余加入消息选项）
    private boolean joinDisabled;
    // 加入/退出消息：完全禁用所有玩家的退出消息（开启后忽略其余退出消息选项）
    private boolean quitDisabled;
    // 加入/退出消息：未登录（未完成登录/注册）时隐藏原版加入消息
    private boolean joinHideUnauthenticated;
    // 加入/退出消息：隐藏后在登录成功时补发；未登录就退出则丢弃
    private boolean joinDelayUntilAuthenticated;
    // 加入/退出消息：自定义加入消息模板（空 = 保留原版消息）
    private String joinMessageTemplate;
    // 加入/退出消息：未登录时隐藏原版退出消息
    private boolean quitHideUnauthenticated;
    // 加入/退出消息：自定义退出消息模板（空 = 保留原版消息）
    private String quitMessageTemplate;

    public ConfigManager(HowSAuth plugin) {
        this.plugin = plugin;
        load();
    }

    // 内置语言文件资源路径
    private static final String[] LANG_RESOURCES = {
            "lang/zh_CN.properties",
            "lang/en_US.properties"
    };

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        checkConfigVersion(config);
        loadDatabaseConfig(config);
        loadLoginConfig(config);
        loadPasswordConfig(config);
        loadRegisterConfig(config);
        loadProtectionConfig(config);
        loadPearlConfig(config);
        loadPremiumConfig(config);
        loadMessageConfig(config);

        // 通用设置
        this.realUnreg = config.getBoolean("settings.real-unreg", true);
        this.allowSelfUnregister = config.getBoolean("settings.allow-self-unregister", true);
        this.purgeEnabled = config.getBoolean("settings.purge.enabled", false);
        this.purgeDays = clampInt("settings.purge.days", config.getInt("settings.purge.days", 90), 1);

        // 默认语言（控制台日志和客户端语言无匹配文件时使用）
        String defaultLanguage = config.getString("settings.default-language", "zh_CN");
        boolean clientLanguageDetection = config.getBoolean("settings.i18n", true);
        I18n.setDefaultLocale(defaultLanguage);
        I18n.setClientLanguageDetection(clientLanguageDetection);

        // 有修正时写回 config.yml，避免下次启动重复告警
        if (configDirty) {
            plugin.saveConfig();
            configDirty = false;
        }
    }

    // 版本检查：config.yml 的 version 字段存储完整版本号
    // - major.minor 变化：增量合并新配置键 + 覆盖语言文件（保留用户已有的自定义值，不整体覆盖）
    // - patch 变化：仅覆盖语言文件，手动更新 version 字段（保留用户配置）
    private void checkConfigVersion(FileConfiguration config) {
        String fileVersion = config.getString("version", "");
        String pluginVersion = plugin.getPluginMeta().getVersion();

        if (!pluginVersion.equals(fileVersion)) {
            boolean majorMinorChanged = !majorMinor(pluginVersion).equals(majorMinor(fileVersion));

            if (majorMinorChanged) {
                // major.minor 变化：增量合并，仅补入用户 config 缺失的新配置键，保留用户已有值
                mergeMissingKeys(config);
            } else {
                // 仅 patch 变化：不覆盖 config，只更新 version 字段
                config.set("version", pluginVersion);
                plugin.saveConfig();
            }

            // 任何版本变化都覆盖语言文件
            for (String resource : LANG_RESOURCES) {
                plugin.saveResource(resource, true);
            }
            I18n.reload();
            if (majorMinorChanged) {
                plugin.getLogger().warning(I18n.get("plugin.config_version_mismatch", fileVersion, pluginVersion));
            } else {
                plugin.getLogger().warning(I18n.get("plugin.lang_version_mismatch", fileVersion, pluginVersion));
            }
        }
    }

    // 数据库设置
    private void loadDatabaseConfig(FileConfiguration config) {
        this.databaseType = config.getString("database.type", "sqlite").toLowerCase(Locale.ROOT);
        // 数据库类型校验：仅支持 sqlite/mysql，非法值回退为 sqlite
        if (!"sqlite".equals(this.databaseType) && !"mysql".equals(this.databaseType)) {
            plugin.getLogger().warning(I18n.get("log.config_database_type_invalid", this.databaseType, "sqlite"));
            this.databaseType = "sqlite";
            config.set("database.type", "sqlite");
            configDirty = true;
        }
        this.mysqlHost = config.getString("database.mysql.host", "localhost");
        this.mysqlPort = clampRange("database.mysql.port", config.getInt("database.mysql.port", 3306), 1, 65535);
        this.mysqlDatabase = config.getString("database.mysql.database", "hsauth");
        this.mysqlUsername = config.getString("database.mysql.username", "root");
        this.mysqlPassword = config.getString("database.mysql.password", "");
        // 连接参数保持顺序，便于拼接 URL
        this.mysqlParams = new LinkedHashMap<>();
        var paramsSection = config.getConfigurationSection("database.mysql.params");
        if (paramsSection != null) {
            for (String key : paramsSection.getKeys(false)) {
                this.mysqlParams.put(key, config.getString("database.mysql.params." + key, ""));
            }
        }
        this.poolSize = clampRange("database.mysql.pool-size", config.getInt("database.mysql.pool-size", 10), 1, 128);
        // 记录数据库配置指纹，用于 reload 时检测是否需要重启
        this.databaseFingerprint = databaseType + "|" + mysqlHost + "|" + mysqlPort
                + "|" + mysqlDatabase + "|" + mysqlUsername + "|" + mysqlPassword + "|" + poolSize
                + "|" + mysqlParams.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }

    // 登录设置
    private void loadLoginConfig(FileConfiguration config) {
        this.loginTimeout = clampInt("login.timeout", config.getInt("login.timeout", 120), 0);
        this.registerTimeout = clampInt("register.timeout", config.getInt("register.timeout", 180), 0);
        this.kickOnTimeout = config.getBoolean("login.kick-on-timeout", true);
        this.failProtectionEnabled = config.getBoolean("login.fail-protection.enabled", true);
        this.failMaxAttempts = clampInt("login.fail-protection.max-attempts", config.getInt("login.fail-protection.max-attempts", 3), 1);
        this.failKickDuration = clampInt("login.fail-protection.kick-duration", config.getInt("login.fail-protection.kick-duration", 60), 0);
        this.failProtectionResetSeconds = clampInt("login.fail-protection.reset-seconds", config.getInt("login.fail-protection.reset-seconds", 300), 1);
        this.sessionEnabled = config.getBoolean("login.session.enabled", true);
        this.sessionExpireMinutes = clampInt("login.session.expire-minutes", config.getInt("login.session.expire-minutes", 120), 1);
        this.loginRemindInterval = clampInt("login.remind-interval", config.getInt("login.remind-interval", 5), 0);
        this.loginRemindMethod = config.getString("login.remind-method", "chat").toLowerCase(Locale.ROOT);
        this.loginDialogEnabled = config.getBoolean("login.dialog.enabled", true);
        this.dialogAllowRiskyVersions = config.getBoolean("login.dialog.allow-risky-versions", false);
        // 提示方式校验：仅支持 chat/title/actionbar/bossbar，非法值回退为 chat
        if (!List.of("chat", "title", "actionbar", "bossbar").contains(this.loginRemindMethod)) {
            plugin.getLogger().warning(I18n.get("log.config_mode_invalid", "login.remind-method", this.loginRemindMethod, "chat"));
            this.loginRemindMethod = "chat";
            config.set("login.remind-method", "chat");
            configDirty = true;
        }
        this.ipChangeNotifyEnabled = config.getBoolean("login.ip-change-notify", false);
        // 双因素认证
        this.twoFaEnabled = config.getBoolean("login.2fa.enabled", true);
        this.twoFaTempSecretExpireSeconds = clampInt("login.2fa.expire-seconds",
                config.getInt("login.2fa.expire-seconds", 300), 0);
        this.twoFaSessionEnabled = config.getBoolean("login.2fa.session.enabled", false);
        this.twoFaSessionExpireMinutes = clampInt("login.2fa.session.expire-minutes",
                config.getInt("login.2fa.session.expire-minutes", 5), 1);
        this.twoFaQrEnabled = config.getBoolean("login.2fa.qr", true);
        this.twoFaQrUrl = config.getString("login.2fa.qr-url", DEFAULT_QR_URL);
        this.twoFaServerName = config.getString("login.2fa.server-name", "");
    }

    // 密码规则
    private void loadPasswordConfig(FileConfiguration config) {
        this.minPasswordLength = config.getInt("password.min-length", 6);
        this.maxPasswordLength = config.getInt("password.max-length", 32);
        // 密码长度配置校验：最小值不得小于 4
        if (this.minPasswordLength < 4) {
            plugin.getLogger().warning(I18n.get("log.password_min_length_clamped", this.minPasswordLength, 4));
            this.minPasswordLength = 4;
            config.set("password.min-length", 4);
            configDirty = true;
        }
        // 最大值不得大于 128
        if (this.maxPasswordLength > 128) {
            plugin.getLogger().warning(I18n.get("log.password_max_length_clamped", this.maxPasswordLength, 128));
            this.maxPasswordLength = 128;
            config.set("password.max-length", 128);
            configDirty = true;
        }
        // 最大值不得小于最小值
        if (this.maxPasswordLength < this.minPasswordLength) {
            plugin.getLogger().warning(I18n.get("log.password_length_range_invalid", this.maxPasswordLength, this.minPasswordLength));
            this.maxPasswordLength = this.minPasswordLength;
            config.set("password.max-length", this.minPasswordLength);
            configDirty = true;
        }
        this.passwordHashAlgorithm = config.getString("password.hash", "bcrypt").toLowerCase(Locale.ROOT);
        // 哈希算法校验：仅支持 bcrypt/sha256，非法值回退为 bcrypt（避免静默降级为 sha256）
        if (!"bcrypt".equals(this.passwordHashAlgorithm) && !"sha256".equals(this.passwordHashAlgorithm)) {
            plugin.getLogger().warning(I18n.get("log.config_hash_invalid", this.passwordHashAlgorithm, "bcrypt"));
            this.passwordHashAlgorithm = "bcrypt";
            config.set("password.hash", "bcrypt");
            configDirty = true;
        }
        // BCrypt work factor：钳制 10-31 有效范围（低于 10 时离线爆破成本过低），越界时回写配置文件
        this.bcryptCost = clampRange("password.hash-cost", config.getInt("password.hash-cost", 12), 10, 31);
        // 密码字符规则：正则表达式，为空表示不限制
        String patternStr = config.getString("password.pattern", "");
        if (patternStr.isBlank()) {
            this.passwordPattern = null;
        } else {
            try {
                this.passwordPattern = Pattern.compile(patternStr);
            } catch (java.util.regex.PatternSyntaxException e) {
                plugin.getLogger().warning(I18n.get("log.password_pattern_invalid", patternStr, e.getMessage()));
                this.passwordPattern = null;
            }
        }
    }

    // 注册限制
    private void loadRegisterConfig(FileConfiguration config) {
        this.maxAccountsPerIp = clampInt("register.max-accounts-per-ip.limit", config.getInt("register.max-accounts-per-ip.limit", 3), 0);
        // 连接阶段拦截未注册玩家（IP 已满时）的开关，默认开启保持严格；共享 IP 环境可关闭
        this.ipLimitRejectJoin = config.getBoolean("register.max-accounts-per-ip.reject-join", true);
    }

    // 登录前保护
    private void loadProtectionConfig(FileConfiguration config) {
        this.protectionPosEnabled = config.getBoolean("protection.pos.enabled", false);
        this.protectionPosMode = config.getString("protection.pos.mode", "random");
        // 坐标模式校验：仅支持 random/fixed，非法值回退为 random
        if (!"random".equals(this.protectionPosMode) && !"fixed".equals(this.protectionPosMode)) {
            plugin.getLogger().warning(I18n.get("log.config_mode_invalid", "protection.pos.mode", this.protectionPosMode, "random"));
            this.protectionPosMode = "random";
            config.set("protection.pos.mode", "random");
            configDirty = true;
        }
        this.protectionPosSpawnRadius = clampInt("protection.pos.spawn-radius", config.getInt("protection.pos.spawn-radius", 10), 1);
        this.protectionPosFixedX = config.getDouble("protection.pos.fixed.x", 0);
        this.protectionPosFixedY = config.getDouble("protection.pos.fixed.y", 64);
        this.protectionPosFixedZ = config.getDouble("protection.pos.fixed.z", 0);
        this.protectionPosFixedYaw = (float) config.getDouble("protection.pos.fixed.yaw", 0);
        this.protectionPosFixedPitch = (float) config.getDouble("protection.pos.fixed.pitch", 0);

        // 未登录旁观模式
        this.protectionGamemodeEnabled = config.getBoolean("protection.gamemode", false);

        // 背包保护（PacketEvents 数据包拦截）
        this.protectionInventoryEnabled = config.getBoolean("protection.inventory", false);

        // 登录前失明
        this.protectionBlindnessEnabled = config.getBoolean("protection.blindness", true);
        this.rejectNoAuthAccount = config.getBoolean("protection.reject-no-auth-account", true);

        // 行为限制（protection.prevent 段）
        this.preventMove = config.getBoolean("protection.prevent.move", true);
        this.preventLook = config.getBoolean("protection.prevent.look", true);
        this.preventChat = config.getBoolean("protection.prevent.chat", true);
        this.preventCommand = config.getBoolean("protection.prevent.command.enabled", true);
        // 命令白名单统一转小写，匹配时大小写不敏感（固定 Locale.ROOT：突等区域设置下无点 i 变形会导致条目失配）
        this.commandWhitelist = config.getStringList("protection.prevent.command.whitelist")
                .stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();
        this.preventWorldInteraction = config.getBoolean("protection.prevent.world-interaction", true);
        this.preventInventory = config.getBoolean("protection.prevent.inventory", true);

    }

    // 末影珍珠保管与返还
    private void loadPearlConfig(FileConfiguration config) {
        this.pearlEnabled = config.getBoolean("pearl.enabled", true);
        this.pearlReturnMode = config.getString("pearl.return", "item");
        // 返还方式校验：仅支持 item/entity，非法值回退为 item
        if (!"item".equals(this.pearlReturnMode) && !"entity".equals(this.pearlReturnMode)) {
            plugin.getLogger().warning(I18n.get("log.config_mode_invalid", "pearl.return", this.pearlReturnMode, "item"));
            this.pearlReturnMode = "item";
            config.set("pearl.return", "item");
            configDirty = true;
        }
    }

    // 正版验证
    private void loadPremiumConfig(FileConfiguration config) {
        this.premiumEnabled = config.getBoolean("premium.enabled", false);
        this.premiumAutoVerify = config.getBoolean("premium.auto-verify", true);
        // HTTP 出站代理列表（host:port）：借道代理访问真正的 Mojang 官方验证服务器。
        // 非法项（格式错误）仅告警、不删除配置——节点可能出错或临时不可用，保留便于排查
        List<Proxy> httpProxies = new ArrayList<>();
        for (String raw : config.getStringList("premium.http-proxies")) {
            String entry = raw == null ? "" : raw.trim();
            // 跳过默认配置自带的占位示例，未修改配置时不会误当真实代理探测
            if (entry.isEmpty() || entry.equals("example.com:25565")) continue;
            Proxy proxy = parseHttpProxy(entry);
            if (proxy == null) {
                plugin.getLogger().warning(I18n.get("log.config_http_proxy_invalid", entry));
            } else {
                httpProxies.add(proxy);
            }
        }
        this.premiumHttpProxies = List.copyOf(httpProxies);
        // 会话验证镜像服务器列表（完整 URL）：实现 hasJoined 接口的替代服务器，作为代理的备选。
        // 非法项（非 http/https 开头）仅告警、不删除配置——节点可能出错或临时不可用，保留便于排查
        List<String> mirrors = new ArrayList<>();
        for (String raw : config.getStringList("premium.session-server-mirrors")) {
            String url = raw == null ? "" : raw.trim();
            // 跳过默认配置自带的占位示例，未修改配置时不会误当真实镜像探测
            if (url.isEmpty() || url.equals("https://mirror.example.com")) continue;
            if (url.startsWith("http://") || url.startsWith("https://")) {
                // 去除末尾斜杠，保证拼接路径正确
                mirrors.add(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
            } else {
                plugin.getLogger().warning(I18n.get("log.config_session_mirror_invalid", url));
            }
        }
        this.premiumSessionServerMirrors = List.copyOf(mirrors);
        this.premiumTimeoutSeconds = clampInt("premium.timeout-seconds", config.getInt("premium.timeout-seconds", 5), 1);
        // 验证总时限：默认 30 秒，不超过客户端"通讯加密中"等待上限（30 秒），避免服务端验证超时后客户端已主动断开
        this.premiumVerifyDeadlineMs = clampInt("premium.verify-deadline-ms", config.getInt("premium.verify-deadline-ms", 30000), 1000);
        this.premiumCrackerCacheSeconds = clampInt("premium.cracker-cache-seconds", config.getInt("premium.cracker-cache-seconds", 120), 0);
        this.premiumHandshakeTimeoutMs = clampInt("premium.handshake-timeout-ms", config.getInt("premium.handshake-timeout-ms", 30000), 0);
        this.premiumMaxRetries = clampInt("premium.max-retries", config.getInt("premium.max-retries", 2), 0);
        this.premiumRetryIntervalMs = clampInt("premium.retry-interval-ms", config.getInt("premium.retry-interval-ms", 500), 0);
        this.premiumHttpPoolSize = clampRange("premium.http-pool-size", config.getInt("premium.http-pool-size", 2), 2, 64);
        this.premiumCacheCap = clampInt("premium.cache-cap", config.getInt("premium.cache-cap", 1000), 0);
        this.premiumUpgradeEnabled = config.getBoolean("premium.upgrade", true);
        this.premiumDowngradeEnabled = config.getBoolean("premium.downgrade", true);
        this.premiumPasswordFallbackEnabled = config.getBoolean("premium.fallback.enabled", false);
        this.premiumFallbackCacheSeconds = clampInt("premium.fallback.cache-seconds", config.getInt("premium.fallback.cache-seconds", 300), 30);
    }

    /**
     * 增量合并默认配置：从插件内置的 config.yml 读取新配置键，仅将用户 config 中缺失的键补入，
     * 保留用户已有的自定义值（不再整体覆盖，避免升级时丢配置）。最后更新 version 字段并写回。
     */
    private void mergeMissingKeys(FileConfiguration config) {
        try (InputStream is = plugin.getResource("config.yml")) {
            if (is != null) {
                FileConfiguration defaults =
                        YamlConfiguration.loadConfiguration(new InputStreamReader(is, StandardCharsets.UTF_8));
                for (String key : defaults.getKeys(true)) {
                    if (!config.contains(key)) {
                        config.set(key, defaults.get(key));
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning(I18n.get("log.config_merge_failed", e.getMessage()));
        }
        config.set("version", plugin.getPluginMeta().getVersion());
        plugin.saveConfig();
    }

    // 加入/退出消息
    private void loadMessageConfig(FileConfiguration config) {
        this.joinDisabled = config.getBoolean("messages.join.disabled", false);
        this.joinHideUnauthenticated = config.getBoolean("messages.join.hide-unauthenticated", true);
        this.joinDelayUntilAuthenticated = config.getBoolean("messages.join.delay-until-authenticated", true);
        this.joinMessageTemplate = config.getString("messages.join.template", "");
        this.quitDisabled = config.getBoolean("messages.quit.disabled", false);
        this.quitHideUnauthenticated = config.getBoolean("messages.quit.hide-unauthenticated", true);
        this.quitMessageTemplate = config.getString("messages.quit.template", "");
    }

    /**
     * 重新加载配置。
     * 数据库配置变化时数据源无法运行时重建，但 ConfigManager 字段仍更新为配置文件当前值，
     * 重启后 PlayerDataManager 会用新配置创建数据源。
     * @return true 表示数据库配置发生变化（调用方应提示重启以应用数据库变更）
     */
    public boolean reload() {
        String oldFingerprint = this.databaseFingerprint;
        load();
        // 字段已更新为配置文件当前值，但运行中的数据源未重建，需提示用户重启
        return !String.valueOf(oldFingerprint).equals(this.databaseFingerprint);
    }

    /** 取版本号前两位（major.minor），patch 版本仅修 bug 不影响配置结构 */
    private static String majorMinor(String version) {
        if (version == null) return "";
        String base = version.split("-", 2)[0];
        String[] parts = base.split("\\.");
        if (parts.length >= 2) return parts[0] + "." + parts[1];
        return base;
    }

    /** 解析 HTTP 出站代理条目（host:port），格式非法返回 null */
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

    /** 整型配置校验：低于下限时调整为下限并告警，避免非法值导致运行时异常 */
    private int clampInt(String key, int value, int min) {
        if (value < min) {
            plugin.getLogger().warning(I18n.get("log.config_num_clamped", key, value, min));
            plugin.getConfig().set(key, min);
            configDirty = true;
            return min;
        }
        return value;
    }

    /** 整型配置校验：限定区间 [min, max]，越界时钳制到边界并告警（用于端口、线程池等有硬性上限的配置） */
    private int clampRange(String key, int value, int min, int max) {
        if (value < min) {
            plugin.getLogger().warning(I18n.get("log.config_num_clamped", key, value, min));
            plugin.getConfig().set(key, min);
            configDirty = true;
            return min;
        }
        if (value > max) {
            plugin.getLogger().warning(I18n.get("log.config_num_clamped", key, value, max));
            plugin.getConfig().set(key, max);
            configDirty = true;
            return max;
        }
        return value;
    }

    // 数据库设置
    public String databaseType() { return databaseType; }
    public String mysqlHost() { return mysqlHost; }
    public int mysqlPort() { return mysqlPort; }
    public String mysqlDatabase() { return mysqlDatabase; }
    public String mysqlUsername() { return mysqlUsername; }
    public String mysqlPassword() { return mysqlPassword; }
    public Map<String, String> mysqlParams() { return mysqlParams; }
    public int poolSize() { return poolSize; }

    // 登录设置
    public int loginTimeout() { return loginTimeout; }
    public int registerTimeout() { return registerTimeout; }
    public boolean kickOnTimeout() { return kickOnTimeout; }
    public boolean failProtectionEnabled() { return failProtectionEnabled; }
    public int failMaxAttempts() { return failMaxAttempts; }
    public int failKickDuration() { return failKickDuration; }
    public int failProtectionResetSeconds() { return failProtectionResetSeconds; }
    public boolean sessionEnabled() { return sessionEnabled; }
    public int sessionExpireMinutes() { return sessionExpireMinutes; }
    public int loginRemindInterval() { return loginRemindInterval; }
    public String loginRemindMethod() { return loginRemindMethod; }
    public boolean loginDialogEnabled() { return loginDialogEnabled; }
    public boolean dialogAllowRiskyVersions() { return dialogAllowRiskyVersions; }
    public boolean ipChangeNotifyEnabled() { return ipChangeNotifyEnabled; }
    public boolean twoFaEnabled() { return twoFaEnabled; }
    public int twoFaTempSecretExpireSeconds() { return twoFaTempSecretExpireSeconds; }
    public boolean twoFaSessionEnabled() { return twoFaSessionEnabled; }
    public int twoFaSessionExpireMinutes() { return twoFaSessionExpireMinutes; }
    public boolean twoFaQrEnabled() { return twoFaQrEnabled; }
    public String twoFaQrUrl() { return twoFaQrUrl; }
    public String twoFaServerName() { return twoFaServerName == null ? "" : twoFaServerName.trim(); }

    // 密码规则
    public int minPasswordLength() { return minPasswordLength; }
    public int maxPasswordLength() { return maxPasswordLength; }
    public String passwordHashAlgorithm() { return passwordHashAlgorithm; }
    public int bcryptCost() { return bcryptCost; }
    /** 密码正则规则，null 表示不限制 */
    public Pattern passwordPattern() { return passwordPattern; }

    // 注册限制
    public int maxAccountsPerIp() { return maxAccountsPerIp; }
    // 同 IP 已满时是否在连接阶段拒绝未注册新玩家进服
    public boolean ipLimitRejectJoin() { return ipLimitRejectJoin; }

    // 行为限制
    public boolean preventMove() { return preventMove; }
    public boolean preventLook() { return preventLook; }
    public boolean preventChat() { return preventChat; }
    public boolean preventCommand() { return preventCommand; }
    public List<String> commandWhitelist() { return commandWhitelist; }
    public boolean preventWorldInteraction() { return preventWorldInteraction; }
    public boolean preventInventory() { return preventInventory; }

    // 登录前保护
    public boolean protectionPosEnabled() { return protectionPosEnabled; }
    public String protectionPosMode() { return protectionPosMode; }
    public int protectionPosSpawnRadius() { return protectionPosSpawnRadius; }
    public double protectionPosFixedX() { return protectionPosFixedX; }
    public double protectionPosFixedY() { return protectionPosFixedY; }
    public double protectionPosFixedZ() { return protectionPosFixedZ; }
    public float protectionPosFixedYaw() { return protectionPosFixedYaw; }
    public float protectionPosFixedPitch() { return protectionPosFixedPitch; }

    // 未登录旁观模式
    public boolean protectionGamemodeEnabled() { return protectionGamemodeEnabled; }

    // 背包保护（PacketEvents 数据包拦截）
    public boolean protectionInventoryEnabled() { return protectionInventoryEnabled; }

    // 登录前失明
    public boolean protectionBlindnessEnabled() { return protectionBlindnessEnabled; }
    public boolean rejectNoAuthAccount() { return rejectNoAuthAccount; }

    // 末影珍珠保管开关
    public boolean pearlEnabled() { return pearlEnabled; }

    // 末影珍珠返还方式（true = entity 世界原位重生）
    public boolean pearlReturnEntity() { return "entity".equals(pearlReturnMode); }

    // 通用设置
    public boolean realUnreg() { return realUnreg; }
    public boolean allowSelfUnregister() { return allowSelfUnregister; }
    public boolean purgeEnabled() { return purgeEnabled; }
    public int purgeDays() { return purgeDays; }

    // 正版验证
    public boolean premiumEnabled() { return premiumEnabled; }
    public boolean premiumAutoVerify() { return premiumAutoVerify; }
    /** HTTP 出站代理列表（借道访问真正的 Mojang 官方验证服务器），验证时优先使用 */
    public List<Proxy> premiumHttpProxies() { return premiumHttpProxies; }
    /** 会话验证镜像候选列表：官方地址（硬编码）在首位，其后为配置的镜像，代理均不可用时依次尝试 */
    public List<String> premiumSessionServerCandidates() {
        List<String> candidates = new ArrayList<>(premiumSessionServerMirrors.size() + 1);
        candidates.add(DEFAULT_SESSION_SERVER_URL);
        candidates.addAll(premiumSessionServerMirrors);
        return candidates;
    }
    public int premiumTimeoutSeconds() { return premiumTimeoutSeconds; }
    public int premiumVerifyDeadlineMs() { return premiumVerifyDeadlineMs; }
    public int premiumCrackerCacheSeconds() { return premiumCrackerCacheSeconds; }
    public int premiumHandshakeTimeoutMs() { return premiumHandshakeTimeoutMs; }
    public int premiumMaxRetries() { return premiumMaxRetries; }
    public long premiumRetryIntervalMs() { return premiumRetryIntervalMs; }
    public int premiumHttpPoolSize() { return premiumHttpPoolSize; }
    public int premiumCacheCap() { return premiumCacheCap; }
    public boolean premiumUpgradeEnabled() { return premiumUpgradeEnabled; }
    public boolean premiumDowngradeEnabled() { return premiumDowngradeEnabled; }
    public boolean premiumPasswordFallbackEnabled() { return premiumPasswordFallbackEnabled; }
    public int premiumFallbackCacheSeconds() { return premiumFallbackCacheSeconds; }

    public boolean joinDisabled() { return joinDisabled; }
    public boolean joinHideUnauthenticated() { return joinHideUnauthenticated; }
    public boolean joinDelayUntilAuthenticated() { return joinDelayUntilAuthenticated; }
    public String joinMessageTemplate() { return joinMessageTemplate == null ? "" : joinMessageTemplate; }
    public boolean quitDisabled() { return quitDisabled; }
    public boolean quitHideUnauthenticated() { return quitHideUnauthenticated; }
    public String quitMessageTemplate() { return quitMessageTemplate == null ? "" : quitMessageTemplate; }

}