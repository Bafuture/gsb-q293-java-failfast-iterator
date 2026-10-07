package com.example.gsb.iterator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * 带版本号的线程安全列表，是所有迭代器模式的宿主集合。
 *
 * <p>每次结构性修改（add / remove / set / clear）都会把版本号 {@code modCount} 加一。
 * 迭代器通过比较版本号检测并发修改。所有读写都在内部锁上进行，
 * 因此多线程下不会出现数据竞争导致的损坏。</p>
 *
 * @param <E> 元素类型
 */
public class VersionedList<E> {

    private final Object lock = new Object();
    private final List<E> delegate;
    private final IterationStats stats = new IterationStats();

    /** 结构性修改计数。用 volatile 保证迭代线程无需加锁即可读到最新值。 */
    private volatile long modCount;

    public VersionedList() {
        this.delegate = new ArrayList<>();
    }

    public VersionedList(Collection<? extends E> initial) {
        this.delegate = new ArrayList<>(initial);
    }

    // ------------------------------------------------------------------
    // 修改操作（结构性修改，递增版本号）
    // ------------------------------------------------------------------

    public void add(E element) {
        synchronized (lock) {
            delegate.add(element);
            modCount++;
        }
    }

    public void add(int index, E element) {
        synchronized (lock) {
            delegate.add(index, element);
            modCount++;
        }
    }

    public boolean remove(Object element) {
        synchronized (lock) {
            boolean removed = delegate.remove(element);
            if (removed) {
                modCount++;
            }
            return removed;
        }
    }

    public E remove(int index) {
        synchronized (lock) {
            E removed = delegate.remove(index);
            modCount++;
            return removed;
        }
    }

    public E set(int index, E element) {
        synchronized (lock) {
            E previous = delegate.set(index, element);
            modCount++;
            return previous;
        }
    }

    public void clear() {
        synchronized (lock) {
            if (!delegate.isEmpty()) {
                delegate.clear();
                modCount++;
            }
        }
    }

    // ------------------------------------------------------------------
    // 读取操作
    // ------------------------------------------------------------------

    public E get(int index) {
        synchronized (lock) {
            return delegate.get(index);
        }
    }

    public int size() {
        synchronized (lock) {
            return delegate.size();
        }
    }

    /** 当前版本号，每次结构性修改加一。 */
    public long modCount() {
        return modCount;
    }

    /** 在锁内拷贝当前内容，供快照迭代器使用。 */
    List<E> copyContents() {
        synchronized (lock) {
            return new ArrayList<>(delegate);
        }
    }

    // ------------------------------------------------------------------
    // 迭代器工厂
    // ------------------------------------------------------------------

    /** 默认使用快速失败模式。 */
    public Iterator<E> iterator() {
        return iterator(IterationMode.FAIL_FAST);
    }

    /** 由调用方选择一致性模式。 */
    public Iterator<E> iterator(IterationMode mode) {
        stats.recordStart();
        return switch (mode) {
            case FAIL_FAST -> new FailFastIterator<>(this, stats);
            case SNAPSHOT -> new SnapshotIterator<>(copyContents(), stats);
            case WEAKLY_CONSISTENT -> new WeaklyConsistentIterator<>(this, stats);
        };
    }

    public IterationStats stats() {
        return stats;
    }
}
