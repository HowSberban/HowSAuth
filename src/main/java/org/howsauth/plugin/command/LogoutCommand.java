package org.howsauth.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.auth.AuthManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class LogoutCommand implements BasicCommand {

    private final AuthManager authManager;

    public LogoutCommand(AuthManager authManager) {
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
            player.sendMessage(I18n.msg("logout.not_logged_in", player));
            return;
        }

        // 登出流程：进入待登录状态 → 踢出服务器
        // 退出位置由退出流程统一保存（PlayerListener#onQuit 对本次连接已认证的玩家保存）
        authManager.logout(player);
        player.kick(I18n.msg("logout.success", player));
    }
}
