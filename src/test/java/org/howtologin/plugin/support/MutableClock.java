package org.howtologin.plugin.support;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** 可控时钟：测试用它推进时间，替代真实 sleep；线程安全 */
public final class MutableClock implements LongSupplier {

    private final AtomicLong millis;

    public MutableClock(long initialMillis) {
        this.millis = new AtomicLong(initialMillis);
    }

    @Override
    public long getAsLong() {
        return millis.get();
    }

    /** 前进指定毫秒数 */
    public void advance(long deltaMillis) {
        millis.addAndGet(deltaMillis);
    }
}
