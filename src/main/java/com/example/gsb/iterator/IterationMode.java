package com.example.gsb.iterator;

/**
 * 迭代模式。调用方在创建迭代器时显式选择，三种模式在一致性与性能之间
 * 提供不同的取舍：
 *
 * <ul>
 *   <li>{@link #FAIL_FAST} —— 强一致。迭代期间集合被结构性修改时立即抛出
 *       {@link ConcurrentModificationDetectedException}。适合"迭代结果必须
 *       精确对应某一时刻集合状态"的场景，例如对账、批处理导出。</li>
 *   <li>{@link #SNAPSHOT} —— 隔离一致。迭代器创建时拷贝一份快照，之后集合
 *       的任何修改都不影响本次迭代，也绝不抛并发修改异常。适合修改频繁、
 *       且调用方需要稳定视图的只读遍历，例如生成报表、序列化。</li>
 *   <li>{@link #WEAKLY_CONSISTENT} —— 弱一致。迭代器可以反映创建之后的部分
 *       修改（看到新追加的元素、跳过已删除的元素），但保证不重复、不死循环、
 *       不抛异常。适合监控、状态打印、事件派发等"读到大概其即可"的场景。</li>
 * </ul>
 */
public enum IterationMode {
    FAIL_FAST,
    SNAPSHOT,
    WEAKLY_CONSISTENT
}
