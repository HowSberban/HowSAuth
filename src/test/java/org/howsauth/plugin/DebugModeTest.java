package org.howsauth.plugin;

import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.support.MockBukkitHarness;
import org.howsauth.plugin.support.MockBukkitHarness.TestPlayerMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调试模式：开关随配置（含 reload 后的重新读取）同步，诊断快照只输出状态、不含敏感值
 */
class DebugModeTest {

    private MockBukkitHarness env;
    private HowSAuth plugin;

    @BeforeEach
    void setUp() throws Exception {
        env = MockBukkitHarness.start("hsauth-debug-test", config ->
                MockBukkitHarness.inject(ConfigManager.class, "bcryptCost", config, 4));
        plugin = env.plugin();
    }

    @AfterEach
    void tearDown() {
        // 复位静态开关，避免影响其它测试类
        MockBukkitHarness.inject(ConfigManager.class, "debug", env.config(), false);
        Debug.refresh(plugin);
        env.close();
    }

    @Test
    void debugSwitchFollowsConfig() {
        Debug.refresh(plugin);
        assertFalse(Debug.on(), "debug must be off with the shipped default config");

        MockBukkitHarness.inject(ConfigManager.class, "debug", env.config(), true);
        Debug.refresh(plugin);
        assertTrue(Debug.on(), "debug must follow the configured value");

        // 运行时可切换：reload 后重新读取配置即静默
        MockBukkitHarness.inject(ConfigManager.class, "debug", env.config(), false);
        Debug.refresh(plugin);
        assertFalse(Debug.on(), "debug must be silent again after switching back");
    }

    @Test
    void diagnosticsContainStateButNoSecrets() {
        String password = "Sup3rSecret-pw";
        TestPlayerMock player = new TestPlayerMock(env.server(), "diaguser");
        assertTrue(env.accounts().forceRegister(player.getUniqueId(), player.getName(), password),
                "force register must create the account");

        List<String> lines = env.auth().diagnostics();
        assertFalse(lines.isEmpty(), "diagnostics must produce at least the summary line");
        String joined = String.join("\n", lines);
        assertTrue(joined.contains("HowSAuth diagnostics"), "diagnostics must carry a recognizable header");
        assertTrue(joined.contains("caches:"), "diagnostics must report cache sizes");
        assertFalse(joined.contains(password), "diagnostics must not contain the password");
        assertFalse(joined.contains("$2a$"), "diagnostics must not contain password hashes");
    }

    /** 配置文件路径与 reload 生效：advanced.debug 改动后开关随之变化（防止键路径写错却因默认值恰好一致而漏测） */
    @Test
    void debugSwitchFollowsConfigFile() throws Exception {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        Files.writeString(configFile.toPath(),
                Files.readString(configFile.toPath(), StandardCharsets.UTF_8).replace("debug: false", "debug: true"),
                StandardCharsets.UTF_8);

        plugin.getConfigManager().reload();
        Debug.refresh(plugin);

        assertTrue(Debug.on(), "advanced.debug must be read from the config file");
    }

    /** 调试输出单独落 plugins/HowSAuth/debug.log（不进控制台与 latest.log） */
    @Test
    void debugOutputGoesToDedicatedFile() throws Exception {
        MockBukkitHarness.inject(ConfigManager.class, "debug", env.config(), true);
        Debug.refresh(plugin);

        Debug.log("test", "hello %s", "world");
        Debug.close();

        File log = new File(plugin.getDataFolder(), "debug.log");
        assertTrue(log.isFile(), "debug output must be written to its own file");
        String content = Files.readString(log.toPath(), StandardCharsets.UTF_8);
        assertTrue(content.contains("[test] hello world"), "debug file must contain the formatted line");
    }

    /** 关闭时不产生、也不触碰日志文件：不写入、不轮转、不归档 */
    @Test
    void debugOffCreatesNoFile() {
        Debug.refresh(plugin);
        Debug.log("test", "must not be written");
        Debug.init(plugin);

        File[] debugFiles = plugin.getDataFolder()
                .listFiles((d, name) -> name.startsWith("debug") && name.endsWith(".log"));
        assertNotNull(debugFiles, "the data folder must be listable");
        assertEquals(0, debugFiles.length, "debug off must not create or touch any debug log file");
    }

    /** 开服轮转：上一次的 debug.log 按开服时间归档，新会话写入空的新文件 */
    @Test
    void debugLogRotatesOnServerStart() throws Exception {
        MockBukkitHarness.inject(ConfigManager.class, "debug", env.config(), true);
        Debug.refresh(plugin);
        Debug.log("test", "before restart");
        Debug.close();

        File dir = plugin.getDataFolder();
        Debug.init(plugin);

        File[] archived = dir.listFiles((d, name) -> name.startsWith("debug-") && name.endsWith(".log"));
        assertNotNull(archived, "the data folder must be listable");
        assertEquals(1, archived.length, "the previous debug log must be archived exactly once");
        assertTrue(Files.readString(archived[0].toPath(), StandardCharsets.UTF_8).contains("before restart"),
                "the archived file must keep the previous session's lines");
        assertTrue(Debug.fileStatus().contains("archives=1"), "the dump status must report the archived file");
        assertFalse(new File(dir, "debug.log").exists(), "the new session must start from an empty debug.log");
    }

    /** 跨日轮转：日期变化后的首次写入先归档前一天的内容，单个文件不超过一天 */
    @Test
    void debugLogRotatesOnDateChange() throws Exception {
        MockBukkitHarness.inject(ConfigManager.class, "debug", env.config(), true);
        Debug.refresh(plugin);
        Debug.log("test", "yesterday");

        // 模拟"当前文件属于昨天"：下一次写入应先归档再开新文件（日期在生产代码里取自系统时钟；
        // 轮转自身会先关闭句柄再改名，无需在此调用 Debug.close()）
        Field logDate = DebugFile.class.getDeclaredField("currentLogDate");
        logDate.setAccessible(true);
        logDate.set(null, LocalDate.now().minusDays(1));

        Debug.log("test", "today");

        File dir = plugin.getDataFolder();
        File[] archived = dir.listFiles((d, name) -> name.startsWith("debug-") && name.endsWith(".log"));
        assertNotNull(archived, "the data folder must be listable");
        assertEquals(1, archived.length, "the log must be archived once the date changes");
        assertTrue(Files.readString(archived[0].toPath(), StandardCharsets.UTF_8).contains("yesterday"),
                "the archived file must keep the previous day's lines");
        String current = Files.readString(new File(dir, "debug.log").toPath(), StandardCharsets.UTF_8);
        assertTrue(current.contains("today"), "the new file must contain the new day's lines");
        assertFalse(current.contains("yesterday"), "the new file must not repeat the archived lines");
    }
}