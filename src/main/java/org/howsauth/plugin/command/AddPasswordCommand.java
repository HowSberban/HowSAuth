package org.howsauth.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.auth.AuthManager;
import org.howsauth.plugin.auth.PasswordValidator;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** 为无密码账户设置密码（/addpassword <新密码> <确认新密码>），已有密码的账户请使用 /changepassword */
public final class AddPasswordCommand implements BasicCommand {

    private final HowSAuth plugin;
    private final AuthManager authManager;

    public AddPasswordCommand(HowSAuth plugin, AuthManager authManager) {
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

        if (!authManager.isLoggedIn(player)) {
            player.sendMessage(I18n.msg("listener.must_login", player));
            return;
        }

        if (!authManager.isPasswordless(player.getUniqueId())) {
            player.sendMessage(I18n.msg("addpassword.has_password", player));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(I18n.msg("addpassword.usage", player));
            return;
        }

        String password = args[0];
        String confirm = args[1];

        if (!password.equals(confirm)) {
            player.sendMessage(I18n.msg("register.password_mismatch", player));
            return;
        }

        if (PasswordValidator.invalid(plugin, player, password)) return;

        // 异步设置：bcrypt 哈希耗时，避免阻塞玩家区域线程；回调在玩家区域线程执行
        authManager.addPasswordAsync(player, password, success ->
                player.sendMessage(I18n.msg(success ? "addpassword.success" : "addpassword.failed", player)));
    }
}
