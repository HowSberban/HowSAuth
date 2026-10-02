package org.howsauth.plugin.premium;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 玩家注入器（模块4）—— authenticatedProfile 设置 + state 推进。
 * <p>
 * 方案：取消 LoginStart 后，服务端 state 保持 HELLO。
 * 验证完成后设置 authenticatedProfile + state=VERIFYING，
 * 让服务端 tick() 自然调用 verifyLoginAndFinishConnectionSetup：
 *   → canPlayerLogin（触发 PlayerLoginEvent）→ state=WAITING_FOR_DUPE_DISCONNECT
 *   → finishLoginAndWaitForClient → 发送 LoginSuccess（含正版 UUID + 皮肤）→ state=PROTOCOL_SWITCHING
 * 客户端收到 LoginSuccess 后发送 LoginAcknowledged，服务端自然接手协议切换。
 * <p>
 * 线程模型：
 * - fireAsyncPreLogin: 异步线程
 * - setProfileAndAdvanceState: IO线程（持有 channel 的 eventLoop）
 * <p>
 * NMS 字段（packetListener/authenticatedProfile/requestedUsername/state）需反射，
 * 因为 NMS 类不在编译期依赖中。GameProfile/Property 可直接使用（authlib compileOnly）。
 */
public final class PlayerInjector {

    private final HowSAuth plugin;
    // 专用线程池：fireAsyncPreLogin 内部同步 callEvent() 调用所有监听器（可能较慢阻塞），
    // 用专用受控线程池避免占用公共 ForkJoinPool 拖累其它插件的异步任务。
    // 池大小沿用 premium.http-pool-size 配置（正版验证异步任务池）
    private final ExecutorService preLoginExecutor;

