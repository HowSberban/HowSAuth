package org.howsauth.plugin.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.auth.AccountLifecycle;
import org.howsauth.plugin.auth.FailProtection;
import org.howsauth.plugin.auth.LoginFlow;
import org.howsauth.plugin.auth.PasswordValidator;
import org.howsauth.plugin.auth.TwoFactorAuth;

import java.util.Map;
import java.util.UUID;

/**
 * Pre-join Dialog 的窗口展示与确认回调。
 * <p>
 * 只做线程安全操作（重弹/断连/闭锁）；会话查找与状态机推进在 {@link PreJoinAuthListener}。
 * 窗口类型（登录/注册/2FA）与各自的重试、踢出、取消规则集中在此。
 */
final class PreJoinDialogFlow {

    private final HowSAuth plugin;
    private final LoginFlow loginFlow;
    private final TwoFactorAuth twoFactor;
    private final AccountLifecycle accounts;
    private final FailProtection failProtection;
    private final DialogManager dialogManager;
    private final Map<UUID, PreJoinSession> configSessions;

    PreJoinDialogFlow(HowSAuth plugin, LoginFlow loginFlow, TwoFactorAuth twoFactor,
                      AccountLifecycle accounts, FailProtection failProtection,
                      DialogManager dialogManager, Map<UUID, PreJoinSession> configSessions) {
        this.plugin = plugin;
        this.loginFlow = loginFlow;
        this.twoFactor = twoFactor;
        this.accounts = accounts;
        this.failProtection = failProtection;
        this.dialogManager = dialogManager;
        this.configSessions = configSessions;
    }

    void showLogin(PreJoinSession session, UUID uuid, String locale, Component error) {
        if (Debug.on()) {
            Debug.log("dialog", "show login dialog for %s", Debug.shortId(uuid));
        }
        showDialog(session, dialogManager.buildLoginDialog(locale, error,
                loginConfirm(session, uuid, locale), cancel(session, uuid, locale)));
    }

    void showRegister(PreJoinSession session, UUID uuid, String locale, Component error) {
        if (Debug.on()) {
            Debug.log("dialog", "show register dialog for %s", Debug.shortId(uuid));
        }
        showDialog(session, dialogManager.buildRegisterDialog(locale, error,
                registerConfirm(session, uuid, locale), cancel(session, uuid, locale)));
    }

    void show2fa(PreJoinSession session, UUID uuid, String locale, Component error) {
        if (Debug.on()) {
            Debug.log("dialog", "show 2fa dialog for %s", Debug.shortId(uuid));
        }
        showDialog(session, dialogManager.build2faDialog(locale, error,
                twoFaConfirm(session, uuid, locale), cancel(session, uuid, locale)));
    }

    /** 取消：主动放弃登录并断连（pre-join 阶段尚未进世界）；标记 kicked 使 onConfigure 不放行，并唤醒配置线程 */
    private DialogActionCallback cancel(PreJoinSession session, UUID uuid, String locale) {
        return (response, audience) -> {
            if (configSessions.get(uuid) != session) return;
            if (Debug.on()) {
                Debug.log("dialog", "cancel dialog for %s: disconnecting", Debug.shortId(uuid));
            }
            session.kicked = true;
            session.latch.countDown();
            session.connection.disconnect(I18n.msgForLocale("dialog.cancelled", locale));
        };
    }

    /** 发送窗口；发送失败（意外）中止 pre-join，回退聊天栏提示 */
    private void showDialog(PreJoinSession session, Dialog dialog) {
        try {
            session.connection.getAudience().showDialog(dialog);
        } catch (Exception e) {
            session.fallback = true;
            session.latch.countDown();
        }
    }

