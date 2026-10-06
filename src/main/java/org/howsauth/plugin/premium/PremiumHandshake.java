package org.howsauth.plugin.premium;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientEncryptionResponse;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerEncryptionRequest;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.howsauth.plugin.OfflineUuids;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.config.ConfigManager;

import javax.crypto.Cipher;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 登录阶段的加密握手：LoginStart 拦截决策与 EncryptionResponse 处理。
 * <p>
 * 只做协议与握手状态机：取消包、生成验证令牌、发 EncryptionRequest、安装断开检测器、
 * 调度超时清理、RSA 解密、校验令牌、启用 AES。验证结果的业务分支交给
 * {@link PremiumVerifier}，控制动作（踢出/进入游戏/清理）由 {@link ConnectionHandler} 提供。
 */
final class PremiumHandshake {

    /** ConnectionHandler 提供的控制动作；实现方掌握会话与连接生命周期 */
    interface LoginHandler {
        /** 进入游戏（异步触发 AsyncPlayerPreLoginEvent 后推进 state） */
        void proceedWithLogin(Channel channel, User user, SessionContext session,
                              UUID uuid, String username, String properties);
    }

    private final HowSAuth plugin;
    private final DataService dataService;
    private final LoginSessions sessions;
    private final PremiumVerifier verifier;
    private final LoginHandler loginHandler;
    private final KeyPair rsaKeyPair;
    private final byte[] publicKeyEncoded;

    // 验证令牌随机数生成器（线程安全，复用避免重复初始化开销）
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    PremiumHandshake(HowSAuth plugin, DataService dataService, LoginSessions sessions, PremiumVerifier verifier,
                     LoginHandler loginHandler, KeyPair rsaKeyPair) {
        this.plugin = plugin;
        this.dataService = dataService;
        this.sessions = sessions;
        this.verifier = verifier;
        this.loginHandler = loginHandler;
        this.rsaKeyPair = rsaKeyPair;
        this.publicKeyEncoded = rsaKeyPair.getPublic().getEncoded();
    }

    // ===== 阶段1：LoginStart 拦截与查档 =====