    public PlayerInjector(HowSAuth plugin) {
        this.plugin = plugin;
        int poolSize = plugin.getConfigManager().premiumHttpPoolSize();
        this.preLoginExecutor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "HowSAuth-PreLogin");
            t.setDaemon(true);
            return t;
        });
    }

    /** 关闭线程池（插件禁用时调用） */
    public void close() {
        preLoginExecutor.shutdown();
    }

    /**
     * 启动时静态自检：反射依赖的 NMS 类/字段/枚举在当前服务端版本是否存在。
     * pipeline 内 packet_handler 等运行期对象无法提前验证，此检查只覆盖静态可验证的部分，
     * 避免服务端版本更新后玩家卡死在登录阶段才暴露问题。
     * @return 全部匹配返回 null；否则返回首个缺失项描述
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static String checkNmsCompatibility() {
        try {
            Class<?> listener = Class.forName("net.minecraft.server.network.ServerLoginPacketListenerImpl");
            findField(listener, "authenticatedProfile");
            findField(listener, "requestedUsername");
            findField(listener, "state");
            Class<?> state = Class.forName("net.minecraft.server.network.ServerLoginPacketListenerImpl$State");
            Enum.valueOf((Class<Enum>) state, "VERIFYING");
            return null;
        } catch (ClassNotFoundException | NoSuchFieldException e) {
            return e.toString();
        } catch (IllegalArgumentException e) {
            return "State.VERIFYING missing";
        }
    }

    /**
     * 异步触发 AsyncPlayerPreLoginEvent，让其他插件（权限组/领地等）准备玩家数据。
     * 由于取消了 LoginStart，服务端不会自动触发此事件，需手动 callEvent。
     *
     * @param name 玩家名
     * @param uuid 正版 UUID
     * @param ip   玩家 IP
     * @return null 表示允许继续；非 null 为应断开给客户端的理由（透传其他插件设置的踢出消息）
     */
    // AsyncPlayerPreLoginEvent 标记为 Experimental/removal，实际为登录流程必需的公开 API
    @SuppressWarnings({"removal", "UnstableApiUsage"})
    public CompletableFuture<Component> fireAsyncPreLogin(String name, UUID uuid, String ip) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                InetAddress address = InetAddress.getByName(ip);
                AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent(name, address, uuid);
                event.callEvent();
                if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                    return null;
                }
                // 被其他插件拒绝：透传对方设置的踢出理由（kickMessage 为 @NotNull，内容为空时用通用消息）
                // 直接返回 Component，避免 § 颜色码经字符串往返后丢失样式
                Component kick = event.kickMessage();
                return PlainTextComponentSerializer.plainText().serialize(kick).isEmpty()
                        ? HowSAuth.legacy(I18n.get("listener.premium_invalid_session")) : kick;
            } catch (Exception e) {
                plugin.getLogger().warning(I18n.get("log.premium_prelogin_failed", name, e.getMessage()));
                return HowSAuth.legacy(I18n.get("listener.premium_unavailable"));
            }
        }, preLoginExecutor).exceptionally(t -> {
            // Error（类加载失败等）不经 Exception 分支，future 会以异常完成，消费端 thenAccept 静默跳过导致会话滞留
            // 兜底返回通用踢出理由（fail-closed），保证调用方总能收到可处理结果
            plugin.getLogger().severe(I18n.get("log.premium_prelogin_failed", name, t.toString()));
            return HowSAuth.legacy(I18n.get("listener.premium_unavailable"));
        });
    }

    /**
     * 设置 authenticatedProfile + state=VERIFYING，让服务端 tick() 接管。
     * <p>
     * 服务端 tick() 在 VERIFYING 状态下调用 verifyLoginAndFinishConnectionSetup：
     *   → canPlayerLogin（触发 PlayerLoginEvent）→ state=WAITING_FOR_DUPE_DISCONNECT
     *   → finishLoginAndWaitForClient → 发送 LoginSuccess（含正版 UUID + 皮肤）→ state=PROTOCOL_SWITCHING
     * 客户端收到 LoginSuccess 后发送 LoginAcknowledged，服务端自然接手协议切换。
     *
     * @param channel        底层 Netty 通道
     * @param uuid           正版 UUID
     * @param name           玩家名
     * @param propertiesJson Mojang 返回的 properties JSON（可能为 null）
     * @throws Exception 反射操作失败
     */
    public void setProfileAndAdvanceState(Channel channel, UUID uuid, String name, String propertiesJson) throws Exception {
        // 1. 取 NMS Connection（原版固定处理器名 packet_handler）
        ChannelPipeline pipeline = channel.pipeline();
        Object connection = pipeline.get("packet_handler");
        if (connection == null) {
            throw new IllegalStateException(I18n.get("log.premium_channel_cast_failed"));
        }

        // 2. 从 Connection 获取 packetListener（ServerLoginPacketListenerImpl）
        Field packetListenerField = findField(connection.getClass(), "packetListener");
        packetListenerField.setAccessible(true);
        Object loginListener = packetListenerField.get(connection);
        if (loginListener == null) {
            throw new IllegalStateException(I18n.get("log.premium_packet_listener_null"));
        }

        // 3. 构造 GameProfile 并设置到 authenticatedProfile
        GameProfile profile = buildGameProfile(uuid, name, propertiesJson);
        setFieldValue(loginListener, "authenticatedProfile", profile);
        // 取消了 LoginStart，服务端未设置此字段
        setFieldValue(loginListener, "requestedUsername", name);

        // 4. 设置 state = VERIFYING
        // 服务端 tick() 会调用 verifyLoginAndFinishConnectionSetup：
        //   → canPlayerLogin（触发 PlayerLoginEvent）→ state=WAITING_FOR_DUPE_DISCONNECT
        //   → finishLoginAndWaitForClient → 发送 LoginSuccess → state=PROTOCOL_SWITCHING
        @SuppressWarnings({"unchecked", "rawtypes"})
        Object verifying = Enum.valueOf(
                (Class<Enum>) Class.forName("net.minecraft.server.network.ServerLoginPacketListenerImpl$State"),
                "VERIFYING");
        setFieldValue(loginListener, "state", verifying);
    }

    /**
     * 构造 authlib GameProfile（含皮肤 properties）。
     * 用于反射设置 ServerLoginPacketListenerImpl.authenticatedProfile。
     * 服务端在创建玩家实体时使用此 profile 的 UUID。
     * <p>
     * authlib 9.0+ GameProfile 为 record，PropertyMap 内部 Multimap 不可变，
     * 故用可变 ArrayListMultimap 收集 properties 后通过 PropertyMap(Multimap) 构造器传入，
     * 再用 GameProfile 三参构造器重建 profile。
     */
    private static GameProfile buildGameProfile(UUID uuid, String name, String propertiesJson) {
        if (propertiesJson == null || propertiesJson.isEmpty()) {
            return new GameProfile(uuid, name);
        }
        try {
            JsonArray array = JsonParser.parseString(propertiesJson).getAsJsonArray();
            if (array.isEmpty()) {
                return new GameProfile(uuid, name);
            }
            com.google.common.collect.Multimap<String, Property> multimap =
                    com.google.common.collect.ArrayListMultimap.create();
            for (JsonElement element : array) {
                JsonObject obj = element.getAsJsonObject();
                String propName = obj.get("name").getAsString();
                String value = obj.get("value").getAsString();
                String signature = obj.has("signature") && !obj.get("signature").isJsonNull()
                        ? obj.get("signature").getAsString() : null;
                multimap.put(propName, signature != null
                        ? new Property(propName, value, signature)
                        : new Property(propName, value));
            }
            return new GameProfile(uuid, name, new PropertyMap(multimap));
        } catch (Exception e) {
            // JSON 解析失败不影响登录（仅缺少皮肤）
            return new GameProfile(uuid, name);
        }
    }

    /** 反射设置 private 字段（递归查找父类） */
    private static void setFieldValue(Object obj, String fieldName, Object value) throws Exception {
        Field field = findField(obj.getClass(), fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    // 递归查找字段（含父类）
    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
