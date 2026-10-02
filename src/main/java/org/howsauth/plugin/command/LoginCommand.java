package org.howsauth.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.auth.AuthManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class LoginCommand implements BasicCommand {

    private final AuthManager authManager;

    public LoginCommand(AuthManager authManager) {
        this.authManager = authManager;
    }

    @Override
    public void execute(CommandSourceStack stack, String @NotNull [] args) {
        CommandSender sender = stack.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(I18n.msg("command.player_only"));
            return;
        }

        if (authManager.isLoggedIn(player)) {
            player.sendMessage(I18n.msg("login.already_logged_in", player));
            return;
        }

        if (!authManager.hasAccount(player)) {
            player.sendMessage(I18n.msg("login.no_account", player));
            return;
        }

        // 无密码账户不走密码登录，验证码是唯一登录因素
        if (authManager.isPasswordless(player.getUniqueId())) {
            player.sendMessage(I18n.msg("login.passwordless_no_password", player));
            return;
        }

        if (args.length < 1) {
            player.sendMessage(I18n.msg("login.usage", player));
            return;
        }

        // 异步登录：bcrypt 校验在异步线程执行，回调回到玩家区域线程处理结果
        // 踢出期检查已包含在 loginAsync 的轻量检查中，FAILED 回调的 kickSeconds > 0 即踢出
        authManager.loginAsync(player, args[0], (result, kickSeconds) -> {
            switch (result) {
                case SUCCESS -> {
                    player.sendMessage(I18n.msg("login.success", player));
                    // 登录成功后传送回上次退出位置（启用坐标保护时生效）
                    authManager.returnToLogoutLocation(player);
                }
                case NEED_2FA -> player.sendMessage(I18n.msg("login.need_2fa", player));
                case FAILED -> {
                    if (kickSeconds != null && kickSeconds > 0) {
                        // 踢出期内（本次失败达到上限触发）
                        player.kick(I18n.msg("login.kicked", kickSeconds));
                    } else {
                        player.sendMessage(I18n.msg("login.incorrect_password", player));
                    }
                }
            }
        });
    }
}
