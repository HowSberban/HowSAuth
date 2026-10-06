package org.howsauth.plugin.premium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.security.MessageDigest;

/**
 * ConnectionHandler 中纯计算部分的回归。
 * <p>
 * 该类的握手状态机（handleLoginStart / handleEncryptionResponse）由 PacketEvents 事件驱动、
 * 需要 Netty pipeline 才能构造事件，因此本测试只覆盖其中无副作用、却最易在重构中被静默改错的一段：
 * Mojang 会话哈希。
 * <p>
 * 会话哈希错一位，正版玩家就会全部验证失败（被当作非正版），而失败表现只是“玩家进不去”，
 * 既没有编译错误也没有断言提示——所以必须用固定向量钉住它。
 */
class ConnectionHandlerHashTest {

    /** 反射调用私有静态方法：只做输入/输出断言，不触碰实例状态 */
    private static String computeServerHash(byte[] sharedSecret, byte[] publicKey) throws Exception {
        Method m = LoginFrames.class.getDeclaredMethod("computeServerHash", byte[].class, byte[].class);
        m.setAccessible(true);
        return (String) m.invoke(null, sharedSecret, publicKey);
    }

    /**
     * 与 vanilla 一致的参考实现：hex(SHA1(serverId + sharedSecret + publicKey))，
     * serverId 为空串，且用不带 signum 的 BigInteger——首字节 >= 0x80 时结果带负号。
     */
    private static String referenceHash(byte[] sharedSecret, byte[] publicKey) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(sharedSecret);
        sha1.update(publicKey);
        return new BigInteger(sha1.digest()).toString(16);
    }

    /** 固定向量：输出必须与参考实现逐字一致（含可能的负号） */
    @Test
    void matchesTheVanillaAlgorithmForAFixedVector() throws Exception {
        byte[] sharedSecret = new byte[]{0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10};
        byte[] publicKey = new byte[]{0x30, 0x7f, 0x21, 0x55, (byte) 0xaa, (byte) 0xff};

        assertEquals(referenceHash(sharedSecret, publicKey), computeServerHash(sharedSecret, publicKey),
                "the session hash must match the vanilla algorithm exactly");
    }

    /**
     * 关键怪癖：digest 首字节 >= 0x80 时结果带负号。
     * 若有人把实现“修正”为无符号十六进制，本用例会失败——而那正是会让正版验证全线失效的改动。
     */
    @Test
    void keepsTheUnsignedDigestSignQuirk() throws Exception {
        byte[] publicKey = new byte[0];
        byte[] sharedSecret = null;
        for (int i = 0; i < 512; i++) {
            byte[] candidate = new byte[]{(byte) i};
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(candidate);
            if ((sha1.digest()[0] & 0x80) != 0) {
                sharedSecret = candidate;
                break;
            }
        }
        assertNotNull(sharedSecret,
                "the vector search must find a digest whose first byte has the high bit set");

        String hash = computeServerHash(sharedSecret, publicKey);
        assertTrue(hash.startsWith("-"),
                "a digest starting with a byte >= 0x80 must produce a negative hash, got: " + hash);
    }

    /** 空输入稳定输出，不得抛异常 */
    @Test
    void handlesEmptyInputs() throws Exception {
        String hash = computeServerHash(new byte[0], new byte[0]);
        assertEquals(new BigInteger(MessageDigest.getInstance("SHA-1").digest()).toString(16), hash,
                "empty inputs must hash the empty digest");
    }

    /** 不同输入给出不同哈希（防止实现退化成常量） */
    @Test
    void differentInputsProduceDifferentHashes() throws Exception {
        String a = computeServerHash(new byte[]{1}, new byte[]{2});
        String b = computeServerHash(new byte[]{2}, new byte[]{1});
        assertNotEquals(a, b, "different inputs must not collide");
    }

    /** 同一输入重复计算结果一致 */
    @Test
    void isDeterministic() throws Exception {
        byte[] secret = new byte[]{9, 8, 7};
        byte[] key = new byte[]{6, 5, 4};
        String first = computeServerHash(secret, key);
        for (int i = 0; i < 20; i++) {
            assertEquals(first, computeServerHash(secret, key), "the hash must be deterministic");
        }
    }
}