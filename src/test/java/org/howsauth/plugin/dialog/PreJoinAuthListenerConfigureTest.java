package org.howsauth.plugin.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.ClientOption;
import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.ServerLinks;
import org.howsauth.plugin.dialog.PreJoinAuthListener.AuthOutcome;
import org.howsauth.plugin.support.MockBukkitHarness;
import org.howsauth.plugin.support.MockBukkitHarness.TestPlayerMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 配置阶段认证（{@link PreJoinAuthListener}）的入口决策回归。
 * <p>
 * {@code onConfigure} 决定"这次连接是否被接纳、是否要复用上次的认证结果"，是登录链路上最先拿主意的一环，
 * 此前零测试覆盖。测试直接构造 {@link AsyncPlayerConnectionConfigureEvent}（其构造函数为 public）并调用
 * {@code onConfigure}，配一个最小的 {@link PlayerConfigurationConnection} 实现观察 {@code disconnect}
 * 是否发生——不需要事件总线，也不需要真实客户端。
 * <p>
 * <b>刻意只覆盖"早退"分支</b>：这些分支只依赖配置、名字认领表与会话/2FA 状态，不进入 Dialog 窗口，
 * 因此不会引入线程等待导致的时序漂移。走到 Dialog 阻塞窗口的分支需要真实弹窗回调才能推进，不在本测试范围。
 * <p>
 * 重点锁住两处注释里写明的安全约束：
 * <ul>
 *   <li>名字认领失败必须<b>立即返回且不触碰共享状态</b>——否则会清掉先到者正在进行的认证结果；</li>
 *   <li>认领成功后必须清理<b>上次连接残留</b>的认证结果与 2FA 待验证标记（配置阶段断开无 PlayerQuitEvent，
 *       残留的 pending2fa 会让下次连接的密码玩家跳过密码验证）。</li>
 * </ul>
 */
