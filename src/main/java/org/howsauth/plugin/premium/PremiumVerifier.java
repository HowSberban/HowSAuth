package org.howsauth.plugin.premium;

import com.github.retrooper.packetevents.protocol.player.User;
import io.netty.channel.Channel;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.data.PlayerData;
import org.howsauth.plugin.data.PlayerDataManager;

import java.util.UUID;

/**
 * 正版验证（Mojang hasJoined）的结果处理：已验证放行、失败回退、失败踢出。
 * <p>
 * 只负责决定走哪个分支（账号迁移、密码回退、踢出）；踢出与进入游戏涉及连接生命周期
 * （发送 Disconnect、清理会话、推进服务端 state），由调用方通过 {@link ResultHandler} 注入。
 */
final class PremiumVerifier {

    /** ConnectionHandler 提供的控制动作；实现方掌握会话与连接生命周期 */
    interface ResultHandler {
        /**
         * 进入游戏（异步触发 AsyncPlayerPreLoginEvent 后推进 state）。
         * 正版验证成功与失败回退共用此方法。
         */
        void proceedWithLogin(Channel channel, User user, SessionContext session,
                              UUID uuid, String username, String properties);

        /** 踢出连接：发送 Disconnect、关闭 channel 并清理会话 */
        void kick(Channel channel, User user, SessionContext session);

        /** 异步异常兜底：回退升级标记、记日志、踢出（连接已断则仅清会话） */
        void failAsyncLogin(Channel channel, User user, SessionContext session, String reason);
    }

    private final HowSAuth plugin;
    private final DataService dataService;
    private final PlayerDataManager dataManager;
    private final MojangClient mojangClient;
    private final ResultHandler handler;

    PremiumVerifier(HowSAuth plugin, DataService dataService, PlayerDataManager dataManager,
                    MojangClient mojangClient, ResultHandler handler) {
        this.plugin = plugin;
        this.dataService = dataService;
        this.dataManager = dataManager;
        this.mojangClient = mojangClient;
        this.handler = handler;
    }

    /**
     * 计算会话哈希后异步校验玩家是否为正版，并按结果处理。
     * <p>
     * 两条兜底路径都保留：同步块内异常走 {@code catch}，future 异常完成（Error 不经 Exception 分支）
     * 走 {@code exceptionally}——两者都避免会话残留与连接悬挂。
     */
    void verify(Channel channel, User user, SessionContext session, byte[] sharedSecret, byte[] publicKeyEncoded) {
        String serverHash = LoginFrames.computeServerHash(sharedSecret, publicKeyEncoded);
        String username = session.username();

        mojangClient.hasJoined(serverHash, username).thenAccept(premiumProfile -> {
            try {
                if (Debug.on()) {
                    Debug.log("premium", "hasJoined %s: %s", username,
                            premiumProfile.isPresent() ? "verified" : "not premium");
                }
                if (premiumProfile.isEmpty()) {
                    handleVerificationFailed(channel, user, session, username);
                    return;
                }
                handleVerified(channel, user, session, username, premiumProfile.get().uuid(),
                        premiumProfile.get().propertiesJson());
            } catch (Exception e) {
                // 同步操作异常：与非异常失败走同一兜底
                handler.failAsyncLogin(channel, user, session, e.getMessage());
            }
        }).exceptionally(error -> {
            // future 异常完成（Error 不经上面的 Exception 分支）：同样兜底，避免会话残留与连接悬挂
            handler.failAsyncLogin(channel, user, session, error.toString());
            return null;
        });
    }

    /** 验证失败：升级尝试回退离线；数据库正版账号且允许回退则走密码登录；否则踢出 */
    private void handleVerificationFailed(Channel channel, User user, SessionContext session, String username) {
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
            handler.proceedWithLogin(channel, user, session, premiumData.uuid(), username, premiumData.properties());
            return;
        }
        // 否则踢出（发送 Disconnect 并兜底关闭连接）
        if (Debug.on()) {
            Debug.log("premium", "kick %s: premium verification failed", username);
        }
        handler.kick(channel, user, session);
    }

    /** 验证成功：保存/迁移正版数据、清除回退标记，然后进入游戏 */
    private void handleVerified(Channel channel, User user, SessionContext session, String username,
                                UUID uuid, String properties) {
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
            PlayerData pending = dataManager.getPlayer(DataService.offlineUuid(username));
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
        handler.proceedWithLogin(channel, user, session, uuid, username, properties);
    }

    /**
     * 正版账户是否允许密码回退：回退开关开启，且（有密码，或无密码但拒绝开关关闭——放行无凭据玩家）。
     * LoginStart 离线标记回退与 hasJoined 验证失败回退共用，保持同一口径。
     */
    boolean premiumFallbackAllowed(UUID uuid) {
        return plugin.config().premium().passwordFallbackEnabled()
                && (!plugin.accounts().isPasswordless(uuid) || !plugin.config().protectionMisc().rejectNoAuthAccount());
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
        data = dataManager.getPlayer(DataService.offlineUuid(username));
        return data != null && data.premium() ? data : null;
    }
}
