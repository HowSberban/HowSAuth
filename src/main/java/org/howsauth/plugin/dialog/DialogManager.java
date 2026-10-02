package org.howsauth.plugin.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Pre-join Dialog 登录窗口构建器（Paper 1.21.11+）：供 PreJoinAuthListener 在配置阶段弹出。
 * 窗口含"确认/取消"双按钮、不可 ESC 关闭，错误信息作为正文最后一行显示。
 * Dialog API 引用全部隔离在此包：服务端不支持（isSupported 为 false）时不实例化，
 * PreJoinAuthListener 不会注册，未登录玩家回退聊天栏提示（remind-method）。
 */
// Dialog API 标记为 @ApiStatus.Experimental，实际已稳定可用（与 AsyncPlayerSpawnLocationEvent 同类情况）
@SuppressWarnings("UnstableApiUsage")
public final class DialogManager {

    private final HowSAuth plugin;
    // 2FA 密钥高亮色：聊天栏回退文本与 dialog 内密钥共用，突出可点击复制
    public static final TextColor HIGHLIGHT_COLOR = TextColor.color(0xFFAA00);
    // 验证码输入框长度上限：TOTP 为 6 位数字，放宽以兼容误粘贴的空格/分隔符
    private static final int CODE_INPUT_MAX_LENGTH = 10;
    // 回调选项：不限点击次数，1 小时有效期防止回调悬挂
    private static final ClickCallback.Options CALLBACK_OPTIONS = ClickCallback.Options.builder()
            .uses(ClickCallback.UNLIMITED_USES)
            .lifetime(Duration.ofHours(1))
            .build();

    /** 检测服务端是否支持 Dialog API（1.21.6+），不支持时不实例化本类 */
    public static boolean isSupported() {
        try {
            Class.forName("io.papermc.paper.dialog.Dialog");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public DialogManager(HowSAuth plugin) {
        this.plugin = plugin;
    }

    // ===== 窗口构建（错误行显示在正文末尾，取消行为由 onCancel 决定） =====

    /** 登录窗口（密码输入） */
    Dialog buildLoginDialog(String locale, Component error, DialogActionCallback onConfirm, DialogActionCallback onCancel) {
        DialogBase base = base(locale, "dialog.login.title", "dialog.login.body", error)
                .inputs(List.of(DialogInput.text("password", text(locale, "dialog.password_label"))
                        .maxLength(plugin.getConfigManager().maxPasswordLength())
                        .build()))
                .build();
        return confirmDialog(base, locale, onConfirm, onCancel);
    }

    /** 注册窗口（密码 + 确认输入） */
    Dialog buildRegisterDialog(String locale, Component error, DialogActionCallback onConfirm, DialogActionCallback onCancel) {
        int maxLength = plugin.getConfigManager().maxPasswordLength();
        DialogBase base = base(locale, "dialog.register.title", "dialog.register.body", error)
                .inputs(List.of(
                        DialogInput.text("password", text(locale, "dialog.password_label"))
                                .maxLength(maxLength)
                                .build(),
                        DialogInput.text("confirm", text(locale, "dialog.confirm_label"))
                                .maxLength(maxLength)
                                .build()))
                .build();
        return confirmDialog(base, locale, onConfirm, onCancel);
    }

    /** 双因素验证窗口（验证码输入） */
    Dialog build2faDialog(String locale, Component error, DialogActionCallback onConfirm, DialogActionCallback onCancel) {
        DialogBase base = base(locale, "dialog.2fa.title", "dialog.2fa.body", error)
                .inputs(List.of(codeInput(locale)))
                .build();
        return confirmDialog(base, locale, onConfirm, onCancel);
    }

    /**
     * /2fa setup 游戏内绑定对话框：密钥 + 扫码（浏览器）按钮 + 验证码输入。
     * 与 pre-join 对话框不同：取消/ESC 仅关闭窗口，不踢出玩家。
     */
    public Dialog buildSetupDialog(String locale, String secret, String qrUrl,
                                   Component error, DialogActionCallback onConfirm, DialogActionCallback onCancel) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(text(locale, "dialog.setup.body")));
        // 密钥以金色突出显示（上文已说明用途，不再加"密钥："前缀）
        body.add(DialogBody.plainMessage(Component.text(secret).color(HIGHLIGHT_COLOR)));
        body.add(DialogBody.plainMessage(text(locale, "dialog.setup_hint")));
        // 有限时配置时追加红色过期提醒
        int expireSeconds = plugin.getConfigManager().twoFaTempSecretExpireSeconds();
        if (expireSeconds > 0) {
            body.add(DialogBody.plainMessage(text(locale, "2fa.setup_expire", expireSeconds)));
        }
        if (error != null) {
            body.add(DialogBody.plainMessage(error));
        }
        DialogBase base = DialogBase.builder(text(locale, "dialog.setup.title"))
                .canCloseWithEscape(true)
                // 非暂停 + NONE：动作后不自动关闭，复制不关窗；取消/确认由各自回调 closeDialog
                //（暂停游戏的 dialog 要求 after_action 必须能解除暂停，故此处必须 pause(false)）
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .body(body)
                .inputs(List.of(codeInput(locale)))
                .build();
        // 扫码（浏览器）/ 复制 / 取消 / 确认；二维码服务模板未配置时省略扫码按钮
        List<ActionButton> buttons = new ArrayList<>();
        if (qrUrl != null) {
            buttons.add(ActionButton.builder(text(locale, "2fa.setup_scan"))
                    .action(DialogAction.staticAction(ClickEvent.openUrl(qrUrl)))
                    .build());
        }
        buttons.add(ActionButton.builder(text(locale, "dialog.setup_copy"))
                .action(DialogAction.staticAction(ClickEvent.copyToClipboard(secret)))
                .build());
        buttons.add(cancelButton(locale, onCancel));
        buttons.add(confirmButton(locale, onConfirm));
        return Dialog.create(factory -> factory.empty().base(base).type(DialogType.multiAction(buttons, null, 4)));
    }

