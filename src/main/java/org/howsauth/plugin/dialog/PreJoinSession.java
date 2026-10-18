package org.howsauth.plugin.dialog;

import io.papermc.paper.connection.PlayerConfigurationConnection;

import java.util.concurrent.CountDownLatch;

/**
 * 配置阶段会话：连接 + 完成信号（配置线程阻塞等待 Dialog 提交结果）。
 */
final class PreJoinSession {

    final PlayerConfigurationConnection connection;
    final CountDownLatch latch = new CountDownLatch(1);
    volatile boolean success;
    volatile boolean kicked;
    // 发送窗口失败：中止 pre-join 直接放行（回退聊天栏提示）
    volatile boolean fallback;
    // 注册流程完成（写在 countDown 前，由闭锁建立 happens-before）
    boolean registered;
    // 免密登录直弹 2FA（正版/IP）：验证状态失效时重弹验证码窗口而非密码窗口
    // volatile：写入发生在 configSessions.put 之后，回调线程读取无闭锁保护，需保证可见性
    volatile boolean passwordless2fa;

    PreJoinSession(PlayerConfigurationConnection connection) {
        this.connection = connection;
    }
}
