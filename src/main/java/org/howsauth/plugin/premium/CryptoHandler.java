package org.howsauth.plugin.premium;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.List;

/**
 * AES-CFB8 加密处理器（模块1 辅助）。
 * <p>
 * Netty 4.1.136 移除了 io.netty.handler.codec.crypto 包（CipherEncoder/CipherDecoder），
 * 此处自行实现等价处理器。
 * <p>
 * 安装位置（与原版 Minecraft Connection.setupEncryption 一致）：
 * - DecryptHandler: addBefore("splitter", ...) — 在帧解码器之前解密原始字节流
 * - EncryptHandler: addBefore("prepender", ...) — 在帧编码器之前加密输出字节流
 * <p>
 * 线程模型：pipeline 修改必须在 channel 的 eventLoop 中执行。
 */
public final class CryptoHandler {

    /** Netty pipeline 中帧解码器的处理器名（原版 Minecraft） */
    private static final String SPLITTER = "splitter";
    /** Netty pipeline 中帧编码器的处理器名（原版 Minecraft） */
    private static final String PREPENDER = "prepender";
    /** 解密处理器名 */
    private static final String DECRYPT_NAME = "hsauth_decrypt";
    /** 加密处理器名 */
    private static final String ENCRYPT_NAME = "hsauth_encrypt";

    private CryptoHandler() {}

    /**
     * 在连接上启用 AES-CFB8 双向加密。
     * 必须在持有 channel 的 eventLoop 线程中调用。
     *
     * @param channel      底层 Netty 通道
     * @param sharedSecret 共享密钥（已 RSA 解密）
     * @throws Exception 密钥初始化或 pipeline 安装失败
     */
    public static void enableEncryption(Channel channel, byte[] sharedSecret) throws Exception {
        SecretKeySpec keySpec = new SecretKeySpec(sharedSecret, "AES");
        // Minecraft 原版约定：共享密钥同时用作 key 和 IV（与 Connection.setupEncryption 一致）
        // Java 17+ 不再为 CFB8 自动生成 IV，必须显式指定，否则抛 "Parameters missing"
        IvParameterSpec ivSpec = new IvParameterSpec(sharedSecret);
        Cipher encryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
        encryptCipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
        Cipher decryptCipher = Cipher.getInstance("AES/CFB8/NoPadding");
        decryptCipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

        ChannelPipeline pipeline = channel.pipeline();

        // 安装解密处理器：在帧解码器之前解密原始字节流
        if (pipeline.get(SPLITTER) != null) {
            pipeline.addBefore(SPLITTER, DECRYPT_NAME, new DecryptHandler(decryptCipher));
        } else {
            // 兼容：找不到 splitter 时尝试遍历查找帧解码器
            String frameDecoder = findFrameDecoder(pipeline);
            if (frameDecoder != null) {
                pipeline.addBefore(frameDecoder, DECRYPT_NAME, new DecryptHandler(decryptCipher));
            } else {
                pipeline.addFirst(DECRYPT_NAME, new DecryptHandler(decryptCipher));
            }
        }

        // 安装加密处理器：在帧编码器之前加密输出字节流
        // outbound 流向为 tail→head，addBefore 使加密器位于 prepender 之前（即更靠近 head），
        // 实际执行顺序：encoder → prepender → encrypt → wire，整帧（含长度前缀）被加密
        if (pipeline.get(PREPENDER) != null) {
            pipeline.addBefore(PREPENDER, ENCRYPT_NAME, new EncryptHandler(encryptCipher));
        } else {
            String frameEncoder = findFrameEncoder(pipeline);
            if (frameEncoder != null) {
                pipeline.addBefore(frameEncoder, ENCRYPT_NAME, new EncryptHandler(encryptCipher));
            } else {
                pipeline.addLast(ENCRYPT_NAME, new EncryptHandler(encryptCipher));
            }
        }
    }

    /** 遍历 pipeline 查找帧解码器（类名含 FrameDecoder 或 splitter） */
    private static String findFrameDecoder(ChannelPipeline pipeline) {
        for (var entry : pipeline) {
            String name = entry.getKey();
            ChannelHandler handler = entry.getValue();
            if (handler instanceof ByteToMessageDecoder || name.toLowerCase().contains("split")
                    || name.toLowerCase().contains("frame_decoder")) {
                return name;
            }
        }
        return null;
    }

    /** 遍历 pipeline 查找帧编码器（类名含 FramePrepender 或 prepender） */
    private static String findFrameEncoder(ChannelPipeline pipeline) {
        for (var entry : pipeline) {
            String name = entry.getKey();
            ChannelHandler handler = entry.getValue();
            if (handler instanceof MessageToByteEncoder || name.toLowerCase().contains("prepend")
                    || name.toLowerCase().contains("frame_prepender")) {
                return name;
            }
        }
        return null;
    }

    /**
     * 出站加密处理器：将明文字节流加密后传递给下一站。
     * Cipher.update 用于流式加密，无需 doFinal（AES-CFB8 每字节独立）。
     */
    private static final class EncryptHandler extends MessageToByteEncoder<ByteBuf> {
        private final Cipher cipher;

        EncryptHandler(Cipher cipher) {
            this.cipher = cipher;
        }

        @Override
        protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
            byte[] data = new byte[msg.readableBytes()];
            msg.readBytes(data);
            byte[] encrypted = cipher.update(data);
            out.writeBytes(encrypted);
        }
    }

    /**
     * 入站解密处理器：将密文字节流解密后传递给帧解码器。
     */
    private static final class DecryptHandler extends ByteToMessageDecoder {
        private final Cipher cipher;

        DecryptHandler(Cipher cipher) {
            this.cipher = cipher;
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            byte[] data = new byte[in.readableBytes()];
            in.readBytes(data);
            byte[] decrypted = cipher.update(data);
            out.add(ctx.alloc().buffer(decrypted.length).writeBytes(decrypted));
        }
    }
}
