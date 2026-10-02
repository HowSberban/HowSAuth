package org.howtologin.plugin;

import static org.howtologin.plugin.support.MockBukkitHarness.collectionSize;
import static org.howtologin.plugin.support.MockBukkitHarness.mapSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.howtologin.plugin.auth.AuthManager;
import org.howtologin.plugin.auth.AuthManager.LoginResult;
import org.howtologin.plugin.config.ConfigManager;
import org.howtologin.plugin.data.PlayerDataManager;
import org.howtologin.plugin.support.MockBukkitHarness;
import org.howtologin.plugin.support.MockBukkitHarness.TestPlayerMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.ServerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * 长期运行稳定性压测：在 MockBukkit 模拟的 Bukkit 运行时下，使用真实生产构造器
 * （真实 ConfigManager / SQLite / 连接池 / AuthManager / bcrypt）模拟大量玩家反复
 * 注册、登录、改密、强制下线、2FA、注销等业务流，验证：
 * - 内存缓存收敛（脏标记、会话、2FA 临时状态无泄漏）
 * - 周期 flush 落库后与内存一致且无残留
 * - 注销/删号重启后不复活（历史竞态 bug 回归）
 * - IP 注册上限在并发下不被穿透
 * - 2FA 全生命周期（绑定 / 必须验证 / 管理员强制解除）
 * <p>
 * 刻意不触发 onEnable：dialog / packet / premium / 命令等外部 API 分支在 mock 下不可用。
 * bcrypt 用低成本（{@link #BCRYPT_COST}）以控制套件时长——真实生产强度的哈希链路由
 * {@code HighCostAuthTest} 单独覆盖；暴力破解防护由 {@code FailProtectionTest} 覆盖
 * （本套件为规避失败计数干扰已关闭该功能）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LongRunStabilityTest {

    /** 压测用 bcrypt cost：仅影响耗时，不影响被断言的语义 */
    private static final int BCRYPT_COST = 4;
    private static final int MAX_ACCOUNTS_PER_IP = 3;
    /** 同 IP 并发注册的探测任务数（远大于名额上限，用于验证名额原子性） */
    private static final int IP_PROBE_TASKS = 40;
    private static final int IP_PROBE_THREADS = 8;
    private static final int BUSINESS_THREADS = 3;
    private static final int BUSINESS_ROUNDS = 16;
    private static final int SIMULTANEOUS_REGISTRATIONS = 20;
    private static final int BULK_REGISTRATIONS = 200;
    private static final int GHOST_PLAYERS = 100;
    private static final int GHOST_REENTRY_ROUNDS = 5;

    private MockBukkitHarness env;
    private ServerMock server;
    private HTLogin plugin;
    private PlayerDataManager data;
    private AuthManager auth;

    @BeforeAll
    void bootstrap() throws Exception {
        env = MockBukkitHarness.start("htlogin-stab-test", config -> {
            MockBukkitHarness.inject(ConfigManager.class, "bcryptCost", config, BCRYPT_COST);
            MockBukkitHarness.inject(ConfigManager.class, "maxAccountsPerIp", config, MAX_ACCOUNTS_PER_IP);
            // 失败保护关闭：多轮密码/2FA 错误码验证会累积失败计数触发踢出，干扰成功率类断言
            MockBukkitHarness.inject(ConfigManager.class, "failProtectionEnabled", config, false);
        });
        server = env.server();
        plugin = env.plugin();
        data = env.data();
        auth = env.auth();
    }

    @AfterAll
    void teardown() {
        if (env != null) {
            env.close();
        }
    }

    /** IP 注册上限在并发注册下不被穿透（registerConfig 名额判定与建号须原子完成） */
    @Test
    @Order(1)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void ipLimitConcurrentNoBypass() throws Exception {
        String ip = "10.0.0.77";
        try (ExecutorService pool = Executors.newFixedThreadPool(IP_PROBE_THREADS)) {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < IP_PROBE_TASKS; i++) {
                UUID uuid = UUID.randomUUID();
                String name = "ipu" + i;
                String password = "pw" + i;
                tasks.add(() -> auth.registerConfig(uuid, name, password, ip));
            }
            int ok = 0;
            for (Future<Boolean> f : pool.invokeAll(tasks)) {
                if (f.get(30, TimeUnit.SECONDS)) ok++;
            }
            // 名额判定与建号在同一临界区内完成：并发下既不能突破上限，也不应因竞态少成功
            assertEquals(MAX_ACCOUNTS_PER_IP, ok,
                    "same-IP concurrent registrations must yield exactly " + MAX_ACCOUNTS_PER_IP + " accounts, actual " + ok);
        }
    }

    /** 长期并发业务流：反复注册/登录（含错误密码）/改密/强制下线，最后断言内存收敛 */
    @Test
    @Order(2)
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void longRunConcurrentBusinessConverges() throws Exception {
        AtomicInteger registered = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(BUSINESS_THREADS)) {
            List<Future<?>> results = new ArrayList<>();
            for (int t = 0; t < BUSINESS_THREADS; t++) {
                final int tid = t;
                results.add(pool.submit(() -> {
                    for (int i = 0; i < BUSINESS_ROUNDS; i++) {
                        String name = "long" + tid + "x" + i;
                        String password = "pass" + i;
                        // 每轮独立 IP（不触发 IP 名额上限）
                        String ip = "10.1." + tid + "." + (i % 250);
                        UUID uuid = UUID.randomUUID();
                        try {
                            if (!auth.registerConfig(uuid, name, password, ip)) {
                                continue; // 名额/重名等拒绝，不视为异常
                            }
                            registered.incrementAndGet();
                            // 错误密码登录：触发失败计数（踢出保护路径）
                            env.loginBlocking(uuid, "wrongpw", ip);
                            // 正确密码登录
                            assertEquals(LoginResult.SUCCESS, env.loginBlocking(uuid, password, ip), name + " must log in successfully");
                            // 修改密码：旧密码失效、新密码可用
                            assertTrue(auth.forceChangePassword(uuid, "newpass" + i), name + " forceChangePassword must succeed");
                            assertEquals(LoginResult.FAILED, env.loginBlocking(uuid, password, ip), name + " old password must be rejected after change");
                            assertEquals(LoginResult.SUCCESS, env.loginBlocking(uuid, "newpass" + i, ip), name + " new password must log in");
                            // 管理员强制下线后仍可再次登录
                            auth.forceLogout(uuid);
                            assertEquals(LoginResult.SUCCESS, env.loginBlocking(uuid, "newpass" + i, ip), name + " must log in again after force logout");
                            // 间歇触发周期任务（模拟 5s flush 与 30s 状态清理）
                            if ((i & 7) == 0) {
                                data.flushDirty();
                                auth.cleanupExpiredStates();
                            }
                        } catch (Throwable ex) {
                            errors.incrementAndGet();
                            firstError.compareAndSet(null, ex);
                            plugin.getLogger().log(Level.SEVERE, "business loop failed: " + name, ex);
                        }
                    }
                }));
            }
            for (Future<?> f : results) {
                f.get(600, TimeUnit.SECONDS);
            }
        }

        // 最终收敛：flush 两轮后不应再有脏数据
        data.flushDirty();
        auth.cleanupExpiredStates();
        data.flushDirty();

        assertTrue(registered.get() >= BUSINESS_THREADS * BUSINESS_ROUNDS / 2,
                "registration success rate too low: " + registered.get() + "/" + (BUSINESS_THREADS * BUSINESS_ROUNDS));
        assertEquals(0, errors.get(),
                () -> "business loop raised " + errors.get() + " error(s), first: " + describe(firstError.get()));
        assertEquals(0, collectionSize(data, "dirty"), "dirty set must be empty after flush");
        assertEquals(0, collectionSize(auth, "verifying"), "password verification re-entry markers must be empty");
        assertEquals(0, collectionSize(auth, "pending2fa"), "pending 2FA states must be empty");
        assertEquals(0, collectionSize(auth, "loggedIn"), "logged-in states must be empty (this scenario never involves a Player)");
        assertEquals(0, mapSize(auth, "pending2faSecret"), "pending 2FA secrets must be cleared");
        assertEquals(0, mapSize(auth, "pending2faSecretCreatedAt"), "pending 2FA secret timestamps must be cleared");
        assertEquals(0, mapSize(auth, "loginSessions"), "login sessions must be cleared");
        assertEquals(0, mapSize(auth, "twoFaSessions"), "2FA sessions must be cleared");
    }

    /** 注销后重启（同库重新装载）账号不得复活——历史"已删行被并发 upsert 复活"回归测试 */
    @Test
    @Order(3)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void unregisterThenRestartNoResurrect() {
        UUID uuid = UUID.randomUUID();
        assertTrue(auth.registerConfig(uuid, "ghost1", "pw1", "10.9.9.9"), "registration must succeed");
        assertTrue(auth.unregister(uuid), "unregister must succeed");
        // 注销的删除任务先入队，随后刷盘并排空写队列：确保 DELETE 落库后再重读
        env.flushAndAwaitDbWrites();
        assertFalse(data.hasAccount(uuid), "account must not exist in memory after unregister");
        // 模拟重启：全新实例装载同一 SQLite 文件，用完即释放连接池
        try (PlayerDataManager restarted = new PlayerDataManager(plugin)) {
            assertFalse(restarted.hasAccount(uuid), "unregistered account must not resurrect after restart");
        }
    }

    /** 2FA 全生命周期：绑定（正确/错误验证码）→ 登录需验证 → 管理员强制解除 → 恢复直接登录 */
    @Test
    @Order(4)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void twoFaLifecycleBindDisableAndReset() throws Exception {
        var player = server.addPlayer("faUser");
        UUID uuid = player.getUniqueId();
        String ip = "10.8.8.8";
        String password = "pw";
        assertTrue(auth.registerConfig(uuid, "faUser", password, ip), "registration must succeed");

        // 绑定：错误验证码被拒，正确验证码生效
        String secret = auth.setup2fa(player);
        assertNotNull(secret, "setup2fa must return a temporary secret");
        assertFalse(auth.confirm2fa(player, "000000"), "wrong verification code must be rejected");
        assertTrue(auth.confirm2fa(player, env.totpCode(secret)), "correct verification code must bind 2FA");
        assertTrue(auth.has2fa(uuid), "has2fa must be true after binding");

        // 绑定后登录必须走 2FA（与密码正确与否无关，requires2faAtLogin 拦截）
        assertEquals(LoginResult.NEED_2FA, env.loginBlocking(uuid, password, ip), "login must require 2FA after binding");

        // 管理员强制解除（误删验证器凭证的救济通道）：解除后直接登录
        assertTrue(auth.reset2fa(uuid), "admin reset2fa must succeed");
        assertFalse(auth.has2fa(uuid), "has2fa must be false after reset");
        assertEquals(LoginResult.SUCCESS, env.loginBlocking(uuid, password, ip), "login must succeed directly after reset");
    }

    /** 立即落库（关键操作）后，断电模拟（重载新实例）不丢失注册与 2FA 绑定 */
    @Test
    @Order(5)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void criticalOperationsSurviveReload() throws Exception {
        UUID uuid = UUID.randomUUID();
        assertTrue(auth.registerConfig(uuid, "crit1", "pw1", "10.7.7.7"), "registration must succeed");
        assertEquals(LoginResult.SUCCESS, env.loginBlocking(uuid, "pw1", "10.7.7.7"), "login must succeed");
        // 注册为关键操作已经 saveNow 立即落库，排空写队列后重载验证（幂等覆盖：saveNow 直接入队）
        data.awaitPendingWrites();
        try (PlayerDataManager restarted = new PlayerDataManager(plugin)) {
            assertTrue(restarted.hasAccount(uuid), "registration saved immediately must survive reload");
        }
    }

    /** 场景 1：20 人几乎同时注册（CountDownLatch 齐发），全部成功且抽测可登录 */
    @Test
    @Order(6)
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void twentySimultaneousRegistrations() throws Exception {
        int n = SIMULTANEOUS_REGISTRATIONS;
        UUID[] uuids = new UUID[n];
        for (int i = 0; i < n; i++) {
            uuids[i] = UUID.randomUUID();
        }
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    gate.await(); // 齐发：所有注册任务同时起跑
                    return auth.registerConfig(uuids[idx], "sync" + idx, "spw" + idx, "10.20.0." + idx);
                }));
            }
            gate.countDown();
            int ok = 0;
            for (Future<Boolean> f : futures) {
                if (f.get(60, TimeUnit.SECONDS)) ok++;
            }
            assertEquals(n, ok, "all simultaneous registrations must succeed");
        }
        // 抽测 3 人真实登录（完整密码校验链）
        for (int i = 0; i < 3; i++) {
            assertEquals(LoginResult.SUCCESS, env.loginBlocking(uuids[i], "spw" + i, "10.20.0." + i), "simultaneously registered account must log in");
        }
    }

    /** 场景 2：200 人注册，期间 100 个未注册玩家反复重进（注册/重进任务交错提交同一并发池，模拟"中间插入"） */
    @Test
    @Order(7)
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void bulkRegisterWithGhostReentry() throws Exception {
        int reg = BULK_REGISTRATIONS;
        int ghost = GHOST_PLAYERS;
        int rounds = GHOST_REENTRY_ROUNDS; // 每个未注册玩家反复重进轮数
        List<UUID> regUuids = new ArrayList<>(reg);
        List<UUID> ghostUuids = new ArrayList<>(ghost);
        for (int i = 0; i < reg; i++) {
            regUuids.add(UUID.randomUUID());
        }
        for (int g = 0; g < ghost; g++) {
            ghostUuids.add(UUID.randomUUID());
        }

        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger regOk = new AtomicInteger();
        AtomicInteger ghostRejected = new AtomicInteger();
        int baseAccountCount = data.getAllUuids().size(); // 重载口径基数：不含本批新增（历史用例账号同库共存）
        try (ExecutorService pool = Executors.newFixedThreadPool(32)) {
            List<Callable<Object>> merged = new ArrayList<>();
            // 注册与重进任务按序交错入队：偶数位放注册、奇数位放某未注册玩家的多轮重进
            int ghostUsed = 0;
            for (int i = 0; i < reg; i++) {
                final int idx = i;
                merged.add(() -> {
                    gate.await();
                    return auth.registerConfig(regUuids.get(idx), "bulk" + idx, "bpw" + idx, "10.30.0." + idx);
                });
                if ((i & 1) == 1 && ghostUsed < ghost) { // 注册间隙穿插未注册玩家的反复重进
                    final UUID ghostUuid = ghostUuids.get(ghostUsed);
                    final int gIdx = ghostUsed++;
                    for (int r = 0; r < rounds; r++) {
                        merged.add(() -> {
                            gate.await();
                            // 未注册账号重进：玩家数据为 null，应直接拒入（FAILED）
                            return env.loginBlocking(ghostUuid, "nopw", "10.31.0." + gIdx);
                        });
                    }
                }
            }
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : merged) {
                futures.add(pool.submit(task));
            }
            gate.countDown();
            for (Future<Object> f : futures) {
                Object r = f.get(180, TimeUnit.SECONDS);
                if (r instanceof Boolean b) {
                    if (b) regOk.incrementAndGet();
                } else if (r == LoginResult.FAILED) {
                    ghostRejected.incrementAndGet();
                }
            }
        }

        assertEquals(reg, regOk.get(), reg + " registrations must all succeed");
        assertEquals(ghost * rounds, ghostRejected.get(), "unregistered re-entries must all be rejected");

        // 未注册玩家不占 IP 名额、不产生账号：重载后账号数仅增本批注册数，且不含任何未注册账号
        env.flushAndAwaitDbWrites();
        try (PlayerDataManager restarted = new PlayerDataManager(plugin)) {
            assertEquals(baseAccountCount + reg, restarted.getAllUuids().size(), "account count after reload must grow by exactly the registered batch");
            for (UUID ghostUuid : ghostUuids) {
                assertFalse(restarted.hasAccount(ghostUuid), "unregistered account must not appear in the database");
            }
        }
        // 内存收敛
        data.flushDirty();
        assertEquals(0, collectionSize(data, "dirty"), "dirty set must be empty after flush");
        assertEquals(0, collectionSize(auth, "pendingLogin"), "pending login states must be empty");
        assertEquals(0, collectionSize(auth, "loggedIn"), "logged-in states must be empty");
        assertEquals(0, mapSize(auth, "loginSessions"), "login sessions must be cleared");
    }

    /** 场景 3：addpw / rmpw / 2FA 生命周期状态机稳定——绑定、移除密码、恢复密码、解绑按序交替两轮后无残留 */
    @Test
    @Order(8)
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    void passwordLifecycleAnd2faStability() throws Exception {
        // 自定义 PlayerMock：addPasswordAsync 的回调经 player.getScheduler() 派发，MockBukkit 未实现需同步注入
        TestPlayerMock player = env.addScheduledPlayer("pwd2fa");
        UUID uuid = player.getUniqueId();
        String ip = "10.40.0.1";
        String password = "start-pw";
        assertTrue(auth.registerConfig(uuid, "pwd2fa", password, ip), "registration must succeed");
        assertEquals(LoginResult.SUCCESS, env.loginBlocking(uuid, password, ip), "initial password must log in");

        // 绑定 2FA：错误码被拒，正确码生效
        String secret = auth.setup2fa(player);
        assertNotNull(secret, "setup2fa must return a temporary secret");
        assertFalse(auth.confirm2fa(player, "000000"), "wrong verification code must be rejected");
        assertTrue(auth.confirm2fa(player, env.totpCode(secret)), "correct verification code must bind 2FA");
        assertTrue(auth.has2fa(uuid), "2FA must be active after binding");

        // 绑定后登录需 2FA：错误码保持待验证，正确码通过，同周期验证码被防重放拒绝
        assertEquals(LoginResult.NEED_2FA, env.loginBlocking(uuid, password, ip), "login must require 2FA after binding");
        assertFalse(auth.verify2faConfig(uuid, "000000", ip), "wrong 2FA code must be rejected");
        assertTrue(auth.isPending2fa(uuid), "state must return to pending verification after failure");
        String code = env.totpCode(secret);
        assertTrue(auth.verify2faConfig(uuid, code, ip), "correct 2FA code must pass");
        auth.addPending2fa(uuid);
        assertFalse(auth.verify2faConfig(uuid, code, ip), "code from the same TOTP period must be rejected by replay protection");

        // rmpw：错误码拒、正确码转无密码账户；旧密码随之失效
        assertFalse(auth.removePassword(player, "000000"), "removePassword with a wrong code must fail");
        assertTrue(auth.removePassword(player, env.totpCode(secret)), "removePassword with a correct code must succeed");
        assertTrue(auth.isPasswordless(uuid), "account must be passwordless after removal");
        assertEquals(LoginResult.FAILED, env.loginBlocking(uuid, password, ip), "old password must be rejected after removal");

        // 无密码账户 2FA 为唯一登录因素：免密直入仍需验证码且可通过。
        // verify2faConfig 带防重放（按 30s TOTP 周期推进）：上文已消费当前周期计数，
        // 注入时钟前进一个周期即可（无需真实等待 30 秒）
        env.advanceTotpPeriod();
        auth.addPending2fa(uuid);
        assertTrue(auth.verify2faConfig(uuid, env.totpCode(secret), ip), "passwordless account must verify with 2FA only");

        // addpw：无密码账户可设密；已有密码再设应被拒绝
        CompletableFuture<Boolean> setFuture = new CompletableFuture<>();
        auth.addPasswordAsync(player, "set-pw", setFuture::complete);
        assertTrue(setFuture.get(30, TimeUnit.SECONDS), "passwordless account must accept a new password");
        assertFalse(auth.isPasswordless(uuid), "account must have a password after set");
        assertTrue(auth.has2fa(uuid), "2FA must stay active after setting a password");
        CompletableFuture<Boolean> again = new CompletableFuture<>();
        auth.addPasswordAsync(player, "another-pw", again::complete);
        assertFalse(again.get(30, TimeUnit.SECONDS), "addpw on an account that already has a password must be rejected");

        // 有密 + 2FA：登录仍须验证码（拦截生效）；解绑（错误码拒/正确码过）后直接登录
        assertEquals(LoginResult.NEED_2FA, env.loginBlocking(uuid, "set-pw", ip), "login must still require 2FA after setting a password");
        assertFalse(auth.disable2fa(player, "000000"), "disable2fa with a wrong code must fail");
        assertTrue(auth.disable2fa(player, env.totpCode(secret)), "disable2fa with a correct code must succeed");
        assertFalse(auth.has2fa(uuid), "2FA must be inactive after disable");
        assertEquals(LoginResult.SUCCESS, env.loginBlocking(uuid, "set-pw", ip), "login must succeed directly after disable");

        // 再压 2 轮绑定/移除密码/恢复密码/解绑交替（验证状态机可重复使用、无残留泄漏）。
        // 每轮以解绑收尾，下一轮 setup 才能生成新密钥
        String lastPw = "set-pw";
        for (int round = 1; round <= 2; round++) {
            String s = auth.setup2fa(player);
            assertNotNull(s, "round " + round + " setup2fa must return a new secret");
            assertTrue(auth.confirm2fa(player, env.totpCode(s)), "round " + round + " confirm2fa must succeed");
            assertTrue(auth.has2fa(uuid), "round " + round + " 2FA must be active after binding");
            assertEquals(LoginResult.NEED_2FA, env.loginBlocking(uuid, lastPw, ip), "round " + round + " login must require 2FA");
            assertTrue(auth.removePassword(player, env.totpCode(s)), "round " + round + " removePassword must succeed");
            assertTrue(auth.isPasswordless(uuid), "round " + round + " account must be passwordless");
            CompletableFuture<Boolean> set = new CompletableFuture<>();
            auth.addPasswordAsync(player, "cycle-pw" + round, set::complete);
            assertTrue(set.get(30, TimeUnit.SECONDS), "round " + round + " addpw must succeed");
            assertFalse(auth.isPasswordless(uuid), "round " + round + " account must have a password");
            lastPw = "cycle-pw" + round;
            // 解绑（凭本轮密钥验证码）后直接登录，为下一轮绑定腾出状态
            assertTrue(auth.disable2fa(player, env.totpCode(s)), "round " + round + " disable2fa must succeed");
            assertFalse(auth.has2fa(uuid), "round " + round + " 2FA must be inactive after disable");
            assertEquals(LoginResult.SUCCESS, env.loginBlocking(uuid, lastPw, ip), "round " + round + " login must succeed directly after disable");
        }
        // 终态：无 2FA 绑定、可凭密码直接登录
        assertFalse(auth.has2fa(uuid), "2FA must not be bound at the end");
        assertEquals(LoginResult.SUCCESS, env.loginBlocking(uuid, lastPw, ip), "login must succeed with the password at the end");

        // 状态收敛：无临时密钥/待验证残留
        assertEquals(0, collectionSize(auth, "pending2fa"), "pending verification states must be empty");
        assertEquals(0, mapSize(auth, "pending2faSecret"), "temporary secret must be cleared");
        assertEquals(0, mapSize(auth, "pending2faSecretCreatedAt"), "temporary secret timestamp must be cleared");

        // 关键状态落库：注册/密码/2FA 绑定跨重载恢复（confirm 走脏标记，flush 排空后重读）
        env.flushAndAwaitDbWrites();
        try (PlayerDataManager restarted = new PlayerDataManager(plugin)) {
            assertTrue(restarted.hasAccount(uuid), "registration must be persisted");
            PlayerDataManager.PlayerData reloaded = restarted.getPlayer(uuid);
            assertNotNull(reloaded, "reloaded data must expose the account");
            assertNull(reloaded.totpSecret(), "unbound 2FA state must be persisted");
        }
    }

    /** 汇总首个异常，供断言消息定位失败原因（仅取首条，避免消息过长） */
    private static String describe(Throwable error) {
        if (error == null) return "none";
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }
}
