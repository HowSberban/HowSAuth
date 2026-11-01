package org.howsauth.plugin.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;

import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.data.PlayerDataManager;
import org.howsauth.plugin.dialog.PreJoinAuthListener;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * hsauth 管理命令，使用 brigadier 原生注册。
 * <p>
 * 本类只负责命令树、补全和用法提示；检查类与账号操作类子命令分别由
 * {@link AdminInspectionHandlers} 与 {@link AdminAccountHandlers} 处理。
 */
@SuppressWarnings("SameReturnValue")
public final class HSAuthCommand {

    private final AdminInspectionHandlers inspectionHandlers;
    private final AdminAccountHandlers accountHandlers;
    private final SuggestionProvider<io.papermc.paper.command.brigadier.CommandSourceStack> suggestAllPlayers;
    private final SuggestionProvider<io.papermc.paper.command.brigadier.CommandSourceStack> suggestLoginTargets;

    public HSAuthCommand(HowSAuth plugin, PlayerDataManager dataManager) {
        this.inspectionHandlers = new AdminInspectionHandlers(plugin, dataManager);
        this.accountHandlers = new AdminAccountHandlers(plugin, dataManager);
        this.suggestAllPlayers = suggestAllPlayers(dataManager);
        this.suggestLoginTargets = suggestLoginTargets(plugin);
    }

    /** 补全：在线玩家 + 已注册的离线玩家（大小写不敏感前缀匹配）。static，供其它命令复用。 */
    public static SuggestionProvider<io.papermc.paper.command.brigadier.CommandSourceStack> suggestAllPlayers(
            PlayerDataManager dataManager) {
        return (context, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            // 先添加在线玩家
            for (Player player : Bukkit.getOnlinePlayers()) {
                String name = player.getName();
                if (remaining.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(name);
                }
            }
            // 再添加已注册的离线玩家
            for (UUID uuid : dataManager.getAllUuids()) {
                if (Bukkit.getPlayer(uuid) != null) continue;
                OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
                String name = offline.getName();
                if (name != null && (remaining.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith(remaining))) {
                    builder.suggest(name);
                }
            }
            return builder.buildFuture();
        };
    }

    /** 补全强制登录目标：在线玩家 + 处于 Pre-join Dialog 等待中的玩家。 */
    private static SuggestionProvider<io.papermc.paper.command.brigadier.CommandSourceStack> suggestLoginTargets(
            HowSAuth plugin) {
        return (context, builder) -> {
            String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
            Set<String> names = new LinkedHashSet<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            PreJoinAuthListener preJoin = plugin.preJoinAuth();
            if (preJoin != null) {
                names.addAll(preJoin.waitingPlayerNames());
            }
            for (String name : names) {
                if (remaining.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                    builder.suggest(name);
                }
            }
            return builder.buildFuture();
        };
    }

    /** 构建命令树节点（由 HowSAuth 注册时调用） */
    public LiteralCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> buildNode() {
        return literal("hsauth")
                .requires(stack -> stack.getSender().hasPermission("hsauth.admin"))
                // /hsauth — 显示用法
                .executes(this::showUsage)
                // /hsauth reload
                .then(literal("reload")
                        .executes(inspectionHandlers::handleReload))
                // /hsauth debug dump
                .then(literal("debug")
                        .then(literal("dump")
                                .executes(inspectionHandlers::handleDebugDump)))
                // /hsauth accounts <player>
                .then(literal("accounts")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(suggestAllPlayers)
                                .executes(inspectionHandlers::handleAccounts)))
                // /hsauth forcelogout <player>
                .then(literal("forcelogout")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(suggestAllPlayers)
                                .executes(accountHandlers::handleForceLogout)))
                // /hsauth forcechangepw <player> <newpassword>
                .then(literal("forcechangepw")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(suggestAllPlayers)
                                .then(argument("newpassword", StringArgumentType.word())
                                        .executes(accountHandlers::handleForceChangePw))))
                // /hsauth forcermpw <player>：管理员强制清空玩家密码（转为无密码账户，凭正版验证或 2FA 登录）
                .then(literal("forcermpw")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(suggestAllPlayers)
                                .executes(accountHandlers::handleForceRemovePw)))
                // /hsauth forcelogin <player>
                .then(literal("forcelogin")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(suggestLoginTargets)
                                .executes(accountHandlers::handleForceLogin)))
                // /hsauth forceregister <player> <password>
                .then(literal("forceregister")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(suggestAllPlayers)
                                .then(argument("password", StringArgumentType.word())
                                        .executes(accountHandlers::handleForceRegister))))
                // /hsauth reset2fa <player>：管理员强制解除 2FA 绑定（玩家误删验证器密钥时解锁）
                .then(literal("reset2fa")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(suggestAllPlayers)
                                .executes(accountHandlers::handleReset2fa)))
                // /hsauth unreg <player>：删除账号（管理员，无别名）
                .then(literal("unreg")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(suggestAllPlayers)
                                .executes(accountHandlers::handleUnregister)))
                .build();
    }

    private int showUsage(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(I18n.msg("hsauth.usage", sender));
        return Command.SINGLE_SUCCESS;
    }
}
