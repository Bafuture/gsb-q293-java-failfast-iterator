package com.example.gsb.iterator;

import java.util.ConcurrentModificationException;

/**
 * 快速失败模式检测到结构性修改时抛出的异常。继承自
 * {@link ConcurrentModificationException}，并额外携带期望版本与实际版本，
 * 便于定位"迭代期间集合被改了几次"。
 */
public class ConcurrentModificationDetectedException extends ConcurrentModificationException {

    private final long expectedVersion;
    private final long actualVersion;

    public ConcurrentModificationDetectedException(long expectedVersion, long actualVersion) {
        super("Concurrent modification detected during iteration: "
                + "expected version " + expectedVersion
                + " but actual version " + actualVersion);
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    /** 迭代器创建时记录的集合版本号。 */
    public long getExpectedVersion() {
        return expectedVersion;
    }

    /** 检测到修改时集合的实际版本号。 */
    public long getActualVersion() {
        return actualVersion;
    }
}
