package org.howsauth.plugin.premium;

import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerDisconnect;
import io.netty.channel.Channel;
import net.kyori.adventure.text.Component;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.I18n;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;

/**
 * 登录阶段的"帧级"工具：与会话状态无关的收发与计算——提取 IP、生成 RSA 密钥对、
 * 计算 Mojang 会话哈希、发送 Disconnect、踢出连接。
 */
final class LoginFrames {

    private LoginFrames() {
    }

    /** 从 channel 提取玩家真实 IP。
     *  服务器开启 proxies.proxy-protocol（frp/nginx 内网穿透）时 channel.remoteAddress 是隧道入口地址，
     *  真实 IP 存于 NMS Connection，优先反射读取其 getRemoteAddress()（与 Bukkit 各事件返回的地址一致）；
     *  未开启或反射失败时回退 channel.remoteAddress。 */
    static String extractIp(Channel channel) {
        Object connection = channel.pipeline().get("packet_handler");
        if (connection != null) {
            try {
                Object remote = connection.getClass().getMethod("getRemoteAddress").invoke(connection);
                if (remote instanceof InetSocketAddress isa && isa.getAddress() != null) {
                    return isa.getAddress().getHostAddress();
                }
            } catch (ReflectiveOperationException ignored) {
                // 该 NMS 版本无此方法，回退 channel.remoteAddress
            }
        }
        if (channel.remoteAddress() instanceof InetSocketAddress addr) {
            return addr.getAddress().getHostAddress();
        }
        return null;
    }

    /**
     * 计算 Mojang 服务器哈希：十六进制( SHA-1( serverId("") + sharedSecret + publicKey ) )
     * 使用 new BigInteger(digest)（不带 signum=1），与 vanilla Minecraft 一致：
     * 若 digest 首字节 >= 0x80，结果为负数（如 "-abc123..."），客户端也用相同方式计算。
     */
    static String computeServerHash(byte[] sharedSecret, byte[] publicKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            // serverId 为空字符串，update 空数组是 no-op，直接跳过
            sha1.update(sharedSecret);
            sha1.update(publicKey);
            byte[] digest = sha1.digest();
            return new BigInteger(digest).toString(16);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute server hash", e);
        }
    }

    /** 生成 RSA 2048 密钥对（启动时一次，所有连接复用） */
    static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException(I18n.get("log.premium_keypair_failed"), e);
        }
    }

    /** 经已加密通道发送 Disconnect 包（直接发送 Component，保留其他插件设置的消息样式） */
    static void sendDisconnect(User user, Component message) {
        WrapperLoginServerDisconnect disconnect = new WrapperLoginServerDisconnect(message);
        user.sendPacket(disconnect);
    }

    /**
     * 踢出连接：发送 Disconnect 后立即关闭 channel，不让已被拒绝的连接继续存活。
     * <p>
     * 包在 {@code user.sendPacket(...)} 内即已 writeAndFlush 写出，且本插件未设置
     * {@code SO_LINGER}，关闭是优雅关闭——内核会保留发送缓冲区的数据并负责重传，
     * 因此立刻 close 不会丢失踢出理由。
     * <p>
     * 会话清理由调用方负责（会话注册表在 ConnectionHandler 手中）。
     */
    static void kick(Channel channel, User user, Component message) {
        if (Debug.on()) {
            Debug.log("premium", "kick %s: disconnect sent, closing channel", user.getName());
        }
        sendDisconnect(user, message);
        channel.close();
    }
}
