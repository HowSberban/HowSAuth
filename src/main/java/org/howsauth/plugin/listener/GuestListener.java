package org.howsauth.plugin.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.auth.SessionStore;
import org.howsauth.plugin.config.ConfigManager;

import java.util.Locale;

/**
 * 未登录玩家的行为限制：按配置拦截移动、聊天、命令、方块与实体交互、容器操作等，
 * 防止未认证玩家影响世界或借交互窥视。
 * <p>
 * 本组处理器只依赖配置开关与登录态，与认证流程本身无耦合，故此独立成类：
 * 认证流程的改动不应牵连这 18 个拦截器，反之亦然。
 * <p>
 * 全部处理器与认证流程同用 {@link EventPriority#LOWEST}，保持拦截先于其它监听器的既有次序。
 */
public final class GuestListener implements Listener {

    private final ConfigManager configManager;
    private final SessionStore sessions;

    public GuestListener(ConfigManager configManager, SessionStore sessions) {
        this.configManager = configManager;
        this.sessions = sessions;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        if (!configManager.prevent().move()) return;

        Player player = event.getPlayer();
        if (sessions.isLoggedIn(player)) return;

        // Paper API 保证 getTo() 非 null（@NullMarked）
        // 使用 setTo() 而非 setCancelled(true)：
        //   1. 避免客户端与服务端位置不同步导致的画面卡顿
        //   2. 使用精确坐标比较（getX/Y/Z），避免方块坐标精度不足被绕过
        Location from = event.getFrom();
        Location to = event.getTo();
        boolean positionChanged = from.getX() != to.getX()
                || from.getY() != to.getY()
                || from.getZ() != to.getZ();
        boolean lookChanged = from.getYaw() != to.getYaw()
                || from.getPitch() != to.getPitch();

        boolean preventLook = configManager.prevent().look();
        if (preventLook) {
            // 禁止位置和视角变化：全部回滚到 from
            if (positionChanged || lookChanged) {
                event.setTo(from);
            }
        } else {
            // 仅禁止位置移动：直接改 to 的坐标（复用对象，避免热路径逐次分配），保留其视角（yaw/pitch）
            if (positionChanged) {
                to.setX(from.getX());
                to.setY(from.getY());
                to.setZ(from.getZ());
                event.setTo(to);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (!configManager.prevent().chat()) return;

        Player player = event.getPlayer();
        if (!sessions.isLoggedIn(player)) {
            if (Debug.on()) {
                Debug.log("flow", "blocked chat for %s (not logged in)", player.getName());
            }
            event.setCancelled(true);
            player.sendMessage(I18n.msg("listener.must_login", player));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!configManager.prevent().command()) return;

        Player player = event.getPlayer();
        if (sessions.isLoggedIn(player)) return;

        // 提取命令名（去掉前导 / 和参数），统一小写匹配
        String message = event.getMessage();
        if (message.startsWith("/")) message = message.substring(1);
        int space = message.indexOf(' ');
        String commandName = (space > 0 ? message.substring(0, space) : message).toLowerCase(Locale.ROOT);
        // 剥离命令命名空间前缀（/hsauth:login 与 /login 是同一命令，否则无法经命名空间形式登录）
        int colon = commandName.indexOf(':');
        if (colon >= 0) commandName = commandName.substring(colon + 1);

        // 白名单内的命令允许执行
        if (configManager.prevent().commandWhitelist().contains(commandName)) {
            return;
        }

        if (Debug.on()) {
            Debug.log("cmd", "blocked command for %s: /%s", player.getName(), commandName);
        }
        event.setCancelled(true);
        player.sendMessage(I18n.msg("listener.must_login", player));
    }

    /**
     * 未登录玩家拦截：配置开关开启且玩家未登录时取消事件（已登录或开关关闭一律放行）。
     * 各拦截器共用同一判断，避免同一段逻辑散落在每个事件处理器中。
     */
    private void cancelIfGuest(boolean enabled, Player player, Cancellable event) {
        if (enabled && !sessions.isLoggedIn(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        cancelIfGuest(configManager.prevent().worldInteraction(), event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        cancelIfGuest(configManager.prevent().worldInteraction(), event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (!configManager.prevent().worldInteraction()) return;
        if (event.getEntity() instanceof Player player) {
            // 未登录玩家或传送过渡期玩家不受伤害
            if (!sessions.isLoggedIn(player) || sessions.isInvulnerablePending(player)) {
                event.setCancelled(true);
                return;
            }
        }
        // 未登录玩家不可伤害任何实体（左键攻击不经过交互事件，须拦攻击者一侧）
        if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent byEntity) {
            // 直接近战：damager 为玩家，拦未登录者
            if (byEntity.getDamager() instanceof Player damager && !sessions.isLoggedIn(damager)) {
                event.setCancelled(true);
                return;
            }
            // 投射物：damager 为投射物实体，归因到射击者（箭离弦后射击者注销时仍可命中）
            if (byEntity.getDamager() instanceof Projectile projectile
                    && projectile.getShooter() instanceof Player shooter
                    && !sessions.isLoggedIn(shooter)) {
                event.setCancelled(true);
            }
        }
    }

    // 阻止怪物锁定未登录玩家（怪物不会朝玩家移动或试图攻击）
    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntityTarget(EntityTargetEvent event) {
        if (event.getTarget() instanceof Player player) {
            cancelIfGuest(configManager.prevent().worldInteraction(), player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            cancelIfGuest(configManager.prevent().worldInteraction(), player, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDropItem(PlayerDropItemEvent event) {
        cancelIfGuest(configManager.prevent().worldInteraction(), event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickupItem(PlayerAttemptPickupItemEvent event) {
        cancelIfGuest(configManager.prevent().worldInteraction(), event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        cancelIfGuest(configManager.prevent().worldInteraction(), event.getPlayer(), event);
    }

    // 实体交互（右键实体：村民交易、上马、喂食等）：未登录玩家保持原游戏模式时可打开交易界面窥视
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        cancelIfGuest(configManager.prevent().worldInteraction(), event.getPlayer(), event);
    }

    // 禁止未登录的旁观玩家附身实体：附身后镜头跟随目标实体移动，可窥视他人位置（绕过坐标保护）。
    // Paper 1.21.11 已移除 PlayerSpectateEntityEvent，附身改由 cause=SPECTATE 的传送事件表达
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSpectateTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE) return;
        cancelIfGuest(configManager.prevent().worldInteraction(), event.getPlayer(), event);
    }

    // 容器点击（含创造模式）
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            cancelIfGuest(configManager.prevent().inventory(), player, event);
        }
    }

    // 容器拖拽
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            cancelIfGuest(configManager.prevent().inventory(), player, event);
        }
    }

    // 传送门
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPortal(PlayerPortalEvent event) {
        cancelIfGuest(configManager.prevent().worldInteraction(), event.getPlayer(), event);
    }

    // 物品消耗（进食、喝药水等）
    @EventHandler(priority = EventPriority.LOWEST)
    public void onItemConsume(PlayerItemConsumeEvent event) {
        cancelIfGuest(configManager.prevent().inventory(), event.getPlayer(), event);
    }

    // 副手切换
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        cancelIfGuest(configManager.prevent().inventory(), event.getPlayer(), event);
    }
}
