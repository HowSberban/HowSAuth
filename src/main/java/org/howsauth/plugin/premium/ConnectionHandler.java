package org.howsauth.plugin.premium;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import io.netty.channel.Channel;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.data.PlayerDataManager;

import java.security.KeyPair;
import java.util.UUID;

/**
 * 连接处理器（模块1）—— 调度 + 握手/验证的分工入口。
 * <p>
 * 监听 LoginStart / EncryptionResponse 包事件，把协议握手交给 {@link PremiumHandshake}、
 * 把验证结果的分支交给 {@link PremiumVerifier}，本类只保留：
 * <ul>
 *   <li>包事件路由；</li>
 *   <li>连接生命周期动作——进入游戏（推进服务端 state）与踢出（发送 Disconnect、关闭 channel、清理会话），
 *       这些由前两者通过回调请求本类执行；</li>
 *   <li>RSA 密钥对与每连接会话表（{@link LoginSessions}）的持有。</li>
 * </ul>
 * <p>
 * 方案：取消 LoginStart，自行发送 EncryptionRequest，验证完成后设置
 * authenticatedProfile + state=VERIFYING，让服务端 tick() 自然接管
 * （触发 PlayerLoginEvent → 发送 LoginSuccess → state=PROTOCOL_SWITCHING）。
 * AsyncPlayerPreLoginEvent 由插件手动触发（handleHello 被取消，服务端不会触发）。
 * <p>
 * 线程模型：
 * - onPacketReceive 运行在 PacketEvents IO/事件线程
 * - Mojang HTTP 在异步线程执行
 * - 状态推进切回 IO 线程（channel.eventLoop）
 * <p>
 * 仅拦截需要正版验证的连接（premium=1、开启自动验证的新玩家或升级尝试），
 * 离线玩家（premium=0 或离线确认命中）不取消 LoginStart，由服务端原生处理。
 */
public final class ConnectionHandler extends PacketListenerAbstract {

    private final HowSAuth plugin;
    private final PlayerInjector playerInjector;

    // 每连接会话状态：channel → SessionContext（由 LoginSessions 统一管理增删与清理）
    private final LoginSessions sessions = new LoginSessions();

    // 正版验证结果处理：账号迁移/密码回退/踢出的分支在此，控制动作回指本类
    private final PremiumVerifier verifier;

    // 登录加密握手：LoginStart 拦截决策与 EncryptionResponse 处理
    private final PremiumHandshake handshake;

