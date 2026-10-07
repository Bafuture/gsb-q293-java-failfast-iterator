package com.example.gsb.iterator;

import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 弱一致迭代器：直接遍历实时集合，可能看到迭代期间发生的部分修改，
 * 但提供三条硬保证：
 *
 * <ul>
 *   <li>绝不抛出 {@link java.util.ConcurrentModificationException}；</li>
 *   <li>不会重复返回同一个元素（按 {@code equals} 判重，重复者被跳过）；</li>
 *   <li>不会死循环——步进预算随结构性修改次数有界增长，
 *       修改停止后迭代必然在有限步内结束。</li>
 * </ul>
 *
 * <p>实现要点：按下标前进；用集合记录已返回元素并跳过重复；
 * 步进预算 = 创建时大小 + 创建以来发生的结构性修改次数 + 余量，
 * 每次探测消耗一步。预算耗尽时迭代提前结束并计入“被放弃的迭代”。</p>
 */
final class WeaklyConsistentIterator<E> implements Iterator<E> {

    /** 预算余量，避免边界情况下过早截断。 */
    private static final long BUDGET_SLACK = 16;

    private final VersionedList<E> list;
    private final IterationStats stats;
    private final long startModCount;
    private final int initialSize;
    private final Set<E> returned = new HashSet<>();

    private int cursor;
    private long steps;
    private E nextElement;
    private boolean nextReady;
    private boolean terminated;

    WeaklyConsistentIterator(VersionedList<E> list, IterationStats stats) {
        this.list = list;
        this.stats = stats;
        this.startModCount = list.modCount();
        this.initialSize = list.size();
    }

    @Override
    public boolean hasNext() {
        if (terminated) {
            return false;
        }
        if (nextReady) {
            return true;
        }
        return advance();
    }

    @Override
    public E next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        E element = nextElement;
        nextElement = null;
        nextReady = false;
        return element;
    }

    /**
     * 向前探测下一个未返回过的元素。返回 false 表示迭代结束
     * （自然到达末尾，或步进预算耗尽被放弃）。
     */
    private boolean advance() {
        while (true) {
            if (steps >= budget()) {
                abort();
                return false;
            }
            int size = list.size();
            if (cursor >= size) {
                terminateNaturally();
                return false;
            }
            E candidate;
            try {
                candidate = list.get(cursor);
            } catch (IndexOutOfBoundsException e) {
                // size() 与 get() 之间集合被缩小了，重试即可。
                continue;
            }
            cursor++;
            steps++;
            if (returned.add(candidate)) {
                nextElement = candidate;
                nextReady = true;
                return true;
            }
            // 重复元素：跳过，继续探测。
        }
    }

    private long budget() {
        long modifications = list.modCount() - startModCount;
        return initialSize + modifications + BUDGET_SLACK;
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
            stats.recordAbort();
        }
    }
}
