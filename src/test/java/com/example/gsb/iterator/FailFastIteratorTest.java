package com.example.gsb.iterator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class FailFastIteratorTest {

    private VersionedCollection<String> newCollection(String... elements) {
        VersionedCollection<String> collection = new VersionedCollection<>();
        for (String e : elements) {
            collection.add(e);
        }
        return collection;
    }

    @Test
    void iteratesAllElementsWhenUnmodified() {
        VersionedCollection<String> collection = newCollection("a", "b", "c");
        VersionedIterator<String> it = collection.iterator(IterationMode.FAIL_FAST);

        List<String> seen = new ArrayList<>();
        while (it.hasNext()) {
            seen.add(it.next());
        }

        assertThat(seen).containsExactly("a", "b", "c");
        assertThat(it.mode()).isEqualTo(IterationMode.FAIL_FAST);
        assertThat(collection.stats().getCompletedIterations()).isEqualTo(1);
        assertThat(collection.stats().getConcurrentModificationsDetected()).isZero();
    }

    @Test
    void throwsImmediatelyWhenElementAddedDuringIteration() {
        VersionedCollection<String> collection = newCollection("a", "b", "c");
        VersionedIterator<String> it = collection.iterator(IterationMode.FAIL_FAST);
        long expectedVersion = collection.version();

        assertThat(it.next()).isEqualTo("a");
        collection.add("d");

        assertThatThrownBy(it::next)
                .isInstanceOf(ConcurrentModificationDetectedException.class)
                .isInstanceOf(java.util.ConcurrentModificationException.class)
                .hasMessageContaining("expected version " + expectedVersion)
                .hasMessageContaining("actual version " + (expectedVersion + 1));
    }

    @Test
    void exceptionCarriesExpectedAndActualVersion() {
        VersionedCollection<String> collection = newCollection("a", "b");
        VersionedIterator<String> it = collection.iterator(IterationMode.FAIL_FAST);
        long expectedVersion = collection.version();

        collection.add("c");
        collection.remove("c");
        collection.clear();

        assertThatThrownBy(it::hasNext)
                .isInstanceOfSatisfying(ConcurrentModificationDetectedException.class, e -> {
                    assertThat(e.getExpectedVersion()).isEqualTo(expectedVersion);
                    assertThat(e.getActualVersion()).isEqualTo(expectedVersion + 3);
                });
    }

    @Test
    void throwsWhenElementRemovedDuringIteration() {
        VersionedCollection<String> collection = newCollection("a", "b", "c");
        VersionedIterator<String> it = collection.iterator(IterationMode.FAIL_FAST);

        assertThat(it.next()).isEqualTo("a");
        collection.remove("b");

        assertThatThrownBy(it::hasNext)
                .isInstanceOf(ConcurrentModificationDetectedException.class);
    }

    @Test
    void throwsWhenClearedDuringIteration() {
        VersionedCollection<String> collection = newCollection("a", "b");
        VersionedIterator<String> it = collection.iterator(IterationMode.FAIL_FAST);

        collection.clear();

        assertThatThrownBy(it::next)
                .isInstanceOf(ConcurrentModificationDetectedException.class);
    }

    @Test
    void detectedModificationIsCountedAsDetectedAndAbandoned() {
        VersionedCollection<String> collection = newCollection("a", "b");
        VersionedIterator<String> it = collection.iterator(IterationMode.FAIL_FAST);

        collection.add("c");
        assertThatThrownBy(it::next)
                .isInstanceOf(ConcurrentModificationDetectedException.class);

        assertThat(collection.stats().getConcurrentModificationsDetected()).isEqualTo(1);
        assertThat(collection.stats().getAbandonedIterations()).isEqualTo(1);
        assertThat(collection.stats().getCompletedIterations()).isZero();
        assertThat(it.isAbandoned()).isTrue();
    }

    @Test
    void modificationBeforeFirstNextIsStillDetected() {
        VersionedCollection<String> collection = newCollection("a");
        VersionedIterator<String> it = collection.iterator(IterationMode.FAIL_FAST);

        collection.add("b");

        assertThatThrownBy(it::next)
                .isInstanceOf(ConcurrentModificationDetectedException.class);
    }

    @Test
    void nextOnExhaustedIteratorThrowsNoSuchElement() {
        VersionedCollection<String> collection = newCollection("a");
        VersionedIterator<String> it = collection.iterator(IterationMode.FAIL_FAST);

        it.next();
        assertThat(it.hasNext()).isFalse();
        assertThatThrownBy(it::next).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void abandonedIteratorStopsImmediately() {
        VersionedCollection<String> collection = newCollection("a", "b");
        VersionedIterator<String> it = collection.iterator(IterationMode.FAIL_FAST);

        it.abandon();

        assertThat(it.isAbandoned()).isTrue();
        assertThat(it.hasNext()).isFalse();
        assertThatThrownBy(it::next).isInstanceOf(NoSuchElementException.class);
        assertThat(collection.stats().getAbandonedIterations()).isEqualTo(1);
    }
}
