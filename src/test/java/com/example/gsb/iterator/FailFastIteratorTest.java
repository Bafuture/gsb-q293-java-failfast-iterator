package com.example.gsb.iterator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class FailFastIteratorTest {

    @Test
    void iteratesAllElementsWhenUnmodified() {
        VersionedList<String> list = new VersionedList<>(List.of("a", "b", "c"));
        Iterator<String> it = list.iterator(IterationMode.FAIL_FAST);

        assertThat(it.next()).isEqualTo("a");
        assertThat(it.next()).isEqualTo("b");
        assertThat(it.next()).isEqualTo("c");
        assertThat(it.hasNext()).isFalse();
    }

    @Test
    void throwsImmediatelyOnAddDuringIteration() {
        VersionedList<Integer> list = new VersionedList<>(List.of(1, 2, 3));
        Iterator<Integer> it = list.iterator(IterationMode.FAIL_FAST);
        it.next();

        list.add(4);

        assertThatThrownBy(it::next)
                .isInstanceOf(ConcurrentModificationException.class)
                .hasMessageContaining("expected version 0")
                .hasMessageContaining("found version 1");
    }

    @Test
    void throwsImmediatelyOnRemoveDuringIteration() {
        VersionedList<Integer> list = new VersionedList<>(List.of(1, 2, 3));
        Iterator<Integer> it = list.iterator(IterationMode.FAIL_FAST);
        it.next();

        list.remove(Integer.valueOf(2));

        assertThatThrownBy(it::hasNext)
                .isInstanceOf(ConcurrentModificationException.class)
                .hasMessageContaining("expected version 0")
                .hasMessageContaining("found version 1");
    }

    @Test
    void throwsImmediatelyOnSetDuringIteration() {
        VersionedList<Integer> list = new VersionedList<>(List.of(1, 2, 3));
        Iterator<Integer> it = list.iterator(IterationMode.FAIL_FAST);
        it.next();

        list.set(0, 99);

        assertThatThrownBy(it::next)
                .isInstanceOf(ConcurrentModificationException.class)
                .hasMessageContaining("expected version 0")
                .hasMessageContaining("found version 1");
    }

    @Test
    void exceptionMessageReportsExpectedAndActualVersions() {
        VersionedList<Integer> list = new VersionedList<>(List.of(1, 2, 3));
        Iterator<Integer> it = list.iterator(IterationMode.FAIL_FAST);
        list.add(4);
        list.add(5);
        list.remove(Integer.valueOf(1));

        assertThatThrownBy(it::next)
                .isInstanceOf(ConcurrentModificationException.class)
                .hasMessageContaining("expected version 0")
                .hasMessageContaining("found version 3");
    }

    @Test
    void iteratorCreatedAfterModificationUsesNewVersion() {
        VersionedList<Integer> list = new VersionedList<>(List.of(1, 2, 3));
        list.add(4);
        Iterator<Integer> it = list.iterator(IterationMode.FAIL_FAST);

        assertThat(it).toIterable().containsExactly(1, 2, 3, 4);
    }

    @Test
    void nextPastEndThrowsNoSuchElement() {
        VersionedList<Integer> list = new VersionedList<>(List.of(1));
        Iterator<Integer> it = list.iterator(IterationMode.FAIL_FAST);
        it.next();

        assertThatThrownBy(it::next).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void defaultIteratorIsFailFast() {
        VersionedList<Integer> list = new VersionedList<>(List.of(1, 2));
        Iterator<Integer> it = list.iterator();
        list.add(3);

        assertThatThrownBy(it::next).isInstanceOf(ConcurrentModificationException.class);
    }
}
