package org.howsauth.plugin.premium;

import io.netty.channel.Channel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录握手期间"channel → 会话"的注册表：查询、登记、清理，以及断开检测器的移除。
 * <p>
 * 清理是幂等的：会话被移除后再次清理是安全的（断连与超时可能同时触发）。
 */
final class LoginSessions {

    /** pipeline 中断开检测器的处理器名；安装方在 ConnectionHandler，移除由本类负责 */
    static final String DETECTOR_NAME = "hsauth_disconnect_detector";

    /** 会话表无锁并发：同一 channel 的读写可能来自不同线程（EventLoop 与异步验证回调） */
    private final Map<Channel, SessionContext> sessions = new ConcurrentHashMap<>();

    /** 当前 channel 的会话，无则返回 null */
    SessionContext get(Channel channel) {
        return sessions.get(channel);
    }

    /** 登记/覆盖 channel 的会话；调用方决定是否先清理旧会话 */
    void put(Channel channel, SessionContext session) {
        sessions.put(channel, session);
    }

    /** 移除 channel 的会话（取消其中的超时任务）并摘掉断开检测器 */
    void cleanup(Channel channel) {
        SessionContext session = sessions.remove(channel);
        if (session != null && session.timeoutTask() != null) {
            session.timeoutTask().cancel(false); // 取消超时任务，避免会话结束后无意义触发
        }
        removeDetector(channel);
    }

    /** 移除 pipeline 中的断开检测器 */
    void removeDetector(Channel channel) {
        try {
            channel.pipeline().remove(DETECTOR_NAME);
        } catch (Exception ignored) {
            // 已移除或 pipeline 已关闭
        }
    }
}