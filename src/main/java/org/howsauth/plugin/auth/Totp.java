package org.howsauth.plugin.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.function.LongSupplier;

/**
 * TOTP 双因素认证工具（RFC 6238）。
 * <p>
 * 算法参数：HMAC-SHA1、30 秒周期、6 位验证码，
 * 与 Google Authenticator / Authy 等主流认证器兼容。
 * 密钥使用 RFC 4648 Base32 编码存储。
 */
public final class Totp {

    private static final int PERIOD_SECONDS = 30;
    private static final int CODE_DIGITS = 6;
    private static final int CODE_MODULUS = 1_000_000;
    // RFC 4648 Base32 字母表
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    // 验证时允许的时间窗口偏移（±1 个周期，容忍客户端/服务端时钟偏差）
    private static final int WINDOW = 1;
    // 密钥生成随机源（线程安全，复用避免重复初始化开销）
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    // 时间源：默认系统时钟，测试可注入
    private static volatile LongSupplier timeSource = System::currentTimeMillis;

    private Totp() {}

    /** 当前时间戳（毫秒）：验证路径统一经此取时，便于测试注入时钟 */
    public static long nowMillis() {
        return timeSource.getAsLong();
    }

    /** 注入时间源（仅供测试使用）：传 null 恢复系统时钟 */
    public static void setTimeSource(LongSupplier source) {
        timeSource = source != null ? source : System::currentTimeMillis;
    }

    /**
     * 生成指定时刻的验证码（供测试与排查使用；正式登录/绑定路径不应调用）。
     * @param base32Secret Base32 编码的密钥，非法或为空时返回空串
     * @param epochSeconds 生成时刻（epoch 秒）
     */
    public static String generateCodeAt(String base32Secret, long epochSeconds) {
        if (base32Secret == null) return "";
        byte[] key = decodeBase32(base32Secret);
        if (key.length == 0) return "";
        return generateCode(key, epochSeconds);
    }

    /** 生成随机 TOTP 密钥（20 字节，Base32 编码） */
    public static String generateSecret() {
        byte[] bytes = new byte[20];
        SECURE_RANDOM.nextBytes(bytes);
        return encodeBase32(bytes);
    }

    /**
     * 验证 6 位验证码是否匹配密钥（允许 ±1 个时间窗口的时钟偏差）。
     * @param base32Secret Base32 编码的密钥
     * @param code         用户输入的 6 位数字验证码
     */
    // 保持正向命名：调用方均以 ! 守卫子句使用，反转逻辑会与方法名语义相反
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean verifyCode(String base32Secret, String code) {
        return matchCounter(base32Secret, code) != null;
    }

    /**
     * 验证验证码并返回其对应的时间片计数器（当前时间 ±1 周期窗口内首个匹配项）。
     * 供登录路径使用：RFC 6238 验证是无状态的，同一验证码在窗口内可重复匹配，
     * 调用方记录已消费的 counter 并拒绝同周期或更旧的码即可实现防重放。
     * @return 匹配的 counter（epoch 起的 30 秒周期数），验证失败返回 null
     */
    public static Long matchCounter(String base32Secret, String code) {
        if (base32Secret == null || code == null || !code.matches("\\d{" + CODE_DIGITS + "}")) {
            return null;
        }
        byte[] key = decodeBase32(base32Secret);
        if (key.length == 0) return null;
        long now = nowMillis() / 1000;
        for (int offset = -WINDOW; offset <= WINDOW; offset++) {
            // 与 generateCode 的取整口径一致：counter = timeSeconds / PERIOD_SECONDS
            long timeSeconds = now + (long) offset * PERIOD_SECONDS;
            if (generateCode(key, timeSeconds).equals(code)) {
                return timeSeconds / PERIOD_SECONDS;
            }
        }
        return null;
    }

    /** 按 RFC 6238 生成指定时刻的 6 位验证码 */
    private static String generateCode(byte[] key, long timeSeconds) {
        long counter = timeSeconds / PERIOD_SECONDS;
        // 计数器转 8 字节大端序
        byte[] msg = new byte[8];
        for (int i = 7; i >= 0; i--) {
            msg[i] = (byte) (counter & 0xFF);
            counter >>= 8;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(msg);
            // 动态截断（RFC 4226 §5.4）
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8)
                    | (hash[offset + 3] & 0xFF);
            return String.format("%0" + CODE_DIGITS + "d", binary % CODE_MODULUS);
        } catch (Exception e) {
            return "";
        }
    }

    /** RFC 4648 Base32 编码（不含填充） */
    private static String encodeBase32(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                sb.append(BASE32_ALPHABET.charAt((buffer >> bits) & 0x1F));
            }
        }
        if (bits > 0) {
            sb.append(BASE32_ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    /** RFC 4648 Base32 解码（忽略非法字符与填充） */
    private static byte[] decodeBase32(String encoded) {
        String cleaned = encoded.toUpperCase().replaceAll("[^A-Z2-7]", "");
        if (cleaned.isEmpty()) return new byte[0];
        byte[] result = new byte[cleaned.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int idx = 0;
        for (char c : cleaned.toCharArray()) {
            int val = BASE32_ALPHABET.indexOf(c);
            buffer = (buffer << 5) | val;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                if (idx < result.length) {
                    result[idx++] = (byte) ((buffer >> bits) & 0xFF);
                }
            }
        }
        return result;
    }
}
