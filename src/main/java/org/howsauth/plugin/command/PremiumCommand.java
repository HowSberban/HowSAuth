package org.howsauth.plugin.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.auth.AuthManager;
import org.howsauth.plugin.data.PlayerDataManager.PlayerData;
import org.howsauth.plugin.premium.DataService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * premium 管理命令，强制切换玩家账号是否为正版，使用 brigadier 原生注册以支持玩家名补全。
 * 正版 → 离线：立即迁移账号到离线 UUID（跳过登录手段检查，管理员担责）；
 * 离线 → 正版：仅标记 premium=1，其余字段留待玩家下次正版验证进服时写入正式记录
 */
@SuppressWarnings("SameReturnValue")
public final class PremiumCommand {

    private final HowSAuth plugin;
    private final AuthManager authManager;

    public PremiumCommand(HowSAuth plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
    }

    /** 构建命令树节点（由 HowSAuth 注册时调用） */
    public LiteralCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> buildNode() {
        return literal("premium")
                .requires(stack -> stack.getSender().hasPermission("hsauth.admin"))
                .then(argument("player", StringArgumentType.word())
                        .suggests(HSAuthCommand.suggestAllPlayers(plugin))
                        .executes(this::execute))
                .build();
    }

    private int execute(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");

        // 纯内存改标记 + 异步落库，无阻塞 IO，主线程直接执行
        // 先判 null：findUuidByName 对无账号名字返回 null，直接传入 getPlayer 会抛 NPE
        UUID targetUuid = plugin.getPlayerDataManager().findUuidByName(targetName);
        PlayerData data = targetUuid == null ? null : plugin.getPlayerDataManager().getPlayer(targetUuid);
        if (data == null) {
            sender.sendMessage(I18n.msg("hsauth.accounts_not_found", sender));
            return Command.SINGLE_SUCCESS;
        }
        // 三分支：真·正版记录（含名字）→ 降级离线；forceMarkPremium 残留态（premium=1 但无名字，
        // 尚未正版验证进服）→ 再次切换即撤销标记；普通离线记录 → 标记为正版
        if (data.premium() && data.name() != null) {
            // 正版 → 离线：立即迁移到离线 UUID（与 /downgrade 相同迁移，跳过登录手段检查）
            // 玩家在线时先踢出：其游戏内身份仍是正版 UUID，若不踢出，迁移后在线数据更新会
            // 落到已移除的记录上造成记录分裂；踢出时 onQuit 先于迁移执行，退出位置等数据仍被完整带走
            Player online = Bukkit.getPlayer(data.uuid());
            if (online != null) {
                online.kick(I18n.msg("premium.kicked", online));
            }
            // 离线 UUID 由名字推导且大小写敏感，必须用数据库记录的名字（管理员输入可能大小写不同，
            // 否则玩家重进时按真实名字换算的离线 UUID 与迁移后的记录对不上）
            // 无密码且无 2FA 时降级后账号将无法登录，仅警告不阻止（管理员强制操作）
            // 须在迁移前判定：executeDowngrade 会把记录移到离线 UUID，之后按旧 UUID 查询必为空
            boolean noLoginMethod = authManager.isPasswordless(data.uuid()) && !authManager.hasTotpSecret(data.uuid());
            authManager.executeDowngrade(data.uuid(), DataService.offlineUuid(data.name()), data.name());
            if (noLoginMethod) {
                sender.sendMessage(I18n.msg("premium.no_login_method", sender, targetName));
            }
            sender.sendMessage(I18n.msg("premium.switched_offline", sender, targetName));
        } else if (data.premium()) {
            // 残留态（管理员此前标记为正版但玩家尚未正版验证进服）：撤销标记回离线
            plugin.getPlayerDataManager().forceMarkOffline(data.uuid());
            sender.sendMessage(I18n.msg("premium.switched_offline", sender, targetName));
        } else {
            // 离线 → 正版：仅标记 premium=1，玩家下次正版验证进服时填充正式记录
            plugin.getPlayerDataManager().forceMarkPremium(data.uuid());
            sender.sendMessage(I18n.msg("premium.switched_premium", sender, targetName));
        }
        return Command.SINGLE_SUCCESS;
    }
}