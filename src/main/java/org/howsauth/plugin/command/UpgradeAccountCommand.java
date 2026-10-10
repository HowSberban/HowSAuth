package org.howsauth.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 升级指令：将离线账号升级为正版账号。
 * 玩家输入指令后添加升级标记，下一次进入服务器时尝试正版验证，
 * 验证成功则迁移账号为正版（保留退出位置等数据），失败则回退为离线账号。
 */
public final class UpgradeAccountCommand implements BasicCommand {

    private final HowSAuth plugin;

    public UpgradeAccountCommand(HowSAuth plugin) {
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
        if (plugin.accounts().isPremium(player)) {
            player.sendMessage(I18n.msg("upgrade.already_premium", player));
            return;
        }
        // 正版验证总开关或升级开关未开启时升级不可用
        if (!plugin.config().premium().enabled() || !plugin.config().premium().upgradeEnabled()) {
            player.sendMessage(I18n.msg("feature.disabled", player));
            return;
        }

        UUID offlineUuid = player.getUniqueId();
        if (!plugin.accounts().hasAccount(offlineUuid)) {
            player.sendMessage(I18n.msg("upgrade.no_account", player));
            return;
        }

        // 重复执行即取消已提交的升级请求
        if (plugin.accounts().toggleUpgrade(offlineUuid)) {
            player.sendMessage(I18n.msg("upgrade.marked_success", player));
        } else {
            player.sendMessage(I18n.msg("upgrade.cancelled", player));
        }
    }
}