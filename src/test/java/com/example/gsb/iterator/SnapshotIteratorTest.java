package com.example.gsb.iterator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

class SnapshotIteratorTest {

    @Test
    void iteratesAllElementsWhenUnmodified() {
        VersionedList<String> list = new VersionedList<>(List.of("a", "b", "c"));

        assertThat(list.iterator(IterationMode.SNAPSHOT))
                .toIterable().containsExactly("a", "b", "c");
    }

    @Test
    void additionsAfterCreationAreInvisible() {
        VersionedList<Integer> list = new VersionedList<>(List.of(1, 2, 3));
        Iterator<Integer> it = list.iterator(IterationMode.SNAPSHOT);

        list.add(4);
        list.add(5);

        List<Integer> seen = new ArrayList<>();
        it.forEachRemaining(seen::add);
        assertThat(seen).containsExactly(1, 2, 3);
    }

    @Test
    void removalsAfterCreationAreInvisible() {
        VersionedList<Integer> list = new VersionedList<>(List.of(1, 2, 3));
        Iterator<Integer> it = list.iterator(IterationMode.SNAPSHOT);
        it.next();

        list.remove(Integer.valueOf(2));
        list.clear();

        List<Integer> seen = new ArrayList<>();
        it.forEachRemaining(seen::add);
        assertThat(seen).containsExactly(2, 3);
    }

    @Test
    void neverThrowsConcurrentModification() {
        VersionedList<Integer> list = new VersionedList<>(List.of(1, 2, 3));
        Iterator<Integer> it = list.iterator(IterationMode.SNAPSHOT);

        for (int i = 0; i < 100; i++) {
            list.add(i);
            list.set(0, i);
            if (!list.remove(Integer.valueOf(i))) {
                list.remove(0);
            }
        }

        List<Integer> seen = new ArrayList<>();
        it.forEachRemaining(seen::add);
        assertThat(seen).containsExactly(1, 2, 3);
    }

    @Test
    void snapshotReflectsStateAtCreationTime() {
        VersionedList<Integer> list = new VersionedList<>(List.of(1, 2));
        list.add(3);
        Iterator<Integer> it = list.iterator(IterationMode.SNAPSHOT);
        list.add(4);

        assertThat(it).toIterable().containsExactly(1, 2, 3);
    }
}
