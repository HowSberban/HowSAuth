package com.github.retrooper.packetevents.event;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;

/**
 * 测试专用的 PacketReceiveEvent 构造入口。
 * <p>
 * PacketReceiveEvent 的构造函数是 protected，外部包无法直接 new。Java 允许"拆分包"
 * （同一包名分布在测试源集里），故把本子类放在与其相同的包下，从而能在测试中构造事件。
 * <p>
 * 生产代码不会被本类影响：它只存在于测试源集。
 */
public final class TestPacketReceiveEvent extends PacketReceiveEvent {

    public TestPacketReceiveEvent(Object channel, User user, PacketTypeCommon packetType, Object byteBuf) {
        // 纯读取型构造：只用到 channel/user/packetType/byteBuf；packetID 由 packetType 推导，
        // serverVersion 给真实值以免解密路径拿到 null。player 参数本测试不触及。
        super(0, packetType, ServerVersion.V_1_21_6, channel, user, null, byteBuf);
    }
}