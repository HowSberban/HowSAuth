package org.howsauth.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.data.PlayerData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 升级指令：将离线账号升级为正版账号。
 * <p>
 * /upgrade：切换升级标记（默认覆盖模式）。
 * /upgrade confirm：使用当前离线账号数据覆盖目标正版账号。
 * /upgrade &lt;玩家名&gt;：升级到指定正版账号，并保留该账号原有数据，不执行覆盖。
 * /upgrade cancel：取消升级请求。
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
        String sub = args.length > 0 ? args[0] : "";
        UUID offlineUuid = player.getUniqueId();

        // 取消不依赖当前账号状态，随时可用
        if (sub.equalsIgnoreCase("cancel")) {
            player.sendMessage(I18n.msg(plugin.accounts().cancelUpgrade(offlineUuid)
                    ? "upgrade.cancelled" : "upgrade.nothing_to_cancel", player));
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
        if (!plugin.accounts().hasAccount(offlineUuid)) {
            player.sendMessage(I18n.msg("upgrade.no_account", player));
            return;
        }

        // 显式覆盖模式；与默认无参升级一致
        if (sub.equalsIgnoreCase("confirm")) {
            plugin.accounts().markUpgrade(offlineUuid, false, null);
            player.sendMessage(I18n.msg("upgrade.marked_success", player));
            return;
        }

        // 指定正版玩家名：升级到该账号，但保留目标账号原有数据
        if (!sub.isEmpty()) {
            PlayerData target = plugin.playerData().getByName(sub);
            if (target == null || !target.premium() || target.uuid().equals(offlineUuid)) {
                player.sendMessage(I18n.msg("upgrade.inherit_target_missing", player, sub));
                return;
            }
            plugin.accounts().markUpgrade(offlineUuid, true, target.name());
            player.sendMessage(I18n.msg("upgrade.inherit_marked", player, target.name()));
            return;
        }

        // 无参数保持原有切换语义：无标记则默认覆盖升级，已有标记则取消
        if (plugin.accounts().hasPendingUpgrade(offlineUuid)) {
            plugin.accounts().cancelUpgrade(offlineUuid);
            player.sendMessage(I18n.msg("upgrade.cancelled", player));
        } else {
            plugin.accounts().markUpgrade(offlineUuid, false, null);
            player.sendMessage(I18n.msg("upgrade.marked_success", player));
        }
    }
}
