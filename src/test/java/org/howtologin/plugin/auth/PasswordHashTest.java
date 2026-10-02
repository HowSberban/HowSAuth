package org.howtologin.plugin.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link PasswordHash} 单元测试：验证 BCrypt 与 SHA-256（旧格式）两种算法
 * 的哈希与校验行为、算法名大小写不敏感、加盐随机性、格式识别与异常健壮性。
 * <p>
 * 为保持测试快速，BCrypt 一律使用 cost 4（最低合法 work factor），
 * 不使用 cost 10 以上，也不 sleep。
 */
class PasswordHashTest {

    /** BCrypt work factor：取最低合法值以保持测试快速 */
    private static final int BCRYPT_COST = 4;

    /** SHA-256 哈希格式：saltHex(32 位):hashHex(64 位) */
    private static final String SHA256_PATTERN = "^[0-9a-f]{32}:[0-9a-f]{64}$";

    /** 用于“与正确密码完全不同”的对照密码（原为中文占位值，语义等价） */
    private static final String UNRELATED_PASSWORD = "totally-different";

    /** 用于“错误密码”的对照密码（原为中文占位值，语义等价） */
    private static final String WRONG_PASSWORD = "wrong-password";

    // ==================== 1. BCrypt ====================

    // 哈希应带标识且能校验正确密码
    @Test
    void bcryptHashHasIdentifierAndVerifiesCorrectPassword() {
        String hash = PasswordHash.hashPassword("S3cret!", "bcrypt", BCRYPT_COST);

        assertNotNull(hash, "the BCrypt hash must not be null");
        assertTrue(hash.startsWith("$2"), "the BCrypt hash must start with $2, actual=" + hash);
        assertTrue(PasswordHash.isBcrypt(hash), "isBcrypt must be true for a BCrypt hash");
        assertTrue(PasswordHash.checkPassword("S3cret!", hash), "the correct password must verify");
        assertFalse(PasswordHash.checkPassword("S3cret", hash), "a wrong password missing one character must not verify");
        assertFalse(PasswordHash.checkPassword("S3cret!!", hash), "a wrong password with one extra character must not verify");
        assertFalse(PasswordHash.checkPassword("s3cret!", hash), "a password differing only in case must not verify");
        assertFalse(PasswordHash.checkPassword(UNRELATED_PASSWORD, hash), "a completely different password must not verify");
    }

    // 哈希应包含指定 work factor
    @Test
    void bcryptHashEmbedsRequestedWorkFactor() {
        String hash = PasswordHash.hashPassword("S3cret!", "bcrypt", BCRYPT_COST);

        assertTrue(hash.contains("$04$"),
                "a hash with cost 4 must contain $04$ to prove the work factor took effect, actual=" + hash);
    }

    // ==================== 2. 算法名大小写不敏感 ====================

    // 算法名大小写不敏感
    @Test
    void hashPasswordTreatsAlgorithmNameCaseInsensitively() {
        String upper = PasswordHash.hashPassword("S3cret!", "BCrypt", BCRYPT_COST);
        assertTrue(upper.startsWith("$2"), "\"BCrypt\" must produce a BCrypt hash, actual=" + upper);
        assertTrue(PasswordHash.isBcrypt(upper), "a hash produced from \"BCrypt\" must be recognized as BCrypt");
        assertTrue(PasswordHash.checkPassword("S3cret!", upper), "a \"BCrypt\" hash must verify the correct password");
        assertFalse(PasswordHash.checkPassword(WRONG_PASSWORD, upper), "a \"BCrypt\" hash must not verify a wrong password");

        String mixed = PasswordHash.hashPassword("S3cret!", "Bcrypt", BCRYPT_COST);
        assertTrue(mixed.startsWith("$2"), "\"Bcrypt\" must produce a BCrypt hash, actual=" + mixed);
        assertTrue(PasswordHash.isBcrypt(mixed), "a hash produced from \"Bcrypt\" must be recognized as BCrypt");
        assertTrue(PasswordHash.checkPassword("S3cret!", mixed), "a \"Bcrypt\" hash must verify the correct password");
    }

    // ==================== 3. 加盐随机性 ====================

    // 同一密码两次 BCrypt 哈希应不同且都可校验
    @Test
    void bcryptHashesOfSamePasswordDifferAndBothVerify() {
        String first = PasswordHash.hashPassword("S3cret!", "bcrypt", BCRYPT_COST);
        String second = PasswordHash.hashPassword("S3cret!", "bcrypt", BCRYPT_COST);

        assertNotEquals(first, second, "BCrypt uses a random salt, so two hashes of the same password must differ");
        assertTrue(PasswordHash.checkPassword("S3cret!", first), "the first hash must verify");
        assertTrue(PasswordHash.checkPassword("S3cret!", second), "the second hash must verify");
    }

    // 同一密码两次 SHA-256 哈希应不同且都可校验
    @Test
    void sha256HashesOfSamePasswordDifferAndBothVerify() {
        String first = PasswordHash.hashPassword("S3cret!", "sha256", BCRYPT_COST);
        String second = PasswordHash.hashPassword("S3cret!", "sha256", BCRYPT_COST);

        assertNotEquals(first, second, "SHA-256 uses a random salt, so two hashes of the same password must differ");
        assertTrue(PasswordHash.checkPassword("S3cret!", first), "the first SHA-256 hash must verify");
        assertTrue(PasswordHash.checkPassword("S3cret!", second), "the second SHA-256 hash must verify");
    }

