package org.howsauth.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class ConfigManager {

    private final HowSAuth plugin;

    // 按领域拆分的配置对象（只读快照，随每次 load() 重建）
    private PremiumConfig premium;
    private PreventConfig prevent;
    private DatabaseConfig database;
    private ProtectionPosition protectionPosition;
    private LoginConfig login;
    private TwoFactorConfig twoFactor;
    private PasswordConfig password;
    private RegisterConfig register;
    private ProtectionMiscConfig protectionMisc;
    private PearlConfig pearl;
    private SettingsConfig settings;
    private MessagesConfig messages;

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

        // 各领域配置类在构造时完成校验，修正统一记入 fixer，由本方法末尾一次写盘
        ConfigFixer fixer = newFixer(config);

        checkConfigVersion(config);
        this.database = new DatabaseConfig(config, fixer);
        this.login = new LoginConfig(config, fixer);
        this.twoFactor = new TwoFactorConfig(config, fixer);
        this.password = new PasswordConfig(config, fixer);
        this.register = new RegisterConfig(config, fixer);
        this.protectionPosition = new ProtectionPosition(config, fixer);
        this.prevent = new PreventConfig(config);
        this.protectionMisc = new ProtectionMiscConfig(config);
        this.pearl = new PearlConfig(config, fixer);
        this.premium = new PremiumConfig(config, fixer);
        this.messages = new MessagesConfig(config);
        this.settings = new SettingsConfig(config, fixer);

        // 默认语言（控制台日志和客户端语言无匹配文件时使用）：不归 SettingsConfig，此处内联处理
        String defaultLanguage = config.getString("settings.default-language", "zh_CN");
        boolean clientLanguageDetection = config.getBoolean("settings.i18n", true);
        I18n.setDefaultLocale(defaultLanguage);
        I18n.setClientLanguageDetection(clientLanguageDetection);

        // 有修正时写回 config.yml，避免下次启动重复告警
        if (fixer.dirty()) {
            plugin.saveConfig();
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

    /**
     * 重新加载配置。
     * 数据库配置变化时数据源无法运行时重建，但 ConfigManager 字段仍更新为配置文件当前值，
     * 重启后 PlayerDataManager 会用新配置创建数据源。
     * @return true 表示数据库配置发生变化（调用方应提示重启以应用数据库变更）
     */
    public boolean reload() {
        String oldFingerprint = database.fingerprint();
        load();
        // 字段已更新为配置文件当前值，但运行中的数据源未重建，需提示用户重启
        return !oldFingerprint.equals(database.fingerprint());
    }

    /** 取版本号前两位（major.minor），patch 版本仅修 bug 不影响配置结构 */
    private static String majorMinor(String version) {
        if (version == null) return "";
        String base = version.split("-", 2)[0];
        String[] parts = base.split("\\.");
        if (parts.length >= 2) return parts[0] + "." + parts[1];
        return base;
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

    /**
     * 配置修正记录器：告警走插件日志，并累计是否发生过修正。
     * 一次 {@link #load()} 只创建一个实例，由末尾统一决定是否回写文件。
     */
    private ConfigFixer newFixer(FileConfiguration config) {
        return new ConfigFixer(config, message -> plugin.getLogger().warning(message), () -> { });
    }

    // 领域配置对象（只读，随每次 load() 重建）
    public PremiumConfig premium() { return premium; }
    public PreventConfig prevent() { return prevent; }
    public DatabaseConfig database() { return database; }
    public ProtectionPosition protectionPosition() { return protectionPosition; }
    public LoginConfig login() { return login; }
    public TwoFactorConfig twoFactor() { return twoFactor; }
    public PasswordConfig password() { return password; }
    public RegisterConfig register() { return register; }
    public ProtectionMiscConfig protectionMisc() { return protectionMisc; }
    public PearlConfig pearl() { return pearl; }
    public SettingsConfig settings() { return settings; }
    public MessagesConfig messages() { return messages; }

}
