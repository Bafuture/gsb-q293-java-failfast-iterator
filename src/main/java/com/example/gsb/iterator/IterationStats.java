package com.example.gsb.iterator;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 迭代统计信息。按 {@link IterationMode} 分别计数，所有计数器都是线程安全的。
 *
 * <ul>
 *   <li>迭代次数（started / completed）：创建迭代器与迭代正常结束的累计次数；</li>
 *   <li>检测到的并发修改次数：快速失败模式抛出
 *       {@link ConcurrentModificationDetectedException} 的累计次数；</li>
 *   <li>被放弃的迭代数：迭代未正常走完的次数（异常中断或调用方主动
 *       {@link VersionedIterator#abandon()}）。</li>
 * </ul>
 */
public final class IterationStats {

    private final AtomicLong failFastStarted = new AtomicLong();
    private final AtomicLong snapshotStarted = new AtomicLong();
    private final AtomicLong weaklyConsistentStarted = new AtomicLong();
    private final AtomicLong completedIterations = new AtomicLong();
    private final AtomicLong concurrentModificationsDetected = new AtomicLong();
    private final AtomicLong abandonedIterations = new AtomicLong();

    void recordStarted(IterationMode mode) {
        switch (mode) {
            case FAIL_FAST -> failFastStarted.incrementAndGet();
            case SNAPSHOT -> snapshotStarted.incrementAndGet();
            case WEAKLY_CONSISTENT -> weaklyConsistentStarted.incrementAndGet();
        }
    }

    void recordCompleted() {
        completedIterations.incrementAndGet();
    }

    void recordConcurrentModificationDetected() {
        concurrentModificationsDetected.incrementAndGet();
    }

    void recordAbandoned() {
        abandonedIterations.incrementAndGet();
    }

    /** 所有模式累计创建的迭代器数量。 */
    public long getTotalIterationsStarted() {
        return failFastStarted.get() + snapshotStarted.get() + weaklyConsistentStarted.get();
    }

    public long getIterationsStarted(IterationMode mode) {
        return switch (mode) {
            case FAIL_FAST -> failFastStarted.get();
            case SNAPSHOT -> snapshotStarted.get();
            case WEAKLY_CONSISTENT -> weaklyConsistentStarted.get();
        };
    }

    /** 正常迭代到末尾（hasNext 返回 false）的次数。 */
    public long getCompletedIterations() {
        return completedIterations.get();
    }

    /** 快速失败模式检测到并发修改并抛异常的累计次数。 */
    public long getConcurrentModificationsDetected() {
        return concurrentModificationsDetected.get();
    }

    /** 未正常走完的迭代次数（异常中断或被主动放弃）。 */
    public long getAbandonedIterations() {
        return abandonedIterations.get();
    }

    @Override
    public String toString() {
        return "IterationStats{"
                + "started=" + getTotalIterationsStarted()
                + " (failFast=" + failFastStarted.get()
                + ", snapshot=" + snapshotStarted.get()
                + ", weaklyConsistent=" + weaklyConsistentStarted.get() + ")"
                + ", completed=" + completedIterations.get()
                + ", concurrentModificationsDetected=" + concurrentModificationsDetected.get()
                + ", abandoned=" + abandonedIterations.get()
                + '}';
    }
}
