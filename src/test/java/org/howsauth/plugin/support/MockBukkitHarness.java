package org.howsauth.plugin.support;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.EnderPearl;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.auth.AuthManager;
import org.howsauth.plugin.auth.AuthManager.LoginResult;
import org.howsauth.plugin.auth.Totp;
import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.data.PlayerDataManager;
import org.jetbrains.annotations.NotNull;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.EnderPearlMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * MockBukkit 测试环境：统一组装 ConfigManager / PlayerDataManager / AuthManager 与临时目录，
 * 并补齐 MockBukkit 未实现、而生产代码会调用的调度器与路径接口。
 * <p>
 * 刻意不触发 {@code onEnable}：dialog / packet / premium / 命令等外部 API 分支在 mock 下不可用，
 * 测试只组装核心数据与认证链路。生命周期用 try-with-resources 或 {@code @AfterAll} 关闭，
 * 关闭时释放连接池、还原 TOTP 时间源并删除临时目录。
 */
public final class MockBukkitHarness implements AutoCloseable {

    /** 当前环境加载的插件实例（占位调度任务需要返回 owner） */
    private static volatile Plugin currentPlugin;

    private final Path tempDir;
    private final ServerMock server;
    private final HowSAuth plugin;
    private final ConfigManager config;
    private final PlayerDataManager data;
    private final AuthManager auth;
    private final MutableClock clock;

    private MockBukkitHarness(Path tempDir, ServerMock server, HowSAuth plugin, ConfigManager config,
                              PlayerDataManager data, AuthManager auth, MutableClock clock) {
        this.tempDir = tempDir;
        this.server = server;
        this.plugin = plugin;
        this.config = config;
        this.data = data;
        this.auth = auth;
        this.clock = clock;
    }

    /** 启动环境（不调整配置） */
    public static MockBukkitHarness start(String tempPrefix) throws Exception {
        return start(tempPrefix, null);
    }

    /**
     * 启动环境。
     * @param tempPrefix    临时目录前缀（含 SQLite 数据库与 world 目录）
     * @param configTweaks  在构造数据层之前调整 ConfigManager 私有字段（如 bcryptCost、failProtectionEnabled）
     */
    public static MockBukkitHarness start(String tempPrefix, Consumer<ConfigManager> configTweaks) throws Exception {
        Path tempDir = Files.createTempDirectory(tempPrefix);
        ServerMock server = null;
        try {
            // 自定义 ServerMock：补 GlobalRegionScheduler 实现（生产代码在异步线程触发同步事件时依赖它，
            // MockBukkit 默认未实现，缺它会抛 UnimplementedOperationException 导致异步回调丢失）
            server = MockBukkit.mock(new TestServerMock());
            // 用 MockBukkit 的 loadPlugin 创建插件实例（由插件类加载器构造，满足 Paper 1.21.11 的
            // "JavaPlugin requires to be created by a valid classloader" 校验），但不触发 onEnable
            HowSAuth plugin = (HowSAuth) server.getPluginManager().loadPlugin(HowSAuth.class);
            currentPlugin = plugin;
            inject(JavaPlugin.class, "dataFolder", plugin, tempDir.toFile());
            // JavaPlugin 会缓存 configFile，MockBukkit 在 loadPlugin 阶段已按插件默认目录缓存了它；
            // 不覆盖该字段时 getConfig()/reloadConfig() 始终读取那个不存在的文件，
            // 导致配置全部回落到代码默认值（配置文件里的值一个都读不到）
            inject(JavaPlugin.class, "configFile", plugin, tempDir.resolve("config.yml").toFile());
            inject(JavaPlugin.class, "logger", plugin, Logger.getLogger("HowSAuthTest"));
            // 与 onEnable 次序一致：I18n 先于 ConfigManager 初始化（配置版本检查/日志使用 I18n）
            I18n.init(plugin);
            ConfigManager config = new ConfigManager(plugin);
            if (configTweaks != null) {
                configTweaks.accept(config);
            }
            // PlayerDataManager 建数据源走 plugin.getConfigManager()，需先注入（onEnable 中由字段赋值保证）
            inject(HowSAuth.class, "configManager", plugin, config);
            PlayerDataManager data = new PlayerDataManager(plugin);
            AuthManager auth = new AuthManager(plugin, data, config);
            // 自定义 WorldMock：补 getWorldFolder（注销删档路径依赖它定位世界目录，MockBukkit 默认未实现）
            server.addWorld(new TestWorldMock(new WorldCreator("world"), tempDir.resolve("world").toFile()));
            // 注入可控时钟：TOTP 周期由测试推进，起点与真实时间一致
            MutableClock clock = new MutableClock(System.currentTimeMillis());
            Totp.setTimeSource(clock);
            return new MockBukkitHarness(tempDir, server, plugin, config, data, auth, clock);
        } catch (Exception e) {
            currentPlugin = null;
            if (server != null) {
                MockBukkit.unmock();
            }
            deleteRecursively(tempDir);
            throw e;
        }
    }

