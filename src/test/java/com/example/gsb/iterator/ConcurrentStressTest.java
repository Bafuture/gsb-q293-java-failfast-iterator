package com.example.gsb.iterator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 并发压测：一个写线程持续做结构性修改，多个读线程分别用三种模式迭代，
 * 验证每种模式在真实竞争下都遵守自己的语义，且所有线程都能结束。
 */
class ConcurrentStressTest {

    private static final int INITIAL_SIZE = 256;
    private static final int READERS_PER_MODE = 3;
    private static final long STRESS_MILLIS = 1_500;

    private VersionedList<Integer> list;
    private ExecutorService pool;
    private final AtomicBoolean stop = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
    private final AtomicLong failFastCmeCount = new AtomicLong();
    private final AtomicLong failFastCompleted = new AtomicLong();
    private final AtomicLong snapshotIterations = new AtomicLong();
    private final AtomicLong weakIterations = new AtomicLong();

    @BeforeEach
    void setUp() {
        list = new VersionedList<>();
        for (int i = 0; i < INITIAL_SIZE; i++) {
            list.add(i);
        }
        pool = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    @Timeout(60)
    void allModesBehaveUnderConcurrentWrites() throws InterruptedException {
        CountDownLatch startGun = new CountDownLatch(1);

        pool.submit(() -> {
            await(startGun);
            runWriter();
        });
        for (int i = 0; i < READERS_PER_MODE; i++) {
            pool.submit(() -> {
                await(startGun);
                runFailFastReader();
            });
            pool.submit(() -> {
                await(startGun);
                runSnapshotReader();
            });
            pool.submit(() -> {
                await(startGun);
                runWeakReader();
            });
        }

        startGun.countDown();
        Thread.sleep(STRESS_MILLIS);
        stop.set(true);

        pool.shutdown();
        boolean finished = pool.awaitTermination(30, TimeUnit.SECONDS);
        assertThat(finished).as("所有读写线程都必须在写停止后结束，不得死循环").isTrue();
        assertThat(failures).as("压测期间不应出现非预期异常").isEmpty();

        // 三种模式都被真实执行过。
        assertThat(snapshotIterations.get()).isPositive();
        assertThat(weakIterations.get()).isPositive();
        assertThat(failFastCmeCount.get() + failFastCompleted.get()).isPositive();

        // 统计计数与各模式的实际行为一致。
        IterationStats stats = list.stats();
        assertThat(stats.getConcurrentModificationsDetected())
                .isEqualTo(failFastCmeCount.get());
        assertThat(stats.getIterationsStarted())
                .isEqualTo(failFastCmeCount.get() + failFastCompleted.get()
                        + snapshotIterations.get() + weakIterations.get());
        assertThat(stats.getIterationsAborted())
                .isGreaterThanOrEqualTo(stats.getConcurrentModificationsDetected());
        assertThat(stats.getIterationsCompleted())
                .isLessThanOrEqualTo(stats.getIterationsStarted());
    }

    private void runWriter() {
        int value = INITIAL_SIZE;
        while (!stop.get()) {
            list.add(value++);
            if (list.size() > INITIAL_SIZE * 2) {
                list.remove(0);
            }
            if ((value & 0x3F) == 0) {
                list.set(0, value);
            }
        }
    }

    private void runFailFastReader() {
        while (!stop.get()) {
            Iterator<Integer> it = list.iterator(IterationMode.FAIL_FAST);
            try {
                while (it.hasNext()) {
                    it.next();
                }
                failFastCompleted.incrementAndGet();
            } catch (java.util.ConcurrentModificationException expected) {
                // 快速失败语义：允许抛 CME，且消息必须带版本信息。
                if (!expected.getMessage().contains("expected version")) {
                    failures.add(new AssertionError("CME 缺少版本信息: " + expected.getMessage()));
                }
                failFastCmeCount.incrementAndGet();
            } catch (Throwable t) {
                failures.add(t);
                return;
            }
        }
    }

    private void runSnapshotReader() {
        while (!stop.get()) {
            try {
                Iterator<Integer> it = list.iterator(IterationMode.SNAPSHOT);
                Integer previous = null;
                while (it.hasNext()) {
                    Integer current = it.next();
                    // 快照语义：一次迭代内元素流稳定，绝不抛 CME。
                    previous = current;
                }
                snapshotIterations.incrementAndGet();
            } catch (Throwable t) {
                failures.add(t);
                return;
            }
        }
    }

    private void runWeakReader() {
        while (!stop.get()) {
            try {
                Iterator<Integer> it = list.iterator(IterationMode.WEAKLY_CONSISTENT);
                List<Integer> seen = new ArrayList<>();
                while (it.hasNext()) {
                    seen.add(it.next());
                }
                // 弱一致语义：不抛异常、不重复、不死循环（由 awaitTermination 保证）。
                Set<Integer> unique = new HashSet<>(seen);
                if (unique.size() != seen.size()) {
                    failures.add(new AssertionError("弱一致迭代出现重复元素: " + seen));
                    return;
                }
                weakIterations.incrementAndGet();
            } catch (Throwable t) {
                failures.add(t);
                return;
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
