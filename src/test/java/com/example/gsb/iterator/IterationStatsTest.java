package com.example.gsb.iterator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

class IterationStatsTest {

    @Test
    void countsStartedAndCompletedIterations() {
        VersionedList<Integer> list = new VersionedList<>(List.of(1, 2, 3));

        list.iterator(IterationMode.FAIL_FAST).forEachRemaining(x -> { });
        list.iterator(IterationMode.SNAPSHOT).forEachRemaining(x -> { });
        list.iterator(IterationMode.WEAKLY_CONSISTENT).forEachRemaining(x -> { });

        IterationStats stats = list.stats();
        assertThat(stats.getIterationsStarted()).isEqualTo(3);
        assertThat(stats.getIterationsCompleted()).isEqualTo(3);
        assertThat(stats.getConcurrentModificationsDetected()).isZero();
        assertThat(stats.getIterationsAborted()).isZero();
    }

    @Test
    void countsDetectedConcurrentModificationsAndAborts() {
        VersionedList<Integer> list = new VersionedList<>(List.of(1, 2, 3));

        for (int i = 0; i < 3; i++) {
            Iterator<Integer> it = list.iterator(IterationMode.FAIL_FAST);
            it.next();
            list.add(99);
            try {
                it.next();
            } catch (ConcurrentModificationException expected) {
                // 快速失败语义内的预期异常。
            }
        }

        IterationStats stats = list.stats();
        assertThat(stats.getIterationsStarted()).isEqualTo(3);
        assertThat(stats.getConcurrentModificationsDetected()).isEqualTo(3);
        assertThat(stats.getIterationsAborted()).isEqualTo(3);
        assertThat(stats.getIterationsCompleted()).isZero();
    }

    @Test
    void snapshotAndWeakIterationsAreNotCountedAsConflicts() {
        VersionedList<Integer> list = new VersionedList<>(List.of(1, 2, 3));

        Iterator<Integer> snapshot = list.iterator(IterationMode.SNAPSHOT);
        list.add(4);
        snapshot.forEachRemaining(x -> { });

        Iterator<Integer> weak = list.iterator(IterationMode.WEAKLY_CONSISTENT);
        list.add(5);
        weak.forEachRemaining(x -> { });

        IterationStats stats = list.stats();
        assertThat(stats.getConcurrentModificationsDetected()).isZero();
        assertThat(stats.getIterationsCompleted()).isEqualTo(2);
    }

    @Test
    void statsAreSharedAcrossIteratorsOfSameList() {
        VersionedList<Integer> list = new VersionedList<>(List.of(1));
        list.iterator(IterationMode.SNAPSHOT).forEachRemaining(x -> { });
        list.iterator(IterationMode.SNAPSHOT).forEachRemaining(x -> { });

        assertThat(list.stats().getIterationsStarted()).isEqualTo(2);
        assertThat(list.stats().getIterationsCompleted()).isEqualTo(2);
    }
}
