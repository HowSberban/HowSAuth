package com.github.retrooper.packetevents;

import com.github.retrooper.packetevents.event.EventManager;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.netty.buffer.ByteBufAllocationOperator;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * 让 PacketEvents 在纯 mock 环境下可用（测试专用）。
 * <p>
 * 痛点：{@code new User(channel, ...)} 的构造函数会依次用到
 * {@code PacketEvents.getAPI().getSettings()} 与 {@code getNettyManager().getByteBufAllocationOperator()}，
 * 而测试里从未初始化过 PacketEvents，于是 {@code getAPI()} 返回 null → 静态初始化失败。
 * <p>
 * 这里安装一个最小可用的 API 替身：设置返回真实 {@code PacketEventsSettings}；Netty 管理器返回
 * 委托给 netty {@code Unpooled} 的缓冲分配器；协议管理器返回代理并记录 {@code sendPacket}，
 * 供测试断言"服务端到底发出了什么包"。
 * <p>
 * 生产代码不受影响：本类只存在于测试源集，并且只在测试进程内改 PacketEvents 的静态实例。
 */
public final class PacketEventsTestSupport {

    /** 记录 sendPacket 的协议管理器代理 */
    public static final class RecordingProtocolManager implements InvocationHandler {
        private final List<Object> sentPackets = new ArrayList<>();

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            return switch (method.getName()) {
                // 参数：(channel, packet)
                case "sendPacket" -> {
                    if (args != null && args.length > 1) {
                        sentPackets.add(args[1]);
                    }
                    yield null;
                }
                case "toString" -> "RecordingProtocolManager";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                default -> defaultValue(method.getReturnType());
            };
        }

    }

    /** 缓冲分配器：直接委托 netty 的 Unpooled（packetevents 的读写都走此接口） */
    private static final class NettyProxies {
        static ByteBufAllocationOperator allocationOperator() {
            InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
                case "wrappedBuffer" -> Unpooled.wrappedBuffer((byte[]) args[0]);
                case "copiedBuffer" -> Unpooled.copiedBuffer((byte[]) args[0]);
                case "buffer" -> args == null || args.length == 0
                        ? Unpooled.buffer() : Unpooled.buffer((int) args[0]);
                case "directBuffer" -> args == null || args.length == 0
                        ? Unpooled.directBuffer() : Unpooled.directBuffer((int) args[0]);
                case "compositeBuffer" -> args == null || args.length == 0
                        ? Unpooled.compositeBuffer() : Unpooled.compositeBuffer((int) args[0]);
                case "emptyBuffer" -> Unpooled.EMPTY_BUFFER;
                case "toString" -> "TestByteBufAllocationOperator";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                default -> defaultValue(method.getReturnType());
            };
            return (ByteBufAllocationOperator) Proxy.newProxyInstance(
                    PacketEventsTestSupport.class.getClassLoader(),
                    new Class<?>[]{ByteBufAllocationOperator.class}, h);
        }

        static NettyManager manager() {
            ByteBufAllocationOperator alloc = allocationOperator();
            com.github.retrooper.packetevents.netty.buffer.ByteBufOperator bufOps = byteBufOperator();
            InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
                case "getByteBufAllocationOperator" -> alloc;
                case "getByteBufOperator" -> bufOps;
                case "toString" -> "TestNettyManager";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                default -> defaultValue(method.getReturnType());
            };
            return (NettyManager) Proxy.newProxyInstance(
                    PacketEventsTestSupport.class.getClassLoader(),
                    new Class<?>[]{NettyManager.class}, h);
        }

        /**
         * ByteBufOperator：把调用转发到 netty ByteBuf 的同名方法。
         * 单参数形态是取值（readerIndex(buf)），双参数形态是设值（readerIndex(buf, i)），
         * 与 packetevents 自身语义一致；找不到同名方法时按 get/set 前缀再试一次。
         */
        static com.github.retrooper.packetevents.netty.buffer.ByteBufOperator byteBufOperator() {
            InvocationHandler h = (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("toString")) {
                    return "TestByteBufOperator";
                }
                if (name.equals("hashCode")) {
                    return System.identityHashCode(proxy);
                }
                if (name.equals("equals")) {
                    return proxy == (args == null ? null : args[0]);
                }
                if (args == null || args.length == 0) {
                    return defaultValue(method.getReturnType());
                }
                Object buf = args[0];
                Object[] rest = new Object[args.length - 1];
                System.arraycopy(args, 1, rest, 0, rest.length);
                try {
                    return invokeOnByteBuf(name, buf, rest);
                } catch (Exception e) {
                    return defaultValue(method.getReturnType());
                }
            };
            return (com.github.retrooper.packetevents.netty.buffer.ByteBufOperator) Proxy.newProxyInstance(
                    PacketEventsTestSupport.class.getClassLoader(),
                    new Class<?>[]{com.github.retrooper.packetevents.netty.buffer.ByteBufOperator.class}, h);
        }

        private static Object invokeOnByteBuf(String name, Object buf, Object[] rest) throws Exception {
            Class<?>[] types = new Class<?>[rest.length];
            for (int i = 0; i < rest.length; i++) {
                Class<?> t = rest[i].getClass();
                types[i] = (t == Integer.class) ? int.class
                        : (t == Long.class) ? long.class
                        : (t == Boolean.class) ? boolean.class
                        : (t == Byte.class) ? byte.class
                        : (t == Short.class) ? short.class
                        : (t == Character.class) ? char.class
                        : t;
            }
            java.lang.reflect.Method m;
            try {
                m = ByteBuf.class.getMethod(name, types);
            } catch (NoSuchMethodException first) {
                String alt = rest.length == 0 ? "get" + capitalize(name) : "set" + capitalize(name);
                m = ByteBuf.class.getMethod(alt, types);
            }
            return m.invoke(buf, rest);
        }

        private static String capitalize(String s) {
            return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
    }

    /** 最小 PacketEventsAPI 替身：只实现 User 构造与 sendPacket 真正用到的方法 */
    private static final class StubApi extends PacketEventsAPI<Object> {
        private final PacketEventsSettings settings = new PacketEventsSettings();
        private final ProtocolManager protocolManager;

        StubApi(ProtocolManager protocolManager) {
            this.protocolManager = protocolManager;
        }

        @Override
        public boolean isLoaded() {
            return true;
        }

        @Override
        public void init() {
        }

        @Override
        public boolean isInitialized() {
            return true;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public Object getPlugin() {
            return null;
        }

        @Override
        public ServerManager getServerManager() {
            return null;
        }

        @Override
        public ProtocolManager getProtocolManager() {
            return protocolManager;
        }

        @Override
        public PlayerManager getPlayerManager() {
            return null;
        }

        @Override
        public NettyManager getNettyManager() {
            return NettyProxies.manager();
        }

        @Override
        public ChannelInjector getInjector() {
            return null;
        }

        @Override
        public PacketEventsSettings getSettings() {
            return settings;
        }

        @Override
        public EventManager getEventManager() {
            return new EventManager();
        }
    }

    private PacketEventsTestSupport() {
    }

    /**
     * 安装 API 替身并返回记录器。
     * 幂等：重复调用替换为新的记录器（每个测试用例独立记录）。
     */
    public static void install() {
        RecordingProtocolManager recorder = new RecordingProtocolManager();
        ProtocolManager protocolManager = (ProtocolManager) Proxy.newProxyInstance(
                PacketEventsTestSupport.class.getClassLoader(),
                new Class<?>[]{ProtocolManager.class}, recorder);
        PacketEvents.setAPI(new StubApi(protocolManager));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == void.class) return null;
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == char.class) return (char) 0;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        return 0;
    }
}
