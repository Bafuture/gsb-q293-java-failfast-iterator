package com.example.gsb.iterator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 三种模式的耗时对比。结果打印到标准输出，供 README 引用；
 * 测试本身只对正确性做断言，不对耗时设阈值（避免环境差异导致误报）。
 */
class PerformanceComparisonTest {

    private static final int ELEMENTS = 50_000;
    private static final int ROUNDS = 100;

    @Test
    @Timeout(120)
    void compareIterationCostOfThreeModes() {
        List<Integer> data = new ArrayList<>();
        for (int i = 0; i < ELEMENTS; i++) {
            data.add(i);
        }
        VersionedList<Integer> list = new VersionedList<>(data);

        // 预热 JIT，避免把编译开销算进第一种模式。
        for (IterationMode mode : IterationMode.values()) {
            timeMode(list, mode, 5);
        }

        long failFastNanos = timeMode(list, IterationMode.FAIL_FAST, ROUNDS);
        long snapshotNanos = timeMode(list, IterationMode.SNAPSHOT, ROUNDS);
        long weakNanos = timeMode(list, IterationMode.WEAKLY_CONSISTENT, ROUNDS);

        System.out.printf("%n=== 迭代耗时对比（%d 个元素 x %d 轮）===%n", ELEMENTS, ROUNDS);
        System.out.printf("FAIL_FAST          : %8.2f ms 总计, %7.3f ms/轮%n",
                failFastNanos / 1e6, failFastNanos / 1e6 / ROUNDS);
        System.out.printf("SNAPSHOT           : %8.2f ms 总计, %7.3f ms/轮%n",
                snapshotNanos / 1e6, snapshotNanos / 1e6 / ROUNDS);
        System.out.printf("WEAKLY_CONSISTENT  : %8.2f ms 总计, %7.3f ms/轮%n",
                weakNanos / 1e6, weakNanos / 1e6 / ROUNDS);

        IterationStats stats = list.stats();
        assertThat(stats.getIterationsCompleted())
                .isEqualTo(stats.getIterationsStarted());
    }

    private static long timeMode(VersionedList<Integer> list, IterationMode mode, int rounds) {
        long checksum = 0;
        long start = System.nanoTime();
        for (int round = 0; round < rounds; round++) {
            Iterator<Integer> it = list.iterator(mode);
            while (it.hasNext()) {
                checksum += it.next();
            }
        }
        long elapsed = System.nanoTime() - start;
        // 防止循环被 JIT 整体优化掉。
        if (checksum == Long.MIN_VALUE) {
            throw new AssertionError("unreachable");
        }
        return elapsed;
    }
}