    /** 窗口骨架：标题 + 正文 + 可选错误行，不可 ESC 关闭，确认后自动关闭（失败由确认处理重弹） */
    private DialogBase.Builder base(String locale, String titleKey, String bodyKey, Component error) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(text(locale, bodyKey)));
        if (error != null) {
            body.add(DialogBody.plainMessage(error));
        }
        return DialogBase.builder(text(locale, titleKey))
                .canCloseWithEscape(false)
                .afterAction(DialogBase.DialogAfterAction.CLOSE)
                .body(body);
    }

    /** 组装取消/确认双按钮并创建 Dialog（confirmation 类型：两个按钮呈现为左=取消、右=确认） */
    private Dialog confirmDialog(DialogBase base, String locale, DialogActionCallback onConfirm, DialogActionCallback onCancel) {
        return Dialog.create(factory -> factory.empty().base(base)
                .type(DialogType.confirmation(cancelButton(locale, onCancel), confirmButton(locale, onConfirm))));
    }

    /** 取消按钮：触发自定义回调 */
    private ActionButton cancelButton(String locale, DialogActionCallback onCancel) {
        return ActionButton.builder(text(locale, "dialog.cancel"))
                .action(DialogAction.customClick(onCancel, CALLBACK_OPTIONS))
                .build();
    }

    /** 确认按钮：触发自定义回调 */
    private ActionButton confirmButton(String locale, DialogActionCallback onConfirm) {
        return ActionButton.builder(text(locale, "dialog.confirm"))
                .action(DialogAction.customClick(onConfirm, CALLBACK_OPTIONS))
                .build();
    }

    /** 验证码输入框（2fa 验证与 2fa 绑定共用） */
    private DialogInput codeInput(String locale) {
        return DialogInput.text("code", text(locale, "dialog.code_label"))
                .maxLength(CODE_INPUT_MAX_LENGTH)
                .build();
    }

    /** 按指定语言获取消息转 Adventure Component（配置阶段无 Player 对象，用客户端 locale） */
    static Component text(String locale, String key, Object... args) {
        return I18n.msgForLocale(key, locale, args);
    }
}