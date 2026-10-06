package com.github.retrooper.packetevents.event;

import com.github.retrooper.packetevents.PacketEventsTestSupport;
import com.github.retrooper.packetevents.PacketEventsTestSupport.RecordingProtocolManager;
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
 * 登录阶段事件仿真脚手架（测试专用）。
 * <p>
 * 让 login 阶段的握手逻辑可测，而无需真实服务端：
 * <ul>
 *   <li><b>Channel</b>：真实 {@link EmbeddedChannel}——自带完整 pipeline（断开检测器的 install/remove 可用）
 *       与内联执行的 EmbeddedEventLoop，故 {@code eventLoop().execute(...)} 立即跑完，
 *       不需要等待，也就不会引入时序漂移。</li>
 *   <li><b>User</b>：真实 {@code User}。它能被构造的前提是先装好
 *       {@link PacketEventsTestSupport}（否则 {@code getAPI()} 为 null 会在静态初始化处炸掉）。</li>
 *   <li><b>出站包</b>：由 {@link RecordingProtocolManager} 记录，测试可断言服务端到底发了什么。</li>
 * </ul>
 * 生产代码不受影响：本类只存在于测试源集。
 * <p>
 * <b>已知限制（未解决）</b>：直接构造 LoginStart 包体交给生产代码里的
 * {@code WrapperLoginClientLoginStart} 解析会失败——它会报
 * {@code "The received string length is longer than maximum allowed (11 > 16)"}（该消息的两个数字含义与字面相反，
 * 实际是"长度 11 超过上限 16"）。已确认包体本身正确（VarInt 长度前缀 + UTF-8 用户名，首字节 0x0B、次字节 'O'），
 * 且与 readerIndex 被推进无关（每次解析前都归零仍失败）。
 * 因此**凡是要走到 LoginStart 包体解析的用例目前都无法覆盖**：
 * 只能测"解析之前"或"解析之后不经包体"的分支（见 ConnectionHandlerLoginStartTest 及 ConnectionHandlerHashTest）。
 * 继续排查需要读 packetevents 的 readString 实现与其版本相关的上限判定。
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