// AsyncPlayerConnectionConfigureEvent 等配置阶段连接 API 标注为 @ApiStatus.Internal：
// 本测试必须构造该事件并实现 PlayerConfigurationConnection，无法回避这些类型，故整类抑制。
@SuppressWarnings("UnstableApiUsage")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PreJoinAuthListenerConfigureTest {

    /** 最小连接实现：只回放 profile/clientOption，并记录 disconnect 调用 */
    private static final class FakeConnection implements PlayerConfigurationConnection {
        private final PlayerProfile profile;
        private final String locale;
        final List<String> disconnects = new ArrayList<>();

        FakeConnection(PlayerProfile profile) {
            this.profile = profile;
            this.locale = "zh_CN";
        }

        @Override
        public PlayerProfile getProfile() {
            return profile;
        }

        @Override
        public <T> T getClientOption(ClientOption<T> option) {
            @SuppressWarnings("unchecked")
            T value = (T) locale;
            return value;
        }

        @Override
        public void disconnect(Component reason) {
            disconnects.add(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(reason));
        }

        @Override
        public boolean isConnected() {
            return disconnects.isEmpty();
        }

        @Override
        public Audience getAudience() {
            return Audience.empty();
        }

        // ---- 以下为接口要求的其余成员，本测试不触及 ----

        @Override
        public void clearChat() {
        }

        @Override
        public void completeReconfiguration() {
        }

        @Override
        public void sendReportDetails(Map<String, String> details) {
        }

        @Override
        public void sendLinks(ServerLinks links) {
        }

        @Override
        public void transfer(String host, int port) {
        }

        @Override
        public boolean isTransferred() {
            return false;
        }

        @Override
        public SocketAddress getAddress() {
            return new InetSocketAddress("127.0.0.1", 25565);
        }

        @Override
        public InetSocketAddress getClientAddress() {
            return new InetSocketAddress("127.0.0.1", 25565);
        }

        @Override
        public InetSocketAddress getVirtualHost() {
            return null;
        }

        @Override
        public InetSocketAddress getHAProxyAddress() {
            return null;
        }

        @Override
        public CompletableFuture<byte[]> retrieveCookie(NamespacedKey key) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void storeCookie(NamespacedKey key, byte[] value) {
        }

        @Override
        public java.util.Set<String> getListeningPluginChannels() {
            return java.util.Set.of();
        }

        @Override
        public void sendPluginMessage(org.bukkit.plugin.Plugin source, String channel, byte[] message) {
        }
    }

    private MockBukkitHarness env;
    private PreJoinAuthListener listener;

    @BeforeEach
    void setUp() throws Exception {
        env = MockBukkitHarness.start("hsauth-prejoin-");
        listener = new PreJoinAuthListener(env.plugin(), env.sessions(), env.failProtection(),
                env.twoFactor(), env.accounts(), env.loginFlow(), new DialogManager(env.plugin()));
    }

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    private FakeConnection connection(String name, UUID uuid) {
        return new FakeConnection(org.bukkit.Bukkit.createProfile(uuid, name));
    }

    /** 驱动一次配置阶段事件 */
    private void configure(FakeConnection conn) {
        listener.onConfigure(new AsyncPlayerConnectionConfigureEvent(conn));
    }

    /**
     * 让流程停在"早退"分支：关闭 Dialog 后 {@code onConfigure} 会在弹窗前返回，
     * 从而能观察名字认领与状态清理这类与弹窗无关的行为，且不引入等待。
     */
    private void disableDialogs() {
        env.writeConfig(yaml -> yaml.set("login.dialog.enabled", false));
    }

    /** 反射写入私有 outcomes 映射：该字段无公开写入入口，只能在测试侧注入既有状态 */
    @SuppressWarnings("unchecked")
    private void seedOutcome(UUID uuid, AuthOutcome outcome) {
        try {
            Field f = PreJoinAuthListener.class.getDeclaredField("outcomes");
            f.setAccessible(true);
            ((Map<UUID, AuthOutcome>) f.get(listener)).put(uuid, outcome);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to seed pre-join outcome", e);
        }
    }

    // ---------- 名字认领 ----------

    /** 同名第二个连接被拒绝并断开；先到者不受影响 */
    @Test
    void duplicateNameIsRejectedAndDisconnected() {
        disableDialogs();
        FakeConnection first = connection("Steve", UUID.randomUUID());
        FakeConnection second = connection("Steve", UUID.randomUUID());

        configure(first);
        assertTrue(first.disconnects.isEmpty(), "the first connection must not be disconnected");

        configure(second);

        assertEquals(1, second.disconnects.size(),
                "the second connection with the same name must be disconnected");
        assertFalse(second.disconnects.getFirst().isBlank(),
                "the disconnect reason must be a non-empty message");
    }

    /**
     * 核心安全约束：认领失败时必须<b>不触碰共享状态</b>——
     * 先到者的认证结果不得被后来者的失败认领清掉。
     */
    @Test
    void rejectedDuplicateDoesNotClearTheFirstConnectionsOutcome() {
        disableDialogs();
        UUID firstUuid = UUID.randomUUID();
        FakeConnection first = connection("Alex", firstUuid);
        FakeConnection second = connection("Alex", UUID.randomUUID());

        configure(first);
        seedOutcome(firstUuid, AuthOutcome.LOGIN);
        assertTrue(listener.hasCompleted(firstUuid), "precondition: the first connection has a recorded outcome");

        configure(second);

        assertEquals(1, second.disconnects.size(), "the later duplicate must be disconnected");
        assertTrue(listener.hasCompleted(firstUuid),
                "a rejected duplicate must not clear the outcome of the connection that owns the claim");
    }

    /** 被拒绝的连接不得顺带清掉自己的残留 2FA 状态（同一约束的另一面：失败认领应整体早退） */
    @Test
    void rejectedDuplicateLeavesPendingTwoFactorUntouched() {
        disableDialogs();
        configure(connection("Owner", UUID.randomUUID()));

        UUID otherUuid = UUID.randomUUID();
        env.twoFactor().markPending(otherUuid);
        configure(connection("Owner", otherUuid));

        assertTrue(env.twoFactor().isPending(otherUuid),
                "a rejected duplicate must not run state cleanup at all");
    }

    /** 认领在释放之前不可被同名的另一连接抢走；释放后可以重新认领 */
    @Test
    void claimCanBeReusedOnlyAfterRelease() {
        disableDialogs();
        configure(connection("Herobrine", UUID.randomUUID()));

        FakeConnection retry = connection("Herobrine", UUID.randomUUID());
        configure(retry);
        assertEquals(1, retry.disconnects.size(), "the name must stay claimed while held");

        listener.releaseClaim("Herobrine");

        FakeConnection afterRelease = connection("Herobrine", UUID.randomUUID());
        configure(afterRelease);
        assertTrue(afterRelease.disconnects.isEmpty(),
                "after release the name must be claimable again");
    }

    // ---------- 残留状态清理 ----------

    /**
     * 新连接按 UUID 清掉上次残留的认证结果：配置阶段断开不触发 PlayerQuitEvent，
     * 残留结果会让新连接被误判为"已完成认证"。
     */
    @Test
    void staleOutcomeForTheSameUuidIsClearedOnReconnect() {
        disableDialogs();
        UUID uuid = UUID.randomUUID();
        seedOutcome(uuid, AuthOutcome.LOGIN);
        assertTrue(listener.hasCompleted(uuid), "precondition: a stale outcome exists");

        configure(connection("CleanUser", uuid));

        assertFalse(listener.hasCompleted(uuid),
                "a reconnect must clear the outcome left by the previous connection");
    }

    /** 新连接同时清掉残留的 2FA 待验证标记（否则密码玩家可凭旧状态直接 /2fa 跳过密码） */
    @Test
    void stalePendingTwoFactorIsClearedOnReconnect() {
        disableDialogs();
        UUID uuid = UUID.randomUUID();
        env.twoFactor().markPending(uuid);
        assertTrue(env.twoFactor().isPending(uuid), "precondition: a pending 2FA state exists");

        configure(connection("TwoFaUser", uuid));

        assertFalse(env.twoFactor().isPending(uuid),
                "a reconnect must clear the pending 2FA state left by the previous connection");
    }

    // ---------- 结果消费 ----------

    /** consume 取走结果（同一 UUID 只能取一次），hasCompleted 只查询不消费 */
    @Test
    void consumeReturnsTheOutcomeOnceAndHasCompletedDoesNotConsume() {
        UUID uuid = UUID.randomUUID();
        seedOutcome(uuid, AuthOutcome.REGISTER);
        TestPlayerMock player = new TestPlayerMock(env.server(), "OutcomeUser");
        // 玩家的 UUID 由 MockBukkit 生成，这里按实际 UUID 重新播种，保证 consume 命中
        seedOutcome(player.getUniqueId(), AuthOutcome.LOGIN);

        assertTrue(listener.hasCompleted(player.getUniqueId()),
                "hasCompleted must report the pending outcome");
        assertTrue(listener.hasCompleted(player.getUniqueId()),
                "hasCompleted must not consume the outcome");

        assertEquals(AuthOutcome.LOGIN, listener.consume(player),
                "the first consume must return the outcome for that player");
        assertNull(listener.consume(player), "the outcome must not be returned twice");
        assertFalse(listener.hasCompleted(player.getUniqueId()),
                "after consuming there must be nothing left");
    }
}
