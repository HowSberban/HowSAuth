package org.howsauth.plugin.pearl;

import org.bukkit.Bukkit;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.howsauth.plugin.HowSAuth;
import org.howsauth.plugin.I18n;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 末影珍珠归属者解析与归属认证判断。
 * <p>
 * 一组互相依靠的职责：优先用 {@code getShooter()} 取归属者，取不到时经 NMS 反射读取
 * {@code getHandle().getOwnerUUID()}（抛射物离弦后 shooter 可能已不可用），并判断归属者
 * 当前是否已认证。反射方法与限频告警都局限在此处，Minecraft 版本升级只需改这一个类。
 * <p>
 * 反射方法按 {@code Class} 缓存（{@link ConcurrentHashMap#computeIfAbsent}），空结果同样缓存，
 * 避免每次珍珠落地都重复查找；缓存值为 {@code Optional.empty()} 表示该类确实没有该方法。
 */
final class PearlOwnerResolver {

    private final HowSAuth plugin;
    // 反射方法缓存：类 -> getHandle()/getOwnerUUID()，空结果同样入缓存
    private final Map<Class<?>, Optional<Method>> handleMethods = new ConcurrentHashMap<>();
    private final Map<Class<?>, Optional<Method>> ownerUuidMethods = new ConcurrentHashMap<>();

    private static final long OWNER_WARNING_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final AtomicLong lastOwnerWarningAt = new AtomicLong();

    PearlOwnerResolver(HowSAuth plugin) {
        this.plugin = plugin;
    }

    /** 解析珍珠归属者：优先 shooter，取不到时经 NMS 反射读取；无法解析返回 null */
    UUID resolve(EnderPearl pearl) {
        if (pearl.getShooter() instanceof Player player) {
            return player.getUniqueId();
        }
        return readOwnerUuid(pearl);
    }

    /** 归属者当前是否已认证（在线且处于登录态）；离线或未登录返回 false */
    boolean isAuthenticated(UUID owner) {
        Player player = Bukkit.getPlayer(owner);
        return player != null && plugin.sessions().isLoggedIn(player);
    }

    /** 限频告警：归属者解析失败按 {@link #OWNER_WARNING_INTERVAL_MILLIS} 节流，避免刷屏 */
    void warnResolutionFailure(EnderPearl pearl) {
        long now = System.currentTimeMillis();
        while (true) {
            long previous = lastOwnerWarningAt.get();
            if (now - previous < OWNER_WARNING_INTERVAL_MILLIS) return;
            if (lastOwnerWarningAt.compareAndSet(previous, now)) {
                plugin.getLogger().warning(I18n.get("log.pearl_owner_resolve_failed", pearl.getUniqueId()));
                return;
            }
        }
    }

    /** 经 NMS 反射读取归属者 UUID（shooter 不可用时）；反射失败返回 null */
    private UUID readOwnerUuid(EnderPearl pearl) {
        try {
            Optional<Method> handleMethod = handleMethods.computeIfAbsent(
                    pearl.getClass(), type -> findMethod(type, "getHandle"));
            if (handleMethod.isEmpty()) return null;
            Object handle = handleMethod.get().invoke(pearl);
            Optional<Method> ownerMethod = ownerUuidMethods.computeIfAbsent(
                    handle.getClass(), type -> findMethod(type, "getOwnerUUID"));
            if (ownerMethod.isEmpty()) return null;
            return (UUID) ownerMethod.get().invoke(handle);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Optional<Method> findMethod(Class<?> type, String name) {
        try {
            return Optional.of(type.getMethod(name));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
