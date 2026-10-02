package org.howsauth.plugin.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.auth.AuthManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * 降级指令：将正版账号降级回离线账号。
 * 玩家输入指令后添加降级标记，下一次进入服务器时把账号数据迁移到离线 UUID，
 * 此后以密码或验证码登录（降级前须已设置密码或绑定 2FA，否则降级后无法登录）
 */
public final class DowngradeAccountCommand implements BasicCommand {

    private final HowSAuth plugin;
    private final AuthManager authManager;

    public DowngradeAccountCommand(HowSAuth plugin, AuthManager authManager) {
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
        if (!authManager.isPremium(player)) {
            player.sendMessage(I18n.msg("downgrade.not_premium", player));
            return;
        }
        // 正版验证总开关或降级开关未开启时降级不可用
        if (!plugin.getConfigManager().premiumEnabled() || !plugin.getConfigManager().premiumDowngradeEnabled()) {
            player.sendMessage(I18n.msg("downgrade.disabled", player));
            return;
        }
        UUID premiumUuid = player.getUniqueId();
        // 降级后离线账号须仍有登录手段：密码或 2FA 密钥，否则账号将被锁死
        if (authManager.isPasswordless(premiumUuid) && !authManager.hasTotpSecret(premiumUuid)) {
            player.sendMessage(I18n.msg("downgrade.need_login_method", player));
            return;
        }

        // 重复执行即取消已提交的降级请求
        if (authManager.toggleDowngrade(premiumUuid)) {
            player.sendMessage(I18n.msg("downgrade.marked_success", player));
        } else {
            player.sendMessage(I18n.msg("downgrade.cancelled", player));
        }
    }
}