    /** 登录窗口确认：bcrypt 异步校验，失败重弹，需 2FA 切换验证窗口，达阈值断连 */
    private DialogActionCallback loginConfirm(PreJoinSession session, UUID uuid, String locale) {
        return (response, audience) -> {
            // 会话已结束（超时/取消后提交）：忽略过期提交
            if (configSessions.get(uuid) != session) return;
            String password = response.getText("password");
            if (password == null || password.isEmpty()) {
                showLogin(session, uuid, locale, DialogManager.text(locale, "dialog.empty_password"));
                return;
            }
            loginFlow.loginConfigAsync(uuid, password, PreJoinClientSupport.clientIp(session.connection), (result, kickSeconds) -> {
                if (configSessions.get(uuid) != session) return;
                if (Debug.on()) {
                    Debug.log("dialog", "login dialog confirm for %s: %s", Debug.shortId(uuid), result);
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
    private DialogActionCallback registerConfirm(PreJoinSession session, UUID uuid, String locale) {
        return (response, audience) -> {
            if (configSessions.get(uuid) != session) return;
            if (accounts.hasAccount(uuid)) {
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
            String ip = PreJoinClientSupport.clientIp(session.connection);
            String name = session.connection.getProfile().getName();
            // bcrypt 哈希与建号落库耗时，移到异步线程（与 loginConfirm 的 loginConfigAsync 同模式）；
            // 回调仅做线程安全操作：会话校验/重弹窗口/闭锁
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                if (configSessions.get(uuid) != session) return;
                if (accounts.registerConfig(uuid, name, password, ip)) {
                    if (Debug.on()) {
                        Debug.log("dialog", "register dialog confirm for %s: success", Debug.shortId(uuid));
                    }
                    session.registered = true;
                    session.success = true;
                    session.latch.countDown();
                } else if (accounts.isIpAccountLimitReached(ip)) {
                    // 同 IP 注册数量已达上限：精确提示（连接层已拦已满 IP，此处兜底并发/延迟场景）
                    if (Debug.on()) {
                        Debug.log("dialog", "register dialog confirm for %s: failed (ip limit)",
                                Debug.shortId(uuid));
                    }
                    showRegister(session, uuid, locale, I18n.msgForLocale("register.ip_limit", locale, plugin.config().register().maxAccountsPerIp()));
                } else {
                    if (Debug.on()) {
                        Debug.log("dialog", "register dialog confirm for %s: failed", Debug.shortId(uuid));
                    }
                    showRegister(session, uuid, locale, DialogManager.text(locale, "register.failed"));
                }
            });
        };
    }

    /** 双因素验证窗口确认：通过放行，失败重弹 */
    private DialogActionCallback twoFaConfirm(PreJoinSession session, UUID uuid, String locale) {
        return (response, audience) -> {
            if (configSessions.get(uuid) != session) return;
            // 暴力破解踢出期内断连（无密码账户验证码错误达到阈值后进入踢出期，与密码登录失败行为一致）
            long kickRemaining = failProtection.getKickRemaining(uuid);
            if (kickRemaining > 0) {
                session.kicked = true;
                session.connection.disconnect(I18n.msgForLocale("2fa.kicked", locale, kickRemaining));
                session.latch.countDown();
                return;
            }
            if (!twoFactor.isPending(uuid)) {
                if (session.passwordless2fa) {
                    // 免密会话验证状态失效：重新标记待验证并重弹验证码窗口（正版玩家未必知晓密码，不能回到密码窗口）
                    twoFactor.markPending(uuid);
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
            if (loginFlow.verify2faConfig(uuid, code, PreJoinClientSupport.clientIp(session.connection))) {
                if (Debug.on()) {
                    Debug.log("dialog", "2fa dialog confirm for %s: success", Debug.shortId(uuid));
                }
                session.success = true;
                session.latch.countDown();
            } else {
                if (Debug.on()) {
                    Debug.log("dialog", "2fa dialog confirm for %s: failed", Debug.shortId(uuid));
                }
                show2fa(session, uuid, locale, DialogManager.text(locale, "2fa.confirm_incorrect"));
            }
        };
    }
}
