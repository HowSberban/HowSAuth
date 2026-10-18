package org.howsauth.plugin.listener;

import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.auth.AccountLifecycle;
import org.howsauth.plugin.auth.FailProtection;
import org.howsauth.plugin.auth.LogoutLocation;
import org.howsauth.plugin.auth.SessionStore;
import org.howsauth.plugin.config.ConfigManager;

/**
 * 连接阶段准入检查：数据库不可用、踢出期、无可用登录方式、刚注销、同 IP 名额超限。
 * 从 {@link PlayerListener} 拆出，避免加入/退出/界面逻辑与本组拒绝条件混在一起。
 */
final class PreLoginGuard {

    private final HowSAuth plugin;
    private final SessionStore sessions;
    private final FailProtection failProtection;
    private final AccountLifecycle accounts;
    private final LogoutLocation locations;

    PreLoginGuard(HowSAuth plugin, SessionStore sessions, FailProtection failProtection,
                  AccountLifecycle accounts, LogoutLocation locations) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.failProtection = failProtection;
        this.accounts = accounts;
        this.locations = locations;
    }

    // 在玩家加入世界前拦截：踢出期玩家、同一 IP 账号数量超限
    // 使用 AsyncPlayerPreLoginEvent 替代已弃用的 PlayerLoginEvent（1.21.6+）
    // PlayerDataManager 为插件生命周期内共享的服务，不在此处关闭；此处仅调用其查询方法
    @SuppressWarnings("resource")
    void check(AsyncPlayerPreLoginEvent event) {
        var uuid = event.getUniqueId();
        // 取一次配置快照：本方法多处读配置，reload 换掉领域对象时避免读到新旧混合的值
        ConfigManager config = plugin.config();

        // 数据库加载失败（fail-closed）：缓存为空会把所有玩家误判为未注册，拒绝进入直至恢复
        if (plugin.playerData().isLoadFailed()) {
            if (Debug.on()) {
                Debug.log("flow", "prelogin reject %s: database load failed", event.getName());
            }
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    I18n.msg("login.db_unavailable"));
            return;
        }

        // 踢出期内拒绝进入
        if (failProtection.isKicked(uuid)) {
            long remaining = failProtection.getKickRemaining(uuid);
            if (Debug.on()) {
                Debug.log("flow", "prelogin reject %s: kick period active (%s ms left)", event.getName(), remaining);
            }
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    I18n.msg("login.kicked", remaining));
            return;
        }

        // 有账号但无任何可用登录方式（无密码/未绑 2FA/非正版）。
        // 配置开启时在连接阶段直接拦截；关闭时放行，由 beginAuthFlow 挂起（永远无法通过，超时踢出）
        if (config.protectionMisc().rejectNoAuthAccount()
                && accounts.hasNoUsableLoginMethod(uuid)) {
            if (Debug.on()) {
                Debug.log("flow", "prelogin reject %s: no usable login method", event.getName());
            }
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    I18n.msg("prelogin.account_locked"));
            return;
        }

        // 注销后 5 秒内拒绝重连，确保 .dat 删除完成
        if (sessions.isRecentlyUnregistered(uuid)) {
            long remaining = sessions.getRecentUnregisterRemaining(uuid);
            if (Debug.on()) {
                Debug.log("flow", "prelogin reject %s: recently unregistered (%s ms left)", event.getName(), remaining);
            }
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    I18n.msg("unregister.recently_deleted", remaining));
            return;
        }

        // 同 IP 已达上限时仅在连接层拦截无账号新玩家，避免名额已满的 IP 涌入未注册玩家；
        // max-accounts-per-ip.reject-join 关闭时放行进服，由注册动作精确判定（共享 IP 环境友好）
        // 已达上限判定内部已处理 max<=0，无需在此重复判断
        if (config.register().ipLimitRejectJoin()
                && !accounts.hasAccount(uuid)
                && accounts.isIpAccountLimitReached(event.getAddress().getHostAddress())) {
            if (Debug.on()) {
                Debug.log("flow", "prelogin reject %s: ip account limit reached (max %s)",
                        event.getName(), config.register().maxAccountsPerIp());
            }
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    I18n.msg("register.ip_limit",
                            config.register().maxAccountsPerIp()));
        }

        // 放行玩家异步预载退出位置区块：fire-and-forget，不阻塞、不影响放行判定
        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            locations.preloadChunk(uuid);
        }
    }
}
