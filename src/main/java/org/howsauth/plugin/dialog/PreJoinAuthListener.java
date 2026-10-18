package org.howsauth.plugin.dialog;

import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.auth.AccountLifecycle;
import org.howsauth.plugin.auth.FailProtection;
import org.howsauth.plugin.auth.LoginFlow;
import org.howsauth.plugin.auth.SessionStore;
import org.howsauth.plugin.auth.TwoFactorAuth;
import org.howsauth.plugin.config.ConfigManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Pre-join Dialog 登录（Paper 配置阶段事件，1.21.6+）：
 * 玩家进入世界前弹窗完成登录/注册，认证成功才放行，登录前不接收任何世界信息（坐标保护天然不需要）。
 * 流程：配置阶段事件 → 弹窗阻塞等待提交 → 认证成功记录结果 → 进入世界由 onJoin 消费结果收尾。
 * 回退聊天栏提示：Dialog 未启用 / 客户端&lt;1.21.6 / 正版或会话命中（未绑定 2FA）/ 已登录；超时按 kick-on-timeout 踢出或放行收尾。
 * <p>
 * 本类只负责配置阶段状态机；窗口展示与确认回调在 {@link PreJoinDialogFlow}，
 * 会话对象在 {@link PreJoinSession}，客户端能力/语言解析在 {@link PreJoinClientSupport}。
 */
@SuppressWarnings("UnstableApiUsage")
public final class PreJoinAuthListener implements Listener {

    /** 配置阶段认证结果（玩家进入世界时消费） */
    public enum AuthOutcome {
        /** 密码/双因素验证通过 */
        LOGIN,
        /** 注册完成 */
        REGISTER
    }

    private final HowSAuth plugin;
    private final SessionStore sessions;
    private final AccountLifecycle accounts;
    private final TwoFactorAuth twoFactor;
    // 配置阶段认证结果：UUID → 结果（进入世界时移除）
    private final Map<UUID, AuthOutcome> outcomes = new ConcurrentHashMap<>();
    // 活跃的配置阶段会话（Dialog 提交回调查找）
    private final Map<UUID, PreJoinSession> configSessions = new ConcurrentHashMap<>();
    // 配置阶段玩家名认领：先到者持续占有连接，后来者被拒（离线模式同名解析为同一 UUID）
    private final NameClaims nameClaims;
    private final PreJoinDialogFlow dialogs;
    // timeout=0 时由配置阶段连接生命周期负责释放 Session
    private static final int CONFIG_WAIT_FALLBACK_SECONDS = 600;

