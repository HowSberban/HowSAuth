package org.howsauth.plugin.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.auth.PasswordValidator;
import org.howsauth.plugin.data.PlayerDataManager;

import java.util.UUID;

/**
 * 管理员账号操作类子命令：强制登录/登出、改密/清空密码、强制注册、重置 2FA、删除账号。
 */
final class AdminAccountHandlers {

    private final HowSAuth plugin;
    private final PlayerDataManager dataManager;

    AdminAccountHandlers(HowSAuth plugin, PlayerDataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
    }

    // 强制登出：玩家在线或离线均可（清除登录状态）。在线玩家会被踢出以重新登录
    int handleForceLogout(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        if (Debug.on()) {
            Debug.log("cmd", "forcelogout by %s for %s", sender.getName(), targetName);
        }
        // 异步解析 UUID（Folia 兼容）
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            boolean success = plugin.loginFlow().forceLogout(AdminTargetResolver.resolve(dataManager, targetName));
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
    int handleForceChangePw(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        String newPassword = StringArgumentType.getString(ctx, "newpassword");
        if (Debug.on()) {
            Debug.log("cmd", "forcechangepw by %s for %s", sender.getName(), targetName);
        }
        if (PasswordValidator.invalidPattern(plugin, sender, newPassword)) return Command.SINGLE_SUCCESS;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            UUID uuid = AdminTargetResolver.resolve(dataManager, targetName);
            if (!plugin.loginFlow().forceChangePassword(uuid, newPassword)) {
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
    int handleForceRemovePw(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        if (Debug.on()) {
            Debug.log("cmd", "forcermpw by %s for %s", sender.getName(), targetName);
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            if (!plugin.loginFlow().forceRemovePassword(AdminTargetResolver.resolve(dataManager, targetName))) {
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

    // 强制登录：仅对在线玩家生效
    int handleForceLogin(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
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
        plugin.loginFlow().forceLogin(target);
        // 强制登录后传送回上次退出位置
        plugin.locations().teleportBack(target);
        if (Debug.on()) {
            Debug.log("cmd", "forcelogin by %s for %s: success", sender.getName(), targetName);
        }
        sender.sendMessage(I18n.msg("hsauth.forcelogin_success", sender, targetName));
        return Command.SINGLE_SUCCESS;
    }

    // 强制注册：绕过 IP 限制为玩家创建账号。玩家在线或离线均可，注册后需自行 /login
    int handleForceRegister(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        String password = StringArgumentType.getString(ctx, "password");
        if (Debug.on()) {
            Debug.log("cmd", "forceregister by %s for %s", sender.getName(), targetName);
        }
        if (PasswordValidator.invalidPattern(plugin, sender, password)) return Command.SINGLE_SUCCESS;
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            if (!plugin.accounts().forceRegister(AdminTargetResolver.resolve(dataManager, targetName), targetName, password)) {
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
                plugin.playerListener().suspend(online, "listener.please_login", true);
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    // 强制解除 2FA：管理员救济通道（玩家误删验证器密钥致账号锁死时解锁），解除后玩家可重新 /2fa setup
    int handleReset2fa(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        if (Debug.on()) {
            Debug.log("cmd", "reset2fa by %s for %s", sender.getName(), targetName);
        }
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            if (plugin.twoFactor().reset(AdminTargetResolver.resolve(dataManager, targetName))) {
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
    int handleUnregister(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        if (Debug.on()) {
            Debug.log("cmd", "unreg by %s for %s", sender.getName(), targetName);
        }
        // 异步执行：注销涉及数据库写操作与玩家数据文件删除，不该阻塞主线程
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            // 以数据库记录解析账号：getOfflinePlayer 走 usercache，同名可能缓存到与账号无关的 UUID
            // （玩家改名或正版/离线缓存混杂时），导致删错或漏删账号
            UUID targetUuid = dataManager.findUuidByName(targetName);
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
