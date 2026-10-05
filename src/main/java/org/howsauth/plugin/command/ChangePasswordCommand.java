package org.howsauth.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.auth.PasswordValidator;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class ChangePasswordCommand implements BasicCommand {

    private final HowSAuth plugin;

    public ChangePasswordCommand(HowSAuth plugin) {
        this.plugin = plugin;
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

        // 无密码账户没有旧密码，设置密码走 /addpassword
        if (plugin.accounts().isPasswordless(player.getUniqueId())) {
            player.sendMessage(I18n.msg("changepw.use_addpassword", player));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(I18n.msg("changepw.usage", player));
            return;
        }

        String oldPassword = args[0];
        String newPassword = args[1];

        if (PasswordValidator.invalid(plugin, player, newPassword)) return;

        // 异步修改：旧密码校验与新密码哈希（bcrypt 耗时）在异步线程执行，回调回到玩家区域线程
        plugin.loginFlow().changePasswordAsync(player, oldPassword, newPassword, success ->
                player.sendMessage(I18n.msg(success ? "changepw.success" : "changepw.incorrect_old", player)));
    }
}
