package org.howsauth.plugin;

import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import com.github.retrooper.packetevents.PacketEvents;
import org.howsauth.plugin.api.HSAuthApi;
import org.howsauth.plugin.auth.AuthManager;
import org.howsauth.plugin.auth.AccountLifecycle;
import org.howsauth.plugin.auth.FailProtection;
import org.howsauth.plugin.auth.LogoutLocation;
import org.howsauth.plugin.auth.SessionStore;
import org.howsauth.plugin.auth.TwoFactorAuth;
import org.howsauth.plugin.command.*;
import org.howsauth.plugin.config.ConfigManager;
import org.howsauth.plugin.data.PlayerDataManager;
import org.howsauth.plugin.dialog.DialogManager;
import org.howsauth.plugin.dialog.PreJoinAuthListener;
import org.howsauth.plugin.hook.HSAuthExpansion;
import org.howsauth.plugin.listener.JoinQuitMessageService;
import org.howsauth.plugin.listener.PlayerListener;
import org.howsauth.plugin.packet.InventoryPacketListener;
import org.howsauth.plugin.pearl.PendingPearlManager;
import org.howsauth.plugin.premium.ConnectionHandler;
import org.howsauth.plugin.premium.DataService;
import org.howsauth.plugin.premium.MojangClient;
import org.howsauth.plugin.premium.PlayerInjector;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.TimeUnit;

// 非 final：MockBukkit 需用 ByteBuddy 代理子类化插件类，才能以合法类加载器构造实例用于单元测试
public class HowSAuth extends JavaPlugin {

    private ConfigManager configManager;
    private PlayerDataManager playerDataManager;
    private AuthManager authManager;
    private PlayerListener playerListener;
    // 飞行末影珍珠保管：统一接管退出/进入/返还路径，防止未登录玩家被珍珠传送
    private PendingPearlManager pendingPearlManager;
    // Dialog API 构建器：开关关闭或服务端不支持（<1.21.11）时为 null，pre-join 与游戏内 2FA 绑定均回退
    private DialogManager dialogManager;
    // Pre-join Dialog（配置阶段认证）：配置事件 API 不可用时为 null，自动回退聊天栏提示
    private PreJoinAuthListener preJoinAuthListener;
    // 正版验证异步组件（线程池管理等，禁用时回收）
    private MojangClient mojangClient;
    private PlayerInjector playerInjector;
    // 数据库脏数据批量落库周期（秒）
    private static final int DB_SAVE_FLUSH_SECONDS = 5;

    @Override
    public void onEnable() {
        // I18n 必须先初始化：ConfigManager 在检测到配置版本不匹配时会调用 I18n.reload()
        I18n.init(this);
        // NMS 反射点自检：正版验证依赖 ServerLoginPacketListenerImpl 内部结构，
        // 服务端版本不匹配时立即禁用自身，避免玩家卡死在登录阶段
        String nmsError = PlayerInjector.checkNmsCompatibility();
        if (nmsError != null) {
            getLogger().severe(I18n.get("log.nms_check_failed", nmsError));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.configManager = new ConfigManager(this);
        // 调试开关需在配置就绪后同步，并按每次开服轮转旧调试日志（/hsauth reload 会再次刷新）
        Debug.init(this);
        this.playerDataManager = new PlayerDataManager(this);
        this.authManager = new AuthManager(this, playerDataManager, configManager);
        // Dialog 登录界面：开关开启且服务端支持（1.21.6+）且版本满足（1.21.11+）时启用
        // 1.21.6–1.21.10 存在未验证的兼容问题（Paper #13365/#13708），默认禁用，
        // 经 login.dialog.allow-risky-versions 强制启用时由使用者自担风险
        // dialogManager 为 null 时 pre-join 与游戏内 2FA 绑定均回退聊天栏/文本
        if (configManager.loginDialogEnabled()
                && DialogManager.isSupported()
                && (preJoinSupported() || configManager.dialogAllowRiskyVersions())) {
            this.dialogManager = new DialogManager(this);
            this.preJoinAuthListener = new PreJoinAuthListener(this, authManager, authManager.sessions(), authManager.failProtection(), authManager.twoFactor(), authManager.accounts(), dialogManager);
            getServer().getPluginManager().registerEvents(preJoinAuthListener, this);
        } else if (configManager.loginDialogEnabled()) {
            getLogger().warning(I18n.get("log.dialog_unsupported"));
        }

        registerCommands();
        registerListeners();
        hookPlaceholderAPI();
        registerPacketListener();
        registerPremiumListener();
        // 清理不活跃账号（启动时执行，此时玩家尚未进入）
        if (configManager.purgeEnabled()) {
            int purged = playerDataManager.purgeInactive(configManager.purgeDays());
            if (purged > 0) {
                getLogger().info(I18n.get("log.purge_inactive", purged, configManager.purgeDays()));
            }
        }
        // 周期批量落库脏数据（合并 DB 写，降低 SQLite 锁竞争与 IO 开销）
        Bukkit.getAsyncScheduler().runAtFixedRate(this,
                task -> playerDataManager.flushDirty(), 1, DB_SAVE_FLUSH_SECONDS, TimeUnit.SECONDS);
        rePendOnlinePlayers();

        // 初始化 API 单例：必须在 AuthManager/PlayerDataManager 初始化完成后，
        // 且在 rePendOnlinePlayers 之后（后者可能触发登录流程，API 此时已可用）
        HSAuthApi.initialize(this, authManager, playerDataManager);

        getLogger().info(I18n.get("plugin.enabled", getPluginMeta().getVersion()));
    }

    @Override
    public void onDisable() {
        // 调试日志句柄先关闭：后续不再产生调试输出
        Debug.close();
        // 先清理 API 单例：后续将关闭 dataManager，避免第三方插件在关服过程中读到失效实例
        HSAuthApi.shutdown();
        // 关服前保存所有在线已登录玩家的当前位置
        // stop 关服时 PlayerQuitEvent 可能不触发或时序不确定，显式保存确保位置不丢失
        // 用 updateLogoutLocationCache 只更新内存缓存，由后续 saveSync 统一落库
        // （插件禁用后无法注册异步保存任务）
        if (authManager != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (sessions().isLoggedIn(player)) {
                    authManager.locations().updateCache(player);
                }
            }
        }
        if (playerDataManager != null) {
            playerDataManager.saveSync();
            playerDataManager.close();
        }
        // 关闭正版验证线程池（daemon 线程 JVM 会终止，但规范上应显式关闭）
        if (mojangClient != null) mojangClient.close();
        if (playerInjector != null) playerInjector.close();
        // Paper 1.20+ 统一调度器 API，兼容 Folia
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        Bukkit.getAsyncScheduler().cancelTasks(this);
        // 珍珠保管兜底写盘：关服批量退出触发的异步写可能刚被上方取消，此处同步确保落盘
        if (pendingPearlManager != null) pendingPearlManager.shutdown();
        // PacketEvents 监听器由其自身管理生命周期，无需手动注销
        // 先输出日志再清理 I18n 静态状态，否则 shutdown 后 bundles 被清空会导致 get 返回 key 本身
        getLogger().info(I18n.get("plugin.disabled"));
        I18n.shutdown();
    }