    // EventLoop 为长生命周期资源，不应关闭；调度任务在会话清理时取消
    @SuppressWarnings("resource")
    void handleLoginStart(PacketReceiveEvent event) {
        Channel channel = (Channel) event.getChannel();
        User user = event.getUser();
        ConfigManager config = plugin.config();

        WrapperLoginClientLoginStart wrapper = new WrapperLoginClientLoginStart(event);
        String username = wrapper.getUsername();

        // 获取玩家 IP
        String ip = LoginFrames.extractIp(channel);
        if (ip == null) {
            return; // 无法获取 IP，放行让服务端处理
        }

        // 1. 以数据库标记为准判断是否拦截（premium=0/1），与 premium.enabled 配置开关无关：
        //    premium=1 玩家始终走正版验证，防止管理员关掉正版验证后已注册正版玩家掉线丢账号
        DataService.ProfileResult profile = dataService.getProfile(username);

        boolean upgradeAttempt = false;
        if (profile.exists() && !profile.premium()) {
            // 2. 离线玩家：仅当正版验证总开关开启且有升级标记时拦截做正版验证
            //    （升级成功则迁移账号，失败则回退离线），否则不拦截，由服务端原生处理
            if (!config.premium().enabled() || !plugin.accounts().hasPendingUpgrade(profile.uuid())) {
                return;
            }
            upgradeAttempt = true;
        }

        if (Debug.on()) {
            UUID displayId = profile.exists() ? profile.uuid() : OfflineUuids.of(username);
            Debug.log("premium", "login start %s (%s) upgrade=%s", username,
                    Debug.shortId(displayId), upgradeAttempt);
        }

        // 降级中：正版玩家已提交降级请求 → 迁移账号数据到离线 UUID 后放行，走服务端原生
        // 离线登录（离线 UUID 进入，密码或 2FA 登录）。LoginStart 阶段即可算出离线 UUID，
        // 此时迁移确保后续配置阶段认证读到离线账号
        if (profile.exists() && profile.premium()
                && plugin.accounts().hasPendingDowngrade(profile.uuid())) {
            plugin.accounts().executeDowngrade(profile.uuid(), OfflineUuids.of(username), username);
            return;
        }

        // 3. 新玩家（不在数据库）：仅当正版验证与自动验证均开启时才拦截验证，否则按离线处理
        //    同时检查离线确认标记，避免离线客户端反复尝试正版验证
        //    已注册玩家（含 premium=1）不受离线标记影响，防止同名离线玩家抢占正版账号
        if (!profile.exists()) {
            if (!config.premium().enabled() || !config.premium().autoVerify()) return;
            if (dataService.isOfflineConfirmed(ip, username)) {
                if (Debug.on()) {
                    Debug.log("premium", "offline confirmed (cached) for %s: pass to server", username);
                }
                return;
            }
        }

        // 4. 已注册正版玩家且回退标记有效（上次验证失败/离线启动器断开）：
        //    跳过加密握手，直接以正版 UUID 进入并用密码登录（复用离线标记机制，避免死循环踢出）
        //    无密码账户无密码可验，是否回退由 premiumFallbackAllowed（含 reject-no-auth-account 开关）决定
        if (profile.exists() && profile.premium()
                && verifier.premiumFallbackAllowed(profile.uuid())
                && dataService.isPremiumFallbackConfirmed(ip, username)) {
            plugin.getLogger().info(I18n.get("log.premium_fallback_login", username, ip));
            event.setCancelled(true);
            // 标记本次需密码登录，onJoin 时不自动免密
            plugin.accounts().markPremiumFallback(profile.uuid());
            SessionContext session = new SessionContext();
            session.username(username);
            session.ip(ip);
            loginHandler.proceedWithLogin(channel, user, session, profile.uuid(), username, profile.properties());
            return;
        }

        plugin.getLogger().info(I18n.get("log.premium_verifying", username, ip));

        // 5. premium=1/新玩家/升级尝试 → 取消 LoginStart，走正版验证流程
        event.setCancelled(true);

        // 清理旧会话（同一 channel 不应有多条 LoginStart，但防御性处理）
        sessions.cleanup(channel);

        // 创建会话
        SessionContext session = new SessionContext();
        session.username(username);
        session.ip(ip);
        session.upgradeAttempt(upgradeAttempt);
        session.premiumAccount(profile.exists() && profile.premium());
        if (upgradeAttempt) {
            session.offlineUuid(profile.uuid());
        }
        sessions.put(channel, session);

        // 5. 生成验证令牌并发送 EncryptionRequest
        byte[] verifyToken = new byte[4];
        SECURE_RANDOM.nextBytes(verifyToken);
        session.verifyToken(verifyToken);

        WrapperLoginServerEncryptionRequest request =
                new WrapperLoginServerEncryptionRequest("", rsaKeyPair.getPublic(), verifyToken);
        user.sendPacket(request);

        // 6. 注册断开检测器：若在收到 EncryptionResponse 之前断开，确认为离线客户端
        // 新玩家 → 标记离线确认（重连走离线登录）；已注册正版玩家 → 标记正版回退（重连以正版 UUID 密码登录）
        // 升级玩家在验证期间断开 → 回退离线，清除升级标记
        // 防御性移除同名旧处理器
        final boolean isNewPlayer = !profile.exists();
        try { channel.pipeline().remove(LoginSessions.DETECTOR_NAME); } catch (Exception ignored) {}
        channel.pipeline().addFirst(LoginSessions.DETECTOR_NAME, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                SessionContext s = sessions.get(channel);
                if (s != null && s.stage() == SessionContext.Stage.WAITING_ENCRYPTION_RESPONSE) {
                    if (isNewPlayer) {
                        // 新玩家在收到 EncryptionResponse 前断开 → 离线客户端
                        dataService.markOfflineConfirmed(s.ip(), s.username());
                    } else if (s.isUpgradeAttempt()) {
                        // 升级尝试在验证前断开 → 回退离线，清除升级标记
                        plugin.accounts().clearUpgradePending(s.offlineUuid());
                    } else if (s.premiumAccount() && config.premium().passwordFallbackEnabled()) {
                        // 已注册正版玩家使用离线启动器，无法回应 EncryptionRequest 即断开 →
                        // 记录回退标记，下次重连跳过正版验证，以正版 UUID 进入并用密码登录
                        dataService.markPremiumFallbackConfirmed(s.ip(), s.username());
                    }
                }
                sessions.cleanup(channel);
                ctx.fireChannelInactive();
            }
        });

        session.advance(SessionContext.Stage.START, SessionContext.Stage.WAITING_ENCRYPTION_RESPONSE);
        if (Debug.on()) {
            Debug.log("premium", "handshake %s: stage START -> WAITING_ENCRYPTION_RESPONSE", username);
        }

        // 7. 调度超时清理：预防恶意客户端收到 EncryptionRequest 后既不回传也不断开，
        // 导致会话永久滞留 sessions Map 造成内存泄漏（断开检测器只在 channelInactive 时触发）
        // 仅当当前会话仍为本会话且处于等待阶段时才清理，避免误伤同一 channel 上的新会话
        // 将 ScheduledFuture 存入会话，供清理/断开时取消，避免任务在会话结束后仍触发
        final SessionContext created = session;
        created.timeoutTask(channel.eventLoop().schedule(() -> {
            SessionContext current = sessions.get(channel);
            if (current == created
                    && current.stage() == SessionContext.Stage.WAITING_ENCRYPTION_RESPONSE) {
                sessions.cleanup(channel);
                channel.close();
            }
        }, config.premium().handshakeTimeoutMs(), TimeUnit.MILLISECONDS));
    }

    // ===== 阶段2-3：加密握手与启用 =====

    // EventLoop 为长生命周期资源，不应关闭
    @SuppressWarnings("resource")
    void handleEncryptionResponse(PacketReceiveEvent event) {
        Channel channel = (Channel) event.getChannel();
        User user = event.getUser();

        SessionContext session = sessions.get(channel);
        // 无会话（非正版验证流程的 EncryptionResponse）→ 放行
        if (session == null) return;
        // 阶段不匹配 → 放行
        if (session.stage() != SessionContext.Stage.WAITING_ENCRYPTION_RESPONSE) return;

        // 取消包：阻止服务端处理（服务端 state=HELLO，不取消会因状态不匹配抛异常）
        event.setCancelled(true);

        try {
            WrapperLoginClientEncryptionResponse response = new WrapperLoginClientEncryptionResponse(event);

            // 7. RSA 解密共享密钥
            byte[] sharedSecret = response.getSecretKey(rsaKeyPair.getPrivate()).getEncoded();

            // 8. RSA 解密验证令牌并校验
            byte[] encryptedToken = response.getEncryptedVerifyToken().orElse(null);
            if (encryptedToken == null) {
                // 缺少验证令牌，直接断连
                sessions.cleanup(channel);
                channel.close();
                return;
            }
            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsaCipher.init(Cipher.DECRYPT_MODE, rsaKeyPair.getPrivate());
            byte[] decryptedToken = rsaCipher.doFinal(encryptedToken);

            if (!Arrays.equals(decryptedToken, session.verifyToken())) {
                // 验证令牌不匹配，直接断连（不发送 Disconnect，因为加密尚未启用）
                sessions.cleanup(channel);
                channel.close();
                return;
            }

            // 9. 启用 AES-CFB8 双向加密
            CryptoHandler.enableEncryption(channel, sharedSecret);
            session.advance(SessionContext.Stage.WAITING_ENCRYPTION_RESPONSE, SessionContext.Stage.ENCRYPTED);
            if (Debug.on()) {
                Debug.log("premium", "key exchange complete for %s: encryption enabled", session.username());
                Debug.log("premium", "handshake %s: stage WAITING_ENCRYPTION_RESPONSE -> ENCRYPTED", session.username());
            }

            // 10. 移除断开检测器（已收到响应，确认为正版客户端）
            sessions.removeDetector(channel);

            // 11. 计算服务器哈希并异步调用 hasJoined；结果分支（放行/回退/踢出）由 PremiumVerifier 决定
            verifier.verify(channel, user, session, sharedSecret, publicKeyEncoded);
        } catch (Exception e) {
            plugin.getLogger().severe(I18n.get("log.premium_cipher_init_failed", e.getMessage()));
            sessions.cleanup(channel);
            channel.close();
        }
    }
}