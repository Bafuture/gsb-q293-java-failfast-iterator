package com.example.gsb.iterator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IterationStatsTest {

    @Test
    void countsStartedPerModeAndCompleted() {
        VersionedCollection<Integer> collection = seeded();

        for (IterationMode mode : IterationMode.values()) {
            VersionedIterator<Integer> it = collection.iterator(mode);
            while (it.hasNext()) {
                it.next();
            }
        }

        IterationStats stats = collection.stats();
        assertThat(stats.getTotalIterationsStarted()).isEqualTo(3);
        for (IterationMode mode : IterationMode.values()) {
            assertThat(stats.getIterationsStarted(mode)).isEqualTo(1);
        }
        assertThat(stats.getCompletedIterations()).isEqualTo(3);
        assertThat(stats.getConcurrentModificationsDetected()).isZero();
        assertThat(stats.getAbandonedIterations()).isZero();
    }

    @Test
    void countsDetectedModificationsAndAbandons() {
        VersionedCollection<Integer> collection = seeded();

        for (int i = 0; i < 5; i++) {
            VersionedIterator<Integer> it = collection.iterator(IterationMode.FAIL_FAST);
            it.next();
            collection.add(100 + i);
            try {
                while (it.hasNext()) {
                    it.next();
                }
            } catch (ConcurrentModificationDetectedException expected) {
                // 预期：每次迭代都会检测到修改
            }
        }

        VersionedIterator<Integer> givenUp = collection.iterator(IterationMode.SNAPSHOT);
        givenUp.next();
        givenUp.abandon();

        IterationStats stats = collection.stats();
        assertThat(stats.getConcurrentModificationsDetected()).isEqualTo(5);
        assertThat(stats.getAbandonedIterations()).isEqualTo(6);
        assertThat(stats.getCompletedIterations()).isZero();
        assertThat(stats.getIterationsStarted(IterationMode.FAIL_FAST)).isEqualTo(5);
        assertThat(stats.getIterationsStarted(IterationMode.SNAPSHOT)).isEqualTo(1);
    }

    @Test
    void globalInvariantStartedEqualsCompletedPlusAbandoned() {
        VersionedCollection<Integer> collection = seeded();

        for (int i = 0; i < 3; i++) {
            drain(collection.iterator(IterationMode.WEAKLY_CONSISTENT));
        }
        collection.iterator(IterationMode.SNAPSHOT).abandon();

        VersionedIterator<Integer> it = collection.iterator(IterationMode.FAIL_FAST);
        collection.add(999);
        try {
            drain(it);
        } catch (ConcurrentModificationDetectedException expected) {
            // 预期
        }

        IterationStats stats = collection.stats();
        assertThat(stats.getTotalIterationsStarted())
                .isEqualTo(stats.getCompletedIterations() + stats.getAbandonedIterations());
        assertThat(stats.toString()).contains("concurrentModificationsDetected=1");
    }

    private static void drain(VersionedIterator<Integer> it) {
        while (it.hasNext()) {
            it.next();
        }
    }

    private static VersionedCollection<Integer> seeded() {
        VersionedCollection<Integer> collection = new VersionedCollection<>();
        for (int i = 0; i < 10; i++) {
            collection.add(i);
        }
        return collection;
    }
}
