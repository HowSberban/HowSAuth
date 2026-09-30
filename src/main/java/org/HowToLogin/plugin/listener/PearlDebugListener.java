package org.howtologin.plugin.listener;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.Location;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.util.logging.Logger;

/** Temporary diagnostic listener for observing ender-pearl lifecycle and teleport behavior. */
public final class PearlDebugListener implements Listener {

    private final Logger logger;

    public PearlDebugListener(Logger logger) {
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof EnderPearl pearl) {
            logPearl("launch", pearl, "shooter=" + describeShooter(pearl));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity() instanceof EnderPearl pearl) {
            logPearl("hit", pearl, "hitEntity=" + describeEntity(event.getHitEntity())
                    + " hitBlock=" + (event.getHitBlock() == null ? "none" : event.getHitBlock().getType()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityAddToWorld(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof EnderPearl pearl) {
            logPearl("add-to-world", pearl, "");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoveFromWorld(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof EnderPearl pearl) {
            logPearl("remove-from-world", pearl, "");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        logger.info(() -> "[PearlTest] pearl-teleport player=" + event.getPlayer().getName()
                + " uuid=" + event.getPlayer().getUniqueId()
                + " from=" + describeLocation(event.getFrom())
                + " to=" + describeLocation(event.getTo())
                + " cancelled=" + event.isCancelled());
    }

    private void logPearl(String action, EnderPearl pearl, String extra) {
        Location location = pearl.getLocation();
        Vector velocity = pearl.getVelocity();
        logger.info(() -> "[PearlTest] " + action
                + " pearl=" + pearl.getUniqueId()
                + " valid=" + pearl.isValid()
                + " world=" + (location.getWorld() == null ? "null" : location.getWorld().getName())
                + " location=" + describeLocation(location)
                + " velocity=" + formatVector(velocity)
                + " shooter=" + describeShooter(pearl)
                + (extra.isEmpty() ? "" : " " + extra));
    }

    private static String describeShooter(EnderPearl pearl) {
        if (!(pearl.getShooter() instanceof Player player)) return String.valueOf(pearl.getShooter());
        return player.getName() + "/" + player.getUniqueId() + "/online=" + player.isOnline();
    }

    private static String describeEntity(org.bukkit.entity.Entity entity) {
        if (entity == null) return "none";
        return entity.getType() + "/" + entity.getUniqueId();
    }

    private static String describeLocation(Location location) {
        return location.getWorld() == null ? "null-world"
                : location.getWorld().getName() + ":" + location.getX() + "," + location.getY() + "," + location.getZ();
    }

    private static String formatVector(Vector vector) {
        return vector.getX() + "," + vector.getY() + "," + vector.getZ();
    }
}
