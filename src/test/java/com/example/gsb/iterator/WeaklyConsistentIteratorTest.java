package com.example.gsb.iterator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class WeaklyConsistentIteratorTest {

    @Test
    void iteratesAllElementsWhenUnmodified() {
        VersionedCollection<Integer> collection = new VersionedCollection<>();
        for (int i = 0; i < 10; i++) {
            collection.add(i);
        }

        List<Integer> seen = drain(collection.iterator(IterationMode.WEAKLY_CONSISTENT));

        assertThat(seen).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    @Test
    void maySeeElementsAppendedDuringIteration() {
        VersionedCollection<Integer> collection = new VersionedCollection<>();
        collection.add(1);
        VersionedIterator<Integer> it = collection.iterator(IterationMode.WEAKLY_CONSISTENT);

        assertThat(it.next()).isEqualTo(1);
        collection.add(2);

        assertThat(it.hasNext()).isTrue();
        assertThat(it.next()).isEqualTo(2);
        assertThat(it.hasNext()).isFalse();
    }

    @Test
    void skipsElementsRemovedDuringIteration() {
        VersionedCollection<Integer> collection = new VersionedCollection<>();
        for (int i = 1; i <= 5; i++) {
            collection.add(i);
        }
        VersionedIterator<Integer> it = collection.iterator(IterationMode.WEAKLY_CONSISTENT);

        assertThat(it.next()).isEqualTo(1);
        collection.remove(2);
        collection.remove(3);

        List<Integer> rest = new ArrayList<>();
        while (it.hasNext()) {
            rest.add(it.next());
        }
        assertThat(rest).containsExactly(4, 5);
    }

    @Test
    void neverThrowsAndNeverRepeatsUnderConcurrentAddsAndRemoves() {
        VersionedCollection<Integer> collection = new VersionedCollection<>();
        for (int i = 0; i < 1000; i++) {
            collection.add(i);
        }
        VersionedIterator<Integer> it = collection.iterator(IterationMode.WEAKLY_CONSISTENT);

        List<Integer> seen = new ArrayList<>();
        while (it.hasNext()) {
            int value = it.next();
            seen.add(value);
            if (value < 1000) {
                collection.add(1000 + value);   // 仅对原始元素追加一次，保证修改预算有限
                collection.remove(value);       // 删除刚读到的元素
            }
        }

        assertThat(seen).hasSize(2000);

        assertThat(seen).doesNotHaveDuplicates();
        assertThat(collection.stats().getConcurrentModificationsDetected()).isZero();
    }

    /**
     * 死循环防护证明：写线程以固定预算持续增删，迭代必须在有限步内结束，
     * 且每个元素最多出现一次。由外层 10 秒超时兜底——一旦死循环测试即失败。
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void terminatesWithoutDuplicatesWhileWriterMutates() throws InterruptedException {
        VersionedCollection<Integer> collection = new VersionedCollection<>();
        for (int i = 0; i < 100; i++) {
            collection.add(i);
        }

        Thread writer = new Thread(() -> {
            for (int i = 0; i < 20_000; i++) {
                collection.add(100 + i);
                collection.remove(i % 100);
            }
        });
        writer.start();

        List<Integer> seen = drain(collection.iterator(IterationMode.WEAKLY_CONSISTENT));
        writer.join();

        Set<Integer> unique = new HashSet<>(seen);
        assertThat(unique).hasSameSizeAs(seen);
        assertThat(seen.size()).isLessThanOrEqualTo(100 + 20_000);
    }

    @Test
    void abandonedIteratorStopsImmediately() {
        VersionedCollection<Integer> collection = new VersionedCollection<>();
        collection.add(1);
        collection.add(2);
        VersionedIterator<Integer> it = collection.iterator(IterationMode.WEAKLY_CONSISTENT);

        it.abandon();

        assertThat(it.hasNext()).isFalse();
        assertThat(collection.stats().getAbandonedIterations()).isEqualTo(1);
    }

    private static List<Integer> drain(VersionedIterator<Integer> it) {
        List<Integer> seen = new ArrayList<>();
        while (it.hasNext()) {
            seen.add(it.next());
        }
        return seen;
    }
}
