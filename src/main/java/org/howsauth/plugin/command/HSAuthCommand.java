package org.howsauth.plugin.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;

import net.kyori.adventure.text.Component;

import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.auth.PasswordValidator;
import org.howsauth.plugin.data.PlayerDataManager.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * hsauth 管理命令，使用 brigadier 原生注册。
 * 子命令作为 literal 节点，客户端输入空格后能自动显示子命令列表。
 */
@SuppressWarnings("SameReturnValue")
public final class HSAuthCommand {

    private final HowSAuth plugin;
    /** 在线玩家名补全（不依赖实例状态，static） */
    private static final SuggestionProvider<io.papermc.paper.command.brigadier.CommandSourceStack> SUGGEST_PLAYERS =
            (context, builder) -> {
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (Player player : Bukkit.getOnlinePlayers()) {
                    String name = player.getName();
                    if (remaining.isEmpty() || name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(name);
                    }
                }
                return builder.buildFuture();
            };
    private final SuggestionProvider<io.papermc.paper.command.brigadier.CommandSourceStack> SUGGEST_ALL_PLAYERS;

    public HSAuthCommand(HowSAuth plugin) {
        this.plugin = plugin;
        this.SUGGEST_ALL_PLAYERS = suggestAllPlayers(plugin);
    }

