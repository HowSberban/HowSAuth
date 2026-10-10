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
 * 降级指令：将正版账号降级回离线账号。
 * 二次确认（/downgrade confirm）才添加降级标记，下一次进入服务器时把账号数据迁移到离线 UUID，
 * 此后以密码或验证码登录（降级前须已设置密码或绑定 2FA，否则降级后无法登录）；
 * /downgrade cancel 取消已提交的降级标记
 */
public final class DowngradeAccountCommand implements BasicCommand {

    private final HowSAuth plugin;

    public DowngradeAccountCommand(HowSAuth plugin) {
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
        UUID premiumUuid = player.getUniqueId();
        String sub = args.length > 0 ? args[0] : "";

        // 取消降级始终可用：不受正版状态与开关限制，仅移除已提交的标记
        if (sub.equalsIgnoreCase("cancel")) {
            player.sendMessage(I18n.msg(plugin.accounts().cancelDowngrade(premiumUuid)
                    ? "downgrade.cancelled" : "downgrade.nothing_to_cancel", player));
            return;
        }

        if (!plugin.accounts().isPremium(player)) {
            player.sendMessage(I18n.msg("downgrade.not_premium", player));
            return;
        }
        // 降级开关未开启时降级不可用（不受正版验证总开关控制）
        if (!plugin.config().premium().downgradeEnabled()) {
            player.sendMessage(I18n.msg("feature.disabled", player));
            return;
        }
        // 降级后离线账号须仍有登录手段：密码或 2FA 密钥，否则账号将被锁死
        if (plugin.accounts().isPasswordless(premiumUuid) && !plugin.twoFactor().hasTotpSecret(premiumUuid)) {
            player.sendMessage(I18n.msg("downgrade.need_login_method", player));
            return;
        }

        // confirm 才真正标记（幂等），无参数或未知参数仅给出提示，避免玩家误操作
        if (sub.equalsIgnoreCase("confirm")) {
            player.sendMessage(I18n.msg(plugin.accounts().markDowngrade(premiumUuid)
                    ? "downgrade.marked_success" : "downgrade.already_pending", player));
            return;
        }
        player.sendMessage(I18n.msg(plugin.accounts().hasPendingDowngrade(premiumUuid)
                ? "downgrade.already_pending" : "downgrade.confirm_required", player));
    }
}