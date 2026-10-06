package org.howsauth.plugin.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * 加入/退出消息（{@code messages.*}）配置。
 * <p>
 * 控制原版加入/退出消息的隐藏、延迟补发与自定义模板；模板为空串时表示保留原版消息。
 * 解析无校验，构造后即只读。
 */
public final class MessagesConfig {

    private final boolean joinDisabled;
    private final boolean joinHideUnauthenticated;
    private final boolean joinDelayUntilAuthenticated;
    private final String joinMessageTemplate;
    private final boolean quitDisabled;
    private final boolean quitHideUnauthenticated;
    private final String quitMessageTemplate;

    MessagesConfig(FileConfiguration config) {
        // 完全禁用所有玩家的加入消息（开启后忽略其余加入消息选项）
        this.joinDisabled = config.getBoolean("messages.join.disabled", false);
        // 未登录（未完成登录/注册）时隐藏原版加入消息
        this.joinHideUnauthenticated = config.getBoolean("messages.join.hide-unauthenticated", true);
        // 隐藏后在登录成功时补发；未登录就退出则丢弃
        this.joinDelayUntilAuthenticated = config.getBoolean("messages.join.delay-until-authenticated", true);
        // 自定义加入消息模板（空 = 保留原版消息）
        this.joinMessageTemplate = config.getString("messages.join.template", "");
        // 完全禁用所有玩家的退出消息（开启后忽略其余退出消息选项）
        this.quitDisabled = config.getBoolean("messages.quit.disabled", false);
        // 未登录时隐藏原版退出消息
        this.quitHideUnauthenticated = config.getBoolean("messages.quit.hide-unauthenticated", true);
        // 自定义退出消息模板（空 = 保留原版消息）
        this.quitMessageTemplate = config.getString("messages.quit.template", "");
    }

    /** 是否完全禁用所有玩家的加入消息 */
    public boolean joinDisabled() {
        return joinDisabled;
    }

    /** 未登录时是否隐藏原版加入消息 */
    public boolean joinHideUnauthenticated() {
        return joinHideUnauthenticated;
    }

    /** 隐藏的加入消息是否在登录成功时补发 */
    public boolean joinDelayUntilAuthenticated() {
        return joinDelayUntilAuthenticated;
    }

    /** 自定义加入消息模板，空串表示保留原版消息 */
    public String joinMessageTemplate() {
        return joinMessageTemplate == null ? "" : joinMessageTemplate;
    }

    /** 是否完全禁用所有玩家的退出消息 */
    public boolean quitDisabled() {
        return quitDisabled;
    }

    /** 未登录时是否隐藏原版退出消息 */
    public boolean quitHideUnauthenticated() {
        return quitHideUnauthenticated;
    }

    /** 自定义退出消息模板，空串表示保留原版消息 */
    public String quitMessageTemplate() {
        return quitMessageTemplate == null ? "" : quitMessageTemplate;
    }
}
