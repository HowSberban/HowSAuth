package org.howsauth.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.auth.AuthManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家自助注销账号，两步执行：
 * 第一步凭据验证——按账户持有情况校验密码与 2FA 验证码（正版账户凭正版验证免验）；
 * 第二步 /unregister confirm 二次确认后删除。
 * 管理员删除他人账号走 /hsauth unreg
 */
public final class UnregisterCommand implements BasicCommand {

    /** 注销确认窗口（秒）：凭据验证通过后须在此时间内 confirm，超时作废需重新验证 */
    private static final long CONFIRM_WINDOW_SECONDS = 60;

    private final HowSAuth plugin;
    private final AuthManager authManager;
    // 待确认的注销请求：UUID → 到期时间戳，凭据验证通过后写入，confirm 时校验
    private final Map<UUID, Long> pendingConfirms = new ConcurrentHashMap<>();

    public void clearPendingConfirm(UUID uuid) {
        pendingConfirms.remove(uuid);
    }

    public UnregisterCommand(HowSAuth plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
    }

    @Override
    public void execute(CommandSourceStack stack, String @NotNull [] args) {
        CommandSender sender = stack.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(I18n.msg("command.player_only"));
            return;
        }

        if (!plugin.sessions().isLoggedIn(player)) {
            player.sendMessage(I18n.msg("listener.must_login", player));
            return;
        }

        if (!plugin.getConfigManager().allowSelfUnregister()) {
            player.sendMessage(I18n.msg("unregister.self_disabled", player));
            return;
        }

        UUID uuid = player.getUniqueId();
        // 二次确认：仅当存在待确认请求时，"confirm" 才作为确认指令（否则作为密码/验证码参数）
        if (args.length == 1 && "confirm".equalsIgnoreCase(args[0])
                && pendingConfirms.containsKey(uuid)) {
            Long expiry = pendingConfirms.remove(uuid);
            if (expiry != null && System.currentTimeMillis() > expiry) {
                player.sendMessage(I18n.msg("unregister.confirm_expired", player));
                return;
            }
            // 账号在验证后被他人删除等竞态：无账号可删
            if (!authManager.unregister(uuid)) {
                player.sendMessage(I18n.msg("hsauth.accounts_not_found", player));
                return;
            }
            // 在线删除：kick 触发 PlayerQuitEvent 完成 .dat 删除（避免文件锁冲突）
            player.kick(I18n.msg("unregister.self_kick", player));
            return;
        }

        // 第一步：凭据验证（按账户持有情况组合参数，正版免验）
        boolean premium = authManager.isPremium(uuid);
        boolean passwordless = authManager.isPasswordless(uuid);
        boolean has2fa = plugin.twoFactor().hasTotpSecret(uuid);

        if (!premium) {
            // 所需参数个数 = 密码（非无密码账户）+ 验证码（已绑定）
            int required = (passwordless ? 0 : 1) + (has2fa ? 1 : 0);
            if (args.length < required) {
                player.sendMessage(I18n.msg("unregister.usage", player));
                return;
            }
        }

        String password = null;
        String code = null;
        if (!premium && args.length > 0) {
            if (passwordless) {
                // 无密码账户：唯一参数即验证码
                code = args[0];
            } else {
                password = args[0];
                // 参数个数检查已保证 has2fa 时有第二个参数
                if (has2fa) code = args[1];
            }
        }

        String pw = password;
        String totp = code;
        authManager.verifyUnregisterCredentialsAsync(player, pw, totp, ok -> {
            if (!ok) {
                player.sendMessage(I18n.msg("unregister.incorrect_credentials", player));
                return;
            }
            pendingConfirms.put(uuid, System.currentTimeMillis() + CONFIRM_WINDOW_SECONDS * 1000);
            player.sendMessage(I18n.msg("unregister.confirm_prompt", player, CONFIRM_WINDOW_SECONDS));
        });
    }
}