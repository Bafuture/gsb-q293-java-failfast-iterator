package com.example.gsb.iterator;

import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * 快速失败迭代器：创建时记录期望版本号，每次访问都校验。
 * 一旦发现集合被结构性修改，立即抛出 {@link ConcurrentModificationException}，
 * 异常信息中包含期望版本与实际版本。
 */
final class FailFastIterator<E> implements Iterator<E> {

    private final VersionedList<E> list;
    private final IterationStats stats;
    private final long expectedModCount;
    private int cursor;
    private boolean terminated;

    FailFastIterator(VersionedList<E> list, IterationStats stats) {
        this.list = list;
        this.stats = stats;
        this.expectedModCount = list.modCount();
    }

    @Override
    public boolean hasNext() {
        checkForComodification();
        if (cursor < list.size()) {
            return true;
        }
        // 双重校验：版本校验与 size() 读取之间可能发生并发修改，
        // 只有复核版本仍未变，才能确认是真的迭代到了末尾。
        checkForComodification();
        terminateNaturally();
        return false;
    }

    @Override
    public E next() {
        checkForComodification();
        if (cursor >= list.size()) {
            // 与 hasNext() 相同的双重校验，避免把并发删除误判为迭代结束。
            checkForComodification();
            terminateNaturally();
            throw new NoSuchElementException();
        }
        E element;
        try {
            element = list.get(cursor);
        } catch (IndexOutOfBoundsException e) {
            // size() 与 get() 之间元素被其它线程删除，同样属于结构性并发修改。
            abort();
            throw new ConcurrentModificationException(
                    "Concurrent modification detected: expected version "
                            + expectedModCount + " but found version "
                            + list.modCount() + " (element vanished between checks)", e);
        }
        cursor++;
        return element;
    }

    private void checkForComodification() {
        long actual = list.modCount();
        if (actual != expectedModCount) {
            abort();
            throw new ConcurrentModificationException(
                    "Concurrent modification detected: expected version "
                            + expectedModCount + " but found version " + actual);
        }
    }

    private void terminateNaturally() {
        if (!terminated) {
            terminated = true;
            stats.recordCompletion();
        }
    }

    private void abort() {
        if (!terminated) {
            terminated = true;
            stats.recordConcurrentModification();
            stats.recordAbort();
        }
    }
}
