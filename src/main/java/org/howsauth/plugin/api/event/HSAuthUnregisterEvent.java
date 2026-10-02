package org.howsauth.plugin.api.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 玩家账号注销事件。
 * 在 /unregister 成功后触发。
 * 玩家离线时 getPlayer() 返回 null。
 */
@SuppressWarnings("unused")
public class HSAuthUnregisterEvent extends Event {

    private static final HandlerList handlers = new HandlerList();
    private final UUID uuid;
    private final Player player;

    public HSAuthUnregisterEvent(@NotNull UUID uuid, @Nullable Player player) {
        this.uuid = uuid;
        this.player = player;
    }

    @NotNull
    public UUID getUuid() {
        return uuid;
    }

    @Nullable
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
