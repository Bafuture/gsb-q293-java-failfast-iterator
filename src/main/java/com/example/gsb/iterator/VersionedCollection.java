package com.example.gsb.iterator;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 带版本号的线程安全集合，支持三种并发修改检测策略的迭代器
 * （见 {@link IterationMode}）。
 *
 * <p>内部采用单向链表实现：每次结构性修改（add / remove / clear）都会把
 * {@code version} 加一。快速失败迭代器通过比对版本号检测并发修改；
 * 快照迭代器在创建时拷贝全部元素；弱一致迭代器沿着链表的 {@code next}
 * 指针前进——节点只会被追加、不会被复用，因此天然保证不重复、不死循环。
 *
 * @param <E> 元素类型
 */
public class VersionedCollection<E> implements Iterable<E> {

    private final Object lock = new Object();
    private final IterationStats stats = new IterationStats();

    private volatile Node<E> head;
    private volatile Node<E> tail;
    private int size;
    private volatile long version;

    private static final class Node<E> {
        final E value;
        volatile Node<E> next;
        volatile boolean removed;

        Node(E value) {
            this.value = value;
        }
    }

    /** 追加元素（结构性修改，版本号 +1）。 */
    public void add(E element) {
        synchronized (lock) {
            Node<E> node = new Node<>(element);
            if (tail == null) {
                head = node;
            } else {
                tail.next = node;
            }
            tail = node;
            size++;
            version++;
        }
    }

    /** 删除第一个等于 {@code element} 的元素，删除成功返回 true（结构性修改，版本号 +1）。 */
    public boolean remove(E element) {
        synchronized (lock) {
            Node<E> prev = null;
            Node<E> curr = head;
            while (curr != null) {
                if (java.util.Objects.equals(curr.value, element)) {
                    if (prev == null) {
                        head = curr.next;
                    } else {
                        prev.next = curr.next;
                    }
                    if (curr == tail) {
                        tail = prev;
                    }
                    curr.removed = true;
                    size--;
                    version++;
                    return true;
                }
                prev = curr;
                curr = curr.next;
            }
            return false;
        }
    }

    /** 清空集合（结构性修改，版本号 +1）。 */
    public void clear() {
        synchronized (lock) {
            for (Node<E> n = head; n != null; n = n.next) {
                n.removed = true;
            }
            head = null;
            tail = null;
            size = 0;
            version++;
        }
    }

    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    /** 当前版本号，每次结构性修改递增。 */
    public long version() {
        return version;
    }

    /** 当前迭代统计信息（实时累计，线程安全）。 */
    public IterationStats stats() {
        return stats;
    }

    /** 创建指定模式的迭代器。 */
    public VersionedIterator<E> iterator(IterationMode mode) {
        stats.recordStarted(mode);
        return switch (mode) {
            case FAIL_FAST -> new FailFastIterator();
            case SNAPSHOT -> new SnapshotIterator(snapshot());
            case WEAKLY_CONSISTENT -> new WeaklyConsistentIterator();
        };
    }

    /** 等价于 {@code iterator(IterationMode.FAIL_FAST)}。 */
    @Override
    public VersionedIterator<E> iterator() {
        return iterator(IterationMode.FAIL_FAST);
    }

    /** 拷贝当前全部元素，供快照模式使用。 */
    private List<E> snapshot() {
        synchronized (lock) {
            List<E> copy = new ArrayList<>(size);
            for (Node<E> n = head; n != null; n = n.next) {
                copy.add(n.value);
            }
            return copy;
        }
    }

    private abstract class AbstractVersionedIterator implements VersionedIterator<E> {
        boolean abandoned;
        private boolean completionRecorded;

        @Override
        public final void abandon() {
            if (!abandoned) {
                abandoned = true;
                if (!completionRecorded) {
                    completionRecorded = true;
                    stats.recordAbandoned();
                }
            }
        }

        @Override
        public final boolean isAbandoned() {
            return abandoned;
        }