    private void registerCommands() {
        // Paper 插件规范：通过 LifecycleEvents 程序化注册命令
        // 使用 var 接收 getLifecycleManager() 的返回值，让编译器自动推断通配符类型，
        // 避免显式声明 LifecycleEventManager<Plugin> 时与实际返回类型不匹配的警告
        // 命令描述取自语言文件（默认语言）：命令树随登录一次性下发，无法按玩家语言本地化，
        // 因此这里只取默认语言那一份，且 /hsauth reload 不会更新已注册的描述（需重启）
        var manager = this.getLifecycleManager();
        manager.registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register("register", I18n.get("command.desc.register"), List.of("reg"), new RegisterCommand(this, authManager));
            commands.register("login", I18n.get("command.desc.login"), List.of("l"), new LoginCommand(authManager, authManager.sessions(), authManager.locations(), authManager.accounts()));
            commands.register("changepassword", I18n.get("command.desc.changepassword"), List.of("changepw", "cp"), new ChangePasswordCommand(this, authManager));
            commands.register("addpassword", I18n.get("command.desc.addpassword"), List.of("addpw"), new AddPasswordCommand(this, authManager));
            commands.register("removepassword", I18n.get("command.desc.removepassword"), List.of("removepw", "rmpw"), new RemovePasswordCommand(authManager, authManager.sessions(), authManager.twoFactor(), authManager.accounts()));
            commands.register("logout", I18n.get("command.desc.logout"), List.of(), new LogoutCommand(authManager, authManager.sessions()));
            commands.register("upgrade", I18n.get("command.desc.upgrade"), List.of(), new UpgradeAccountCommand(this, authManager));
            commands.register("downgrade", I18n.get("command.desc.downgrade"), List.of(), new DowngradeAccountCommand(this, authManager));
            UnregisterCommand unregisterCommand;
            commands.register("unregister", I18n.get("command.desc.unregister"), List.of(), unregisterCommand = new UnregisterCommand(this, authManager));
            authManager.accounts().setUnregisterConfirmInvalidator(unregisterCommand::clearPendingConfirm);
            commands.register(new PremiumCommand(this, authManager).buildNode(), I18n.get("command.desc.premium"), List.of());
            // 2fa 与 hsauth 一样使用 brigadier 原生注册，子命令作为 literal 节点，
            // 客户端在输入空格后能自动显示子命令列表
            commands.register(new TwoFactorCommand(this, authManager, dialogManager).buildNode(), I18n.get("command.desc.2fa"), List.of("totp"));
            commands.register(new HSAuthCommand(this).buildNode(), I18n.get("command.desc.hsauth"), List.of());
        });
    }

    private void registerListeners() {
        playerListener = new PlayerListener(this, authManager, authManager.sessions(), authManager.failProtection(), authManager.twoFactor(), authManager.locations(), authManager.accounts());
        getServer().getPluginManager().registerEvents(playerListener, this);
        // 末影珍珠保管：独立监听器（接管飞行珍珠，登录后按配置返还）
        pendingPearlManager = new PendingPearlManager(this);
        getServer().getPluginManager().registerEvents(pendingPearlManager, this);
        // 加入/退出消息：独立监听器（模板替换 + 未登录隐藏 + 登录成功补发）
        JoinQuitMessageService joinQuitMessageService = new JoinQuitMessageService(authManager.sessions(), configManager);
        getServer().getPluginManager().registerEvents(joinQuitMessageService, this);
    }

    /** 注册 PacketEvents 数据包监听器（背包保护：拦截容器/装备同步包） */
    private void registerPacketListener() {
        PacketEvents.getAPI().getEventManager()
                .registerListener(new InventoryPacketListener(authManager.sessions(), configManager));
    }

    /**
     * 注册正版验证监听器。
     * 始终注册监听器；是否拦截正版玩家由 ConnectionHandler 按数据库 premium 标记实时判断
     * （premium=1 始终验证，配置文件 premium.enabled 只决定新玩家是否验证），
     */
    private void registerPremiumListener() {
        DataService dataService = new DataService(playerDataManager, configManager);
        this.mojangClient = new MojangClient(this);
        this.playerInjector = new PlayerInjector(this);
        PacketEvents.getAPI().getEventManager()
                .registerListener(new ConnectionHandler(this, dataService, mojangClient, playerInjector, authManager));
        // 启动时异步探测代理/镜像可用性（不阻塞启动），使用时跳过不可用端点
        if (configManager.premiumEnabled() || playerDataManager.hasPremiumAccount()) {
            mojangClient.probeAll();
        }
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public PlayerDataManager getPlayerDataManager() {
        return playerDataManager;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }

    /** 会话状态中心（登录态/认证标记/过渡标记），AuthManager 拆分后的协作服务 */
    public SessionStore sessions() {
        return authManager.sessions();
    }

    /** 暴力破解防护（失败计数/踢出期），AuthManager 拆分后的协作服务 */
    public FailProtection failProtection() {
        return authManager.failProtection();
    }

    /** 双因素认证（待验证状态/验证码/绑定解绑），AuthManager 拆分后的协作服务 */
    public TwoFactorAuth twoFactor() {
        return authManager.twoFactor();
    }

    /** 退出位置与坐标保护（位置保存/传送/旁观切换），AuthManager 拆分后的协作服务 */
    public LogoutLocation locations() {
        return authManager.locations();
    }

    /** 账号生命周期（注册/注销/数据删除/正版标记），AuthManager 拆分后的协作服务 */
    public AccountLifecycle accounts() {
        return authManager.accounts();
    }

    public PlayerListener getPlayerListener() {
        return playerListener;
    }

    public PendingPearlManager getPendingPearlManager() {
        return pendingPearlManager;
    }

    /** 配置阶段 Dialog 监听器（配置事件 API 不可用时为 null，调用方判空回退聊天栏流程） */
    public PreJoinAuthListener getPreJoinAuthListener() {
        return preJoinAuthListener;
    }

    /**
     * 检测服务端配置阶段 Dialog 是否稳定可用：事件 API 存在（1.21.4+）且服务端版本 >= 1.21.11。
     * 1.21.6–1.21.10 虽能发送配置阶段 Dialog 包，但存在未验证的兼容问题（见 Paper issue #13365/#13708），
     * AuthMe 亦将 pre-join 门槛定为 1.21.11+，故低于此版本回退聊天栏提示更稳妥。
     * 版本解析与 AuthManager#detectNewWorldStructure 同源（兼容 26.x 新命名）。
     */
    private static boolean preJoinSupported() {
        try {
            Class.forName("io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent");
        } catch (ClassNotFoundException e) {
            return false;
        }
        // 解析 Bukkit version：如 "1.21.11-R0.1-SNAPSHOT"；26.x 新命名如 "26.1.2-R0.1"（主版本 >= 26 > 1）
        String nums = Bukkit.getBukkitVersion().split("-", 2)[0];
        String[] parts = nums.split("\\.");
        int[] v = new int[3];
        try {
            for (int i = 0; i < parts.length && i < v.length; i++) {
                v[i] = Integer.parseInt(parts[i]);
            }
        } catch (NumberFormatException e) {
            return false;
        }
        // 阈值 1.21.11：主版本 >1（含 26.x）满足；主版本==1 需次版本>21，或 ==21 且 patch>=11
        return v[0] > 1 || (v[0] == 1 && (v[1] > 21 || (v[1] == 21 && v[2] >= 11)));
    }

    /** PlaceholderAPI 软依赖：存在时注册变量扩展 */
    private void hookPlaceholderAPI() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new HSAuthExpansion(this).register();
            getLogger().info(I18n.get("log.placeholderapi_enabled"));
        }
    }

    /**
     * 处理 /reload 后在线玩家状态丢失：
     * 插件重启后内存中的登录状态被清空，需重新挂起未登录的在线玩家。
     */
    private void rePendOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (sessions().isLoggedIn(player)) continue;
            playerListener.beginAuthFlow(player);
        }
    }

    /** 将旧版 '&' 颜色代码转换为 Adventure Component */
    public static net.kyori.adventure.text.Component legacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
