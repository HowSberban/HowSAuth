package com.github.retrooper.packetevents.event;

import com.github.retrooper.packetevents.PacketEventsTestSupport;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * 登录阶段事件仿真脚手架（测试专用）：用真实 {@code EmbeddedChannel}（真 pipeline + 内联 EventLoop）
 * 与真实 {@code User} 驱动握手代码，出站包记入 {@link RecordingProtocolManager} 供断言。
 * <p>
 * 真实 {@code User} 的构造前提是先装好 {@link PacketEventsTestSupport}。
 * <p>
 * 限制：需要经生产代码的 wrapper 解析包体的分支（如 LoginStart 的用户名）当前无法覆盖，
 * 只测解析之前或不经包体的分支。
 */
public final class LoginEventScaffold {

    /** 一次握手用的假客户端：同一 Channel/User 贯穿 LoginStart 与 EncryptionResponse */
    public static final class FakeClient {
        private final EmbeddedChannel channel = new EmbeddedChannel();
        private final User user;

        private FakeClient(String username, UUID uuid) {
            // install() 安装 PacketEvents API 替身，是构造真实 User 的前提；其返回的记录器此处不使用
            PacketEventsTestSupport.install();
            this.user = new User(channel, ConnectionState.LOGIN, ClientVersion.V_1_21_5,
                    new UserProfile(uuid, username));
        }

        public EmbeddedChannel channel() {
            return channel;
        }

        public User user() {
            return user;
        }
    }

    private LoginEventScaffold() {
    }

    public static FakeClient newClient(String username) {
        return new FakeClient(username, UUID.randomUUID());
    }

    /** 构造 EncryptionResponse 事件；包体留空（测试只覆盖无需解密的早退分支） */
    public static PacketReceiveEvent encryptionResponse(FakeClient client) throws Exception {
        return rawPacket(client, PacketType.Login.Client.ENCRYPTION_RESPONSE, Unpooled.buffer());
    }

    /** 用给定包体构造一个收包事件；readerIndex 归零，保证从包首开始解析 */
    public static PacketReceiveEvent rawPacket(FakeClient client,
                                               com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon type,
                                               ByteBuf body) throws Exception {
        PacketReceiveEvent event = new TestPacketReceiveEvent(client.channel(), client.user(), type, Unpooled.buffer());
        event.setByteBuf(body.readerIndex(0));
        return event;
    }

    /**
     * 反射调用任意对象的私有方法（测试用）。
     * 不返回被调用方法的返回值——调用方只关心副作用；异常原样抛出，避免掩盖真实原因。
     */
    public static void invokePrivate(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method m = target.getClass().getDeclaredMethod(name, types);
            m.setAccessible(true);
            m.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException("invoked " + name + " threw checked exception", cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot invoke " + name, e);
        }
    }

    /**
     * 反射读取任意对象的私有字段（测试用）。
     * 带类型参数，调用方无需再写强制转换（也就无需 {@code @SuppressWarnings("unchecked")}）。
     */
    @SuppressWarnings("unchecked")
    public static <T> T readField(Object target, String name) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return (T) f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read field " + name, e);
        }
    }
}
