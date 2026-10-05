package org.howsauth.plugin.dialog;

import com.destroystokyo.paper.ClientOption;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.auth.AuthManager;
import org.howsauth.plugin.auth.PasswordValidator;
import org.howsauth.plugin.auth.SessionStore;
import org.howsauth.plugin.config.ConfigManager;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Pre-join Dialog 登录（Paper 配置阶段事件，1.21.6+）：
 * 玩家进入世界前弹窗完成登录/注册，认证成功才放行，登录前不接收任何世界信息（坐标保护天然不需要）。
 * 流程：配置阶段事件 → 弹窗阻塞等待提交 → 认证成功记录结果 → 进入世界由 onJoin 消费结果收尾。
 * 回退聊天栏提示：Dialog 未启用 / 客户端&lt;1.21.6 / 正版或会话命中（未绑定 2FA）/ 已登录；超时按 kick-on-timeout 踢出或放行收尾。
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
    private final AuthManager authManager;
    private final SessionStore sessions;
    private final DialogManager dialogManager;
    // 配置阶段认证结果：UUID → 结果（进入世界时移除）
    private final Map<UUID, AuthOutcome> outcomes = new ConcurrentHashMap<>();
    // 活跃的配置阶段会话（Dialog 提交回调查找）
    private final Map<UUID, Session> configSessions = new ConcurrentHashMap<>();
    // timeout=0 时由配置阶段连接生命周期负责释放 Session
    private static final int CONFIG_WAIT_FALLBACK_SECONDS = 600;

    /** 配置阶段会话：连接 + 完成信号（配置线程阻塞等待 Dialog 提交结果） */
    private static final class Session {
        final PlayerConfigurationConnection connection;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean success;
        volatile boolean kicked;
        // 发送窗口失败：中止 pre-join 直接放行（回退聊天栏提示）
        volatile boolean fallback;
        // 注册流程完成（写在 countDown 前，由闭锁建立 happens-before）
        boolean registered;
        // 免密登录直弹 2FA（正版/IP）：验证状态失效时重弹验证码窗口而非密码窗口
        // volatile：写入发生在 configSessions.put 之后，回调线程读取无闭锁保护，需保证可见性
        volatile boolean passwordless2fa;

        Session(PlayerConfigurationConnection connection) {
            this.connection = connection;
        }
    }

    public PreJoinAuthListener(HowSAuth plugin, AuthManager authManager, SessionStore sessions,
                               DialogManager dialogManager) {
        this.plugin = plugin;
        this.authManager = authManager;
        this.sessions = sessions;
        this.dialogManager = dialogManager;
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
        // 清除上次连接可能残留的会话状态：认证结果与 2FA 待验证标记
        // （pre-join 阶段断开无 PlayerQuitEvent → clearSession 不执行，残留的 pending2fa
        //  会让下次连接的密码玩家凭旧会话状态直接 /2fa 跳过密码验证）
        if (uuid != null) {
            outcomes.remove(uuid);
            authManager.clearPending2fa(uuid);
        }
        // 无凭据账号（无密码、未绑 2FA、非正版）：配置阶段先行处理。
        // AsyncPlayerConnectionConfigureEvent 早于 onPreLogin 的 AsyncPlayerPreLoginEvent，
        // 弹窗验证会卡死到超时（reject 开关在 dialog 场景轮不到），此处与连接层拦截口径一致：
        if (uuid != null && authManager.hasAccount(uuid)
                && authManager.hasNoUsableLoginMethod(uuid)) {
            if (plugin.getConfigManager().rejectNoAuthAccount()) {
                if (Debug.on()) {
                    Debug.log("dialog", "pre-join %s: no usable login method, disconnect",
                            uuid.toString().substring(0, 8));
                }
                conn.disconnect(I18n.msgForLocale("prelogin.account_locked", resolveLocale(conn)));
                return;
            }
            // 放行：跳过验证窗口（无凭据永远验不过），由 onJoin 的 beginAuthFlow 挂起至超时踢出
            return;
        }
        if (!plugin.getConfigManager().loginDialogEnabled()) {
            if (Debug.on()) {
                Debug.log("dialog", "pre-join %s: dialog disabled, fallback to chat",
                        uuid == null ? "unknown" : uuid.toString().substring(0, 8));
            }
            return;
        }
        // 已登录（reconfigure 场景）直接放行
        if (uuid == null || sessions.isLoggedIn(uuid)) {
            if (Debug.on()) {
                Debug.log("dialog", "pre-join %s: already logged in, pass",
                        uuid == null ? "unknown" : uuid.toString().substring(0, 8));
            }
            return;
        }
        // 客户端是否支持配置阶段 Dialog（<1.21.6 收到 Show Dialog 包会断连，回退聊天栏提示）
        if (!supportsDialogs(uuid)) {
            if (Debug.on()) {
                Debug.log("dialog", "pre-join %s: dialogs unsupported, fallback to chat",
                        uuid.toString().substring(0, 8));
            }
            return;
        }
        // 登录无需 2FA 验证码（未绑定/开关关闭/2FA 会话命中）时的免弹窗放行
        String ip = clientIp(conn);
        boolean autoLogin = skipAutoLogin(uuid, ip);
        if (!authManager.requires2faAtLogin(uuid, ip)) {
            // 免密（正版非回退/IP 会话命中）：放行，由 onJoin 现有免密分支收尾
            if (autoLogin) {
                if (Debug.on()) {
                    Debug.log("dialog", "pre-join %s: auto login (premium/session), pass",
                            uuid.toString().substring(0, 8));
                }
                return;
            }
            // 无密码账户：仅 2FA 会话命中时免验证码直接登录。无密钥账户不能满足 has2faSession，
            // 不会放行，进入下方验证码窗口等待（永远无法通过，超时断连）
            if (authManager.isPasswordless(uuid) && authManager.has2faSession(uuid, ip)) {
                if (Debug.on()) {
                    Debug.log("dialog", "pre-join %s: passwordless 2FA session, mark login",
                            uuid.toString().substring(0, 8));
                }
                outcomes.put(uuid, AuthOutcome.LOGIN);
                return;
            }
        }

        Session session = new Session(conn);
        synchronized (configSessions) {
            Session previous = configSessions.put(uuid, session);
            if (previous != null) {
                // 同 UUID 重连/并发配置连接：终止旧等待，避免旧回调失效后遗留阻塞线程
                previous.kicked = true;
                previous.latch.countDown();
                previous.connection.disconnect(I18n.msgForLocale("listener.login_timeout", resolveLocale(previous.connection)));
            }
        }
        // 提为方法作用域：超时断连时仍需玩家语言
        String locale = resolveLocale(conn);
        try {
            boolean isLogin = authManager.hasAccount(uuid);
            if (Debug.on()) {
                String type = (autoLogin || (isLogin && authManager.isPasswordless(uuid))) ? "2fa"
                        : isLogin ? "login" : "register";
                Debug.log("dialog", "pre-join %s: show %s dialog", uuid.toString().substring(0, 8), type);
            }
            // 免密（正版/IP）或无密码账户：跳过密码窗口，直接验证验证码
            if (autoLogin || (isLogin && authManager.isPasswordless(uuid))) {
                session.passwordless2fa = true;
                authManager.addPending2fa(uuid);
                show2fa(session, uuid, locale, null);
            } else if (isLogin) {
                showLogin(session, uuid, locale, null);
            } else {
                showRegister(session, uuid, locale, null);
            }
            // 阻塞配置线程直到认证完成/超时（虚拟线程阻塞开销极小）。
            // 配置超时为 0 表示聊天流程无超时，但配置阶段仍需有上限，避免客户端断开后 latch 无人释放。
            ConfigManager cfg = plugin.getConfigManager();
            int configuredTimeout = isLogin ? cfg.loginTimeout() : cfg.registerTimeout();
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
                        Debug.log("dialog", "record pre-join result for %s: %s", uuid.toString().substring(0, 8),
                                session.registered ? "REGISTER" : "LOGIN");
                    }
                }
            }
        }
        if (session.kicked || session.fallback) return;
        if (session.success) {
            return;
        }
        // 超时未完成：kick-on-timeout 开启时踢出，否则由 onJoin 的 beginAuthFlow 接管
        if (plugin.getConfigManager().kickOnTimeout()) {
            conn.disconnect(I18n.msgForLocale("listener.login_timeout", locale));
        }
    }

    /** 正版（非回退）或会话命中：免密登录路径（是否仍需 2FA 窗口由调用方按绑定状态决定） */
    private boolean skipAutoLogin(UUID uuid, String ip) {
        if (authManager.isPremium(uuid) && !authManager.isPremiumFallback(uuid)) return true;
        return ip != null && authManager.hasSession(uuid, ip);
    }

    // ViaVersion 反射惰性缓存：每个连接都会查询客户端协议版本，反射解析一次后复用
    // ViaVersion 未在依赖中声明（加载顺序不保证），无法静态初始化，只能运行时反射
    private static volatile Method viaGetPlayerVersion;
    private static volatile Object viaApiInstance;

    /**
     * 客户端是否支持配置阶段 Dialog（协议 >= 1.21.6 / 771）。
     * 无 ViaVersion 时视为支持：能连上本插件的服务端版本必然 >= 1.21.6
     * （默认 1.21.11+，冒险模式可放宽到 1.21.6，见 HowSAuth#preJoinSupported），
     * 客户端协议匹配该服务端时必然 >= 1.21.6，一定支持 Dialog。
     * 仅在有 ViaVersion（允许旧客户端连新服务端）时，才需用 ViaAPI 按 UUID 查询客户端真实协议版本。
     */
    private static boolean supportsDialogs(UUID playerId) {
        try {
            if (Bukkit.getPluginManager().getPlugin("ViaVersion") == null) {
                return true;
            }
            Method getPlayerVersion = viaGetPlayerVersion;
            Object api = viaApiInstance;
            if (getPlayerVersion == null || api == null) {
                Class<?> viaApiClass = Class.forName("com.viaversion.viaversion.api.ViaAPI");
                Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via");
                api = viaClass.getMethod("getAPI").invoke(null);
                getPlayerVersion = viaApiClass.getMethod("getPlayerVersion", UUID.class);
                viaApiInstance = api;
                viaGetPlayerVersion = getPlayerVersion;
            }
            int version = (int) getPlayerVersion.invoke(api, playerId);
            return version >= 771;
        } catch (Exception e) {
            return true; // 查询失败保守放行（不阻塞正常玩家），回退到聊天栏在 onJoin 处理
        }
    }

    /** 客户端语言（读取失败回退默认）。
     *  客户端发送的原始值是全小写格式（如 en_us），语言文件名为 en_US 形式（语言小写 + 地区大写），
     *  需规范化后再匹配，否则非默认语言玩家会看到默认语言 */
    private static String resolveLocale(PlayerConfigurationConnection conn) {
        try {
            // getClientOption 保证非空，仅需判断是否空白
            String locale = conn.getClientOption(ClientOption.LOCALE);
            if (locale.isBlank()) return null;
            int underscore = locale.indexOf('_');
            if (underscore > 0 && underscore < locale.length() - 1) {
                return locale.substring(0, underscore).toLowerCase(Locale.ROOT)
                        + "_" + locale.substring(underscore + 1).toUpperCase(Locale.ROOT);
            }
            return locale;
        } catch (Exception e) {
            return null;
        }
    }

    private static String clientIp(PlayerConfigurationConnection conn) {
        var address = conn.getClientAddress().getAddress();
        return address != null ? address.getHostAddress() : null;
    }

    // ===== 窗口展示与确认回调（仅做线程安全操作：重弹/断连/闭锁） =====

    private void showLogin(Session session, UUID uuid, String locale, Component error) {
        if (Debug.on()) {
            Debug.log("dialog", "show login dialog for %s", uuid.toString().substring(0, 8));
        }
        showDialog(session, dialogManager.buildLoginDialog(locale, error,
                loginConfirm(session, uuid, locale), cancel(session, uuid, locale)));
    }

    private void showRegister(Session session, UUID uuid, String locale, Component error) {
        if (Debug.on()) {
            Debug.log("dialog", "show register dialog for %s", uuid.toString().substring(0, 8));
        }
        showDialog(session, dialogManager.buildRegisterDialog(locale, error,
                registerConfirm(session, uuid, locale), cancel(session, uuid, locale)));
    }

    private void show2fa(Session session, UUID uuid, String locale, Component error) {
        if (Debug.on()) {
            Debug.log("dialog", "show 2fa dialog for %s", uuid.toString().substring(0, 8));
        }
        showDialog(session, dialogManager.build2faDialog(locale, error,
                twoFaConfirm(session, uuid, locale), cancel(session, uuid, locale)));
    }

    /** 取消：主动放弃登录并断连（pre-join 阶段尚未进世界）；标记 kicked 使 onConfigure 不放行，并唤醒配置线程 */
    private DialogActionCallback cancel(Session session, UUID uuid, String locale) {
        return (response, audience) -> {
            if (configSessions.get(uuid) != session) return;
            if (Debug.on()) {
                Debug.log("dialog", "cancel dialog for %s: disconnecting", uuid.toString().substring(0, 8));
            }
            session.kicked = true;
            session.latch.countDown();
            session.connection.disconnect(I18n.msgForLocale("dialog.cancelled", locale));
        };
    }

    /** 发送窗口；发送失败（意外）中止 pre-join，回退聊天栏提示 */
    private void showDialog(Session session, Dialog dialog) {
        try {
            session.connection.getAudience().showDialog(dialog);
        } catch (Exception e) {
            session.fallback = true;
            session.latch.countDown();
        }
    }

    /** 登录窗口确认：bcrypt 异步校验，失败重弹，需 2FA 切换验证窗口，达阈值断连 */
    private DialogActionCallback loginConfirm(Session session, UUID uuid, String locale) {
        return (response, audience) -> {
            // 会话已结束（超时/取消后提交）：忽略过期提交
            if (configSessions.get(uuid) != session) return;
            String password = response.getText("password");
            if (password == null || password.isEmpty()) {
                showLogin(session, uuid, locale, DialogManager.text(locale, "dialog.empty_password"));
                return;
            }
            authManager.loginConfigAsync(uuid, password, clientIp(session.connection), (result, kickSeconds) -> {
                if (configSessions.get(uuid) != session) return;
                if (Debug.on()) {
                    Debug.log("dialog", "login dialog confirm for %s: %s", uuid.toString().substring(0, 8), result);
                }
                switch (result) {
                    case SUCCESS -> {
                        session.success = true;
                        session.latch.countDown();
                    }
                    case NEED_2FA -> show2fa(session, uuid, locale, null);
                    case FAILED -> {
                        if (kickSeconds != null && kickSeconds > 0) {
                            session.kicked = true;
                            session.connection.disconnect(I18n.msgForLocale("login.kicked", locale, kickSeconds));
                            session.latch.countDown();
                        } else {
                            showLogin(session, uuid, locale, DialogManager.text(locale, "login.incorrect_password"));
                        }
                    }
                }
            });
        };
    }

    /** 注册窗口确认：校验与 /register 一致，成功仅建号（登录收尾延迟到进入世界时） */
    private DialogActionCallback registerConfirm(Session session, UUID uuid, String locale) {
        return (response, audience) -> {
            if (configSessions.get(uuid) != session) return;
            if (authManager.hasAccount(uuid)) {
                showLogin(session, uuid, locale, DialogManager.text(locale, "register.already_registered"));
                return;
            }
            String password = response.getText("password");
            String confirm = response.getText("confirm");
            if (password == null || password.isEmpty()) {
                showRegister(session, uuid, locale, DialogManager.text(locale, "dialog.empty_password"));
                return;
            }
            if (!password.equals(confirm)) {
                showRegister(session, uuid, locale, DialogManager.text(locale, "register.password_mismatch"));
                return;
            }
            String error = PasswordValidator.invalidMessage(plugin, locale, password);
            if (error != null) {
                showRegister(session, uuid, locale, HowSAuth.legacy(error));
                return;
            }
            String ip = clientIp(session.connection);
            String name = session.connection.getProfile().getName();
            // bcrypt 哈希与建号落库耗时，移到异步线程（与 loginConfirm 的 loginConfigAsync 同模式）；
            // 回调仅做线程安全操作：会话校验/重弹窗口/闭锁
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                if (configSessions.get(uuid) != session) return;
                if (authManager.registerConfig(uuid, name, password, ip)) {
                    if (Debug.on()) {
                        Debug.log("dialog", "register dialog confirm for %s: success", uuid.toString().substring(0, 8));
                    }
                    session.registered = true;
                    session.success = true;
                    session.latch.countDown();
                } else if (authManager.isIpAccountLimitReached(ip)) {
                    // 同 IP 注册数量已达上限：精确提示（连接层已拦已满 IP，此处兜底并发/延迟场景）
                    if (Debug.on()) {
                        Debug.log("dialog", "register dialog confirm for %s: failed (ip limit)",
                                uuid.toString().substring(0, 8));
                    }
                    showRegister(session, uuid, locale, I18n.msgForLocale("register.ip_limit", locale, plugin.getConfigManager().maxAccountsPerIp()));
                } else {
                    if (Debug.on()) {
                        Debug.log("dialog", "register dialog confirm for %s: failed", uuid.toString().substring(0, 8));
                    }
                    showRegister(session, uuid, locale, DialogManager.text(locale, "register.failed"));
                }
            });
        };
    }

    /** 双因素验证窗口确认：通过放行，失败重弹 */
    private DialogActionCallback twoFaConfirm(Session session, UUID uuid, String locale) {
        return (response, audience) -> {
            if (configSessions.get(uuid) != session) return;
            // 暴力破解踢出期内断连（无密码账户验证码错误达到阈值后进入踢出期，与密码登录失败行为一致）
            long kickRemaining = authManager.getKickRemaining(uuid);
            if (kickRemaining > 0) {
                session.kicked = true;
                session.connection.disconnect(I18n.msgForLocale("2fa.kicked", locale, kickRemaining));
                session.latch.countDown();
                return;
            }
            if (!authManager.isPending2fa(uuid)) {
                if (session.passwordless2fa) {
                    // 免密会话验证状态失效：重新标记待验证并重弹验证码窗口（正版玩家未必知晓密码，不能回到密码窗口）
                    authManager.addPending2fa(uuid);
                    show2fa(session, uuid, locale, null);
                } else {
                    // 密码验证状态已失效（插件重载等），回到登录窗口重新开始
                    showLogin(session, uuid, locale, null);
                }
                return;
            }
            String code = response.getText("code");
            if (code == null || code.isEmpty()) {
                show2fa(session, uuid, locale, DialogManager.text(locale, "dialog.empty_code"));
                return;
            }
            if (authManager.verify2faConfig(uuid, code, clientIp(session.connection))) {
                if (Debug.on()) {
                    Debug.log("dialog", "2fa dialog confirm for %s: success", uuid.toString().substring(0, 8));
                }
                session.success = true;
                session.latch.countDown();
            } else {
                if (Debug.on()) {
                    Debug.log("dialog", "2fa dialog confirm for %s: failed", uuid.toString().substring(0, 8));
                }
                show2fa(session, uuid, locale, DialogManager.text(locale, "2fa.confirm_incorrect"));
            }
        };
    }
}
