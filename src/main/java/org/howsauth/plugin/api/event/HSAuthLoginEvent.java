package org.howsauth.plugin.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * 玩家登录成功事件。
 * 在密码登录、会话免密登录、正版免密登录、强制登录成功后触发。
 */
@SuppressWarnings("unused")
public class HSAuthLoginEvent extends Event {

    private static final HandlerList handlers = new HandlerList();
    private final Player player;

    public HSAuthLoginEvent(@NotNull Player player) {
        this.player = player;
    }

    @NotNull
    public Player getPlayer() {
        return player;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
