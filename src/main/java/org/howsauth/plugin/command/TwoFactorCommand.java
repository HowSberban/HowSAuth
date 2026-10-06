package org.howsauth.plugin.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.dialog.DialogManager;
import org.bukkit.entity.Player;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.papermc.paper.command.brigadier.Commands.argument;
import static io.papermc.paper.command.brigadier.Commands.literal;

/**
 * 双因素认证命令，与 hsauth 一样使用 brigadier 原生注册，
 * 子命令作为 literal 节点，客户端输入空格后能自动提示子命令列表。
 * /2fa setup              - 生成密钥，开始绑定
 * /2fa confirm <验证码>   - 验证码确认，完成绑定
 * /2fa disable <验证码>   - 验证码确认，关闭双因素
 * /2fa <验证码>           - 登录时的双因素验证
 */
// Dialog API 标记为 @ApiStatus.Experimental，实际已稳定可用（与 DialogManager 同类情况）
@SuppressWarnings({"SameReturnValue", "UnstableApiUsage"})
public final class TwoFactorCommand {

    private final HowSAuth plugin;
    // 游戏内绑定对话框构建器：服务端不支持 Dialog 时为 null，回退文本展示
    private final DialogManager dialogManager;
    // 取消回调：仅关闭窗口（afterAction 为 NONE，手动关闭），无副作用，可复用
    private static final DialogActionCallback CANCEL = (returnValue, audience) -> audience.closeDialog();

    public TwoFactorCommand(HowSAuth plugin, DialogManager dialogManager) {
        this.plugin = plugin;
        this.dialogManager = dialogManager;
    }

    /** 构建命令树节点（由 HowSAuth 注册时调用） */
    public LiteralCommandNode<CommandSourceStack> buildNode() {
        return literal("2fa")
                .requires(stack -> stack.getSender() instanceof Player)
                // /2fa — 显示用法
                .executes(this::handleUsage)
                // /2fa setup
                .then(literal("setup")
                        .executes(this::handleSetup))
                // /2fa confirm <验证码>
                .then(literal("confirm")
                        .then(argument("code", StringArgumentType.word())
                                .executes(this::handleConfirm)))
                // /2fa disable <验证码>
                .then(literal("disable")
                        .then(argument("code", StringArgumentType.word())
                                .executes(this::handleDisable)))
                // /2fa <验证码>（登录时验证；词法上不匹配 setup/confirm/disable）
                .then(argument("code", StringArgumentType.word())
                        .executes(this::handleVerify))
                .build();
    }

    private int handleUsage(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        player.sendMessage(msg(player, "2fa.usage"));
        return Command.SINGLE_SUCCESS;
    }

    /** 按玩家语言解析消息组件 */
    private static Component msg(Player player, String key) {
        return I18n.msg(key, player);
    }

    /** 全局开关 + 登录状态前置校验：不满足时发提示并返回 true（中止本次命令） */
    private boolean blocked(Player player) {
        if (!plugin.config().twoFactor().enabled()) {
            player.sendMessage(msg(player, "2fa.feature_disabled"));
            return true;
        }
        if (!plugin.sessions().isLoggedIn(player)) {
            player.sendMessage(msg(player, "listener.must_login"));
            return true;
        }
        return false;
    }

