package org.howsauth.plugin.listener;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;
import org.howsauth.plugin.auth.AccountLifecycle;
import org.howsauth.plugin.auth.SessionStore;
import org.howsauth.plugin.auth.TwoFactorAuth;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 未登录提示的周期提醒与提示文案选择。
 * <p>
 * 一组互相依靠的职责：提示文案口径（{@link #promptKey}）、按配置方式重发提示、自管理提醒任务，
 * 以及提醒 BossBar 的生命周期。提示文案由首次挂起（{@code PlayerListener#beginAuthFlow}）与
 * 周期提醒共用，故一并放在此处，避免两处口径不一致。
 * <p>
 * 任务自管理：玩家登录/注册成功或下线后自动取消并清理 BossBar；玩家退出时由调用方
 * 显式调 {@link #hide} 与 {@link #cancelTask}（退出瞬间玩家调度器已 retired，任务内分支不再执行）。
 */
public final class AuthReminder {

    private final HowSAuth plugin;
    private final SessionStore sessions;
    private final AccountLifecycle accounts;
    private final TwoFactorAuth twoFactor;
    // 活跃的提醒 BossBar：登录成功/玩家退出时立即隐藏（不等下一个任务周期）
    private final Map<UUID, net.kyori.adventure.bossbar.BossBar> reminderBars = new ConcurrentHashMap<>();
    // 活跃的提醒任务：重新挂起（reload）时取消旧任务，避免新旧任务并行重复提醒
    private final Map<UUID, ScheduledTask> reminderTasks = new ConcurrentHashMap<>();

    public AuthReminder(HowSAuth plugin, SessionStore sessions,
                               AccountLifecycle accounts, TwoFactorAuth twoFactor) {
        this.plugin = plugin;
        this.sessions = sessions;
        this.accounts = accounts;
        this.twoFactor = twoFactor;
    }

    /**
     * 挂起提示文案选择：首次挂起与周期提醒共用，防止两类提示口径不一致。
     * 注册 → 待 2FA → 无密码（无可用登录方式细分联系管理员）→ 密码登录。
     */
    public String promptKey(UUID uuid, boolean needsLogin) {
        if (!needsLogin) return "listener.please_register";
        if (twoFactor.isPending(uuid)) return "login.need_2fa";
        return accounts.isPasswordless(uuid)
                ? (accounts.hasNoUsableLoginMethod(uuid)
                        ? "login.passwordless_no_auth" : "login.passwordless_prompt")
                : "listener.please_login";
    }

    /**
     * 周期性重发登录/注册提示，防止玩家没看到。
     * 任务自管理：玩家登录/注册成功或下线后自动取消。
     * 提示方式由 login.remind-method 配置：chat / title / actionbar / bossbar。
     * @param needsLogin true = 发送登录提示，false = 发送注册提示
     */
    public void schedule(Player player, boolean needsLogin) {
        int interval = plugin.getConfigManager().loginRemindInterval();
        if (interval <= 0) return;
        long periodTicks = interval * 20L;
        UUID uuid = player.getUniqueId();
        // 取消旧提醒任务（refreshPendingPlayers 重新挂起时避免新旧任务并行重复提醒）
        ScheduledTask old = reminderTasks.remove(uuid);
        if (old != null) old.cancel();
        // Paper 1.20+ 统一调度器 API，兼容 Folia
        // 玩家调度器已退休（退出瞬间与 reload 挂起竞态）时返回 null，null 不允许入 Map
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, scheduledTask -> {
            if (!player.isOnline()) {
                reminderTasks.remove(uuid);
                hide(player);
                scheduledTask.cancel();
                return;
            }
            boolean done = needsLogin ? sessions.isLoggedIn(player) : accounts.hasAccount(player);
            if (done) {
                reminderTasks.remove(uuid);
                hide(player);
                scheduledTask.cancel();
                return;
            }
            show(player, needsLogin);
        }, null, periodTicks, periodTicks);
        if (task != null) {
            reminderTasks.put(uuid, task);
        }
    }

    /** 按配置方式发送登录/注册提醒（bossbar 引用统一由 reminderBars 持有） */
    private void show(Player player, boolean needsLogin) {
        // 提示文案与首次挂起共用同一选择逻辑（promptKey），保证周期提醒口径一致
        String key = promptKey(player.getUniqueId(), needsLogin);
        String method = plugin.getConfigManager().loginRemindMethod();
        switch (method) {
            case "title" -> player.showTitle(net.kyori.adventure.title.Title.title(
                    I18n.msg(key, player),
                    net.kyori.adventure.text.Component.empty(),
                    net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(500),
                            java.time.Duration.ofMillis(2000),
                            java.time.Duration.ofMillis(500))));
            case "actionbar" -> player.sendActionBar(I18n.msg(key, player));
            case "bossbar" -> {
                net.kyori.adventure.bossbar.BossBar bar = reminderBars.get(player.getUniqueId());
                if (bar == null) {
                    net.kyori.adventure.text.Component text = I18n.msg(key, player);
                    bar = net.kyori.adventure.bossbar.BossBar.bossBar(
                            text, 1.0f,
                            net.kyori.adventure.bossbar.BossBar.Color.YELLOW,
                            net.kyori.adventure.bossbar.BossBar.Overlay.PROGRESS);
                    player.showBossBar(bar);
                    reminderBars.put(player.getUniqueId(), bar);
                } else {
                    bar.name(I18n.msg(key, player));
                }
            }
            default -> player.sendMessage(I18n.msg(key, player));
        }
    }

    /** 隐藏并移除提醒 BossBar（登录成功、玩家退出、任务自检清理时调用；非 bossbar 方式时为空操作） */
    public void hide(Player player) {
        net.kyori.adventure.bossbar.BossBar bar = reminderBars.remove(player.getUniqueId());
        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    /** 清理所有提醒 BossBar（重新挂起前调用，防止旧 BossBar 悬挂到玩家登录才消失） */
    public void clearAll() {
        reminderBars.forEach((uuid, bar) -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) player.hideBossBar(bar);
        });
        reminderBars.clear();
    }

    /** 丢弃该玩家的提醒任务引用（退出时调用：任务随玩家调度器 retired 不再执行，防止 Map 残留） */
    public void cancelTask(UUID uuid) {
        reminderTasks.remove(uuid);
    }
}
