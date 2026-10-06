package org.howsauth.plugin.dialog;

import org.howsauth.plugin.config.ConfigManager;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置阶段的玩家名认领：<b>先到者持续占有连接，后来者被拒</b>。
 * <p>
 * 离线模式（online-mode=false）下同名连接解析为同一离线 UUID，而服务端的同名单会话检查
 * 要到创建 ServerPlayer（play 阶段）才生效。pre-join Dialog 会在配置阶段阻塞等待认证，
 * 这个窗口内两个同名连接可以同时存在并共享同一 UUID 的状态，因此必须在配置阶段入口
 * 就认领名字：先到的连接独占该名字，后来者收到"已在连接中"而不是把先到者踢下线。
 * <p>
 * <b>释放路径</b>（三条互补，保证名字不会被永久锁死）：
 * <ul>
 *   <li>玩家进入世界：play 阶段起服务端单会话检查接管，{@link #releaseByName}；</li>
 *   <li>认证失败被踢 / 无凭据断连：连接不会进入游戏，{@link #release}；</li>
 *   <li>TTL 兜底：异常路径（进程内异常、客户端静默断开）下按
 *       {@link #ttlMillis()} 自动回收，过期认领可被新连接接管。</li>
 * </ul>
 */
final class NameClaims {

    /** 认领在认证超时之外额外保留的宽限期（秒）：覆盖配置阶段结束到创建 ServerPlayer 的过渡 */
    private static final long GRACE_SECONDS = 30;
    /** 超时配置为 0（聊天流程不限时）时的兜底上限（秒），与 PreJoinAuthListener 的等待上限一致 */
    private static final long FALLBACK_TIMEOUT_SECONDS = 600;

    private final Map<String, Claim> claims = new ConcurrentHashMap<>();
    private final ConfigManager configManager;

    /** 名字认领：持有者身份（释放时校验，避免误释放他人的认领）+ 过期时间（TTL 兜底） */
    private record Claim(Object owner, long expiresAt) {}

    NameClaims(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * 认领名字：先到者胜。
     * 已被未过期认领占用时返回 false，调用方应拒绝该连接且不得触碰任何共享状态
     * （否则会清除先到者正在进行的认证结果）。
     * @param owner 认领持有者（本次连接对象），释放时须与其身份一致
     * @return true 认领成功；false 该名字已被先到的连接占有
     */
    boolean claim(String name, Object owner) {
        String key = key(name);
        long now = System.currentTimeMillis();
        long expiresAt = now + ttlMillis();
        while (true) {
            Claim current = claims.get(key);
            // 先到者持续占有：未过期的既有认领不可被抢占
            if (current != null && current.expiresAt() > now) return false;
            Claim mine = new Claim(owner, expiresAt);
            if (current == null) {
                if (claims.putIfAbsent(key, mine) == null) return true;
            } else if (claims.replace(key, current, mine)) {
                return true; // 接管已过期的认领
            }
            // CAS 失败说明并发认领者胜出，回到循环重新判定
        }
    }

    /** 释放认领：仅当持有者身份一致时移除，避免误释放他人（或新连接）的认领 */
    void release(String name, Object owner) {
        claims.computeIfPresent(key(name), (k, c) -> c.owner() == owner ? null : c);
    }

    /** 按名字无条件释放：玩家已进入世界时调用（play 阶段起服务端单会话检查接管同名保护） */
    void releaseByName(String name) {
        claims.remove(key(name));
    }

    /**
     * 认领存活上限：认证超时 + 宽限期。
     * 超时配置为 0（聊天流程不限时）时用兜底上限，
     * 确保任何异常路径下名字都会被回收，不会永久锁死。
     */
    private long ttlMillis() {
        long timeout = Math.max(configManager.login().timeout(), configManager.login().registerTimeout());
        if (timeout <= 0) timeout = FALLBACK_TIMEOUT_SECONDS;
        return (timeout + GRACE_SECONDS) * 1000L;
    }

    /** 名字规范化：玩家名不区分大小写（与服务端同名检查口径一致），null 归一为空串 */
    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
