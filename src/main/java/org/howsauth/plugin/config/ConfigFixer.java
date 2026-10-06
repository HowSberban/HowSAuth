package org.howsauth.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.howsauth.plugin.I18n;

import java.util.Collection;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 配置修正记录器：把"校验失败 → 修正并回写"的统一套路收敛到一处。
 * <p>
 * 各领域配置类在自己构造时发现非法值，就通过本类修正：写入内存中的 {@link FileConfiguration}
 * 并记录一次告警，同时标记本次加载发生过修正。回写动作仍由 {@link ConfigManager#load()}
 * 结束时统一执行，保持"一次加载最多写一次盘"的既有行为。
 * <p>
 * 告警顺序与逐字段加载顺序一致，便于对照排查。
 */
final class ConfigFixer {

    private final FileConfiguration config;
    private final Consumer<String> warningSink;
    private final Runnable onFix;
    private boolean dirty;

    /**
     * @param config      内存中的配置对象，修正直接写在这里
     * @param warningSink 告警出口（生产为插件日志）
     * @param onFix       每次发生修正时的回调（ConfigManager 用它置位回写标记）
     */
    ConfigFixer(FileConfiguration config, Consumer<String> warningSink, Runnable onFix) {
        this.config = config;
        this.warningSink = warningSink;
        this.onFix = onFix;
    }

    /** 本次加载是否发生过修正 */
    boolean dirty() {
        return dirty;
    }

    /** 覆写一个配置键，并标记需要回写文件 */
    void set(String key, Object value) {
        config.set(key, value);
        dirty = true;
        onFix.run();
    }

    /** 告警统一出口，避免各处自行拼接文案 */
    void warn(String message) {
        warningSink.accept(message);
    }

    /** 通用数值钳制告警文案 */
    String numberClampedMessage(String key, int value, int bound) {
        return I18n.get("log.config_num_clamped", key, value, bound);
    }

    /** 下限校验：低于 min 时修正为 min 并告警（对应原 clampInt） */
    int clampMin(String key, int value, int min) {
        if (value < min) {
            warn(numberClampedMessage(key, value, min));
            set(key, min);
            return min;
        }
        return value;
    }

    /** 区间校验：越界时钳制到最近边界并告警（对应原 clampRange） */
    int clampRange(String key, int value, int min, int max) {
        if (value < min) {
            warn(numberClampedMessage(key, value, min));
            set(key, min);
            return min;
        }
        if (value > max) {
            warn(numberClampedMessage(key, value, max));
            set(key, max);
            return max;
        }
        return value;
    }

    /**
     * 枚举校验：值不在 {@code allowed} 内时回退到 {@code fallback}、告警并回写配置文件。
     * <p>
     * 告警文案由调用方指定 i18n key（各领域的 key 与参数含义不同，故不在此处统一）。
     * 告警使用**原始值**而非归一后的值，与各国别实现拆分前的输出一致。
     *
     * @param key       配置键，用于回写
     * @param value     原始值
     * @param allowed   允许值集合
     * @param fallback  非法时回退的值，同时作为回写值
     * @param messageKey 该领域的非法值告警 i18n key，参数依次为 (key, 原始值, fallback)
     * @param lowerCase 是否在比较前把值小写归一；仅登录提醒方式一项曾做归一，其余领域保持大小写敏感
     * @return 归一后的合法值，或 fallback
     */
    String enumOrDefault(String key, String value, Collection<String> allowed, String fallback,
                         String messageKey, boolean lowerCase) {
        String candidate = lowerCase && value != null ? value.toLowerCase(Locale.ROOT) : value;
        if (candidate == null || !allowed.contains(candidate)) {
            warn(I18n.get(messageKey, key, value, fallback));
            set(key, fallback);
            return fallback;
        }
        return candidate;
    }
}