    public ConnectionHandler(HowSAuth plugin, DataService dataService, MojangClient mojangClient,
                             PlayerInjector playerInjector, PlayerDataManager dataManager) {
        super(PacketListenerPriority.LOWEST);
        this.plugin = plugin;
        this.playerInjector = playerInjector;
        // 密钥对只交给握手使用，本类无需保留
        KeyPair rsaKeyPair = LoginFrames.generateKeyPair();
        // 控制动作仍由本类实现（涉及连接生命周期），验证器只负责决定走哪个分支
        this.verifier = new PremiumVerifier(plugin, dataService, dataManager, mojangClient,
                new PremiumVerifier.ResultHandler() {
                    @Override
                    public void proceedWithLogin(Channel channel, User user, SessionContext session,
                                                 UUID uuid, String username, String properties) {
                        ConnectionHandler.this.proceedWithLogin(channel, user, session, uuid, username, properties);
                    }

                    @Override
                    public void kick(Channel channel, User user, SessionContext session) {
                        ConnectionHandler.this.kickOnEventLoop(channel, user, session);
                    }

                    @Override
                    public void failAsyncLogin(Channel channel, User user, SessionContext session, String reason) {
                        ConnectionHandler.this.failAsyncLogin(channel, user, session, reason);
                    }
                });
        this.handshake = new PremiumHandshake(plugin, dataService, sessions, verifier,
                this::proceedWithLogin, rsaKeyPair);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Login.Client.LOGIN_START) {
            handshake.handleLoginStart(event);
        } else if (event.getPacketType() == PacketType.Login.Client.ENCRYPTION_RESPONSE) {
            handshake.handleEncryptionResponse(event);
        }
    }

    // ===== 连接生命周期动作（供 PremiumHandshake / PremiumVerifier 回调） =====

    /** 踢出连接：委托 {@link LoginFrames#kick} 发送 Disconnect 并关闭 channel，随后清理会话 */
    // EventLoop 为长生命周期资源，不应关闭；此处仅借用其事件循环调度
    @SuppressWarnings("resource")
    private void kickOnEventLoop(Channel channel, User user, SessionContext session) {
        channel.eventLoop().execute(() -> {
            if (channel.isActive()) {
                kick(channel, user, HowSAuth.legacy(I18n.get("listener.premium_unavailable")));
            } else {
                sessions.cleanup(channel);
            }
        });
    }

    /**
     * 进入游戏：异步触发 AsyncPlayerPreLoginEvent，然后设置 authenticatedProfile + state=VERIFYING，
     * 让服务端 tick() 自然调用 verifyLoginAndFinishConnectionSetup：
     *   → canPlayerLogin（触发 PlayerLoginEvent）→ state=WAITING_FOR_DUPE_DISCONNECT
     *   → finishLoginAndWaitForClient → 发送 LoginSuccess（含 UUID + 皮肤）→ state=PROTOCOL_SWITCHING
     * 客户端收到 LoginSuccess 后发送 LoginAcknowledged，服务端自然接手协议切换。
     * 正版验证成功与失败回退共用此方法。
     */
    // EventLoop 为长生命周期资源，不应关闭
    @SuppressWarnings("resource")
    private void proceedWithLogin(Channel channel, User user, SessionContext session,
                                  UUID uuid, String username, String properties) {
        playerInjector.fireAsyncPreLogin(username, uuid, session.ip()).thenAccept(kickMessage -> channel.eventLoop().execute(() -> {
            if (!channel.isActive()) {
                sessions.cleanup(channel);
                return;
            }
            if (kickMessage != null) {
                // 被 KICK：经加密通道发送 Disconnect（含其他插件设置的理由）并兜底关闭
                kick(channel, user, kickMessage);
                return;
            }
            try {
                playerInjector.setProfileAndAdvanceState(channel, uuid, username, properties);
            } catch (Exception e) {
                plugin.getLogger().severe(I18n.get("log.premium_state_advance_failed", e.toString()));
                kick(channel, user, HowSAuth.legacy(I18n.get("listener.premium_unavailable")));
            } finally {
                sessions.cleanup(channel);
            }
        }));
    }

    /** 异步验证失败兜底：回退升级标记、记日志、踢出连接（连接已断则仅清会话）。
     *  同步异常与 future 异常完成（Error）两条路径共用，避免会话残留或连接悬挂 */
    // EventLoop 为长生命周期资源，不应关闭；此处仅借用其事件循环调度
    @SuppressWarnings("resource")
    private void failAsyncLogin(Channel channel, User user, SessionContext session, String reason) {
        if (session.isUpgradeAttempt()) {
            plugin.accounts().clearUpgradePending(session.offlineUuid());
        }
        plugin.getLogger().severe(I18n.get("log.premium_async_failed", reason));
        channel.eventLoop().execute(() -> {
            if (channel.isActive()) {
                kick(channel, user, HowSAuth.legacy(I18n.get("listener.premium_unavailable")));
            } else {
                sessions.cleanup(channel);
            }
        });
    }

    /**
     * 踢出连接：委托 {@link LoginFrames#kick} 发送 Disconnect 并关闭 channel，
     * 随后清理会话（由 {@link LoginSessions#cleanup} 完成，幂等，重复调用安全）。
     */
    private void kick(Channel channel, User user, net.kyori.adventure.text.Component message) {
        LoginFrames.kick(channel, user, message);
        sessions.cleanup(channel);
    }
}