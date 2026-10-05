package org.howsauth.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.auth.AuthManager;
import org.howsauth.plugin.auth.SessionStore;
import org.howsauth.plugin.auth.TwoFactorAuth;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * 移除密码，转为无密码账户：
 * 离线账户须已绑定双因素认证，移除时输入验证码确认（验证码成为唯一登录因素）；
 * 正版账户以正版验证为身份凭证，直接放行无需验证码（验证失败时无回退登录，直接踢出）
 */
public final class RemovePasswordCommand implements BasicCommand {

    private final AuthManager authManager;
    private final SessionStore sessions;
    private final TwoFactorAuth twoFactor;

    public RemovePasswordCommand(AuthManager authManager, SessionStore sessions, TwoFactorAuth twoFactor) {
        this.authManager = authManager;
        this.sessions = sessions;
        this.twoFactor = twoFactor;
    }

    @Override
    public void execute(CommandSourceStack stack, String @NotNull [] args) {
        CommandSender sender = stack.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(I18n.msg("command.player_only"));
            return;
        }

        if (!sessions.isLoggedIn(player)) {
            player.sendMessage(I18n.msg("listener.must_login", player));
            return;
        }

        if (authManager.isPasswordless(player.getUniqueId())) {
            player.sendMessage(I18n.msg("removepassword.already", player));
            return;
        }

        // 离线账户必须已绑定 2FA，否则移除密码后账号无任何验证因素
        boolean bound2fa = twoFactor.hasTotpSecret(player.getUniqueId());
        if (!bound2fa && !authManager.isPremium(player)) {
            player.sendMessage(I18n.msg("removepassword.need_2fa", player));
            return;
        }

        String code = args.length > 0 ? args[0] : null;
        // 仅离线账户已绑定 2FA 时需要验证码确认（验证码成为唯一登录因素）；正版玩家凭正版验证直接放行
        boolean needCode = bound2fa && !authManager.isPremium(player);
        if (needCode && (code == null || code.isEmpty())) {
            player.sendMessage(I18n.msg("removepassword.usage", player));
            return;
        }

        if (authManager.removePassword(player, code)) {
            player.sendMessage(I18n.msg("removepassword.success", player));
        } else {
            player.sendMessage(I18n.msg("2fa.confirm_incorrect", player));
        }
    }
}
