package com.example.gsb.iterator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 并发压测：1 个写线程持续做结构性修改，4 个读线程同时用三种模式迭代，
 * 验证每种模式在压力下都遵守自己的语义。
 */
class ConcurrentStressTest {

    private static final int READERS = 4;
    private static final long DURATION_MILLIS = 1500;

    private static final class Harness {
        final VersionedCollection<Integer> collection = new VersionedCollection<>();
        final AtomicBoolean stop = new AtomicBoolean();
        final AtomicLong writeOps = new AtomicLong();

        Harness() {
            for (int i = 0; i < 100; i++) {
                collection.add(i);
            }
        }

        Thread startWriter() {
            // 写入的值严格递增（全局唯一），并用滑动窗口把集合大小限制在约 600，
            // 这样"弱一致迭代不重复"的断言才不会被集合里本就存在的重复值干扰。
            Thread writer = new Thread(() -> {
                long i = 0;
                while (!stop.get()) {
                    collection.add((int) (100 + i));
                    if (i >= 500) {
                        collection.remove((int) (100 + i - 500));
                    }
                    writeOps.incrementAndGet();
                    i++;
                }
            }, "stress-writer");
            writer.start();
            return writer;
        }

        /** 并行跑 READERS 个读线程，收集每个线程抛出的异常（应为空）。 */
        List<Throwable> runReaders(Runnable readerLoop) throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(READERS);
            CountDownLatch ready = new CountDownLatch(READERS);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < READERS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    readerLoop.run();
                    return null;
                }));
            }
            ready.await();
            go.countDown();
            Thread.sleep(DURATION_MILLIS);
            stop.set(true);
            pool.shutdown();
            List<Throwable> errors = new ArrayList<>();
            for (Future<?> f : futures) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    errors.add(e.getCause());
                }
            }
            return errors;
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void failFastUnderStressThrowsOnlyConcurrentModification() throws Exception {
        Harness h = new Harness();
        Thread writer = h.startWriter();
        AtomicLong detected = new AtomicLong();
        AtomicLong completed = new AtomicLong();

        List<Throwable> errors = h.runReaders(() -> {
            while (!h.stop.get()) {
                VersionedIterator<Integer> it = h.collection.iterator(IterationMode.FAIL_FAST);
                try {
                    while (it.hasNext()) {
                        it.next();
                    }
                    completed.incrementAndGet();
                } catch (ConcurrentModificationDetectedException expected) {
                    detected.incrementAndGet();
                }
            }
        });
        writer.join();

        assertThat(errors).isEmpty();
        assertThat(h.writeOps.get()).isGreaterThan(0);
        assertThat(detected.get()).isGreaterThan(0);
        // 统计与读线程自计数一致：检测数 == 统计的并发修改数，且都被记为放弃
        assertThat(h.collection.stats().getConcurrentModificationsDetected())
                .isEqualTo(detected.get());
        assertThat(h.collection.stats().getAbandonedIterations())
                .isEqualTo(detected.get());
        assertThat(h.collection.stats().getCompletedIterations())
                .isEqualTo(completed.get());
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void snapshotUnderStressNeverThrowsAndMatchesCreationView() throws Exception {
        Harness h = new Harness();
        Thread writer = h.startWriter();

        List<Throwable> errors = h.runReaders(() -> {
            while (!h.stop.get()) {
                VersionedIterator<Integer> it = h.collection.iterator(IterationMode.SNAPSHOT);
                Set<Integer> seen = new HashSet<>();
                while (it.hasNext()) {
                    // 快照语义：写线程的修改永远不可见——元素全局唯一，
                    // 因此迭代结果绝不抛异常、绝不重复。
                    assertThat(seen.add(it.next())).isTrue();
                }
                assertThat(seen.size()).isLessThanOrEqualTo(10_000);
            }
        });
        writer.join();

        assertThat(errors).isEmpty();
        assertThat(h.collection.stats().getConcurrentModificationsDetected()).isZero();
        assertThat(h.collection.stats().getAbandonedIterations()).isZero();
        assertThat(h.collection.stats().getCompletedIterations())
                .isEqualTo(h.collection.stats().getIterationsStarted(IterationMode.SNAPSHOT));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void weaklyConsistentUnderStressNeverThrowsNeverDuplicatesAndTerminates() throws Exception {
        Harness h = new Harness();
        Thread writer = h.startWriter();

        List<Throwable> errors = h.runReaders(() -> {
            while (!h.stop.get()) {
                VersionedIterator<Integer> it = h.collection.iterator(IterationMode.WEAKLY_CONSISTENT);
                Set<Integer> seen = new HashSet<>();
                while (it.hasNext()) {
                    Integer value = it.next();
                    // 弱一致的核心保证：同一轮迭代绝不出现重复元素
                    assertThat(seen.add(value))
                            .as("duplicate element %s in weakly consistent iteration", value)
                            .isTrue();
                }
            }
        });
        writer.join();

        assertThat(errors).isEmpty();
        assertThat(h.collection.stats().getConcurrentModificationsDetected()).isZero();
        assertThat(h.collection.stats().getAbandonedIterations()).isZero();
        assertThat(h.collection.stats().getCompletedIterations())
                .isEqualTo(h.collection.stats().getIterationsStarted(IterationMode.WEAKLY_CONSISTENT));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void allModesTogetherUnderStress() throws Exception {
        Harness h = new Harness();
        Thread writer = h.startWriter();

        List<Throwable> errors = h.runReaders(() -> {
            while (!h.stop.get()) {
                for (IterationMode mode : IterationMode.values()) {
                    VersionedIterator<Integer> it = h.collection.iterator(mode);
                    try {
                        while (it.hasNext()) {
                            it.next();
                        }
                    } catch (ConcurrentModificationDetectedException expectedInFailFast) {
                        assertThat(mode).isEqualTo(IterationMode.FAIL_FAST);
                    }
                }
            }
        });
        writer.join();

        assertThat(errors).isEmpty();
        IterationStats stats = h.collection.stats();
        assertThat(stats.getIterationsStarted(IterationMode.FAIL_FAST)).isGreaterThan(0);
        assertThat(stats.getIterationsStarted(IterationMode.SNAPSHOT)).isGreaterThan(0);
        assertThat(stats.getIterationsStarted(IterationMode.WEAKLY_CONSISTENT)).isGreaterThan(0);
        // 全局不变式：started == completed + abandoned
        assertThat(stats.getTotalIterationsStarted())
                .isEqualTo(stats.getCompletedIterations() + stats.getAbandonedIterations());
    }
}
