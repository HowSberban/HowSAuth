package org.howsauth.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据库（{@code database.*}）配置。
 * <p>
 * {@link #fingerprint()} 供 {@link ConfigManager#reload()} 判断数据库配置是否变化（变化需重启而非热重载）。
 * 指纹包含口令等敏感值，故仅作为包内方法暴露、不提供公开 getter，避免被日志或调试输出意外带出。
 */
public final class DatabaseConfig {

    /** 支持的数据库类型 */
    private static final List<String> TYPES = List.of("sqlite", "mysql");

    private final String type;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final Map<String, String> params;
    private final int poolSize;

    DatabaseConfig(FileConfiguration config, ConfigFixer fixer) {
        // 数据库类型校验：仅支持 sqlite/mysql，非法值回退为 sqlite（键值统一小写）
        this.type = fixer.enumOrDefault("database.type", config.getString("database.type", "sqlite"),
                TYPES, "sqlite", "log.config_database_type_invalid", true);
        this.host = config.getString("database.mysql.host", "localhost");
        this.port = fixer.clampRange("database.mysql.port", config.getInt("database.mysql.port", 3306), 1, 65535);
        this.database = config.getString("database.mysql.database", "hsauth");
        this.username = config.getString("database.mysql.username", "root");
        this.password = config.getString("database.mysql.password", "");
        // 连接参数用保序的不可变包装：Map.copyOf 不保证迭代顺序，而拼接 JDBC URL 依赖顺序
        Map<String, String> collected = new LinkedHashMap<>();
        var paramsSection = config.getConfigurationSection("database.mysql.params");
        if (paramsSection != null) {
            for (String key : paramsSection.getKeys(false)) {
                collected.put(key, config.getString("database.mysql.params." + key, ""));
            }
        }
        this.params = Collections.unmodifiableMap(collected);
        this.poolSize = fixer.clampRange("database.mysql.pool-size", config.getInt("database.mysql.pool-size", 10), 1, 128);
    }

    /**
     * 数据库配置指纹：用于检测 reload 时数据库配置是否变化。
     * 参数按 key 排序拼接，保证顺序无关的稳定性。
     */
    String fingerprint() {
        return type + "|" + host + "|" + port + "|" + database + "|" + username + "|" + password + "|" + poolSize
                + "|" + params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }

    /** 数据库类型：sqlite / mysql */
    public String type() {
        return type;
    }

    /** MySQL 主机名（sqlite 下不生效） */
    public String host() {
        return host;
    }

    /** MySQL 端口 */
    public int port() {
        return port;
    }

    /** MySQL 库名 */
    public String database() {
        return database;
    }

    /** MySQL 用户名 */
    public String username() {
        return username;
    }

    /** MySQL 口令 */
    public String password() {
        return password;
    }

    /** 连接参数（按键排序无关的只读视图） */
    public Map<String, String> params() {
        return params;
    }

    /** 连接池大小 */
    public int poolSize() {
        return poolSize;
    }
}