    public PreJoinAuthListener(HowSAuth plugin, SessionStore sessions,
                               FailProtection failProtection, TwoFactorAuth twoFactor,
                               AccountLifecycle accounts, LoginFlow loginFlow, DialogManager dialogManager) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.accounts = accounts;
        this.twoFactor = twoFactor;
        this.nameClaims = new NameClaims(plugin.config());
        this.dialogs = new PreJoinDialogFlow(plugin, loginFlow, twoFactor, accounts,
                failProtection, dialogManager, configSessions);
    }

    /**
     * 玩家进入世界后释放名字认领：play 阶段起服务端的同名单会话检查接管，
     * 插件侧的配置阶段认领不再需要（不释放会让名字在 TTL 内无法被重新连接使用）。
     */
    public void releaseClaim(String name) {
        nameClaims.releaseByName(name);
    }

    /** 玩家进入世界时消费配置阶段认证结果（无结果返回 null，走正常登录流程） */
    public AuthOutcome consume(Player player) {
        AuthOutcome outcome = outcomes.remove(player.getUniqueId());
        if (Debug.on()) {
            Debug.log("dialog", "consume pre-join result for %s: %s", player.getName(),
                    outcome != null ? outcome : "none");
        }
        return outcome;
    }

    /** 配置阶段是否已完成认证（供出生点决策查询，不消费结果） */
    public boolean hasCompleted(UUID uuid) {
        return outcomes.containsKey(uuid);
    }

    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent event) {
        PlayerConfigurationConnection conn = event.getConnection();
        UUID uuid = conn.getProfile().getId();
        // 名字认领必须最先执行：先到者持续占有连接，后来者直接拒绝（"您已登录此服务器"）。
        // 离线模式同名解析为同一 UUID，而服务端的同名单会话检查要到创建 ServerPlayer
        // （play 阶段）才生效，配置阶段的同名竞争只能在此拦截。也正因如此，认领失败时
        // 必须立即返回、不得触碰任何共享状态——否则会清掉先到者正在进行的认证结果。
        String name = conn.getProfile().getName();
        if (!nameClaims.claim(name, conn)) {
            if (Debug.on()) {
                Debug.log("dialog", "pre-join %s: name already claimed, disconnect", Debug.shortId(uuid));
            }
            conn.disconnect(I18n.msgForLocale("prelogin.already_online", PreJoinClientSupport.resolveLocale(conn)));
            return;
        }
        // 清除上次连接可能残留的会话状态：认证结果与 2FA 待验证标记
        // （pre-join 阶段断开无 PlayerQuitEvent → clearSession 不执行，残留的 pending2fa
        //  会让下次连接的密码玩家凭旧会话状态直接 /2fa 跳过密码验证）
        if (uuid != null) {
            outcomes.remove(uuid);
            twoFactor.clearPending(uuid);
        }
        // 无凭据账号（无密码、未绑 2FA、非正版）：配置阶段先行处理。
        // AsyncPlayerConnectionConfigureEvent 早于 onPreLogin 的 AsyncPlayerPreLoginEvent，
        // 弹窗验证会卡死到超时（reject 开关在 dialog 场景轮不到），此处与连接层拦截口径一致：
        if (uuid != null && accounts.hasAccount(uuid)
                && accounts.hasNoUsableLoginMethod(uuid)) {
            if (plugin.config().protectionMisc().rejectNoAuthAccount()) {
                if (Debug.on()) {
                    Debug.log("dialog", "pre-join %s: no usable login method, disconnect",
                            Debug.shortId(uuid));
                }
                // 连接就此断开，不会进入游戏，立即释放名字认领
                nameClaims.release(name, conn);
                conn.disconnect(I18n.msgForLocale("prelogin.account_locked",
                        PreJoinClientSupport.resolveLocale(conn)));
                return;
            }
            // 放行：跳过验证窗口（无凭据永远验不过），由 onJoin 的 beginAuthFlow 挂起至超时踢出
            return;
        }
        if (!plugin.config().login().dialogEnabled()) {
            if (Debug.on()) {
                Debug.log("dialog", "pre-join %s: dialog disabled, fallback to chat",
                        Debug.shortId(uuid));
            }
            return;
        }
        // 已登录（reconfigure 场景）直接放行
        if (uuid == null || sessions.isLoggedIn(uuid)) {
            if (Debug.on()) {
                Debug.log("dialog", "pre-join %s: already logged in, pass",
                        Debug.shortId(uuid));
            }
            return;
        }
        // 客户端是否支持配置阶段 Dialog（<1.21.6 收到 Show Dialog 包会断连，回退聊天栏提示）
        if (!PreJoinClientSupport.supportsDialogs(uuid)) {
            if (Debug.on()) {
                Debug.log("dialog", "pre-join %s: dialogs unsupported, fallback to chat",
                        Debug.shortId(uuid));
            }
            return;
        }
        // 登录无需 2FA 验证码（未绑定/开关关闭/2FA 会话命中）时的免弹窗放行
        String ip = PreJoinClientSupport.clientIp(conn);
        boolean autoLogin = skipAutoLogin(uuid, ip);
        if (!twoFactor.requiresAtLogin(uuid, ip)) {
            // 免密（正版非回退/IP 会话命中）：放行，由 onJoin 现有免密分支收尾
            if (autoLogin) {
                if (Debug.on()) {
                    Debug.log("dialog", "pre-join %s: auto login (premium/session), pass",
                            Debug.shortId(uuid));
                }
                return;
            }
            // 无密码账户：仅 2FA 会话命中时免验证码直接登录。无密钥账户不能满足 has2faSession，
            // 不会放行，进入下方验证码窗口等待（永远无法通过，超时断连）
            if (accounts.isPasswordless(uuid) && twoFactor.hasSession(uuid, ip)) {
                if (Debug.on()) {
                    Debug.log("dialog", "pre-join %s: passwordless 2FA session, mark login",
                            Debug.shortId(uuid));
                }
                outcomes.put(uuid, AuthOutcome.LOGIN);
                return;
            }
        }

        PreJoinSession session = new PreJoinSession(conn);
        synchronized (configSessions) {
            PreJoinSession previous = configSessions.put(uuid, session);
            if (previous != null) {
                // 同 UUID 重连/并发配置连接：终止旧等待，避免旧回调失效后遗留阻塞线程
                previous.kicked = true;
                previous.latch.countDown();
                previous.connection.disconnect(I18n.msgForLocale("listener.login_timeout",
                        PreJoinClientSupport.resolveLocale(previous.connection)));
            }
        }
        // 提为方法作用域：超时断连时仍需玩家语言
        String locale = PreJoinClientSupport.resolveLocale(conn);
        try {
            boolean isLogin = accounts.hasAccount(uuid);
            if (Debug.on()) {
                String type = (autoLogin || (isLogin && accounts.isPasswordless(uuid))) ? "2fa"
                        : isLogin ? "login" : "register";
                Debug.log("dialog", "pre-join %s: show %s dialog", Debug.shortId(uuid), type);
            }
            // 免密（正版/IP）或无密码账户：跳过密码窗口，直接验证验证码
            if (autoLogin || (isLogin && accounts.isPasswordless(uuid))) {
                session.passwordless2fa = true;
                twoFactor.markPending(uuid);
                dialogs.show2fa(session, uuid, locale, null);
            } else if (isLogin) {
                dialogs.showLogin(session, uuid, locale, null);
            } else {
                dialogs.showRegister(session, uuid, locale, null);
            }
            // 阻塞配置线程直到认证完成/超时（虚拟线程阻塞开销极小）。
            // 配置超时为 0 表示聊天流程无超时，但配置阶段仍需有上限，避免客户端断开后 latch 无人释放。
            ConfigManager cfg = plugin.config();
            int configuredTimeout = isLogin ? cfg.login().timeout() : cfg.login().registerTimeout();
            int timeout = configuredTimeout > 0 ? configuredTimeout : CONFIG_WAIT_FALLBACK_SECONDS;
            //noinspection ResultOfMethodCallIgnored
            session.latch.await(timeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (configSessions) {
                boolean current = configSessions.get(uuid) == session;
                configSessions.remove(uuid, session);
                if (current && session.success && !session.kicked && !session.fallback) {
                    outcomes.put(uuid, session.registered ? AuthOutcome.REGISTER : AuthOutcome.LOGIN);
                    if (Debug.on()) {
                        Debug.log("dialog", "record pre-join result for %s: %s", Debug.shortId(uuid),
                                session.registered ? "REGISTER" : "LOGIN");
                    }
                }
            }
        }
        // 被接管的旧会话：连接已被 disconnect，不会进入游戏，释放名字认领
        if (session.kicked) {
            nameClaims.release(name, conn);
            return;
        }
        // 发送窗口失败回退聊天栏：仍会进入游戏，认领由 onJoin 释放
        if (session.fallback) return;
        if (session.success) {
            return;
        }
        // 超时未完成：kick-on-timeout 开启时踢出（连接断开，释放认领）；
        // 关闭时由 onJoin 的 beginAuthFlow 接管（仍会进入游戏，认领保留到 onJoin）
        if (plugin.config().login().kickOnTimeout()) {
            nameClaims.release(name, conn);
            conn.disconnect(I18n.msgForLocale("listener.login_timeout", locale));
        }
    }

    /** 正版（非回退）或会话命中：免密登录路径（是否仍需 2FA 窗口由调用方按绑定状态决定） */
    private boolean skipAutoLogin(UUID uuid, String ip) {
        if (accounts.isPremium(uuid) && !accounts.isPremiumFallback(uuid)) return true;
        return ip != null && sessions.hasSession(uuid, ip);
    }
}
