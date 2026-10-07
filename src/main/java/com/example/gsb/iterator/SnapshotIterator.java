package com.example.gsb.iterator;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 快照迭代器：遍历创建迭代器时拷贝出来的集合副本。
 * 原集合之后的任何修改对本次迭代都不可见，也绝不会抛出并发修改异常。
 */
final class SnapshotIterator<E> implements Iterator<E> {

    private final List<E> snapshot;
    private final IterationStats stats;
    private int cursor;
    private boolean terminated;

    SnapshotIterator(List<E> snapshot, IterationStats stats) {
        this.snapshot = snapshot;
        this.stats = stats;
    }

    @Override
    public boolean hasNext() {
        if (cursor < snapshot.size()) {
            return true;
        }
        terminateNaturally();
        return false;
    }

    @Override
    public E next() {
        if (cursor >= snapshot.size()) {
            terminateNaturally();
            throw new NoSuchElementException();
        }
        return snapshot.get(cursor++);
    }

    private void terminateNaturally() {
        if (!terminated) {
            terminated = true;
            stats.recordCompletion();
        }
    }
}