    /** 补全：在线玩家 + 已注册的离线玩家（大小写不敏感前缀匹配） */
    public static SuggestionProvider<io.papermc.paper.command.brigadier.CommandSourceStack> suggestAllPlayers(HowSAuth plugin) {
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
            for (UUID uuid : plugin.getPlayerDataManager().getAllUuids()) {
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

    /** 构建命令树节点（由 HowSAuth 注册时调用） */
    public LiteralCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack> buildNode() {
        return literal("hsauth")
                .requires(stack -> stack.getSender().hasPermission("hsauth.admin"))
                // /hsauth — 显示用法
                .executes(this::showUsage)
                // /hsauth reload
                .then(literal("reload")
                        .executes(this::handleReload))
                // /hsauth debug dump
                .then(literal("debug")
                        .then(literal("dump")
                                .executes(this::handleDebugDump)))
                // /hsauth accounts <player>
                .then(literal("accounts")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(SUGGEST_ALL_PLAYERS)
                                .executes(this::handleAccounts)))
                // /hsauth forcelogout <player>
                .then(literal("forcelogout")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(SUGGEST_ALL_PLAYERS)
                                .executes(this::handleForceLogout)))
                // /hsauth forcechangepw <player> <newpassword>
                .then(literal("forcechangepw")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(SUGGEST_ALL_PLAYERS)
                                .then(argument("newpassword", StringArgumentType.word())
                                        .executes(this::handleForceChangePw))))
                // /hsauth forcermpw <player>：管理员强制清空玩家密码（转为无密码账户，凭正版验证或 2FA 登录）
                .then(literal("forcermpw")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(SUGGEST_ALL_PLAYERS)
                                .executes(this::handleForceRemovePw)))
                // /hsauth forcelogin <player>
                .then(literal("forcelogin")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(SUGGEST_PLAYERS)
                                .executes(this::handleForceLogin)))
                // /hsauth forceregister <player> <password>
                .then(literal("forceregister")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(SUGGEST_PLAYERS)
                                .then(argument("password", StringArgumentType.word())
                                        .executes(this::handleForceRegister))))
                // /hsauth reset2fa <player>：管理员强制解除 2FA 绑定（玩家误删验证器密钥时解锁）
                .then(literal("reset2fa")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(SUGGEST_ALL_PLAYERS)
                                .executes(this::handleReset2fa)))
                // /hsauth unreg <player>：删除账号（管理员，无别名）
                .then(literal("unreg")
                        .then(argument("player", StringArgumentType.word())
                                .suggests(SUGGEST_ALL_PLAYERS)
                                .executes(this::handleUnregister)))
                .build();
    }

    private int showUsage(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(I18n.msg("hsauth.usage", sender));
        return Command.SINGLE_SUCCESS;
    }

    private int handleReload(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (Debug.on()) {
            Debug.log("cmd", "reload by %s", sender.getName());
        }
        boolean dbChanged = plugin.getConfigManager().reload();
        I18n.reload();
        // 调试开关可能被切换：重新读取配置
        Debug.refresh(plugin);
        plugin.getAuthManager().cleanupExpiredStates();
        // 登录界面方式可能被切换：清理现有 BossBar，重新挂起未登录玩家（关闭旧 Dialog，按新配置展示）
        plugin.getPlayerListener().refreshPendingPlayers();
        // 珍珠保管开关可能被切换：关闭时清空全部保管记录
        plugin.getPendingPearlManager().refresh();
        if (dbChanged) {
            sender.sendMessage(I18n.msg("hsauth.reload_db_changed", sender));
            plugin.getLogger().warning(I18n.get("hsauth.reload_db_changed"));
        } else {
            sender.sendMessage(I18n.msg("hsauth.reload_success", sender));
        }
        plugin.getLogger().info(I18n.get("plugin.config_reload_log"));
        if (Debug.on()) {
            Debug.log("cmd", "reload by %s: success (db changed=%s)", sender.getName(), dbChanged);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** 导出认证状态快照到执行者与控制台，便于排查工单（数据源为内存状态，不含密码/密钥等敏感值） */
    private int handleDebugDump(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        boolean console = !(sender instanceof Player);
        List<String> lines = plugin.getAuthManager().diagnostics();
        // 补一行调试文件状态：排查时最先要确认的就是"日志在哪、有没有在写"
        lines.add(Debug.fileStatus());
        if (Debug.on()) {
            Debug.log("cmd", "debug dump by %s: %s lines", sender.getName(), lines.size());
        }
        for (String line : lines) {
            sender.sendMessage(Component.text(line));
            // 控制台/RCON 执行时已输出到控制台，无需重复写日志
            if (!console) {
                plugin.getLogger().info(line);
            }
        }
        return Command.SINGLE_SUCCESS;
    }

    // 查找指定玩家 IP 下的其它账号（异步执行，避免 getOfflinePlayer 阻塞区域线程）
    private int handleAccounts(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        if (Debug.on()) {
            Debug.log("cmd", "accounts by %s for %s", sender.getName(), targetName);
        }

        // 异步执行：getOfflinePlayer 可能阻塞网络查询（Folia 兼容）
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            UUID targetUuid = resolveTargetUuid(targetName);
            PlayerData data = plugin.getPlayerDataManager().getPlayer(targetUuid);
            if (data == null) {
                if (Debug.on()) {
                    Debug.log("cmd", "accounts by %s for %s: not found", sender.getName(), targetName);
                }
                sender.sendMessage(I18n.msg("hsauth.accounts_not_found", sender));
                return;
            }
            String ip = data.ip();
            if (ip == null || ip.isEmpty()) {
                if (Debug.on()) {
                    Debug.log("cmd", "accounts by %s for %s: no ip", sender.getName(), targetName);
                }
                sender.sendMessage(I18n.msg("hsauth.accounts_no_ip", sender));
                return;
            }

            // 查找同 IP 的所有账号
            List<PlayerData> sameIpAccounts = plugin.getPlayerDataManager().findByIp(ip);
            // 排除目标玩家自身，输出其他账号名
            List<String> otherNames = sameIpAccounts.stream()
                    .filter(d -> !d.uuid().equals(targetUuid))
                    .map(d -> {
                        OfflinePlayer op = Bukkit.getOfflinePlayer(d.uuid());
                        return op.getName() != null ? op.getName() : d.uuid().toString();
                    })
                    .toList();

            if (Debug.on()) {
                Debug.log("cmd", "accounts by %s for %s: %s other accounts", sender.getName(), targetName,
                        otherNames.size());
            }
            sender.sendMessage(I18n.msg("hsauth.accounts_result", sender, targetName, ip, otherNames.size()));
            for (String name : otherNames) {
                sender.sendMessage(I18n.msg("hsauth.accounts_item", sender, name));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    // 强制登出：玩家在线或离线均可（清除登录状态）。在线玩家会被踢出以重新登录
    private int handleForceLogout(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        if (Debug.on()) {
            Debug.log("cmd", "forcelogout by %s for %s", sender.getName(), targetName);
        }
        // 异步解析 UUID（Folia 兼容）
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            boolean success = plugin.getAuthManager().forceLogout(resolveTargetUuid(targetName));
            if (success) {
                if (Debug.on()) {
                    Debug.log("cmd", "forcelogout by %s for %s: success", sender.getName(), targetName);
                }
                sender.sendMessage(I18n.msg("hsauth.forcelogout_success", sender, targetName));
                // 在线玩家踢出以重新登录
                Player online = Bukkit.getPlayerExact(targetName);
                if (online != null) {
                    online.kick(I18n.msg("hsauth.forcelogout_kick", online));
                }
            } else {
                if (Debug.on()) {
                    Debug.log("cmd", "forcelogout by %s for %s: not logged in", sender.getName(), targetName);
                }
                sender.sendMessage(I18n.msg("hsauth.not_logged_in", sender, targetName));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    // 强制修改密码：玩家在线或离线均可，无需旧密码。在线玩家会被踢出以重新登录
    private int handleForceChangePw(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        String newPassword = StringArgumentType.getString(ctx, "newpassword");
        if (Debug.on()) {
            Debug.log("cmd", "forcechangepw by %s for %s", sender.getName(), targetName);
        }
        if (PasswordValidator.invalidPattern(plugin, sender, newPassword)) return Command.SINGLE_SUCCESS;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            UUID uuid = resolveTargetUuid(targetName);
            if (!plugin.getAuthManager().forceChangePassword(uuid, newPassword)) {
                if (Debug.on()) {
                    Debug.log("cmd", "forcechangepw by %s for %s: account not found", sender.getName(), targetName);
                }
                sender.sendMessage(I18n.msg("hsauth.accounts_not_found", sender));
                return;
            }
            if (Debug.on()) {
                Debug.log("cmd", "forcechangepw by %s for %s: success", sender.getName(), targetName);
            }
            sender.sendMessage(I18n.msg("hsauth.forcechangepw_success", sender, targetName));
            // 在线玩家踢出以重新登录
            Player online = Bukkit.getPlayerExact(targetName);
            if (online != null) {
                online.kick(I18n.msg("hsauth.forcechangepw_kick", online));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    /** 强制清空密码：转为无密码账户（凭正版验证或 2FA 登录）。玩家在线或离线均可，无需验证码 */
    private int handleForceRemovePw(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        if (Debug.on()) {
            Debug.log("cmd", "forcermpw by %s for %s", sender.getName(), targetName);
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            if (!plugin.getAuthManager().forceRemovePassword(resolveTargetUuid(targetName))) {
                if (Debug.on()) {
                    Debug.log("cmd", "forcermpw by %s for %s: account not found", sender.getName(), targetName);
                }
                sender.sendMessage(I18n.msg("hsauth.accounts_not_found", sender));
                return;
            }
            if (Debug.on()) {
                Debug.log("cmd", "forcermpw by %s for %s: success", sender.getName(), targetName);
            }
            sender.sendMessage(I18n.msg("hsauth.forcermpw_success", sender, targetName));
        });
        return Command.SINGLE_SUCCESS;
    }

    /** 解析命令目标账号：优先离线 UUID，无账号时按名字匹配正版账号（premium=1 存储正版 UUID） */
    private UUID resolveTargetUuid(String targetName) {
        UUID uuid = Bukkit.getOfflinePlayer(targetName).getUniqueId();
        // 离线模式下 getOfflinePlayer 返回离线 UUID，与正版账号存储的正版 UUID 不匹配，
        // 不回溯会对正版玩家误报"账号不存在"
        if (!plugin.getPlayerDataManager().hasAccount(uuid)) {
            PlayerData premium = plugin.getPlayerDataManager().getByName(targetName);
            if (premium != null) uuid = premium.uuid();
        }
        return uuid;
    }

    // 强制登录：仅对在线玩家生效
    private int handleForceLogin(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        if (Debug.on()) {
            Debug.log("cmd", "forcelogin by %s for %s", sender.getName(), targetName);
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            if (Debug.on()) {
                Debug.log("cmd", "forcelogin by %s for %s: player not online", sender.getName(), targetName);
            }
            sender.sendMessage(I18n.msg("hsauth.player_not_online", sender, targetName));
            return 0;
        }
        // 已登录则无需重复操作
        if (plugin.sessions().isLoggedIn(target)) {
            if (Debug.on()) {
                Debug.log("cmd", "forcelogin by %s for %s: already logged in", sender.getName(), targetName);
            }
            sender.sendMessage(I18n.msg("hsauth.already_logged_in", sender, targetName));
            return 0;
        }
        plugin.getAuthManager().forceLogin(target);
        // 强制登录后传送回上次退出位置
        plugin.locations().teleportBack(target);
        if (Debug.on()) {
            Debug.log("cmd", "forcelogin by %s for %s: success", sender.getName(), targetName);
        }
        sender.sendMessage(I18n.msg("hsauth.forcelogin_success", sender, targetName));
        return Command.SINGLE_SUCCESS;
    }

    // 强制注册：绕过 IP 限制为玩家创建账号。玩家在线或离线均可，注册后需自行 /login
    private int handleForceRegister(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        String password = StringArgumentType.getString(ctx, "password");
        if (Debug.on()) {
            Debug.log("cmd", "forceregister by %s for %s", sender.getName(), targetName);
        }
        if (PasswordValidator.invalidPattern(plugin, sender, password)) return Command.SINGLE_SUCCESS;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            if (!plugin.accounts().forceRegister(resolveTargetUuid(targetName), targetName, password)) {
                if (Debug.on()) {
                    Debug.log("cmd", "forceregister by %s for %s: already exists", sender.getName(), targetName);
                }
                sender.sendMessage(I18n.msg("hsauth.forceregister_already_exists", sender, targetName));
                return;
            }
            if (Debug.on()) {
                Debug.log("cmd", "forceregister by %s for %s: success", sender.getName(), targetName);
            }
            sender.sendMessage(I18n.msg("hsauth.forceregister_success", sender, targetName));
            // 在线玩家：挂起等待登录并重启提醒/超时任务（注册提醒会因 hasAccount=true 自动取消）
            Player online = Bukkit.getPlayerExact(targetName);
            if (online != null) {
                // 复用通用挂起：补旁观者保护（登录成功后按存储模式恢复游戏模式）
                plugin.getPlayerListener().suspend(online, "listener.please_login", true);
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    // 强制解除 2FA：管理员救济通道（玩家误删验证器密钥致账号锁死时解锁），解除后玩家可重新 /2fa setup
    private int handleReset2fa(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        if (Debug.on()) {
            Debug.log("cmd", "reset2fa by %s for %s", sender.getName(), targetName);
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            if (plugin.twoFactor().reset(resolveTargetUuid(targetName))) {
                if (Debug.on()) {
                    Debug.log("cmd", "reset2fa by %s for %s: success", sender.getName(), targetName);
                }
                sender.sendMessage(I18n.msg("hsauth.reset2fa_success", sender, targetName));
            } else {
                if (Debug.on()) {
                    Debug.log("cmd", "reset2fa by %s for %s: not enabled", sender.getName(), targetName);
                }
                sender.sendMessage(I18n.msg("hsauth.reset2fa_not_enabled", sender, targetName));
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    // 删除账号：玩家在线或离线均可，账号本人在线则踢出（下次进服需重新注册）
    private int handleUnregister(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        if (Debug.on()) {
            Debug.log("cmd", "unreg by %s for %s", sender.getName(), targetName);
        }
        // 异步执行：注销涉及数据库写操作与玩家数据文件删除，不该阻塞主线程
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            // 以数据库记录解析账号：getOfflinePlayer 走 usercache，同名可能缓存到与账号无关的 UUID
            // （玩家改名或正版/离线缓存混杂时），导致删错或漏删账号
            UUID targetUuid = plugin.getPlayerDataManager().findUuidByName(targetName);
            if (targetUuid == null || !plugin.accounts().unregister(targetUuid)) {
                if (Debug.on()) {
                    Debug.log("cmd", "unreg by %s for %s: not found", sender.getName(), targetName);
                }
                sender.sendMessage(I18n.msg("hsauth.accounts_not_found", sender));
                return;
            }
            // 账号本人在线则踢出（按 UUID 精确匹配）
            Player onlinePlayer = Bukkit.getPlayer(targetUuid);
            if (onlinePlayer != null) {
                onlinePlayer.kick(I18n.msg("unregister.kick", onlinePlayer));
            }
            if (Debug.on()) {
                Debug.log("cmd", "unreg by %s for %s: success", sender.getName(), targetName);
            }
            sender.sendMessage(I18n.msg("unregister.success", sender, targetName));
        });
        return Command.SINGLE_SUCCESS;
    }
}
