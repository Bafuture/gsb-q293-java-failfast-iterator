# 并发修改检测迭代器

Pair-wise GSB 标注任务仓库（第 18 批 / 293）。

| 项目 | 内容 |
|------|------|
| 任务类型 | Feature 迭代 |
| 任务难度 | 困难 |
| 语言/框架 | Java, Maven, JUnit 5 |
| 环境可复现等级 | 无外部依赖 |
| 构建方式 | Maven（含 mvnw wrapper，无需本机安装 Maven） |

## 运行方式

```bash
./mvnw -q verify
```

一条命令编译、运行全部 24 个测试并构建。性能对比结果会打印在
`PerformanceComparisonTest` 的输出中。

## 实现概览

所有代码位于 `com.example.gsb.iterator` 包：

| 文件 | 职责 |
|------|------|
| `VersionedList.java` | 带版本号的线程安全列表；每次结构性修改（add/remove/set/clear）将 `modCount` 加一，读写均在内部锁上进行 |
| `IterationMode.java` | 三种迭代模式枚举：`FAIL_FAST` / `SNAPSHOT` / `WEAKLY_CONSISTENT` |
| `FailFastIterator.java` | 创建时记录期望版本，每次访问校验；发现版本变化立即抛 `ConcurrentModificationException` |
| `SnapshotIterator.java` | 创建时在锁内拷贝集合，遍历副本，原集合后续修改不可见 |
| `WeaklyConsistentIterator.java` | 按下标遍历实时集合，跳过已返回元素，步进预算保证必然终止 |
| `IterationStats.java` | 线程安全统计：开始数、完成数、检测到的并发修改数、被放弃数 |

### 快速失败（FAIL_FAST）

- 迭代期间集合被结构性修改时**立即**抛出 `ConcurrentModificationException`。
- 异常消息包含期望版本与实际版本，例如：
  `Concurrent modification detected: expected version 0 but found version 3`。
- 采用双重校验：`版本校验 → 读取 size → 再次版本校验`，消除了
  “版本刚校验通过、读 size 前元素被删”导致误判为正常结束的竞态；
  `size()` 与 `get()` 之间被删则转换为 CME。

### 快照（SNAPSHOT）

- 创建迭代器时在锁内拷贝一份集合，之后遍历副本。
- 后续修改对本次迭代完全不可见，也绝不抛出并发修改异常。

### 弱一致（WEAKLY_CONSISTENT）

- 直接遍历实时集合，可能看到迭代期间的部分修改。
- 三条硬保证：
  1. 不抛 `ConcurrentModificationException`；
  2. 用 `HashSet` 记录已返回元素并跳过重复者，**不重复返回元素**
     （朴素下标迭代在头部增删时会重复读到旧元素）；
  3. 步进预算 = `创建时大小 + 创建以来的结构性修改次数 + 16`，
     每探测一步消耗一步；修改停止后预算有界、下标单调递增，**必然终止**；
     极端情况下预算耗尽则提前结束并计入“被放弃的迭代”。

## 三种模式如何选择（适用场景）

| 模式 | 一致性 | 开销 | 适用场景 |
|------|--------|------|----------|
| `FAIL_FAST` | 最强：任何结构性修改都被发现 | 每次访问一次 volatile 版本校验，无拷贝 | 单线程使用、调试期暴露 bug、可捕获异常后整体重试的场景 |
| `SNAPSHOT` | 创建时刻的强一致视图 | 创建时一次 O(n) 拷贝，之后遍历无竞争 | 需要稳定视图（报表、不可变快照导出）、迭代耗时长、能接受拷贝内存 |
| `WEAKLY_CONSISTENT` | 弱一致：可能看到部分修改，不保证全/旧/新 | 每次访问加锁读 + 判重集合 | 监控、统计、近似聚合等高并发场景，允许读到近似结果但要求“不会出错/不会卡死” |

选择建议：
- 想尽早发现“不该并发却并发了”的问题 → `FAIL_FAST`；
- 需要迭代结果确定、可重放 → `SNAPSHOT`；
- 追求高并发可用、只关心近似聚合结果 → `WEAKLY_CONSISTENT`。

## 性能与一致性取舍

实测环境：本机 OpenJDK 17（Temurin），集合 50,000 个 `Integer` 元素，
每种模式迭代 100 轮（含 5 轮 JIT 预热）：

```
=== 迭代耗时对比（50000 个元素 x 100 轮）===
FAIL_FAST          :   116.62 ms 总计,   1.166 ms/轮
SNAPSHOT           :    24.80 ms 总计,   0.248 ms/轮
WEAKLY_CONSISTENT  :   192.26 ms 总计,   1.923 ms/轮
```

数字会随机器波动，重点是取舍逻辑：

- `SNAPSHOT` 在本基准里最快：拷贝是一次连续的 `System.arraycopy`，
  之后遍历普通 `ArrayList` 不再加锁；代价是创建时 O(n) 时间和 O(n) 内存，
  且看不到创建后的任何修改。集合很大或迭代器很多时拷贝成本会成为主导。
- `FAIL_FAST` 没有拷贝，但每次 `next()` 都要经加锁的 `get()`/`size()` 读取
  并做版本校验；换来的是最强的修改感知能力。
- `WEAKLY_CONSISTENT` 最慢：同样有逐元素加锁读，还要维护判重 `HashSet`；
  换来的是写入不被拒绝、读到近似结果且绝不重复/死循环。
- 一致性排序：`SNAPSHOT`（创建时刻强一致）≈ `FAIL_FAST`（检测强一致）
  ＞ `WEAKLY_CONSISTENT`（允许中间态）。

## 使用示例

```java
VersionedList<Integer> list = new VersionedList<>();
list.add(1);
list.add(2);

// 由调用方选择模式
list.iterator(IterationMode.FAIL_FAST);
list.iterator(IterationMode.SNAPSHOT);
list.iterator(IterationMode.WEAKLY_CONSISTENT);
list.iterator(); // 默认 FAIL_FAST

IterationStats stats = list.stats();
stats.getIterationsStarted();                 // 已开始迭代数
stats.getIterationsCompleted();               // 正常结束数
stats.getConcurrentModificationsDetected();   // 检测到的并发修改数
stats.getIterationsAborted();                 // 被放弃的迭代数
```

## 测试覆盖（24 个用例）

| 测试类 | 覆盖内容 |
|--------|----------|
| `FailFastIteratorTest`（8） | 正常迭代；add/remove/set 期间立即抛异常；异常消息含期望/实际版本；版本递增后消息准确；默认模式 |
| `SnapshotIteratorTest`（5） | 迭代中增删改全部不可见；快照反映创建时刻；密集修改下不抛异常 |
| `WeaklyConsistentIteratorTest`（5） | 头部插入/删除不产生重复；持续头部增删（5 万次）下有限时间内结束、无重复；连续 200 轮迭代全部终止 |
| `ConcurrentStressTest`（1） | 1 个写线程持续增删改 + 每模式 3 个读线程跑 1.5 秒：快速失败只允许 CME 且消息带版本、快照不抛异常、弱一致不重复且全部线程在限时内结束；统计计数与实际行为一致 |
| `IterationStatsTest`（4） | 开始/完成计数、并发修改与放弃计数、快照与弱一致不误报冲突、同一集合统计共享 |
| `PerformanceComparisonTest`（1） | 三模式耗时对比并打印结果，断言全部迭代正常完成 |
