package org.howsauth.plugin.auth;

import org.bukkit.command.CommandSender;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;

/** 密码规则校验：统一处理长度与正则规则，供命令与 Dialog 登录窗口复用 */
public final class PasswordValidator {

    private PasswordValidator() {}

    /**
     * 校验密码规则（长度 + 正则），非法时向发送者发送错误消息。
     * @return true 表示非法（已发送消息，调用方应直接 return）；false 表示合法
     */
    public static boolean invalid(HowSAuth plugin, CommandSender sender, String password) {
        String error = invalidMessage(plugin, sender, password);
        if (error == null) return false;
        sender.sendMessage(HowSAuth.legacy(error));
        return true;
    }

    /**
     * 校验密码规则，返回格式化后的错误消息。
     * @return null 表示合法；非 null 为错误消息（Dialog 直接显示在窗口内）
     */
    public static String invalidMessage(HowSAuth plugin, CommandSender sender, String password) {
        int minLen = plugin.config().password().minLength();
        int maxLen = plugin.config().password().maxLength();
        if (password.length() < minLen || password.length() > maxLen) {
            return I18n.get("command.password_length", sender, minLen, maxLen);
        }
        java.util.regex.Pattern pattern = plugin.config().password().pattern();
        if (pattern != null && !pattern.matcher(password).matches()) {
            return I18n.get("command.password_pattern", sender);
        }
        return null;
    }

    /**
     * 校验密码规则，返回按指定语言的错误消息（无 Player 对象的场景，如配置阶段 Dialog）。
     * @return null 表示合法；非 null 为错误消息
     */
    public static String invalidMessage(HowSAuth plugin, String locale, String password) {
        int minLen = plugin.config().password().minLength();
        int maxLen = plugin.config().password().maxLength();
        if (password.length() < minLen || password.length() > maxLen) {
            return I18n.getForLocale("command.password_length", locale, minLen, maxLen);
        }
        java.util.regex.Pattern pattern = plugin.config().password().pattern();
        if (pattern != null && !pattern.matcher(password).matches()) {
            return I18n.getForLocale("command.password_pattern", locale);
        }
        return null;
    }

    /**
     * 仅校验正则规则（管理员强制操作不限制长度，保持原行为）。
     * @return true 表示不匹配（已发送消息）；false 表示合法或未配置规则
     */
    public static boolean invalidPattern(HowSAuth plugin, CommandSender sender, String password) {
        java.util.regex.Pattern pattern = plugin.config().password().pattern();
        if (pattern == null) return false;
        if (!pattern.matcher(password).matches()) {
            sender.sendMessage(I18n.msg("command.password_pattern", sender));
            return true;
        }
        return false;
    }
}
