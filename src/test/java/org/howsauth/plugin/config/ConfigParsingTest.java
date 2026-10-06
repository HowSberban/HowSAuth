package org.howsauth.plugin.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.configuration.file.YamlConfiguration;
import org.howsauth.plugin.support.MockBukkitHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 配置解析、钳制与回退的覆盖补充。
 * <p>
 * 目标对象是 {@link ConfigManager} 中此前无直接测试的逻辑：
 * <ul>
 *   <li>{@code premium.http-proxies}：{@code host:port} 解析（非法项告警跳过、占位示例跳过、端口边界）；</li>
 *   <li>{@code premium.session-server-mirrors}：URL 校验、末尾斜杠归一、trim 与空项过滤；</li>
 *   <li>{@code premiumSessionServerCandidates}：官方地址恒在首位、列表不可变；</li>
 *   <li>{@code clampInt}/{@code clampRange}：越界钳制并回写配置文件（合规值不触发回写）；</li>
 *   <li>枚举回退：{@code database.type}、{@code protection.pos.mode}、{@code password.hash}；</li>
 *   <li>命令白名单归一：trim + 小写（Locale.ROOT）+ 去空；</li>
 *   <li>{@code databaseFingerprint}：{@code reload()} 是否报告数据库配置变化。</li>
 * </ul>
 * 这些逻辑在 ConfigManager 拆分迁移时会被搬到子配置类，本测试用于锁定迁移前后的行为一致。
 * <p>
 * <b>两个测试环境约束</b>（都会影响断言写法，已在下文规避）：
 * <ol>
 *   <li>YAML 序列化会重排引号与列表缩进，因此"配置文件是否被回写"不能用字节比较，
 *       只能比较语义值（用例写入原始值，断言读回的语义值）。</li>
 *   <li>{@code JavaPlugin.reloadConfig()} 在文件修改时间未变时不会重读磁盘，
 *       而 {@code ConfigManager.reload()} 依赖该重读。故本类在写入配置后显式把文件修改时间前推，
 *       使重读必然发生，避免断言依赖文件系统时间戳精度。</li>
 * </ol>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConfigParsingTest {

    private static final String EXAMPLE_PROXY = "example.com:25565";
    private static final String EXAMPLE_MIRROR = "https://mirror.example.com";
    private static final String OFFICIAL_SESSION_SERVER = "https://sessionserver.mojang.com";

    private MockBukkitHarness env;
    private File configFile;

    @BeforeAll
    void bootstrap() throws Exception {
        // bcrypt cost 覆盖为 4：本类不测哈希强度，但构造 ConfigManager 会读到该值
        env = MockBukkitHarness.start("hsauth-config-parsing-",
                config -> MockBukkitHarness.inject(PasswordConfig.class, "bcryptCost", config.password(), 4));
        configFile = new File(env.plugin().getDataFolder(), "config.yml");
        assertTrue(configFile.exists(), "the config file must have been generated");
    }

    @AfterAll
    void teardown() {
        if (env != null) {
            env.close();
        }
    }

    /**
     * 每个用例前从 jar 内置资源重建干净的 config.yml。
     * 删除后由 {@code saveDefaultConfig()} 重新落盘，既保证内容为出厂默认，也必然推进文件修改时间。
     */
    @BeforeEach
    void restoreShippedConfig() {
        try {
            Files.deleteIfExists(configFile.toPath());
        } catch (IOException e) {
            throw new IllegalStateException("failed to reset the test config file", e);
        }
    }

    // ---------- premium.http-proxies 解析 ----------

    /** 合法 host:port 被解析为 HTTP 代理；占位示例与非法项被跳过且不进入列表 */
    @Test
    void httpProxiesAreParsedAndInvalidEntriesSkipped() {
        // 混合 4 类：占位示例（跳过）、合法、端口非法、缺端口
        ConfigManager config = loadWith("premium.http-proxies", List.of(
                EXAMPLE_PROXY,
                "proxy.internal:8080",
                "bad-port.example.com:99999",
                "no-port.example.com"));

        List<Proxy> proxies = config.premium().httpProxies();
        assertEquals(1, proxies.size(), "only the valid entry may survive");

        Proxy proxy = proxies.getFirst();
        assertEquals(Proxy.Type.HTTP, proxy.type(), "proxies must be HTTP type");
        InetSocketAddress address = assertInstanceOf(InetSocketAddress.class, proxy.address(),
                "address must be an InetSocketAddress");
        assertEquals("proxy.internal", address.getHostString(), "host must be parsed verbatim");
        assertEquals(8080, address.getPort(), "port must be parsed as a number");
    }

    /** 端口边界：1 与 65535 合法，0 与 65536 非法（越界一律丢弃，不做钳制） */
    @Test
    void httpProxyPortBoundariesAreEnforced() {
        ConfigManager config = loadWith("premium.http-proxies", List.of(
                "low.example.com:1",
                "high.example.com:65535",
                "zero.example.com:0",
                "over.example.com:65536"));

        List<Proxy> proxies = config.premium().httpProxies();
        assertEquals(2, proxies.size(), "only ports inside 1..65535 may be accepted");
        assertEquals(1, ((InetSocketAddress) proxies.get(0).address()).getPort(),
                "port 1 is the inclusive lower bound");
        assertEquals(65535, ((InetSocketAddress) proxies.get(1).address()).getPort(),
                "port 65535 is the inclusive upper bound");
    }

    /** 格式畸形项一律跳过：空串、纯空白、无冒号、空主机、空端口、非数字端口 */
    @Test
    void malformedHttpProxyEntriesAreRejected() {
        ConfigManager config = loadWith("premium.http-proxies", List.of(
                "",
                "   ",
                "no-colon",
                ":8080",
                "host.example.com:",
                "host.example.com:abc"));

        assertTrue(config.premium().httpProxies().isEmpty(), "malformed entries must all be dropped");
    }

    /** 主机名内含冒号时以最后一个冒号分隔；解析走未解析地址，主机名不被 DNS 归一 */
    @Test
    void httpProxySplitsOnTheLastColonSoHostsMayContainColons() {
        ConfigManager config = loadWith("premium.http-proxies", List.of("host.example.com:80:90"));

        List<Proxy> proxies = config.premium().httpProxies();
        assertEquals(1, proxies.size(), "everything before the last colon is treated as the host");
        InetSocketAddress address = (InetSocketAddress) proxies.getFirst().address();
        assertEquals("host.example.com:80", address.getHostString(), "only the last colon separates the port");
        assertEquals(90, address.getPort(), "the port part is everything after the last colon");
    }

    /** 条目两侧空白被 trim，占位示例带空白时同样被识别为占位而跳过 */
    @Test
    void httpProxyEntriesAreTrimmedBeforeParsing() {
        ConfigManager config = loadWith("premium.http-proxies", List.of(
                "  spaced.example.com:3128  ",
                "  " + EXAMPLE_PROXY + "  "));

        List<Proxy> proxies = config.premium().httpProxies();
        assertEquals(1, proxies.size(), "the placeholder must be skipped even when padded with spaces");
        assertEquals("spaced.example.com", ((InetSocketAddress) proxies.getFirst().address()).getHostString(),
                "surrounding whitespace must be trimmed");
    }

    /** 出厂默认配置（只含占位示例）解析后代理列表为空，不会误当真实代理探测 */
    @Test
    void shippedPlaceholderProxyIsNotTreatedAsARealProxy() {
        // 先构造以触发 saveDefaultConfig 落盘，再读文件内容
        ConfigManager config = loadFresh();

        assertTrue(readYaml().getStringList("premium.http-proxies").contains(EXAMPLE_PROXY),
                "the shipped config must still contain its placeholder entry");
        assertTrue(config.premium().httpProxies().isEmpty(),
                "the shipped placeholder must not be parsed into a real proxy");
    }

    // ---------- premium.session-server-mirrors 解析 ----------

    /** 合法 http/https 镜像被接受，末尾斜杠被去掉；官方地址恒在候选列表首位 */
    @Test
    void sessionMirrorsAreNormalizedAndOfficialEndpointComesFirst() {
        ConfigManager config = loadWith("premium.session-server-mirrors", List.of(
                "https://mirror-a.example.com/",
                "http://mirror-b.example.com"));

        List<String> candidates = config.premium().sessionServerCandidates();
        assertEquals(OFFICIAL_SESSION_SERVER, candidates.getFirst(),
                "the official session server must always be the first candidate");
        assertEquals(List.of("https://mirror-a.example.com", "http://mirror-b.example.com"),
                candidates.subList(1, 3),
                "trailing slashes must be stripped and order preserved");
        assertEquals(3, candidates.size(), "official endpoint plus two mirrors");
    }

    /** 非 http/https 协议、占位示例、空项与纯空白项都被丢弃 */
    @Test
    void invalidSessionMirrorsAreDropped() {
        ConfigManager config = loadWith("premium.session-server-mirrors", List.of(
                EXAMPLE_MIRROR,
                "",
                "   ",
                "ftp://mirror.example.com",
                "mirror.example.com",
                "file:///etc/passwd"));

        List<String> candidates = config.premium().sessionServerCandidates();
        assertEquals(1, candidates.size(), "only the official endpoint may remain");
        assertEquals(OFFICIAL_SESSION_SERVER, candidates.getFirst(), "the official endpoint must survive");
    }

    /** 镜像条目两侧空白被 trim；候选列表不可变，调用方无法改动内部状态 */
    @Test
    void sessionMirrorsAreTrimmedAndCandidatesAreImmutable() {
        ConfigManager config = loadWith("premium.session-server-mirrors", List.of("  https://mirror.example.com/  "));

        List<String> candidates = config.premium().sessionServerCandidates();
        assertEquals("https://mirror.example.com", candidates.get(1),
                "whitespace and trailing slash must both be removed");

        assertThrows(UnsupportedOperationException.class, candidates::clear,
                "the returned candidate list must be immutable");
        assertEquals(2, config.premium().sessionServerCandidates().size(),
                "the internal state must be unaffected by a rejected mutation");
    }

    // ---------- clampInt / clampRange ----------

    /** 下限钳制：越界值被抬到下限，并回写配置文件（避免下次启动重复告警） */
    @Test
    void belowRangeValuesAreClampedUpAndWrittenBack() {
        ConfigManager config = loadWith("premium.http-pool-size", 1);

        assertEquals(2, config.premium().httpPoolSize(), "a value below the minimum must be raised to it");
        assertEquals(2, readYaml().getInt("premium.http-pool-size"),
                "the clamped value must be persisted so the next start does not warn again");
    }

    /** 上限钳制：越界值被压到上限并回写 */
    @Test
    void aboveRangeValuesAreClampedDownAndWrittenBack() {
        ConfigManager config = loadWith("premium.http-pool-size", 999);

        assertEquals(64, config.premium().httpPoolSize(), "a value above the maximum must be lowered to it");
        assertEquals(64, readYaml().getInt("premium.http-pool-size"), "the clamped value must be persisted");
    }

    /** 区间内的值原样保留，且不触发回写：文件里仍是用例写入的原始值 */
    @Test
    void inRangeValuesAreKeptVerbatimWithoutWriteBack() {
        ConfigManager config = loadWith("premium.http-pool-size", 16);

        assertEquals(16, config.premium().httpPoolSize(), "an in-range value must be kept");
        assertEquals(16, readYaml().getInt("premium.http-pool-size"),
                "an in-range value must survive untouched (no rewrite)");
    }

    /** 仅下限（clampInt）的键同样被钳制并回写 */
    @Test
    void lowerBoundOnlyKeysAreAlsoClampedAndWrittenBack() {
        ConfigManager config = loadWith("premium.retry-interval-ms", -5);

        assertEquals(0, config.premium().retryIntervalMs(), "a negative retry interval must be clamped to 0");
        assertEquals(0, readYaml().getLong("premium.retry-interval-ms"), "the clamped value must be persisted");
    }

    /** 多处越界同时被修正，每个键各自钳制并回写 */
    @Test
    void multipleOutOfRangeKeysAreClampedIndependently() {
        ConfigManager config = loadWith(yaml -> {
            yaml.set("premium.timeout-seconds", 0);
            yaml.set("premium.max-retries", -1);
            yaml.set("premium.cache-cap", -100);
            yaml.set("protection.pos.spawn-radius", 0);
            yaml.set("settings.purge.days", 0);
        });

        assertEquals(1, config.premium().timeoutSeconds(), "timeout below 1 must be raised to 1");
        assertEquals(0, config.premium().maxRetries(), "negative retries must be raised to 0");
        assertEquals(0, config.premium().cacheCap(), "negative cache cap must be raised to 0");
        assertEquals(1, config.protectionPosition().spawnRadius(), "a zero spawn radius must be raised to 1");
        assertEquals(1, config.settings().purgeDays(), "a zero purge interval must be raised to 1");

        YamlConfiguration written = readYaml();
        assertEquals(1, written.getInt("premium.timeout-seconds"), "every clamped key must be persisted");
        assertEquals(1, written.getInt("protection.pos.spawn-radius"), "every clamped key must be persisted");
        assertEquals(1, written.getInt("settings.purge.days"), "every clamped key must be persisted");
    }

    /** 哈希 cost 使用独立钳制区间 10..31，且边界被回写 */
    @Test
    void bcryptCostIsClampedToItsOwnRange() {
        ConfigManager low = loadWith("password.hash-cost", 9);
        assertEquals(10, low.password().bcryptCost(), "a cost below 10 must be raised to 10");
        assertEquals(10, readYaml().getInt("password.hash-cost"), "the raised cost must be persisted");

        ConfigManager high = loadWith("password.hash-cost", 32);
        assertEquals(31, high.password().bcryptCost(), "a cost above 31 must be lowered to 31");
        assertEquals(31, readYaml().getInt("password.hash-cost"), "the lowered cost must be persisted");
    }

    // ---------- 枚举回退 ----------

    /** 非法数据库类型回退 sqlite；合法值按小写归一 */
    @Test
    void invalidDatabaseTypeFallsBackToSqlite() {
        assertEquals("sqlite", loadWith("database.type", "oracle").database().type(),
                "an unsupported database type must fall back to sqlite");
        assertEquals("mysql", loadWith("database.type", "MySQL").database().type(),
                "a supported type must be normalized to lower case");
    }

    /** 非法坐标模式回退 random 并回写 */
    @Test
    void invalidPositionModeFallsBackToRandomAndIsWrittenBack() {
        ConfigManager config = loadWith("protection.pos.mode", "spiral");

        assertEquals("random", config.protectionPosition().mode(), "an unsupported position mode must fall back to random");
        assertEquals("random", readYaml().getString("protection.pos.mode"),
                "the fallback must be persisted so the next start does not warn again");
    }

    /** 合法坐标模式保留原值，且不触发回写 */
    @Test
    void validPositionModeIsKeptVerbatim() {
        ConfigManager config = loadWith("protection.pos.mode", "fixed");

        assertEquals("fixed", config.protectionPosition().mode(), "a supported mode must be kept as-is");
        assertEquals("fixed", readYaml().getString("protection.pos.mode"),
                "a valid mode must survive untouched (no rewrite)");
    }

    /** 非法哈希算法回退 bcrypt 并回写（防止静默降级为 sha256）；合法值小写归一 */
    @Test
    void invalidHashAlgorithmFallsBackToBcrypt() {
        assertEquals("bcrypt", loadWith("password.hash", "md5").password().hashAlgorithm(),
                "an unsupported hash must fall back to bcrypt");
        assertEquals("bcrypt", readYaml().getString("password.hash"), "the fallback must be persisted");
        assertEquals("sha256", loadWith("password.hash", "SHA256").password().hashAlgorithm(),
                "sha256 is supported and must be normalized to lower case");
    }

    /** 非法正则回退为不限制（null），且不因异常中断后续加载 */
    @Test
    void invalidPasswordPatternFallsBackToNoRestriction() {
        ConfigManager config = loadWith("password.pattern", "[unclosed");

        assertNull(config.password().pattern(), "an uncompilable pattern must fall back to no restriction");
        assertEquals(6, config.password().minLength(), "the rest of the password config must still load");
    }

    /** 空字符串正则视为不限制 */
    @Test
    void blankPasswordPatternMeansNoRestriction() {
        assertNull(loadWith("password.pattern", "").password().pattern(),
                "an empty pattern must mean no restriction");
    }

    /** 密码长度上下界小于/大于边界时被钳制并回写 */
    @Test
    void passwordLengthBoundsAreClampedAndWrittenBack() {
        ConfigManager tooSmall = loadWith("password.min-length", 2);
        assertEquals(4, tooSmall.password().minLength(), "a minimum below 4 must be raised to 4");
        assertEquals(4, readYaml().getInt("password.min-length"), "the raised minimum must be persisted");

        ConfigManager tooLarge = loadWith("password.max-length", 999);
        assertEquals(128, tooLarge.password().maxLength(), "a maximum above 128 must be lowered to 128");
        assertEquals(128, readYaml().getInt("password.max-length"), "the lowered maximum must be persisted");
    }

    /** 最大值小于最小值时被抬到最小值并回写（避免出现空区间） */
    @Test
    void maxPasswordLengthBelowMinIsRaisedToMin() {
        ConfigManager config = loadWith(yaml -> {
            yaml.set("password.min-length", 20);
            yaml.set("password.max-length", 8);
        });

        assertEquals(20, config.password().minLength(), "the minimum must be kept");
        assertEquals(20, config.password().maxLength(), "the maximum must be raised to the minimum");
        assertEquals(20, readYaml().getInt("password.max-length"), "the corrected maximum must be persisted");
    }

    // ---------- 命令白名单归一 ----------

    /** 白名单条目 trim + 小写（Locale.ROOT）+ 去空，顺序保留 */
    @Test
    void commandWhitelistIsTrimmedLowerCasedAndOrderPreserved() {
        ConfigManager config = loadWith("protection.prevent.command.whitelist", List.of(
                "  LOGIN  ",
                "",
                "   ",
                "Register",
                "2FA"));

        assertEquals(List.of("login", "register", "2fa"), config.prevent().commandWhitelist(),
                "entries must be trimmed, lower-cased and blanks dropped, order preserved");
    }

    /** 珍珠返还方式：仅 entity 表示实体模式，其余一律按 item 处理 */
    @Test
    void pearlReturnModeOtherThanEntityMeansItem() {
        assertFalse(loadWith("pearl.return", "item").pearl().returnEntity(), "\"item\" must mean item mode");
        assertFalse(loadWith("pearl.return", "bogus").pearl().returnEntity(), "an unknown mode must fall back to item");
        assertTrue(loadWith("pearl.return", "entity").pearl().returnEntity(), "\"entity\" must mean entity mode");
    }

    /** 模板为空时返回空串而非 null，避免调用方各自判空 */
    @Test
    void blankMessageTemplatesFallBackToEmptyString() {
        ConfigManager config = loadWith(yaml -> {
            yaml.set("messages.join.template", "");
            yaml.set("messages.quit.template", "");
        });

        assertEquals("", config.messages().joinMessageTemplate(), "an empty join template must surface as an empty string");
        assertEquals("", config.messages().quitMessageTemplate(), "an empty quit template must surface as an empty string");
    }

    // ---------- databaseFingerprint / reload ----------

    /**
     * 相对基线新建的 ConfigManager：其指纹取自当前文件内容，
     * 因此随后的任何数据库键改动都会被 {@code reload()} 检测到。
     */
    @Test
    void reloadReportsDatabaseChangeWhenHostChanges() {
        ConfigManager config = loadFresh();
        writeYaml(yaml -> yaml.set("database.mysql.host", "db.changed.example.com"));

        assertTrue(config.reload(), "a changed database host must be reported as needing a restart");
        assertEquals("db.changed.example.com", config.database().host(),
                "the in-memory value must follow the file even though the datasource is not rebuilt");
    }

    /** 池大小变化同样计入指纹（不只是主机名） */
    @Test
    void reloadReportsDatabaseChangeWhenPoolSizeChanges() {
        ConfigManager config = loadFresh();
        writeYaml(yaml -> yaml.set("database.mysql.pool-size", 7));

        assertTrue(config.reload(), "a changed pool size must be reported as needing a restart");
        assertEquals(7, config.database().poolSize(), "the changed pool size must be applied in memory");
    }

    /** 数据库连接参数 Map 变化计入指纹（改一个 mysql.params 条目即需重启） */
    @Test
    void reloadReportsDatabaseChangeWhenAParameterChanges() {
        ConfigManager config = loadFresh();
        writeYaml(yaml -> yaml.set("database.mysql.params.useSSL", "true"));

        assertTrue(config.reload(), "a changed mysql parameter must be reported as needing a restart");
    }

    /** 非数据库键变化不影响数据库指纹，但取值仍需生效 */
    @Test
    void reloadIgnoresNonDatabaseChanges() {
        ConfigManager config = loadFresh();
        writeYaml(yaml -> yaml.set("login.timeout", 99));

        assertFalse(config.reload(), "a login setting change must not be reported as a database change");
        assertEquals(99, config.login().timeout(), "the changed value must still be applied");
    }

    /** 指纹涵盖全部数据库字段：逐项改动都应被判为需要重启 */
    @Test
    void reloadReportsChangeForEveryDatabaseField() {
        record Field(String key, Object value) {
        }
        List<Field> fields = List.of(
                new Field("database.type", "mysql"),
                new Field("database.mysql.host", "other-host"),
                new Field("database.mysql.port", 3307),
                new Field("database.mysql.database", "other-db"),
                new Field("database.mysql.username", "other-user"),
                new Field("database.mysql.password", "other-secret"),
                new Field("database.mysql.pool-size", 3));

        for (Field field : fields) {
            ConfigManager config = loadFresh();
            writeYaml(yaml -> yaml.set(field.key(), field.value()));
            assertTrue(config.reload(),
                    "changing " + field.key() + " must be reported as needing a restart");
        }
    }

    // ---------- 修正告警文案（锁定 ConfigFixer 统一枚举回退后的输出不变） ----------

    /**
     * 枚举回退的告警按领域区分：四个领域各自用不同的 i18n key 与参数，
     * 统一到 ConfigFixer 后输出必须逐字不变，故在此逐条断言。
     */
    @Test
    void enumFallbackWarningsKeepTheirDomainSpecificText() {
        List<String> warnings = loadCapturingWarnings(yaml -> {
            yaml.set("database.type", "oracle");
            yaml.set("login.remind-method", "smoke-signal");
            yaml.set("pearl.return", "teleport");
            yaml.set("protection.pos.mode", "spiral");
        });

        assertFalse(warnings.isEmpty(), "invalid enum values must produce warnings");
        for (String key : List.of("login.remind-method", "pearl.return", "protection.pos.mode")) {
            assertTrue(warnings.stream().anyMatch(w -> w.contains(key)),
                    "the warning for " + key + " must name the offending config key; warnings=" + warnings);
        }
        for (String raw : List.of("oracle", "smoke-signal", "teleport", "spiral")) {
            assertTrue(warnings.stream().anyMatch(w -> w.contains(raw)),
                    "the warning must quote the raw invalid value " + raw + "; warnings=" + warnings);
        }
    }

    /** 数值钳制的告警（log.config_num_clamped）在统一后仍带配置键与边界值 */
    @Test
    void numericClampWarningsNameTheKeyAndTheBound() {
        List<String> warnings = loadCapturingWarnings(yaml -> yaml.set("premium.http-pool-size", 1));

        assertTrue(warnings.stream().anyMatch(w -> w.contains("premium.http-pool-size") && w.contains("2")),
                "the clamp warning must name the key and the lower bound; warnings=" + warnings);
    }

    /** 无效代理与无效镜像各自沿用既有告警 key，且引用被跳过的原始条目 */
    @Test
    void invalidEndpointWarningsQuoteTheOffendingEntry() {
        List<String> warnings = loadCapturingWarnings(yaml -> {
            yaml.set("premium.http-proxies", List.of("no-port.example.com"));
            yaml.set("premium.session-server-mirrors", List.of("ftp://mirror.example.com"));
        });

        assertTrue(warnings.stream().anyMatch(w -> w.contains("no-port.example.com")),
                "the proxy warning must quote the offending entry; warnings=" + warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("ftp://mirror.example.com")),
                "the mirror warning must quote the offending url; warnings=" + warnings);
    }

    /**
     * 大小写行为逐领域原样保留：只有登录提醒方式做过小写归一（配置键值统一小写），
     * 其余三个领域保持大小写敏感——这是拆分前的既有差异，统一实现后不得改变。
     */
    @Test
    void enumCasingBehaviourIsUnchangedPerDomain() {
        assertEquals("chat", loadWith("login.remind-method", "CHAT").login().remindMethod(),
                "the remind method must stay case-insensitive");
        assertFalse(loadWith("pearl.return", "ITEM").pearl().returnEntity(),
                "an upper-case pearl mode must fall back to item (case-sensitive, as before)");
        assertEquals("random", loadWith("protection.pos.mode", "FIXED").protectionPosition().mode(),
                "an upper-case position mode must fall back to random (case-sensitive, as before)");
        assertEquals("sqlite", loadWith("database.type", "SQLITE").database().type(),
                "the database type stays case-insensitive (lower-cased before validation)");
    }

    // ---------- 辅助 ----------

    /**
     * 在构造 ConfigManager 期间捕获插件日志告警。
     * 借助 harness 已注入的测试 Logger（见 MockBukkitHarness）临时挂上捕获 Handler。
     */
    private List<String> loadCapturingWarnings(Consumer<YamlConfiguration> mutator) {
        List<String> captured = new ArrayList<>();
        writeYaml(mutator);
        Logger logger = env.plugin().getLogger();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                // 只收告警及以上：加载过程也有 INFO 级输出
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
                    captured.add(record.getMessage());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        logger.addHandler(handler);
        try {
            loadFresh();
        } finally {
            logger.removeHandler(handler);
        }
        return captured;
    }

    /** 按消费者改写 config.yml，并返回基于该文件新建的 ConfigManager（模拟重启后的加载过程） */
    private ConfigManager loadWith(Consumer<YamlConfiguration> mutator) {
        writeYaml(mutator);
        return loadFresh();
    }

    /** 写入单个配置值后新建 ConfigManager */
    private ConfigManager loadWith(String key, Object value) {
        return loadWith(yaml -> yaml.set(key, value));
    }

    /** 基于当前配置文件新建 ConfigManager（构造即 load） */
    private ConfigManager loadFresh() {
        return new ConfigManager(env.plugin());
    }

    private void writeYaml(Consumer<YamlConfiguration> mutator) {
        YamlConfiguration yaml = readYaml();
        mutator.accept(yaml);
        try {
            yaml.save(configFile);
            // 前推修改时间：保证 plugin.reloadConfig() 的"文件已变更"判定必然成立，
            // 否则它可能因时间戳未变而跳过重读，使 ConfigManager.reload() 读到旧内容
            Files.setLastModifiedTime(configFile.toPath(),
                    FileTime.fromMillis(System.currentTimeMillis() + 1000L));
        } catch (IOException e) {
            throw new IllegalStateException("failed to rewrite the test config file", e);
        }
    }

    private YamlConfiguration readYaml() {
        return YamlConfiguration.loadConfiguration(configFile);
    }
}
