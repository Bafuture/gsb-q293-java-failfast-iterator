package com.example.gsb.iterator;

/**
 * 迭代器的一致性模式，由调用方在创建迭代器时选择。
 */
public enum IterationMode {

    /**
     * 快速失败：迭代期间集合被结构性修改时立即抛出
     * {@link java.util.ConcurrentModificationException}。
     * 适用于单线程或能容忍重试的场景，一致性最强、开销最小。
     */
    FAIL_FAST,

    /**
     * 快照：迭代基于创建迭代器时的集合副本，后续修改对本次迭代不可见，
     * 也绝不抛出并发修改异常。适用于需要稳定视图且能接受拷贝开销的场景。
     */
    SNAPSHOT,

    /**
     * 弱一致：直接遍历实时集合，可能看到迭代期间发生的部分修改，
     * 但保证不抛出异常、不重复返回元素、不死循环。
     * 适用于高并发、允许读到近似结果的场景（如监控、统计）。
     */
    WEAKLY_CONSISTENT
}