    public ServerMock server() {
        return server;
    }

    public HowSAuth plugin() {
        return plugin;
    }

    public ConfigManager config() {
        return config;
    }

    public PlayerDataManager data() {
        return data;
    }

    public AuthManager auth() {
        return auth;
    }

    /** 注册一个使用同步实体调度器的玩家（addPasswordAsync 等依赖 player.getScheduler()） */
    public TestPlayerMock addScheduledPlayer(String name) {
        TestPlayerMock player = new TestPlayerMock(server, name);
        server.addPlayer(player);
        return player;
    }

    /** 推进一个 TOTP 周期（30 秒），替代真实 sleep */
    public void advanceTotpPeriod() {
        clock.advance(30_000L);
    }

    /** 生成当前（注入时钟）时刻的有效 TOTP 验证码 */
    public String totpCode(String base32Secret) {
        return Totp.generateCodeAt(base32Secret, clock.getAsLong() / 1000);
    }

    /** 登录结果与剩余踢出秒数 */
    public record LoginOutcome(LoginResult result, long kickRemainingSeconds) {}

    /** 阻塞等待异步登录结果（loginConfigAsync 的回调在异步调度线程执行） */
    public LoginOutcome login(UUID uuid, String password, String ip) throws Exception {
        CompletableFuture<LoginOutcome> future = new CompletableFuture<>();
        auth.loginConfigAsync(uuid, password, ip,
                (result, kickSeconds) -> future.complete(new LoginOutcome(result, kickSeconds)));
        return future.get(30, TimeUnit.SECONDS);
    }

    /** 阻塞等待异步登录结果，只关心结果枚举 */
    public LoginResult loginBlocking(UUID uuid, String password, String ip) throws Exception {
        return login(uuid, password, ip).result();
    }

    /** 提交写盘并等待串行写队列排空（不关闭线程池，可重复调用） */
    public void flushAndAwaitDbWrites() {
        data.flushDirty();
        data.awaitPendingWrites();
    }

    @Override
    public void close() {
        Totp.setTimeSource(null);
        try {
            data.close();
        } catch (RuntimeException e) {
            plugin.getLogger().warning("failed to close test data source: " + e.getMessage());
        }
        currentPlugin = null;
        MockBukkit.unmock();
        deleteRecursively(tempDir);
    }

    /** 反射注入私有字段（测试需要覆盖 ConfigManager 的校验结果与 JavaPlugin 的运行环境） */
    public static void inject(Class<?> declaring, String fieldName, Object target, Object value) {
        try {
            Field field = declaring.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to inject test field: " + declaring.getSimpleName() + "." + fieldName, e);
        }
    }

    /** 读取私有集合字段的元素个数（用于断言内存状态收敛，如 pending2fa / dirty） */
    public static int collectionSize(Object target, String fieldName) {
        return ((Collection<?>) readField(target, fieldName)).size();
    }

    /** 读取私有 Map 字段的元素个数（用于断言会话/临时状态无泄漏） */
    public static int mapSize(Object target, String fieldName) {
        return ((Map<?, ?>) readField(target, fieldName)).size();
    }

