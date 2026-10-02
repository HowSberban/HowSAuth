package org.howsauth.plugin.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 玩家登录失败事件。
 * 在密码错误等登录失败后触发，供第三方插件联动（如惩罚、统计登录失败次数）。
 * 注意：Pre-join Dialog（配置阶段）的登录失败发生在玩家进入世界前，此时 {@link #getPlayer()} 返回 null。
 */
@SuppressWarnings("unused")
public class HSAuthLoginFailEvent extends Event {

    /** 登录失败原因 */
    public enum Reason {
        /** 密码错误 */
        WRONG_PASSWORD
    }

    private static final HandlerList handlers = new HandlerList();
    private final @Nullable Player player;
    private final Reason reason;

    public HSAuthLoginFailEvent(@Nullable Player player, @NotNull Reason reason) {
        this.player = player;
        this.reason = reason;
    }

    /** 登录失败的玩家；配置阶段（pre-join）失败时玩家尚未进入世界，返回 null */
    public @Nullable Player getPlayer() {
        return player;
    }

    @NotNull
    public Reason getReason() {
        return reason;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
