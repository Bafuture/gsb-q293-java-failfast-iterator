package com.example.gsb.iterator;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 一个 {@link VersionedList} 上全部迭代器共享的运行统计。
 *
 * <ul>
 *   <li>{@code iterationsStarted}：已开始的迭代次数；</li>
 *   <li>{@code iterationsCompleted}：自然走到末尾、正常结束的迭代次数；</li>
 *   <li>{@code concurrentModificationsDetected}：快速失败模式检测到的并发修改次数；</li>
 *   <li>{@code iterationsAborted}：被放弃的迭代次数（快速失败中途抛异常，
 *       或弱一致迭代因安全步进预算耗尽而提前结束）。</li>
 * </ul>
 */
public final class IterationStats {

    private final AtomicLong iterationsStarted = new AtomicLong();
    private final AtomicLong iterationsCompleted = new AtomicLong();
    private final AtomicLong concurrentModificationsDetected = new AtomicLong();
    private final AtomicLong iterationsAborted = new AtomicLong();

    void recordStart() {
        iterationsStarted.incrementAndGet();
    }

    void recordCompletion() {
        iterationsCompleted.incrementAndGet();
    }

    void recordConcurrentModification() {
        concurrentModificationsDetected.incrementAndGet();
    }

    void recordAbort() {
        iterationsAborted.incrementAndGet();
    }

    public long getIterationsStarted() {
        return iterationsStarted.get();
    }

    public long getIterationsCompleted() {
        return iterationsCompleted.get();
    }

    public long getConcurrentModificationsDetected() {
        return concurrentModificationsDetected.get();
    }

    public long getIterationsAborted() {
        return iterationsAborted.get();
    }

    @Override
    public String toString() {
        return "IterationStats{started=" + iterationsStarted
                + ", completed=" + iterationsCompleted
                + ", concurrentModificationsDetected=" + concurrentModificationsDetected
                + ", aborted=" + iterationsAborted + '}';
    }
}