        final void checkNotAbandoned() {
            if (abandoned) {
                throw new NoSuchElementException("iteration has been abandoned");
            }
        }

        /** 迭代自然走到末尾时调用一次，计入完成数。 */
        final void recordCompletionOnce() {
            if (!completionRecorded) {
                completionRecorded = true;
                stats.recordCompleted();
            }
        }
    }

    /**
     * 快速失败：每次 hasNext/next 都比对版本号，发现结构性修改立即抛出
     * {@link ConcurrentModificationDetectedException}。
     */
    private final class FailFastIterator extends AbstractVersionedIterator {
        private final long expectedVersion = version;
        private Node<E> cursor = head;
        private Node<E> lastReturned;

        @Override
        public IterationMode mode() {
            return IterationMode.FAIL_FAST;
        }

        @Override
        public boolean hasNext() {
            if (abandoned) {
                return false;
            }
            checkConcurrentModification();
            boolean hasNext = cursor != null;
            if (!hasNext) {
                recordCompletionOnce();
            }
            return hasNext;
        }

        @Override
        public E next() {
            checkNotAbandoned();
            checkConcurrentModification();
            if (cursor == null) {
                recordCompletionOnce();
                throw new NoSuchElementException();
            }
            lastReturned = cursor;
            cursor = cursor.next;
            return lastReturned.value;
        }

        private void checkConcurrentModification() {
            long actual = version;
            if (actual != expectedVersion) {
                stats.recordConcurrentModificationDetected();
                abandon();
                throw new ConcurrentModificationDetectedException(expectedVersion, actual);
            }
        }
    }

    /**
     * 快照：迭代创建时拷贝的元素列表，之后集合的任何修改都不可见、不抛异常。
     */
    private final class SnapshotIterator extends AbstractVersionedIterator {
        private final List<E> snapshot;
        private int index;

        SnapshotIterator(List<E> snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public IterationMode mode() {
            return IterationMode.SNAPSHOT;
        }

        @Override
        public boolean hasNext() {
            if (abandoned) {
                return false;
            }
            boolean hasNext = index < snapshot.size();
            if (!hasNext) {
                recordCompletionOnce();
            }
            return hasNext;
        }

        @Override
        public E next() {
            checkNotAbandoned();
            if (index >= snapshot.size()) {
                recordCompletionOnce();
                throw new NoSuchElementException();
            }
            return snapshot.get(index++);
        }
    }

    /**
     * 弱一致：沿链表 next 指针前进，跳过已删除节点，可以看到迭代期间
     * 新追加的元素。节点只追加不复用、指针只前进不回头，因此保证
     * 不重复、不死循环、不抛并发修改异常。
     */
    private final class WeaklyConsistentIterator extends AbstractVersionedIterator {
        private Node<E> cursor = head;
        private Node<E> lastReturned;

        @Override
        public IterationMode mode() {
            return IterationMode.WEAKLY_CONSISTENT;
        }

        @Override
        public boolean hasNext() {
            if (abandoned) {
                return false;
            }
            advance();
            boolean hasNext = cursor != null;
            if (!hasNext) {
                recordCompletionOnce();
            }
            return hasNext;
        }

        @Override
        public E next() {
            checkNotAbandoned();
            advance();
            if (cursor == null) {
                recordCompletionOnce();
                throw new NoSuchElementException();
            }
            Node<E> current = cursor;
            cursor = current.next;
            lastReturned = current;
            return current.value;
        }

        /**
         * 前进到下一个未删除节点。cursor 为 null 时从最后返回节点的后继
         * （或集合头部）重新探测——这样迭代期间新追加到尾部的元素也能被看到。
         * 指针只沿链表向前移动、节点绝不复用，因此不会重复也不会死循环。
         */
        private void advance() {
            if (cursor == null) {
                cursor = (lastReturned == null) ? head : lastReturned.next;
            }
            while (cursor != null && cursor.removed) {
                cursor = cursor.next;
            }
        }
    }
}
