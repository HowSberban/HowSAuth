package org.howsauth.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.auth.SessionStore;
import org.howsauth.plugin.auth.PasswordValidator;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class RegisterCommand implements BasicCommand {

    private final HowSAuth plugin;

    public RegisterCommand(HowSAuth plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack stack, String @NotNull [] args) {
        CommandSender sender = stack.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(I18n.msg("command.player_only"));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(I18n.msg("register.usage", player));
            return;
        }

        if (plugin.accounts().hasAccount(player)) {
            player.sendMessage(I18n.msg("register.already_registered", player));
            return;
        }

        String password = args[0];
        String confirm = args[1];

        if (!password.equals(confirm)) {
            player.sendMessage(I18n.msg("register.password_mismatch", player));
            return;
        }

        if (PasswordValidator.invalid(plugin, player, password)) return;

        // 同 IP 注册数量上限：此处拦截并精确提示；registerAsync 内仍有兜底判定（并发场景）
        var playerIp = SessionStore.clientIp(player);
        if (plugin.accounts().isIpAccountLimitReached(playerIp)) {
            player.sendMessage(I18n.msg("register.ip_limit", player, plugin.getConfigManager().maxAccountsPerIp()));
            return;
        }

        // 异步注册：bcrypt 哈希耗时，避免阻塞玩家区域线程；回调在玩家区域线程执行
        plugin.loginFlow().registerAsync(player, password, success -> {
            if (success) {
                player.sendMessage(I18n.msg("register.success", player));
                // 注册成功后传送到默认世界 spawn（启用坐标保护时生效）
                plugin.locations().teleportBack(player);
            } else {
                player.sendMessage(I18n.msg("register.failed", player));
            }
        });
    }
}