    private static Object readField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to read test field: " + target.getClass().getSimpleName() + "." + fieldName, e);
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 临时文件被占用时忽略：不影响测试结论
                }
            });
        } catch (IOException ignored) {
            // 目录遍历失败时忽略
        }
    }

    /**
     * 补 GlobalRegionScheduler / RegionScheduler / isOwnedByCurrentRegion：
     * 生产代码在异步线程触发同步事件时经 GlobalRegionScheduler 转调度，
     * 末影珍珠返还走 RegionScheduler 延迟生成实体，吸收珍珠时经 isOwnedByCurrentRegion 决定是否直接移除；
     * 三者 MockBukkit 默认均抛 UnimplementedOperationException。
     * 抑制项来自 MockBukkit 的 ServerMock#getBanList 原始类型签名。
     */
    @SuppressWarnings({"unchecked", "RedundantSuppression"})
    private static final class TestServerMock extends ServerMock {
        private final TestGlobalScheduler globalScheduler = new TestGlobalScheduler();
        private final TestRegionScheduler regionScheduler = new TestRegionScheduler();
        private final TestAsyncScheduler asyncScheduler = new TestAsyncScheduler(super.getAsyncScheduler());

        @Override
        @NotNull
        public GlobalRegionScheduler getGlobalRegionScheduler() {
            return globalScheduler;
        }

        @Override
        @NotNull
        public RegionScheduler getRegionScheduler() {
            return regionScheduler;
        }

        @Override
        @NotNull
        public AsyncScheduler getAsyncScheduler() {
            return asyncScheduler;
        }

        @Override
        public boolean isOwnedByCurrentRegion(@NotNull Location location) {
            // 测试在单一主线程环境下运行：所有区域都归属当前线程，让 removePearl 走直接移除分支
            return true;
        }
    }

    /**
     * 补 AsyncScheduler#cancel：真实 MockBukkit 的 PaperScheduledTask.cancel 抛 UnimplementedOperationException，
     * 使守卫任务的 cancel（PendingPearlManager.shutdown 取消清理/落盘任务）失败而连带 shutdown 崩掉；
     * 这里委托真实调度器执行任务体，仅把返回的取消句柄替换为安全实现，保留原有调度时序。
     */
    private static final class TestAsyncScheduler implements AsyncScheduler {
        private final AsyncScheduler delegate;

        private TestAsyncScheduler(AsyncScheduler delegate) {
            this.delegate = delegate;
        }

        @Override
        @NotNull
        public ScheduledTask runNow(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task) {
            return new CancelSafeTask(delegate.runNow(plugin, task));
        }

        @Override
        @NotNull
        public ScheduledTask runDelayed(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task,
                                        long delay, @NotNull TimeUnit unit) {
            return new CancelSafeTask(delegate.runDelayed(plugin, task, delay, unit));
        }

        @Override
        @NotNull
        public ScheduledTask runAtFixedRate(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task,
                                            long initialDelay, long period, @NotNull TimeUnit unit) {
            return new CancelSafeTask(delegate.runAtFixedRate(plugin, task, initialDelay, period, unit));
        }

        @Override
        public void cancelTasks(@NotNull Plugin plugin) {
            delegate.cancelTasks(plugin);
        }
    }

    /** 包装真实任务句柄：取消退化为安全空操作（MockBukkit 未实现 cancel），其余状态透传 */
    private static final class CancelSafeTask implements ScheduledTask {
        private final ScheduledTask delegate;

        private CancelSafeTask(ScheduledTask delegate) {
            this.delegate = delegate;
        }

        @Override
        @NotNull
        public Plugin getOwningPlugin() {
            return delegate.getOwningPlugin();
        }

        @Override
        public boolean isRepeatingTask() {
            return delegate.isRepeatingTask();
        }

        @Override
        @NotNull
        public CancelledState cancel() {
            try {
                return delegate.cancel();
            } catch (RuntimeException e) {
                return CancelledState.CANCELLED_ALREADY;
            }
        }

        @Override
        @NotNull
        public ExecutionState getExecutionState() {
            return delegate.getExecutionState();
        }
    }

    /** 同步执行的事件转发调度器：压测语义下无需真实 tick，立即跑完任务体（callEvent） */
    private static final class TestGlobalScheduler implements GlobalRegionScheduler {
        @Override
        public void execute(@NotNull Plugin plugin, @NotNull Runnable runnable) {
            runnable.run();
        }

        @Override
        @NotNull
        public ScheduledTask run(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task) {
            task.accept(TestScheduledTask.INSTANCE);
            return TestScheduledTask.INSTANCE;
        }

        @Override
        @NotNull
        public ScheduledTask runDelayed(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, long delayTicks) {
            task.accept(TestScheduledTask.INSTANCE);
            return TestScheduledTask.INSTANCE;
        }

        @Override
        @NotNull
        public ScheduledTask runAtFixedRate(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, long initialDelayTicks, long periodTicks) {
            // 周期任务仅首次执行：测试不依赖后续 tick
            task.accept(TestScheduledTask.INSTANCE);
            return TestScheduledTask.INSTANCE;
        }

        @Override
        public void cancelTasks(@NotNull Plugin plugin) {
        }
    }

    /**
     * 同步执行的区域调度器：末影珍珠返还经 Bukkit.getRegionScheduler().run 延迟生成实体，
     * MockBukkit 默认未实现；测试下立即执行任务体（含离线回填 pending 与在线 spawn 两条分支）。
     */
    private static final class TestRegionScheduler implements RegionScheduler {
        @Override
        public void execute(@NotNull Plugin plugin, @NotNull World world, int chunkX, int chunkZ, @NotNull Runnable runnable) {
            runnable.run();
        }

        @Override
        @NotNull
        public ScheduledTask run(@NotNull Plugin plugin, @NotNull World world, int chunkX, int chunkZ, @NotNull Consumer<ScheduledTask> task) {
            task.accept(TestScheduledTask.INSTANCE);
            return TestScheduledTask.INSTANCE;
        }

        @Override
        @NotNull
        public ScheduledTask runDelayed(@NotNull Plugin plugin, @NotNull World world, int chunkX, int chunkZ,
                                        @NotNull Consumer<ScheduledTask> task, long delayTicks) {
            task.accept(TestScheduledTask.INSTANCE);
            return TestScheduledTask.INSTANCE;
        }

        @Override
        @NotNull
        public ScheduledTask runAtFixedRate(@NotNull Plugin plugin, @NotNull World world, int chunkX, int chunkZ,
                                            @NotNull Consumer<ScheduledTask> task, long initialDelayTicks, long periodTicks) {
            // 周期任务仅首次执行：测试不依赖后续 tick
            task.accept(TestScheduledTask.INSTANCE);
            return TestScheduledTask.INSTANCE;
        }
    }

    /** 同步执行的实体调度器：addPasswordAsync 的回调经 player.getScheduler() 派发，测试下立即回调 */
    private static final class TestEntityScheduler implements EntityScheduler {
        @Override
        public boolean execute(@NotNull Plugin plugin, @NotNull Runnable runnable, Runnable retired, long initialDelayTicks) {
            runnable.run();
            return true;
        }

        @Override
        public ScheduledTask run(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, Runnable retired) {
            task.accept(TestScheduledTask.INSTANCE);
            return TestScheduledTask.INSTANCE;
        }

        @Override
        public ScheduledTask runDelayed(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, Runnable retired, long delayTicks) {
            task.accept(TestScheduledTask.INSTANCE);
            return TestScheduledTask.INSTANCE;
        }

        @Override
        public ScheduledTask runAtFixedRate(@NotNull Plugin plugin, @NotNull Consumer<ScheduledTask> task, Runnable retired, long initialDelayTicks, long periodTicks) {
            // 周期任务仅首次执行：测试不依赖后续 tick
            task.accept(TestScheduledTask.INSTANCE);
            return TestScheduledTask.INSTANCE;
        }
    }

    /** 仅用于满足接口返回值的占位任务，测试不依赖其状态 */
    private static final class TestScheduledTask implements ScheduledTask {
        private static final TestScheduledTask INSTANCE = new TestScheduledTask();

        @Override
        @NotNull
        public Plugin getOwningPlugin() {
            return currentPlugin;
        }

        @Override
        public boolean isRepeatingTask() {
            return false;
        }

        @Override
        @NotNull
        public CancelledState cancel() {
            return CancelledState.CANCELLED_ALREADY;
        }

        @Override
        @NotNull
        public ExecutionState getExecutionState() {
            return ExecutionState.FINISHED;
        }
    }

    /** 补 getScheduler / getEnderPearls：账号操作依赖实体调度器，珍珠模块依赖在飞珍珠列表，MockBukkit 的 PlayerMock 均未实现 */
    public static final class TestPlayerMock extends PlayerMock {
        private final TestEntityScheduler scheduler = new TestEntityScheduler();
        private final List<EnderPearl> enderPearls = new ArrayList<>();

        public TestPlayerMock(ServerMock server, String name) {
            super(server, name);
        }

        @Override
        @NotNull
        public EntityScheduler getScheduler() {
            return scheduler;
        }

        /** 测试登记一颗"在飞"的末影珍珠（生产代码只读 getEnderPearls，不会自行登记） */
        public void trackEnderPearl(EnderPearl pearl) {
            enderPearls.add(pearl);
        }

        @Override
        @NotNull
        public Collection<EnderPearl> getEnderPearls() {
            // 返回副本：生产代码用 List.copyOf 包装，避免外部修改内部跟踪列表
            return List.copyOf(enderPearls);
        }
    }

    /** 补 getScheduler：EnderPearlMock 继承的 EntityMock#getScheduler 抛异常，而吸收珍珠时会用它清理 handled 标记 */
    public static final class TestEnderPearlMock extends EnderPearlMock {
        private final TestEntityScheduler scheduler = new TestEntityScheduler();

        public TestEnderPearlMock(ServerMock server, UUID uuid) {
            super(server, uuid);
        }

        @Override
        @NotNull
        public EntityScheduler getScheduler() {
            return scheduler;
        }
    }

    /** 补 getWorldFolder：注销删档路径用它定位世界目录，MockBukkit 默认抛 UnimplementedOperationException */
    private static final class TestWorldMock extends WorldMock {
        private final File worldFolder;

        private TestWorldMock(WorldCreator creator, File worldFolder) {
            super(creator);
            this.worldFolder = worldFolder;
        }

        @Override
        @NotNull
        public File getWorldFolder() {
            return worldFolder;
        }
    }
}