    /** /2fa setup：生成临时密钥。服务端支持 Dialog 时弹游戏内绑定窗口，否则回退文本展示 */
    private int handleSetup(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        if (blocked(player)) return Command.SINGLE_SUCCESS;
        if (plugin.twoFactor().has2fa(player.getUniqueId())) {
            player.sendMessage(msg(player, "2fa.already_enabled"));
            return Command.SINGLE_SUCCESS;
        }
        String secret = plugin.twoFactor().setup(player);
        // 扫码 URL 只与密钥和配置相关，绑定会话期间不变，算一次复用
        String url = qrUrl(player, secret);
        // Dialog 可用时聊天栏完全静默，两者互斥（扫码入口在对话框按钮上）
        // 运行时复查 login.dialog.enabled：reload 关闭配置后 dialogManager 仍非空（启动时创建、reload 不重建），
        // 需按当前配置回退聊天栏，与 PreJoinAuthListener 的运行时检查保持一致
        if (dialogManager != null && plugin.config().login().dialogEnabled()) {
            showSetupDialog(player, secret, url, null);
        } else {
            // dialog 关闭/服务端不支持（<1.21.11）：回退聊天栏展示密钥与完成指引
            player.sendMessage(buildSetupMessage(player, secret, url));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** 合成 /2fa setup 的聊天消息：标题 + 提示 + 可复制密钥 + [扫码链接] + [过期提醒] + 完成指引 */
    private Component buildSetupMessage(Player player, String secret, String qrUrl) {
        Component message = Component.empty()
                .append(msg(player, "2fa.setup_title")).appendNewline()
                .append(msg(player, "2fa.setup_hint")).appendNewline()
                .append(clickToCopy(secret, player));
        // 二维码服务模板可用时附上可点击的扫码链接
        if (qrUrl != null) {
            message = message.appendNewline().append(clickToOpen(qrUrl, player));
        }
        // 有限时配置时追加红色过期提醒
        Component expire = expireReminder(player);
        if (expire != null) {
            message = message.appendNewline().append(expire);
        }
        return message.appendNewline().append(msg(player, "2fa.setup_confirm"));
    }

    /** 弹游戏内 2FA 绑定对话框，error 为上次校验失败的提示（首次为 null）；取消关闭窗口，复制不关闭 */
    private void showSetupDialog(Player player, String secret, String qrUrl, Component error) {
        player.showDialog(dialogManager.buildSetupDialog(player.locale().toString(), secret, qrUrl, error,
                setupOnConfirm(player, secret, qrUrl), CANCEL));
    }

    private DialogActionCallback setupOnConfirm(Player player, String secret, String qrUrl) {
        return (returnValue, audience) -> {
            // 绑定已在别处完成（如对话框确认后又跑 /2fa confirm）：直接关闭并提示
            if (plugin.twoFactor().has2fa(player.getUniqueId())) {
                audience.closeDialog();
                player.sendMessage(msg(player, "2fa.already_enabled"));
                return;
            }
            // 密钥已过期（等待期间超时）：重弹只会展示作废密钥，关闭并提示重新 setup
            if (plugin.twoFactor().isSecretExpired(player.getUniqueId())) {
                audience.closeDialog();
                player.sendMessage(msg(player, "2fa.setup_expired"));
                return;
            }
            String code = returnValue.getText("code");
            if (code == null || code.isEmpty()) {
                showSetupDialog(player, secret, qrUrl, msg(player, "dialog.empty_code"));
                return;
            }
            if (plugin.twoFactor().confirm(player, code)) {
                player.sendMessage(msg(player, "2fa.confirm_success"));
                // 绑定成功即关闭窗口
                audience.closeDialog();
            } else {
                showSetupDialog(player, secret, qrUrl, msg(player, "2fa.confirm_incorrect"));
            }
        };
    }

    /** 生成二维码服务 URL 供扫码按钮打开；开关关闭或模板缺失 {data} 占位符时返回 null（省略扫码入口） */
    private String qrUrl(Player player, String secret) {
        if (!plugin.config().twoFactor().qrEnabled()) return null;
        String template = plugin.config().twoFactor().qrUrl();
        if (template == null || !template.contains("{data}")) return null;
        // otpauth 资料：issuer（服务器名，可空）+ 账户名（玩家名）+ 密钥，拼好后整体编码一次（与 AuthMe 一致）
        // label 用 "服务器名:玩家名" 并带 issuer 参数：验证器条目标题指向服务器，玩家名作账户详情
        String issuer = plugin.config().twoFactor().serverName();
        String data = "otpauth://totp/"
                + (issuer.isEmpty() ? "" : issuer + ":")
                + player.getName() + "?secret=" + secret
                + (issuer.isEmpty() ? "" : "&issuer=" + issuer);
        String encoded = URLEncoder.encode(data, StandardCharsets.UTF_8).replace("+", "%20");
        return template.replace("{data}", encoded);
    }

    /** 红色过期提醒组件：配置为 0（永不过期）时返回 null */
    private Component expireReminder(Player player) {
        int seconds = plugin.config().twoFactor().tempSecretExpireSeconds();
        if (seconds <= 0) return null;
        return I18n.msg("2fa.setup_expire", player, seconds);
    }

    /** 可点击复制组件：金色突出提示可点击，内容即展示文本，点击复制到剪贴板 */
    private static Component clickToCopy(String content, Player player) {
        return Component.text(content)
                .color(DialogManager.HIGHLIGHT_COLOR)
                .hoverEvent(HoverEvent.showText(msg(player, "2fa.click_to_copy")))
                .clickEvent(ClickEvent.copyToClipboard(content));
    }

    /**
     * 可点击打开扫码网页的组件：前缀（"或者直接"）无色，"扫描二维码添加"金色——
     * 颜色由语言文件内嵌 &6 控制，此处不再整段强制上色，避免把无色前缀也染成金色
     */
    private static Component clickToOpen(String url, Player player) {
        return msg(player, "2fa.setup_scan")
                .hoverEvent(HoverEvent.showText(msg(player, "2fa.click_to_open")))
                .clickEvent(ClickEvent.openUrl(url));
    }

    /** /2fa confirm <验证码>：验证码通过后完成绑定 */
    private int handleConfirm(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        if (blocked(player)) return Command.SINGLE_SUCCESS;
        String code = StringArgumentType.getString(ctx, "code");
        if (plugin.twoFactor().confirm(player, code)) {
            player.sendMessage(msg(player, "2fa.confirm_success"));
        } else if (plugin.twoFactor().has2fa(player.getUniqueId())) {
            // 已绑定成功（临时密钥已清除），重复确认不提示验证码错误
            player.sendMessage(msg(player, "2fa.already_enabled"));
        } else if (plugin.twoFactor().isSecretExpired(player.getUniqueId())) {
            // 临时密钥已不在（过期被清理）：提示重新 setup 而非验证码错误
            player.sendMessage(msg(player, "2fa.setup_expired"));
        } else {
            player.sendMessage(msg(player, "2fa.confirm_incorrect"));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** /2fa disable <验证码>：验证当前验证码后关闭双因素认证 */
    private int handleDisable(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        // 无密码离线账户关闭 2FA 会失去唯一验证因素，须先恢复密码（正版账户有正版验证兜底）
        // 此检查须在 blocked() 之前：开关关闭时 blocked 的提示无法给出正确指引
        if (plugin.accounts().isPasswordless(player.getUniqueId()) && !plugin.accounts().isPremium(player)) {
            player.sendMessage(msg(player, "2fa.disable_need_password"));
            return Command.SINGLE_SUCCESS;
        }
        if (blocked(player)) return Command.SINGLE_SUCCESS;
        if (!plugin.twoFactor().has2fa(player.getUniqueId())) {
            player.sendMessage(msg(player, "2fa.not_enabled"));
            return Command.SINGLE_SUCCESS;
        }
        String code = StringArgumentType.getString(ctx, "code");
        if (plugin.twoFactor().disable(player, code)) {
            player.sendMessage(msg(player, "2fa.disabled"));
        } else {
            player.sendMessage(msg(player, "2fa.confirm_incorrect"));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** /2fa <验证码>：登录时的双因素验证（密码已通过，等待 TOTP） */
    private int handleVerify(CommandContext<CommandSourceStack> ctx) {
        Player player = (Player) ctx.getSource().getSender();
        // 无密码账户不受全局开关影响：验证码是其唯一登录因素
        if (!plugin.config().twoFactor().enabled()
                && !plugin.accounts().isPasswordless(player.getUniqueId())) {
            player.sendMessage(msg(player, "2fa.feature_disabled"));
            return Command.SINGLE_SUCCESS;
        }
        String code = StringArgumentType.getString(ctx, "code");
        // 暴力破解踢出期内拒绝验证（验证码错误与密码错误同待遇，达阈值即踢出）
        if (plugin.failProtection().isKicked(player)) {
            player.sendMessage(I18n.msg("2fa.kicked", player, plugin.failProtection().getKickRemaining(player)));
            return Command.SINGLE_SUCCESS;
        }
        if (!plugin.twoFactor().isPending(player.getUniqueId())) {
            player.sendMessage(msg(player, "2fa.usage"));
            return Command.SINGLE_SUCCESS;
        }
        if (plugin.loginFlow().verify2fa(player, code)) {
            player.sendMessage(msg(player, "2fa.verify_success"));
            // 登录完成后传送回上次退出位置（与密码登录成功一致）
            plugin.locations().teleportBack(player);
        } else {
            player.sendMessage(msg(player, "2fa.confirm_incorrect"));
        }
        return Command.SINGLE_SUCCESS;
    }
}