package com.example.gsb.iterator;

import java.util.Iterator;

/**
 * 带并发修改检测能力的迭代器。由 {@link VersionedCollection#iterator(IterationMode)}
 * 创建，行为取决于创建时选择的模式，见 {@link IterationMode}。
 *
 * <p>所有实现都不支持 {@link #remove()}，调用会抛出
 * {@link UnsupportedOperationException}。
 */
public interface VersionedIterator<E> extends Iterator<E> {

    /** 本迭代器使用的模式。 */
    IterationMode mode();

    /**
     * 主动放弃本次迭代。放弃后迭代器立即失效：{@code hasNext()} 返回
     * {@code false}，{@code next()} 抛出 {@link java.util.NoSuchElementException}。
     * 被放弃的迭代会计入 {@link IterationStats#getAbandonedIterations()}。
     */
    void abandon();

    /** 本次迭代是否已被放弃。 */
    boolean isAbandoned();
}
