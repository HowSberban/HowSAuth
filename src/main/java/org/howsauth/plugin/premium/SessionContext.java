package org.howsauth.plugin.premium;

import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单连接会话状态机（模块1 辅助）。
 * <p>
 * 绑定唯一网络连接，记录正版验证流程的当前阶段和临时数据。
 * 内部状态变更使用 AtomicReference 保证可见性（IO线程写入，异步线程可能读取）。
 * <p>
 * 阶段流转：START → WAITING_ENCRYPTION_RESPONSE → ENCRYPTED
 */
public final class SessionContext {

    /** 会话阶段 */
    public enum Stage {
        /** 初始状态：尚未确定是否需要拦截 */
        START,
        /** 已发送 EncryptionRequest，等待客户端回传 EncryptionResponse */
        WAITING_ENCRYPTION_RESPONSE,
        /** 已启用 AES 加密，正在进行 Mojang 会话验证 */
        ENCRYPTED
    }

    private final AtomicReference<Stage> stage = new AtomicReference<>(Stage.START);

    // LoginStart 时获取的玩家名
    private volatile String username;
    // 玩家 IP 地址
    private volatile String ip;
    // 一次性验证令牌（RSA 加密后发送给客户端）
    private volatile byte[] verifyToken;
    // 是否为离线账号升级尝试（升级成功则迁移账号，失败则回退离线并清除标记）
    private volatile boolean isUpgradeAttempt;
    // 升级玩家的离线 UUID（用于迁移账号 / 清除升级标记）
    private volatile UUID offlineUuid;
    // 是否为数据库已注册正版账号（premium=1）：用于正版验证失败时判断是否可回退密码登录
    private volatile boolean premiumAccount;

    // 超时清理任务（EventLoop 调度），会话被清理或断开时取消，避免任务在会话结束后触发
    private volatile ScheduledFuture<?> timeoutTask;

    public ScheduledFuture<?> timeoutTask() { return timeoutTask; }
    public void timeoutTask(ScheduledFuture<?> timeoutTask) { this.timeoutTask = timeoutTask; }

    public Stage stage() {
        return stage.get();
    }

    /**
     * 原子推进到下一阶段，仅当当前阶段匹配预期时成功
     */
    public void advance(Stage expected, Stage next) {
        stage.compareAndSet(expected, next);
    }

    public String username() { return username; }
    public void username(String username) { this.username = username; }

    public String ip() { return ip; }
    public void ip(String ip) { this.ip = ip; }

    public byte[] verifyToken() { return verifyToken; }
    public void verifyToken(byte[] verifyToken) { this.verifyToken = verifyToken; }

    public boolean isUpgradeAttempt() { return isUpgradeAttempt; }
    public void upgradeAttempt(boolean upgradeAttempt) { this.isUpgradeAttempt = upgradeAttempt; }

    public UUID offlineUuid() { return offlineUuid; }
    public void offlineUuid(UUID offlineUuid) { this.offlineUuid = offlineUuid; }

    public boolean premiumAccount() { return premiumAccount; }
    public void premiumAccount(boolean premiumAccount) { this.premiumAccount = premiumAccount; }

}
