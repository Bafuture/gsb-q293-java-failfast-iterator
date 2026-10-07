package com.example.gsb.iterator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 三种模式的耗时对比基准。结果会打印到 surefire 输出，README 中也记录了
 * 一次参考数据；这里只做"代码路径可运行、结果自洽"的弱断言，不绑定
 * 具体机器性能。
 */
class IteratorBenchmarkTest {

    private static final int ELEMENTS = 100_000;
    private static final int ROUNDS = 50;
    private static final int WARMUP = 20;

    @Test
    void benchmarkWithoutWriter() {
        VersionedCollection<Integer> collection = new VersionedCollection<>();
        for (int i = 0; i < ELEMENTS; i++) {
            collection.add(i);
        }

        System.out.println();
        System.out.println("=== 静态读基准: 集合 " + ELEMENTS + " 元素, " + ROUNDS
                + " 轮全量迭代（单位 ms，越小越好）===");
        System.out.printf("%-18s %10s %12s%n", "mode", "total(ms)", "avg(ms/round)");
        for (IterationMode mode : IterationMode.values()) {
            for (int r = 0; r < WARMUP; r++) {
                drain(collection, mode);
            }
            long start = System.nanoTime();
            for (int r = 0; r < ROUNDS; r++) {
                drain(collection, mode);
            }
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            System.out.printf("%-18s %10d %12.2f%n",
                    mode.name(), millis, millis / (double) ROUNDS);
            assertThat(millis).isPositive();
        }
    }

    @Test
    void benchmarkWithConcurrentWriter() {
        long runMillis = 800;
        System.out.println();
        System.out.println("=== 并发写基准: 写线程持续 add+remove（滑动窗口 1000）, "
                + "每种模式迭代 " + runMillis + " ms ===");
        System.out.printf("%-18s %12s %12s %12s %12s%n",
                "mode", "completed", "detected", "abandoned", "elems/ms");

        for (IterationMode mode : IterationMode.values()) {
            VersionedCollection<Integer> collection = new VersionedCollection<>();
            for (int i = 0; i < 1000; i++) {
                collection.add(i);
            }
            AtomicBoolean stop = new AtomicBoolean();
            AtomicLong added = new AtomicLong();
            Thread writer = new Thread(() -> {
                long i = 1000;
                while (!stop.get()) {
                    collection.add((int) i);
                    collection.remove((int) (i - 1000));
                    i++;
                    added.incrementAndGet();
                }
            }, "bench-writer-" + mode);
            writer.start();

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(runMillis);
            long seenElements = 0;
            while (System.nanoTime() < deadline) {
                VersionedIterator<Integer> it = collection.iterator(mode);
                try {
                    while (it.hasNext()) {
                        it.next();
                        seenElements++;
                    }
                } catch (ConcurrentModificationDetectedException expected) {
                    // 仅快速失败模式会走到这里：重试一轮即可
                }
            }
            stop.set(true);
            try {
                writer.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            IterationStats s = collection.stats();
            System.out.printf("%-18s %12d %12d %12d %12.1f%n",
                    mode.name(),
                    s.getCompletedIterations(),
                    s.getConcurrentModificationsDetected(),
                    s.getAbandonedIterations(),
                    seenElements / (double) runMillis);
            assertThat(s.getTotalIterationsStarted())
                    .isEqualTo(s.getCompletedIterations() + s.getAbandonedIterations());
        }
    }

    private static void drain(VersionedCollection<Integer> collection, IterationMode mode) {
        VersionedIterator<Integer> it = collection.iterator(mode);
        while (it.hasNext()) {
            it.next();
        }
    }
}
