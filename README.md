# 并发修改检测迭代器

一个从零实现的、带并发修改检测的 Java 迭代器组件。集合被并发修改时，普通
迭代器可能"悄悄给出错误结果甚至死循环"；本组件让调用方在三种语义之间显式
选择：**快速失败、快照、弱一致**。

## 构建与验证

```bash
./mvnw -q verify
```

共 28 个测试（JUnit 5 + AssertJ），一条命令跑通。

## 快速上手

```java
VersionedCollection<Integer> collection = new VersionedCollection<>();
collection.add(1);
collection.add(2);

// 由调用方显式选择模式
VersionedIterator<Integer> it = collection.iterator(IterationMode.FAIL_FAST);
while (it.hasNext()) {
    System.out.println(it.next());
}

IterationStats stats = collection.stats();
```

## 三种模式与适用场景

| 模式 | 并发修改时的行为 | 一致性 | 适用场景 |
|------|------------------|--------|----------|
| `FAIL_FAST` | 立即抛出 `ConcurrentModificationDetectedException`，消息含期望/实际版本号 | 强一致：迭代必须对应一个未被破坏的版本 | 对账、批处理导出等"结果必须精确"的只读遍历 |
| `SNAPSHOT` | 不抛异常，迭代基于创建时的拷贝，后续增删清空全部不可见 | 快照隔离：看到创建时刻的完整视图 | 写很频繁且需要稳定视图：报表、序列化、长遍历 |
| `WEAKLY_CONSISTENT` | 不抛异常；可看到迭代期间新追加的元素、跳过已删除元素；**保证不重复、不死循环** | 弱一致 | 监控、状态打印、事件派发等"读到大概其即可"的场景 |

异常信息示例：

```
Concurrent modification detected during iteration: expected version 3 but actual version 4
```

异常上还可用 `getExpectedVersion()` / `getActualVersion()` 取版本号。

## 实现要点

- 集合内部是单向链表，每次结构性修改（`add` / `remove` / `clear`）在锁内把
  版本号 `version` 加一；节点字段用 `volatile` 保证无锁读的可见性。
- **快速失败**：每次 `hasNext` / `next` 比对创建时记录的版本号，不一致即抛异常，
  同时该迭代被计入"检测到并发修改"与"被放弃"。
- **快照**：创建迭代器时在锁内把全部元素拷进一个不可变 `List`，之后与集合完全脱钩。
- **弱一致**：沿 `next` 指针前进并跳过已删除节点；到尾时会重新探测尾部，
  因此能看到迭代期间新追加的元素。节点只追加、不复用、指针只前进，从结构上
  杜绝重复与环（死循环）；`WeaklyConsistentIteratorTest#terminatesWithoutDuplicatesWhileWriterMutates`
  用 2 万次增删 + 10 秒超时对此给出测试证明。
- 所有模式均不支持 `remove()`；迭代器可主动 `abandon()`。

## 统计信息

`collection.stats()` 返回线程安全的 `IterationStats`：

- `getIterationsStarted(mode)` / `getTotalIterationsStarted()`：各模式及累计迭代次数；
- `getConcurrentModificationsDetected()`：快速失败检测到并发修改的次数；
- `getAbandonedIterations()`：被放弃的迭代数（异常中断 + 主动 `abandon()`）；
- `getCompletedIterations()`：正常走到末尾的迭代次数。

全局不变式（测试覆盖）：`started == completed + abandoned`。

## 性能与一致性取舍

- **快速失败**：读路径零拷贝、最快，但在写冲突频繁时几乎无法完成一次完整遍历，
  高并发写下会有海量异常（异常本身很贵），适合冲突罕见的场景。
- **快照**：每次创建都要 O(n) 全量拷贝，创建成本最高、占额外内存；但一旦创建，
  遍历期间与写完全解耦，绝不失败，开销稳定可预期。
- **弱一致**：无拷贝、无锁、无异常，吞吐量通常最高；代价是结果可能混合多个版本，
  只能保证"不重复、不死循环"。

### 耗时对比（参考数据）

来自测试 `IteratorBenchmarkTest`（结果随每次 `mvn verify` 打印；数据为某次
CI 容器内运行，仅用于定性比较）。

静态读：集合 100 000 元素，50 轮全量迭代（含 20 轮预热）：

| 模式 | 总耗时 | 平均每轮 |
|------|-------:|---------:|
| `FAIL_FAST` | 7 ms | 0.14 ms |
| `SNAPSHOT` | 13 ms | 0.26 ms |
| `WEAKLY_CONSISTENT` | 13 ms | 0.26 ms |

并发写：1 个写线程持续 add+remove（滑动窗口保持约 1000 元素），每种模式迭代 800 ms：

| 模式 | 完成迭代 | 检测到修改（放弃） | 读取元素速率 |
|------|---------:|-------------------:|-------------:|
| `FAIL_FAST` | 25 070 | 266 467 | 37 000 elems/ms |
| `SNAPSHOT` | 193 043 | 0 | 241 000 elems/ms |
| `WEAKLY_CONSISTENT` | 200 065 | 0 | 271 000 elems/ms |

结论：静态读三种模式接近（快照多出一次 O(n) 拷贝）；持续并发写下快速失败
约 91% 的迭代被立即中断、吞吐被异常处理拖垮，快照与弱一致不受影响，弱一致
略快于快照（省去创建时拷贝）。

## 测试覆盖

- `FailFastIteratorTest`（9 个）：正常迭代、增/删/清空立即抛异常、消息与
  版本号字段、主动放弃、统计计数；
- `SnapshotIteratorTest`（4 个）：增删清空不可见、多个快照相互独立、风暴修改下不抛异常；
- `WeaklyConsistentIteratorTest`（6 个）：看到追加、跳过删除、不重复、
  写线程 2 万次修改下超时证明不死循环；
- `ConcurrentStressTest`（4 个）：1 写线程 + 4 读线程，分别及混合压测三种模式，
  校验各自语义与统计不变式；
- `IterationStatsTest`（3 个）：计数与全局不变式；
- `IteratorBenchmarkTest`（2 个）：静态/并发耗时对比并打印结果。
