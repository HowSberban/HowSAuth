package org.howsauth.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;
import java.util.Locale;

/**
 * 未登录行为限制（{@code protection.prevent.*}）配置。
 * <p>
 * 登录前禁止移动、转向、聊天、执行非白名单命令以及世界/背包交互。
 * 命令白名单在构造时统一 trim + 小写（固定 Locale.ROOT），此后只读。
 */
public final class PreventConfig {

    private final boolean move;
    private final boolean look;
    private final boolean chat;
    private final boolean command;
    private final List<String> commandWhitelist;
    private final boolean worldInteraction;
    private final boolean inventory;

    PreventConfig(FileConfiguration config) {
        this.move = config.getBoolean("protection.prevent.move", true);
        this.look = config.getBoolean("protection.prevent.look", true);
        this.chat = config.getBoolean("protection.prevent.chat", true);
        this.command = config.getBoolean("protection.prevent.command.enabled", true);
        // 命令白名单统一转小写，匹配时大小写不敏感（固定 Locale.ROOT：突等区域设置下无点 i 变形会导致条目失配）
        this.commandWhitelist = config.getStringList("protection.prevent.command.whitelist")
                .stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();
        this.worldInteraction = config.getBoolean("protection.prevent.world-interaction", true);
        this.inventory = config.getBoolean("protection.prevent.inventory", true);
    }

    /** 禁止移动 */
    public boolean move() {
        return move;
    }

    /** 禁止转动视角（仅当 move 启用时生效） */
    public boolean look() {
        return look;
    }

    /** 禁止聊天 */
    public boolean chat() {
        return chat;
    }

    /** 禁止执行非白名单命令 */
    public boolean command() {
        return command;
    }

    /** 命令白名单（已 trim + 小写 + 去空，不可变） */
    public List<String> commandWhitelist() {
        return commandWhitelist;
    }

    /** 禁止与世界交互 */
    public boolean worldInteraction() {
        return worldInteraction;
    }

    /** 禁止背包交互 */
    public boolean inventory() {
        return inventory;
    }
}
