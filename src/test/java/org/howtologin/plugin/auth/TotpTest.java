package org.howtologin.plugin.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.howtologin.plugin.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link Totp} 单元测试：验证 RFC 6238 算法正确性、时间源注入、
 * 验证窗口边界、非法输入健壮性与 Base32 宽松解析。
 * <p>
 * 断言使用 RFC 6238 附录 B 的官方测试向量（SHA1 行），密钥为 ASCII
 * "12345678901234567890"，对应 Base32 为 GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ。
 * 所有涉及时钟的用例均通过 {@link Totp#setTimeSource} 注入可控时钟，
 * 不依赖真实时间、不 sleep；{@code @AfterEach} 统一恢复系统时钟，
 * 避免静态状态污染其它测试类。
 */
class TotpTest {

    /** RFC 6238 测试密钥（ASCII "12345678901234567890" 的 Base32 编码） */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    /** 各用例共用的基准时刻（秒） */
    private static final long T = 1234567890L;

    /** 一个 TOTP 周期（秒） */
    private static final long PERIOD = 30L;

    /** Base32 字母表，用于校验 generateSecret 的字符集 */
    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    /** 每个测试后恢复系统时钟，防止注入的时间源泄漏到其它测试类 */
    @AfterEach
    void restoreSystemClock() {
        Totp.setTimeSource(null);
    }

    // ==================== 1. RFC 6238 官方测试向量 ====================

    // 应匹配 RFC 6238 官方测试向量：逐个校验附录 B 的 6 位期望值
    @Test
    void generateCodeAtMatchesRfc6238Vectors() {
        assertEquals("287082", Totp.generateCodeAt(RFC_SECRET, 59L), "6-digit code at T=59 must be 287082");
        assertEquals("081804", Totp.generateCodeAt(RFC_SECRET, 1111111109L), "6-digit code at T=1111111109 must be 081804");
        assertEquals("050471", Totp.generateCodeAt(RFC_SECRET, 1111111111L), "6-digit code at T=1111111111 must be 050471");
        assertEquals("005924", Totp.generateCodeAt(RFC_SECRET, 1234567890L), "6-digit code at T=1234567890 must be 005924");
        assertEquals("279037", Totp.generateCodeAt(RFC_SECRET, 2000000000L), "6-digit code at T=2000000000 must be 279037");
        assertEquals("353130", Totp.generateCodeAt(RFC_SECRET, 20000000000L), "6-digit code at T=20000000000 must be 353130");
    }

    // 同一周期内不同秒应生成相同验证码，跨周期后应改变
    @Test
    void generateCodeAtIsStableWithinPeriodAndChangesAcrossPeriods() {
        String atStart = Totp.generateCodeAt(RFC_SECRET, 1234567890L);
        String laterInPeriod = Totp.generateCodeAt(RFC_SECRET, 1234567919L);
        String nextPeriod = Totp.generateCodeAt(RFC_SECRET, 1234567920L);

        assertEquals(atStart, laterInPeriod, "T=1234567890 and T+29s share the same 30s period, so codes must be identical");
        assertNotEquals(atStart, nextPeriod, "code must change once the clock crosses into the next period");
    }

    // ==================== 2. 时间源注入生效 ====================

    // 应返回注入时钟的值
    @Test
    void nowMillisReturnsInjectedClockValue() {
        MutableClock clock = new MutableClock(1234567890000L);
        Totp.setTimeSource(clock);

        assertEquals(1234567890000L, Totp.nowMillis(), "nowMillis must return the injected clock time");
    }

    // 注入 null 应恢复系统时钟
    @Test
    void nowMillisFallsBackToSystemClockWhenSourceIsNull() {
        MutableClock clock = new MutableClock(0L);
        Totp.setTimeSource(clock);
        assertEquals(0L, Totp.nowMillis(), "after injection nowMillis must use the injected clock");

        Totp.setTimeSource(null);
        long actual = Totp.nowMillis();
        long lowerBound = System.currentTimeMillis() - 5000L;
        long upperBound = System.currentTimeMillis() + 5000L;
        assertTrue(actual >= lowerBound && actual <= upperBound,
                "after restoring the system clock nowMillis must be close to the real current time, actual=" + actual);
    }

    // verifyCode 与 matchCounter 应依据注入时间判定
    @Test
    void verifyCodeAndMatchCounterUseInjectedTime() {
        MutableClock clock = new MutableClock(T * 1000L);
        Totp.setTimeSource(clock);

        String codeAtNow = Totp.generateCodeAt(RFC_SECRET, T);
        assertEquals("005924", codeAtNow, "with the injected clock the code must equal the official vector value at the base instant");
        assertTrue(Totp.verifyCode(RFC_SECRET, codeAtNow), "with the injected clock at T, a code generated for T must verify");
        assertNotNull(Totp.matchCounter(RFC_SECRET, codeAtNow), "with the injected clock at T, a code generated for T must match a counter");

        clock.advance(PERIOD * 1000L);
        // 注入时钟推进 30 秒后，原验证码仍落在 ±1 周期窗口内
        assertTrue(Totp.verifyCode(RFC_SECRET, codeAtNow), "after advancing the injected clock by 30s the previous code is still inside the -1/+1 period window");
    }

    // ==================== 3. 验证窗口边界（±1 周期） ====================

    // 时钟固定时窗口内三个周期的码都应匹配
    @Test
    void matchCounterAcceptsAllThreePeriodsInsideWindow() {
        Totp.setTimeSource(() -> T * 1000L);

        assertEquals(T / PERIOD - 1, Totp.matchCounter(RFC_SECRET, Totp.generateCodeAt(RFC_SECRET, T - PERIOD)),
                "the code from T-30 must match, with counter (T-30)/30");
        assertEquals(T / PERIOD, Totp.matchCounter(RFC_SECRET, Totp.generateCodeAt(RFC_SECRET, T)),
                "the code from T must match, with counter T/30");
        assertEquals(T / PERIOD + 1, Totp.matchCounter(RFC_SECRET, Totp.generateCodeAt(RFC_SECRET, T + PERIOD)),
                "the code from T+30 must match, with counter (T+30)/30");
    }

    // 时钟固定时窗口外的码应拒绝
    @Test
    void matchCounterRejectsCodesOutsideWindow() {
        Totp.setTimeSource(() -> T * 1000L);

        String codeBefore = Totp.generateCodeAt(RFC_SECRET, T - 2 * PERIOD);
        assertNull(Totp.matchCounter(RFC_SECRET, codeBefore), "code from T-60 is outside the -1/+1 period window and must not match");
        assertFalse(Totp.verifyCode(RFC_SECRET, codeBefore), "code from T-60 is outside the window, so verifyCode must be false");

        String codeAfter = Totp.generateCodeAt(RFC_SECRET, T + 2 * PERIOD);
        assertNull(Totp.matchCounter(RFC_SECRET, codeAfter), "code from T+60 is outside the -1/+1 period window and must not match");
        assertFalse(Totp.verifyCode(RFC_SECRET, codeAfter), "code from T+60 is outside the window, so verifyCode must be false");
    }

    // ==================== 4. 返回的周期计数正确 ====================

    // 返回值应等于时刻除以 30
    @Test
    void matchCounterReturnsInstantDividedByThirty() {
        Totp.setTimeSource(() -> T * 1000L);

        Long counter = Totp.matchCounter(RFC_SECRET, Totp.generateCodeAt(RFC_SECRET, T));
        assertNotNull(counter, "the match must succeed when the instant equals the generation instant");
        assertEquals(T / PERIOD, (long) counter, "the matched counter must equal T/30, actual=" + counter);
    }

    // 应返回窗口内首个匹配项的计数
    @Test
    void matchCounterReturnsFirstMatchingOffsetInWindow() {
        // 时钟置于 T，验证码取 T-30：偏移 -1 先命中，返回 (T-30)/30 而非当前周期
        Totp.setTimeSource(() -> T * 1000L);

        String codeFromPreviousPeriod = Totp.generateCodeAt(RFC_SECRET, T - PERIOD);
        assertEquals((T - PERIOD) / PERIOD, Totp.matchCounter(RFC_SECRET, codeFromPreviousPeriod),
                "offset -1 hits before offset 0, so the counter for T-30 must be returned");
    }

    // ==================== 5. 非法输入 ====================

    // verifyCode 与 matchCounter 对非法密钥应安全拒绝
    @Test
    void verifyCodeAndMatchCounterRejectInvalidSecrets() {
        String validCode = Totp.generateCodeAt(RFC_SECRET, T);

        assertNull(Totp.matchCounter(null, validCode), "null secret must be rejected");
        assertFalse(Totp.verifyCode(null, validCode), "verifyCode must be false for a null secret");

        assertNull(Totp.matchCounter("", validCode), "empty secret must be rejected");
        assertFalse(Totp.verifyCode("", validCode), "verifyCode must be false for an empty secret");

        assertNull(Totp.matchCounter("!!!", validCode), "a secret made only of illegal characters must be rejected");
        assertFalse(Totp.verifyCode("!!!", validCode), "verifyCode must be false for a secret made only of illegal characters");
    }

    // verifyCode 与 matchCounter 对非法验证码应安全拒绝
    @Test
    void verifyCodeAndMatchCounterRejectInvalidCodes() {
        assertNull(Totp.matchCounter(RFC_SECRET, null), "null code must be rejected");
        assertFalse(Totp.verifyCode(RFC_SECRET, null), "verifyCode must be false for a null code");

        assertNull(Totp.matchCounter(RFC_SECRET, ""), "empty code must be rejected");
        assertFalse(Totp.verifyCode(RFC_SECRET, ""), "verifyCode must be false for an empty code");

        assertNull(Totp.matchCounter(RFC_SECRET, "12a456"), "a code containing a non-digit must be rejected");
        assertFalse(Totp.verifyCode(RFC_SECRET, "12a456"), "verifyCode must be false for a code containing a non-digit");

        assertNull(Totp.matchCounter(RFC_SECRET, "12345"), "a 5-digit code is too short and must be rejected");
        assertFalse(Totp.verifyCode(RFC_SECRET, "12345"), "verifyCode must be false for a 5-digit code that is too short");

        assertNull(Totp.matchCounter(RFC_SECRET, "1234567"), "a 7-digit code is too long and must be rejected");
        assertFalse(Totp.verifyCode(RFC_SECRET, "1234567"), "verifyCode must be false for a 7-digit code that is too long");
    }

    // 非法密钥应返回空串
    @Test
    void generateCodeAtReturnsEmptyStringForInvalidSecrets() {
        assertEquals("", Totp.generateCodeAt(null, T), "a null secret must yield an empty string");
        assertEquals("", Totp.generateCodeAt("", T), "an empty secret must yield an empty string");
        assertEquals("", Totp.generateCodeAt("!!!", T), "a secret made only of illegal characters must yield an empty string");
    }

    // ==================== 6. Base32 宽松解析 ====================

    // 小写、带空白或带填充的密钥应与规范形式等价
    @Test
    void generateCodeAtNormalizesCaseWhitespaceAndPadding() {
        String canonical = Totp.generateCodeAt(RFC_SECRET, T);
        assertNotNull(canonical, "the canonical secret must produce a code");
        assertEquals(6, canonical.length(), "the code must be 6 digits, actual=" + canonical);

        String lowerCase = RFC_SECRET.toLowerCase();
        String withSpaces = "GEZD GNBV GY3T QOJQ GEZD GNBV GY3T QOJQ";
        String withPadding = RFC_SECRET + "======";

        assertEquals(canonical, Totp.generateCodeAt(lowerCase, T), "an all-lowercase secret must produce the same code as the canonical form");
        assertEquals(canonical, Totp.generateCodeAt(withSpaces, T), "a secret containing spaces must produce the same code as the canonical form");
        assertEquals(canonical, Totp.generateCodeAt(withPadding, T), "a secret with '=' padding must produce the same code as the canonical form");
    }

    // ==================== 7. generateSecret ====================

    // 应为 32 位 Base32 且两次生成不同
    @Test
    void generateSecretReturns32Base32CharsAndDiffersAcrossCalls() {
        String first = Totp.generateSecret();
        String second = Totp.generateSecret();

        assertNotNull(first, "the generated secret must not be null");
        assertEquals(32, first.length(), "the Base32 encoding of a 20-byte secret must be 32 characters, actual=" + first);
        for (int i = 0; i < first.length(); i++) {
            char c = first.charAt(i);
            assertTrue(BASE32_ALPHABET.indexOf(c) >= 0,
                    "character '" + c + "' at index " + i + " must belong to the Base32 alphabet A-Z2-7, secret=" + first);
        }

        assertEquals(32, second.length(), "the second generated secret must also be 32 characters");
        assertNotEquals(first, second, "two generated random secrets must not be equal");
    }

    // 生成的密钥应可用于生成与校验验证码
    @Test
    void generateSecretProducesUsableVerifiableSecret() {
        String secret = Totp.generateSecret();
        MutableClock clock = new MutableClock(T * 1000L);
        Totp.setTimeSource(clock);

        String code = Totp.generateCodeAt(secret, T);
        assertEquals(6, code.length(), "a code generated from a random secret must also be 6 digits, actual=" + code);
        assertTrue(Totp.verifyCode(secret, code), "a code generated from a random secret must verify at the same instant");
    }
}
