package org.howsauth.plugin.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.howsauth.plugin.Debug;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.data.PlayerData;
import org.howsauth.plugin.data.PlayerDataManager;

import java.util.List;
import java.util.UUID;

/**
 * 管理员检查类子命令：reload、debug dump、账号关联查询。
 */
final class AdminInspectionHandlers {

    private final HowSAuth plugin;
    private final PlayerDataManager dataManager;

    AdminInspectionHandlers(HowSAuth plugin, PlayerDataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
    }

    int handleReload(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (Debug.on()) {
            Debug.log("cmd", "reload by %s", sender.getName());
        }
        boolean dbChanged = plugin.config().reload();
        I18n.reload();
        // 调试开关可能被切换：重新读取配置
        Debug.refresh(plugin);
        plugin.auth().cleanupExpiredStates();
        // 登录界面方式可能被切换：清理现有 BossBar，重新挂起未登录玩家（关闭旧 Dialog，按新配置展示）
        plugin.playerListener().refreshPendingPlayers();
        // 珍珠保管开关可能被切换：关闭时清空全部保管记录
        plugin.pendingPearls().refresh();
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
    int handleDebugDump(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        boolean console = !(sender instanceof Player);
        List<String> lines = plugin.auth().diagnostics();
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
    int handleAccounts(CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String targetName = StringArgumentType.getString(ctx, "player");
        if (Debug.on()) {
            Debug.log("cmd", "accounts by %s for %s", sender.getName(), targetName);
        }

        // 异步执行：getOfflinePlayer 可能阻塞网络查询（Folia 兼容）
        Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            UUID targetUuid = AdminTargetResolver.resolve(dataManager, targetName);
            PlayerData data = dataManager.getPlayer(targetUuid);
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
            List<PlayerData> sameIpAccounts = dataManager.findByIp(ip);
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
}
