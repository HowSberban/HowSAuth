package org.howsauth.plugin.packet;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.howsauth.plugin.auth.SessionStore;
import org.howsauth.plugin.config.ConfigManager;
import org.jspecify.annotations.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 数据包层物品保护：未登录期间，拦截发送给该玩家本人的物品信息数据包，
 * 清空为 EMPTY，防止客户端 Mod（如 MiniHUD）窥视。
 * <p>
 * 拦截范围：
 * - WINDOW_ITEMS / SET_SLOT（windowId=0/-1/-2）：未登录玩家本人收到时清空
 * - ENTITY_EQUIPMENT：发给未登录玩家本人时清空（目标为自己）
 * <p>
 * 其他玩家不受影响，始终能看到未登录玩家的真实装备。
 * 登录后由 AuthManager.onLoginSuccess 触发 updateInventory 刷新本人背包（含装备槽）。
 * <p>
 * 注意：未登录玩家本人的行为限制（不能打开容器/末影箱等）由 prevent.* 配置控制，
 * 不由此监听器负责。此监听器只负责"物品信息不外泄"。
 */
public final class InventoryPacketListener extends PacketListenerAbstract {

    private final SessionStore sessions;
    private final ConfigManager configManager;

    public InventoryPacketListener(SessionStore sessions, ConfigManager configManager) {
        super(PacketListenerPriority.LOW);
        this.sessions = sessions;
        this.configManager = configManager;
    }

    @SuppressWarnings("IfCanBeSwitch")
    @Override
    public void onPacketSend(@NonNull PacketSendEvent event) {
        if (!configManager.protectionInventoryEnabled()) return;

        UUID receiverId = event.getUser().getUUID();

        if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
            handleWindowItems(event, receiverId);
        } else if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
            handleSetSlot(event, receiverId);
        } else if (event.getPacketType() == PacketType.Play.Server.ENTITY_EQUIPMENT) {
            handleEntityEquipment(event, receiverId);
        }
    }

    /**
     * 拦截玩家背包同步（windowId=0）：
     * 未登录玩家本人收到时清空所有槽位，使其无法看到自己的背包内容。
     */
    private void handleWindowItems(PacketSendEvent event, UUID receiverId) {
        WrapperPlayServerWindowItems wrapper = new WrapperPlayServerWindowItems(event);
        if (wrapper.getWindowId() != 0) return;
        if (sessions.isLoggedIn(receiverId)) return;

        List<ItemStack> original = wrapper.getItems();
        if (original.isEmpty()) return;

        List<ItemStack> empty = new ArrayList<>(original.size());
        for (int i = 0; i < original.size(); i++) {
            empty.add(ItemStack.EMPTY);
        }
        wrapper.setItems(empty);
        wrapper.setCarriedItem(ItemStack.EMPTY);
        event.markForReEncode(true);
    }

    /**
     * 拦截单槽位更新（windowId=0）：
     * 未登录玩家本人收到时把该槽位替换为 EMPTY。
     * windowId=-1/-2 为光标携带物品，也一并清空。
     */
    private void handleSetSlot(PacketSendEvent event, UUID receiverId) {
        WrapperPlayServerSetSlot wrapper = new WrapperPlayServerSetSlot(event);
        int windowId = wrapper.getWindowId();
        // windowId=0 玩家背包；-1/-2 光标携带物品，都可能泄露物品信息
        if (windowId != 0 && windowId != -1 && windowId != -2) return;
        if (sessions.isLoggedIn(receiverId)) return;

        wrapper.setItem(ItemStack.EMPTY);
        event.markForReEncode(true);
    }

    /**
     * 拦截实体装备同步：
     * 发送给未登录玩家本人、且目标是自己时，清空装备槽为 EMPTY。
     * 其他玩家可以看到未登录玩家的真实装备（不影响游戏体验）。
     */
    private void handleEntityEquipment(PacketSendEvent event, UUID receiverId) {
        WrapperPlayServerEntityEquipment wrapper = new WrapperPlayServerEntityEquipment(event);
        int entityId = wrapper.getEntityId();

        // 获取接收者玩家，检查数据包目标是否为自己（无需遍历全服玩家）
        Player receiver = Bukkit.getPlayer(receiverId);
        if (receiver == null) return;
        if (receiver.getEntityId() != entityId) return;
        if (sessions.isLoggedIn(receiverId)) return;

        List<Equipment> original = wrapper.getEquipment();
        if (original.isEmpty()) return;

        List<Equipment> empty = new ArrayList<>(original.size());
        for (Equipment eq : original) {
            empty.add(new Equipment(eq.getSlot(), ItemStack.EMPTY));
        }
        wrapper.setEquipment(empty);
        event.markForReEncode(true);
    }
}