    // ==================== 4. SHA-256 旧格式 ====================

    // 哈希应符合 saltHex:hashHex 格式
    @Test
    void sha256HashMatchesSaltHexColonHashHexFormat() {
        String hash = PasswordHash.hashPassword("S3cret!", "sha256", BCRYPT_COST);

        assertNotNull(hash, "the SHA-256 hash must not be null");
        assertTrue(hash.matches(SHA256_PATTERN),
                "the SHA-256 hash must match ^[0-9a-f]{32}:[0-9a-f]{64}$, actual=" + hash);
        assertFalse(PasswordHash.isBcrypt(hash), "isBcrypt must be false for a SHA-256 hash");
        assertTrue(PasswordHash.checkPassword("S3cret!", hash), "the correct password must verify");
        assertFalse(PasswordHash.checkPassword(WRONG_PASSWORD, hash), "a wrong password must not verify");
    }

    // 未识别的算法名应按 SHA-256 处理
    @Test
    void hashPasswordFallsBackToSha256ForUnknownAlgorithm() {
        String hash = PasswordHash.hashPassword("S3cret!", "md5", BCRYPT_COST);

        assertTrue(hash.matches(SHA256_PATTERN),
                "\"md5\" is unrecognized and must be handled as SHA-256, consistent with the implementation, actual=" + hash);
        assertFalse(PasswordHash.isBcrypt(hash), "\"md5\" falls back to SHA-256 and must not be recognized as BCrypt");
        assertTrue(PasswordHash.checkPassword("S3cret!", hash), "a hash in the fallback format must verify");
        assertFalse(PasswordHash.checkPassword(WRONG_PASSWORD, hash), "a hash in the fallback format must not verify a wrong password");
    }

    // ==================== 5. 健壮性 ====================

    // 非法存储哈希应返回 false 而不抛异常
    @Test
    void checkPasswordReturnsFalseForMalformedStoredHash() {
        String password = "S3cret!";

        assertFalse(PasswordHash.checkPassword(password, null), "a null stored hash must return false");
        assertFalse(PasswordHash.checkPassword(password, ""), "an empty stored hash must return false");
        assertFalse(PasswordHash.checkPassword(password, "garbage"), "a garbage string must return false");
        assertFalse(PasswordHash.checkPassword(password, "abc"), "a wrongly delimited format without a colon must return false");
        assertFalse(PasswordHash.checkPassword(password, "zz:zz"), "a non-hexadecimal format must return false");
        assertFalse(PasswordHash.checkPassword(password, ":"), "an empty format with only a colon must return false");
        assertFalse(PasswordHash.checkPassword(password, "$2a$04$"), "a string with only the BCrypt prefix must return false");
        assertFalse(PasswordHash.checkPassword(password, "$2"), "a string with only $2 must return false");
    }

    // 被截断的 BCrypt 哈希应返回 false
    @Test
    void checkPasswordRejectsTruncatedBcryptHash() {
        String password = "S3cret!";
        String hash = PasswordHash.hashPassword(password, "bcrypt", BCRYPT_COST);

        assertTrue(PasswordHash.checkPassword(password, hash), "a complete BCrypt hash must verify");

        // 截掉尾部字符：盐/哈希不完整，既不能匹配正确密码，也不能抛异常
        String truncated = hash.substring(0, 10);
        assertFalse(PasswordHash.checkPassword(password, truncated),
                "a truncated BCrypt hash must return false, actual truncated value=" + truncated);
    }

    // 被篡改的 SHA-256 哈希应返回 false
    @Test
    void checkPasswordRejectsTamperedSha256Hash() {
        String password = "S3cret!";
        String hash = PasswordHash.hashPassword(password, "sha256", BCRYPT_COST);
        assertTrue(PasswordHash.checkPassword(password, hash), "the original SHA-256 hash must verify");

        // 翻转哈希部分的最后一位十六进制字符（'0'->'1'，其余->'0'）
        int lastIndex = hash.length() - 1;
        char original = hash.charAt(lastIndex);
        char tampered = original == '0' ? '1' : '0';
        String tamperedHash = hash.substring(0, lastIndex) + tampered;

        assertNotEquals(hash, tamperedHash, "the tampered hash must differ from the original hash");
        assertFalse(PasswordHash.checkPassword(password, tamperedHash),
                "a tampered SHA-256 hash must return false, actual tampered value=" + tamperedHash);
    }

    // ==================== 6. isBcrypt ====================

    // 仅识别 $2 开头
    @Test
    void isBcryptRecognizesOnlyDollarTwoPrefix() {
        assertFalse(PasswordHash.isBcrypt(absentHash()), "null must return false");
        assertFalse(PasswordHash.isBcrypt(""), "an empty string must return false");
        assertTrue(PasswordHash.isBcrypt("$2a$04$abcdefghijklmnopqrstuv"), "a string starting with $2a$ must return true");
        assertTrue(PasswordHash.isBcrypt("$2y$10$abcdefghijklmnopqrstuv"), "a string starting with $2y$ must return true");
        assertFalse(PasswordHash.isBcrypt("aa:bb"), "a string in SHA-256 format must return false");
        assertFalse(PasswordHash.isBcrypt("garbage"), "a garbage string must return false");
        assertFalse(PasswordHash.isBcrypt("$1$abc"), "a hash identifier not starting with $2 must return false");
    }

    /** 返回 null 的哨兵值：提供 null 而不被折叠成常量条件 */
    @SuppressWarnings("SameReturnValue")
    private static String absentHash() {
        return null;
    }
}
