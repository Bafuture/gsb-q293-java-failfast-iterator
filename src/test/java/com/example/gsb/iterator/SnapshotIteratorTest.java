package com.example.gsb.iterator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotIteratorTest {

    @Test
    void laterAddsAreInvisible() {
        VersionedCollection<Integer> collection = new VersionedCollection<>();
        collection.add(1);
        collection.add(2);

        VersionedIterator<Integer> it = collection.iterator(IterationMode.SNAPSHOT);
        collection.add(3);
        collection.add(4);

        List<Integer> seen = new ArrayList<>();
        while (it.hasNext()) {
            seen.add(it.next());
        }

        assertThat(seen).containsExactly(1, 2);
        assertThat(it.mode()).isEqualTo(IterationMode.SNAPSHOT);
    }

    @Test
    void laterRemovesAndClearAreInvisible() {
        VersionedCollection<Integer> collection = new VersionedCollection<>();
        for (int i = 1; i <= 5; i++) {
            collection.add(i);
        }

        VersionedIterator<Integer> it = collection.iterator(IterationMode.SNAPSHOT);
        assertThat(it.next()).isEqualTo(1);

        collection.remove(3);
        collection.clear();

        List<Integer> seen = new ArrayList<>();
        seen.add(1);
        while (it.hasNext()) {
            seen.add(it.next());
        }

        assertThat(seen).containsExactly(1, 2, 3, 4, 5);
        assertThat(collection.isEmpty()).isTrue();
    }

    @Test
    void neverThrowsUnderConcurrentModification() {
        VersionedCollection<Integer> collection = new VersionedCollection<>();
        for (int i = 0; i < 100; i++) {
            collection.add(i);
        }

        VersionedIterator<Integer> it = collection.iterator(IterationMode.SNAPSHOT);
        for (int j = 0; j < 100; j++) {
            collection.add(1000 + j);
            collection.remove(j);
        }
        collection.clear();

        List<Integer> seen = new ArrayList<>();
        while (it.hasNext()) {
            seen.add(it.next());
        }

        assertThat(seen).hasSize(100);
        assertThat(seen).doesNotHaveDuplicates();
        assertThat(seen).containsExactlyElementsOf(range(0, 100));
        assertThat(collection.stats().getConcurrentModificationsDetected()).isZero();
        assertThat(collection.stats().getCompletedIterations()).isEqualTo(1);
    }

    @Test
    void multipleSnapshotsAreIndependent() {
        VersionedCollection<Integer> collection = new VersionedCollection<>();
        collection.add(1);

        VersionedIterator<Integer> first = collection.iterator(IterationMode.SNAPSHOT);
        collection.add(2);
        VersionedIterator<Integer> second = collection.iterator(IterationMode.SNAPSHOT);

        assertThat(drain(first)).containsExactly(1);
        assertThat(drain(second)).containsExactly(1, 2);
    }

    private static List<Integer> range(int fromInclusive, int toExclusive) {
        List<Integer> list = new ArrayList<>();
        for (int i = fromInclusive; i < toExclusive; i++) {
            list.add(i);
        }
        return list;
    }

    private static List<Integer> drain(VersionedIterator<Integer> it) {
        List<Integer> seen = new ArrayList<>();
        while (it.hasNext()) {
            seen.add(it.next());
        }
        return seen;
    }
}
