package com.example.gsb.iterator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class WeaklyConsistentIteratorTest {

    @Test
    void iteratesAllElementsWhenUnmodified() {
        VersionedList<Integer> list = new VersionedList<>(List.of(1, 2, 3, 4, 5));

        List<Integer> seen = drain(list.iterator(IterationMode.WEAKLY_CONSISTENT));

        assertThat(seen).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void insertionsAtFrontDoNotCauseDuplicates() {
        VersionedList<Integer> list = new VersionedList<>(new ArrayList<>(List.of(10, 20, 30)));
        Iterator<Integer> it = list.iterator(IterationMode.WEAKLY_CONSISTENT);
        assertThat(it.next()).isEqualTo(10);

        // 在头部插入会让后续元素整体右移，朴素的下标迭代会重新读到 10。
        list.add(0, 10);
        list.add(0, 5);

        List<Integer> seen = drain(it);
        assertThat(seen).doesNotContain(10);
        assertThat(seen).contains(20, 30);
        assertNoDuplicates(seen);
    }

    @Test
    void removalsAtFrontDoNotCauseDuplicatesAndTerminate() {
        VersionedList<Integer> list = new VersionedList<>(new ArrayList<>(List.of(1, 2, 3, 4)));
        Iterator<Integer> it = list.iterator(IterationMode.WEAKLY_CONSISTENT);
        assertThat(it.next()).isEqualTo(1);

        list.remove(0);
        list.remove(0);

        List<Integer> seen = drain(it);
        assertNoDuplicates(seen);
        assertThat(seen).isSubsetOf(2, 3, 4);
    }

    @Test
    void neverThrowsUnderRapidChurnAndAlwaysTerminates() throws InterruptedException {
        VersionedList<Integer> list = new VersionedList<>();
        for (int i = 0; i < 64; i++) {
            list.add(i);
        }
        AtomicBoolean stop = new AtomicBoolean(false);

        // 故意使用最容易造成元素平移、朴素下标迭代会重复/死循环的头部增删。
        Thread writer = new Thread(() -> {
            int value = 0;
            for (int i = 0; i < 50_000 && !stop.get(); i++) {
                list.add(0, value++);
                if (list.size() > 128) {
                    list.remove(0);
                }
            }
            stop.set(true);
        }, "hostile-writer");

        List<Integer> seen;
        try {
            writer.start();
            // 关键断言：在持续头部增删下迭代必须在有限时间内结束（不死循环）。
            seen = assertFinishes(list);
        } finally {
            stop.set(true);
        }

        assertNoDuplicates(seen);
    }

    private static List<Integer> drain(Iterator<Integer> it) {
        List<Integer> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }

    private static List<Integer> assertFinishes(VersionedList<Integer> list)
            throws InterruptedException {
        List<Integer> result = new ArrayList<>();
        AtomicBoolean done = new AtomicBoolean(false);
        Thread reader = new Thread(() -> {
            Iterator<Integer> it = list.iterator(IterationMode.WEAKLY_CONSISTENT);
            it.forEachRemaining(result::add);
            done.set(true);
        }, "weak-reader");
        reader.start();
        reader.join(10_000);
        assertThat(reader.isAlive())
                .as("弱一致迭代器在写线程持续修改时必须终止，不得死循环")
                .isFalse();
        assertThat(done.get()).isTrue();
        return result;
    }

    private static void assertNoDuplicates(List<Integer> seen) {
        Set<Integer> unique = new HashSet<>(seen);
        assertThat(unique).as("弱一致迭代返回的元素不得重复: %s", seen)
                .hasSize(seen.size());
    }

    @Test
    @Timeout(20)
    void repeatedIterationsUnderContinuousWritesAllFinish() {
        VersionedList<Integer> list = new VersionedList<>();
        for (int i = 0; i < 32; i++) {
            list.add(i);
        }
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread writer = new Thread(() -> {
            int v = 0;
            while (!stop.get()) {
                list.add(0, v++);
                if (list.size() > 64) {
                    list.remove(0);
                }
            }
        }, "continuous-writer");
        writer.start();
        try {
            for (int round = 0; round < 200; round++) {
                List<Integer> seen = drain(list.iterator(IterationMode.WEAKLY_CONSISTENT));
                assertNoDuplicates(seen);
            }
        } finally {
            stop.set(true);
        }
    }
}
