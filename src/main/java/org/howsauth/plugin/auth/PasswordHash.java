package org.howsauth.plugin.auth;

import org.mindrot.jbcrypt.BCrypt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 密码哈希工具：支持 BCrypt 与 SHA-256 两种算法并存。
 * <p>
 * 存储格式：
 *   - BCrypt：  $2a$10$...（BCrypt 标准格式，以 $2 开头）
 *   - SHA-256： saltHex:hashHex（旧格式，salt 与 hash 用冒号分隔的十六进制）
 * <p>
 * checkPassword 会自动识别存储格式并按对应算法验证。
 * hashPassword 根据传入的 algorithm 参数决定新密码使用哪种算法。
 * <p>
 * 推荐使用 BCrypt（自带盐值、可调 work factor、抗 GPU 暴力破解）。
 * SHA-256 仅作为兼容旧数据的回退方案，登录成功后应通过 AuthManager 自动升级为 BCrypt。
 */
public final class PasswordHash {

    private static final int SALT_LENGTH = 16;
    private static final String SHA256_ALGORITHM = "SHA-256";

    /**
     * 使用指定算法和 work factor 哈希密码。
     * @param password   明文密码
     * @param algorithm  "bcrypt" 或 "sha256"（不区分大小写）
     * @param bcryptCost BCrypt work factor（4-31），仅 algorithm 为 bcrypt 时有效
     * @return 哈希字符串
     */
    public static String hashPassword(String password, String algorithm, int bcryptCost) {
        if ("bcrypt".equalsIgnoreCase(algorithm)) {
            return BCrypt.hashpw(password, BCrypt.gensalt(bcryptCost));
        }
        return hashSha256(password);
    }

    /** 使用 SHA-256 + 随机盐值哈希（旧格式，仅用于兼容） */
    private static String hashSha256(String password) {
        try {
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[SALT_LENGTH];
            random.nextBytes(salt);

            MessageDigest digest = MessageDigest.getInstance(SHA256_ALGORITHM);
            digest.update(salt);
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(salt) + ":" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * 验证明文密码是否匹配存储的哈希。
     * 自动识别存储格式：以 $2 开头为 BCrypt，否则视为 SHA-256。
     */
    public static boolean checkPassword(String password, String storedHash) {
        if (storedHash == null || storedHash.isEmpty()) return false;

        try {
            if (storedHash.startsWith("$2")) {
                // BCrypt 格式
                return BCrypt.checkpw(password, storedHash);
            }
            // SHA-256 格式：saltHex:hashHex
            return checkSha256(password, storedHash);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean checkSha256(String password, String storedHash) {
        try {
            String[] parts = storedHash.split(":");
            if (parts.length != 2) return false;

            byte[] salt = HexFormat.of().parseHex(parts[0]);
            byte[] expectedHash = HexFormat.of().parseHex(parts[1]);

            MessageDigest digest = MessageDigest.getInstance(SHA256_ALGORITHM);
            digest.update(salt);
            byte[] actualHash = digest.digest(password.getBytes(StandardCharsets.UTF_8));

            return MessageDigest.isEqual(actualHash, expectedHash);
        } catch (Exception e) {
            return false;
        }
    }

    /** 判断存储的哈希是否为 BCrypt 格式（用于自动升级判断） */
    public static boolean isBcrypt(String storedHash) {
        return storedHash != null && storedHash.startsWith("$2");
    }

    private PasswordHash() {}
}
