package org.howsauth.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.howsauth.plugin.I18n;

import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 密码规则（{@code password.*}）配置。
 * <p>
 * 构造时完成长度上下界、哈希算法、BCrypt work factor 与字符正则的全部校验。
 * 长度三段的校验互相依赖，顺序不可调换：先分别钳制单侧边界，再修正上下界倒置。
 * 非法项按既有语义修正（钳制/回退并回写，正则非法回退为不限制），此后只读。
 */
public final class PasswordConfig {

    private final int minLength;
    private final int maxLength;
    private final String hashAlgorithm;
    private final int bcryptCost;
    private final Pattern pattern;

    PasswordConfig(FileConfiguration config, ConfigFixer fixer) {
        int min = config.getInt("password.min-length", 6);
        int max = config.getInt("password.max-length", 32);
        // 密码长度配置校验：最小值不得小于 4
        if (min < 4) {
            fixer.warn(I18n.get("log.password_min_length_clamped", min, 4));
            fixer.set("password.min-length", 4);
            min = 4;
        }
        // 最大值不得大于 128
        if (max > 128) {
            fixer.warn(I18n.get("log.password_max_length_clamped", max, 128));
            fixer.set("password.max-length", 128);
            max = 128;
        }
        // 最大值不得小于最小值
        if (max < min) {
            fixer.warn(I18n.get("log.password_length_range_invalid", max, min));
            fixer.set("password.max-length", min);
            max = min;
        }
        this.minLength = min;
        this.maxLength = max;

        String hash = config.getString("password.hash", "bcrypt").toLowerCase(Locale.ROOT);
        // 哈希算法校验：仅支持 bcrypt/sha256，非法值回退为 bcrypt（避免静默降级为 sha256）
        if (!"bcrypt".equals(hash) && !"sha256".equals(hash)) {
            fixer.warn(I18n.get("log.config_hash_invalid", hash, "bcrypt"));
            fixer.set("password.hash", "bcrypt");
            hash = "bcrypt";
        }
        this.hashAlgorithm = hash;

        // BCrypt work factor：钳制 10-31 有效范围（低于 10 时离线爆破成本过低），越界时回写配置文件
        this.bcryptCost = fixer.clampRange("password.hash-cost", config.getInt("password.hash-cost", 12), 10, 31);

        // 密码字符规则：正则表达式，为空表示不限制
        String patternStr = config.getString("password.pattern", "");
        Pattern compiled = null;
        if (!patternStr.isBlank()) {
            try {
                compiled = Pattern.compile(patternStr);
            } catch (PatternSyntaxException e) {
                // 编译失败保持 compiled 初值 null，即"不限制"
                fixer.warn(I18n.get("log.password_pattern_invalid", patternStr, e.getMessage()));
            }
        }
        this.pattern = compiled;
    }

    /** 密码最小长度（不低于 4） */
    public int minLength() {
        return minLength;
    }

    /** 密码最大长度（不高于 128，且不小于最小长度） */
    public int maxLength() {
        return maxLength;
    }

    /** 哈希算法：bcrypt / sha256 */
    public String hashAlgorithm() {
        return hashAlgorithm;
    }

    /** BCrypt work factor（10-31） */
    public int bcryptCost() {
        return bcryptCost;
    }

    /** 密码正则规则，null 表示不限制 */
    public Pattern pattern() {
        return pattern;
    }
}
