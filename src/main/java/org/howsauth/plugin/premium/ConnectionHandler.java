package org.howsauth.plugin.premium;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientEncryptionResponse;
import com.github.retrooper.packetevents.wrapper.login.client.WrapperLoginClientLoginStart;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerDisconnect;
import com.github.retrooper.packetevents.wrapper.login.server.WrapperLoginServerEncryptionRequest;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.data.PlayerDataManager.PlayerData;

import javax.crypto.Cipher;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 连接处理器（模块1）—— 调度 + 加密握手合并。
 * <p>
 * 监听 LoginStart / EncryptionResponse 包事件，为每个连接维护会话状态机，
 * 编排 DataService / MojangClient / PlayerInjector 完成正版验证流程。
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
    private final DataService dataService;
    private final MojangClient mojangClient;
    private final PlayerInjector playerInjector;
    private final KeyPair rsaKeyPair;
    private final byte[] publicKeyEncoded;

    // 每连接会话状态：channel → SessionContext
    private final Map<Channel, SessionContext> sessions = new ConcurrentHashMap<>();

    // Netty pipeline 中断开检测器名称
    private static final String DETECTOR_NAME = "hsauth_disconnect_detector";

    // 验证令牌随机数生成器（线程安全，复用避免重复初始化开销）
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public ConnectionHandler(HowSAuth plugin, DataService dataService, MojangClient mojangClient,
                             PlayerInjector playerInjector) {
        super(PacketListenerPriority.LOWEST);
        this.plugin = plugin;
        this.dataService = dataService;
        this.mojangClient = mojangClient;
        this.playerInjector = playerInjector;
        this.rsaKeyPair = generateKeyPair();
        this.publicKeyEncoded = rsaKeyPair.getPublic().getEncoded();
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Login.Client.LOGIN_START) {
            handleLoginStart(event);
        } else if (event.getPacketType() == PacketType.Login.Client.ENCRYPTION_RESPONSE) {
            handleEncryptionResponse(event);
        }
    }

    // ===== 阶段1：LoginStart 拦截与查档 =====

    // EventLoop 为长生命周期资源，不应关闭；调度任务在会话清理时取消
    @SuppressWarnings("resource")
    private void handleLoginStart(PacketReceiveEvent event) {
        Channel channel = (Channel) event.getChannel();
        User user = event.getUser();
        ConfigManager config = plugin.getConfigManager();

        WrapperLoginClientLoginStart wrapper = new WrapperLoginClientLoginStart(event);
        String username = wrapper.getUsername();

        // 获取玩家 IP
        String ip = extractIp(channel);
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
            if (!config.premiumEnabled() || !plugin.accounts().hasPendingUpgrade(profile.uuid())) {
                return;
            }
            upgradeAttempt = true;
        }

        if (Debug.on()) {
            UUID displayId = profile.exists() ? profile.uuid() : DataService.offlineUuid(username);
            Debug.log("premium", "login start %s (%s) upgrade=%s", username,
                    Debug.shortId(displayId), upgradeAttempt);
        }

        // 降级中：正版玩家已提交降级请求 → 迁移账号数据到离线 UUID 后放行，走服务端原生
        // 离线登录（离线 UUID 进入，密码或 2FA 登录）。LoginStart 阶段即可算出离线 UUID，
        // 此时迁移确保后续配置阶段认证读到离线账号
        if (profile.exists() && profile.premium() && plugin.accounts().hasPendingDowngrade(profile.uuid())) {
            plugin.accounts().executeDowngrade(profile.uuid(), DataService.offlineUuid(username), username);
            return;
        }

        // 3. 新玩家（不在数据库）：仅当正版验证与自动验证均开启时才拦截验证，否则按离线处理
        //    同时检查离线确认标记，避免离线客户端反复尝试正版验证
        //    已注册玩家（含 premium=1）不受离线标记影响，防止同名离线玩家抢占正版账号
        if (!profile.exists()) {
            if (!config.premiumEnabled() || !config.premiumAutoVerify()) return;
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
                && premiumFallbackAllowed(profile.uuid())
                && dataService.isPremiumFallbackConfirmed(ip, username)) {
            plugin.getLogger().info(I18n.get("log.premium_fallback_login", username, ip));
            event.setCancelled(true);
            // 标记本次需密码登录，onJoin 时不自动免密
            plugin.accounts().markPremiumFallback(profile.uuid());
            SessionContext session = new SessionContext();
            session.username(username);
            session.ip(ip);
            proceedWithLogin(channel, user, session, profile.uuid(), username, profile.properties());
            return;
        }

        plugin.getLogger().info(I18n.get("log.premium_verifying", username, ip));

        // 5. premium=1/新玩家/升级尝试 → 取消 LoginStart，走正版验证流程
        event.setCancelled(true);

        // 清理旧会话（同一 channel 不应有多条 LoginStart，但防御性处理）
        sessions.remove(channel);

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
        try { channel.pipeline().remove(DETECTOR_NAME); } catch (Exception ignored) {}
        channel.pipeline().addFirst(DETECTOR_NAME, new ChannelInboundHandlerAdapter() {
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
                    } else if (s.premiumAccount() && config.premiumPasswordFallbackEnabled()) {
                        // 已注册正版玩家使用离线启动器，无法回应 EncryptionRequest 即断开 →
                        // 记录回退标记，下次重连跳过正版验证，以正版 UUID 进入并用密码登录
                        dataService.markPremiumFallbackConfirmed(s.ip(), s.username());
                    }
                }
                sessions.remove(channel);
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
                cleanupSession(channel);
                channel.close();
            }
        }, config.premiumHandshakeTimeoutMs(), TimeUnit.MILLISECONDS));
    }

    // ===== 阶段2-3：加密握手与启用 =====

    // EventLoop 为长生命周期资源，不应关闭
    @SuppressWarnings("resource")
    private void handleEncryptionResponse(PacketReceiveEvent event) {
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
                cleanupSession(channel);
                channel.close();
                return;
            }
            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsaCipher.init(Cipher.DECRYPT_MODE, rsaKeyPair.getPrivate());
            byte[] decryptedToken = rsaCipher.doFinal(encryptedToken);

            if (!Arrays.equals(decryptedToken, session.verifyToken())) {
                // 验证令牌不匹配，直接断连（不发送 Disconnect，因为加密尚未启用）
                cleanupSession(channel);
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
            removeDetector(channel);

            // 11. 计算服务器哈希并异步调用 hasJoined
            String serverHash = computeServerHash(sharedSecret, publicKeyEncoded);
            String username = session.username();

            mojangClient.hasJoined(serverHash, username).thenAccept(premiumProfile -> {
                try {
                    if (Debug.on()) {
                        Debug.log("premium", "hasJoined %s: %s", username, premiumProfile.isPresent() ? "verified" : "not premium");
                    }
                    if (premiumProfile.isEmpty()) {
                        // 验证失败
                        if (session.isUpgradeAttempt()) {
                            // 升级尝试回退为离线账号，清除升级标记，玩家重进后按离线登录
                            if (Debug.on()) {
                                Debug.log("premium", "upgrade failed for %s: reverting to offline", username);
                            }
                            plugin.accounts().clearUpgradePending(session.offlineUuid());
                        }
                        // 正版验证失败回退：数据库正版账号且允许回退时，放行以正版 UUID 进入，
                        // 用密码登录（继承正版数据），下次正版验证成功即自动免密。
                        // 无密码账户是否回退由 premiumFallbackAllowed（含 reject-no-auth-account 开关）决定
                        PlayerData premiumData = premiumAccountByName(session, username);
                        if (premiumData != null && premiumFallbackAllowed(premiumData.uuid())) {
                            if (Debug.on()) {
                                Debug.log("premium", "fallback login for %s (verification failed, password path)", username);
                            }
                            plugin.accounts().markPremiumFallback(premiumData.uuid());
                            proceedWithLogin(channel, user, session, premiumData.uuid(), username, premiumData.properties());
                            return;
                        }
                        // 否则踢出（发送 Disconnect 并兜底关闭连接）
                        if (Debug.on()) {
                            Debug.log("premium", "kick %s: premium verification failed", username);
                        }
                        channel.eventLoop().execute(() -> {
                            if (channel.isActive()) {
                                kick(channel, user, HowSAuth.legacy(I18n.get("listener.premium_unavailable")));
                            } else {
                                cleanupSession(channel);
                            }
                        });
                        return;
                    }

                    UUID uuid = premiumProfile.get().uuid();
                    String properties = premiumProfile.get().propertiesJson();

                    // 12. 保存正版数据（异步落盘）
                    if (session.isUpgradeAttempt()) {
                        // 升级成功：将离线账号迁移到正版 UUID（保留退出位置等数据，密码置空）+ 迁移原版玩家数据（背包/成就/统计），清除升级标记
                        dataService.migrateToPremium(session.offlineUuid(), uuid, username, session.ip(), properties);
                        plugin.playerFiles().migrateAsync(session.offlineUuid(), uuid);
                        plugin.accounts().clearUpgradePending(session.offlineUuid());
                        if (Debug.on()) {
                            Debug.log("premium", "upgrade success for %s: migrated offline account to premium uuid %s",
                                    username, Debug.shortId(uuid));
                        }
                    } else {
                        // /premium 强制标记的账号首次正版验证进服：存量记录仍是离线 UUID（仅 premium=1），
                        // 同样迁移到正版 UUID 并保留退出位置等数据，避免与新建记录并存
                        PlayerData pending = plugin.getPlayerDataManager().getPlayer(DataService.offlineUuid(username));
                        if (pending != null && pending.premium()) {
                            // 目标正版 UUID 已有正版记录时保留原记录数据，跳过原版数据迁移（防止离线号文件覆盖正版身份数据）
                            if (dataService.migrateToPremium(pending.uuid(), uuid, username, session.ip(), properties)) {
                                plugin.playerFiles().migrateAsync(pending.uuid(), uuid);
                            }
                        } else {
                            // 首次注册：无密码账户（正版验证即身份凭证，玩家可用 /addpassword 自行设置密码）
                            dataService.savePremium(uuid, username, session.ip(), properties);
                        }
                    }

                    // 清除 ip+名 回退标记，确保下次优先走正常正版验证
                    dataService.clearPremiumFallbackConfirmed(session.ip(), session.username());

                    if (Debug.on()) {
                        Debug.log("premium", "premium verified for %s: entering as premium uuid %s (upgrade=%s)",
                                username, Debug.shortId(uuid), session.isUpgradeAttempt());
                    }

                    // 13. 进入游戏（异步触发 AsyncPlayerPreLoginEvent + 推进 state）
                    proceedWithLogin(channel, user, session, uuid, username, properties);
                } catch (Exception e) {
                    // 同步操作异常：与非异常失败走同一兜底
                    failAsyncLogin(channel, user, session, e.getMessage());
                }
            }).exceptionally(error -> {
                // future 异常完成（Error 不经上面的 Exception 分支）：同样兜底，避免会话残留与连接悬挂
                failAsyncLogin(channel, user, session, error.toString());
                return null;
            });
        } catch (Exception e) {
            plugin.getLogger().severe(I18n.get("log.premium_cipher_init_failed", e.getMessage()));
            cleanupSession(channel);
            channel.close();
        }
    }

    // ===== 辅助方法 =====

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
                cleanupSession(channel);
            }
        });
    }

    /** 从 channel 提取玩家真实 IP。
     *  服务器开启 proxies.proxy-protocol（frp/nginx 内网穿透）时 channel.remoteAddress 是隧道入口地址，
     *  真实 IP 存于 NMS Connection，优先反射读取其 getRemoteAddress()（与 Bukkit 各事件返回的地址一致）；
     *  未开启或反射失败时回退 channel.remoteAddress。 */
    private static String extractIp(Channel channel) {
        Object connection = channel.pipeline().get("packet_handler");
        if (connection != null) {
            try {
                Object remote = connection.getClass().getMethod("getRemoteAddress").invoke(connection);
                if (remote instanceof InetSocketAddress isa && isa.getAddress() != null) {
                    return isa.getAddress().getHostAddress();
                }
            } catch (ReflectiveOperationException ignored) {
                // 该 NMS 版本无此方法，回退 channel.remoteAddress
            }
        }
        if (channel.remoteAddress() instanceof InetSocketAddress addr) {
            return addr.getAddress().getHostAddress();
        }
        return null;
    }

    /**
     * 正版账户是否允许密码回退：回退开关开启，且（有密码，或无密码但拒绝开关关闭——放行无凭据玩家）。
     * LoginStart 离线标记回退与 hasJoined 验证失败回退两处共用，保持同一口径。
     */
    private boolean premiumFallbackAllowed(UUID uuid) {
        return plugin.getConfigManager().premiumPasswordFallbackEnabled()
                && (!plugin.accounts().isPasswordless(uuid) || !plugin.getConfigManager().rejectNoAuthAccount());
    }

    /**
     * 定位该会话对应的数据库正版账号（premium=1）。
     * 仅当会话确认为数据库正版账号（非升级尝试、非新玩家）时返回，否则返回 null，
     * 避免升级失败或新玩家意外触发密码回退。
     */
    private PlayerData premiumAccountByName(SessionContext session, String username) {
        if (!session.premiumAccount()) return null;
        PlayerData data = dataService.getByName(username);
        if (data != null && data.premium()) return data;
        // /premium 强制标记的账号记录仍在离线 UUID 上（名字未写入正版索引），按离线 UUID 定位，
        // 使验证失败时同样能走密码回退（与正常正版账号行为一致）
        data = plugin.getPlayerDataManager().getPlayer(DataService.offlineUuid(username));
        return data != null && data.premium() ? data : null;
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
                cleanupSession(channel);
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
                cleanupSession(channel);
            }
        }));
    }

    /**
     * 计算 Mojang 服务器哈希：十六进制( SHA-1( serverId("") + sharedSecret + publicKey ) )
     * 使用 new BigInteger(digest)（不带 signum=1），与 vanilla Minecraft 一致：
     * 若 digest 首字节 >= 0x80，结果为负数（如 "-abc123..."），客户端也用相同方式计算。
     */
    private static String computeServerHash(byte[] sharedSecret, byte[] publicKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            // serverId 为空字符串，update 空数组是 no-op，直接跳过
            sha1.update(sharedSecret);
            sha1.update(publicKey);
            byte[] digest = sha1.digest();
            return new java.math.BigInteger(digest).toString(16);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute server hash", e);
        }
    }

    /** 经已加密通道发送 Disconnect 包（直接发送 Component，保留其他插件设置的消息样式） */
    private void sendDisconnect(User user, net.kyori.adventure.text.Component message) {
        WrapperLoginServerDisconnect disconnect = new WrapperLoginServerDisconnect(message);
        user.sendPacket(disconnect);
    }

    /** 踢出连接：发送 Disconnect 后延迟关闭 channel（恶意客户端可无视 Disconnect 保持连接），并清理会话 */
    // EventLoop 为 channel 长生命周期资源，不应关闭；借用其调度延迟关闭任务
    @SuppressWarnings("resource")
    private void kick(Channel channel, User user, net.kyori.adventure.text.Component message) {
        if (Debug.on()) {
            Debug.log("premium", "kick %s: disconnect sent, closing channel in 5s", user.getName());
        }
        sendDisconnect(user, message);
        channel.eventLoop().schedule(() -> { channel.close(); }, 5, TimeUnit.SECONDS);
        cleanupSession(channel);
    }

    /** 移除 pipeline 中的断开检测器 */
    private void removeDetector(Channel channel) {
        try {
            channel.pipeline().remove(DETECTOR_NAME);
        } catch (Exception ignored) {
            // 已移除或 pipeline 已关闭
        }
    }

    /** 清理会话和检测器 */
    private void cleanupSession(Channel channel) {
        SessionContext session = sessions.remove(channel);
        if (session != null && session.timeoutTask() != null) {
            session.timeoutTask().cancel(false); // 取消超时任务，避免会话结束后无意义触发
        }
        removeDetector(channel);
    }

    /** 生成 RSA 2048 密钥对（启动时一次，所有连接复用） */
    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            return gen.generateKeyPair();
        } catch (Exception e) {
            throw new RuntimeException(I18n.get("log.premium_keypair_failed"), e);
        }
    }
}
