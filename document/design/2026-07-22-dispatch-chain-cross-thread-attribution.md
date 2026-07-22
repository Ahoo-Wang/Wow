# 命令调度链跨线程开销归因报告（2026-07-22）

## 摘要

本次分析为命令调度层（`AggregateCommandDispatcher` 的 `groupBy → publishOn → concatMap` 链）建立了**隔离基准**，并通过 `SchedulerStrategy`（`PARALLEL`/`IMMEDIATE`）对照实验，将调度链开销**直接归因**到跨线程往返。

核心结论：

- **隔离视角**：跨线程往返占调度链开销的约 95%。消除 `publishOn` 的线程切换（`IMMEDIATE`）后，吞吐提升 **20–25 倍**。
- **端到端视角（关键修正）**：在真实 MongoDB event store 的端到端命令写入中，跨线程往返的相对开销**急剧缩小到 ~5%**——被 I/O 延迟淹没。两个策略的 t1→t4 扩展比相同（~3.5×），说明 I/O 场景下瓶颈在存储而非线程切换。
- **"调度器池过度订阅"假设被推翻**。aggregate-id 基数（1 vs 256）在 `PARALLEL` 下仅带来 ~15% 差异，远小于跨线程本身的量级。
- 调度链结构开销（`groupBy`/`flatMap`/`concatMap` 的分配与协调）相比之下**可忽略**。

> **适用范围**：跨线程消除优化的收益**取决于命令路径是否包含阻塞 I/O**。纯内存/计算密集的命令路径收益巨大（20–25×）；涉及 event store I/O 的真实路径收益有限（~5%）。优化应优先瞄准**无 I/O 的本地命令路径**（如纯投影、saga 内存处理），而非 I/O 主导的持久化路径。

> **方法声明**：本报告数据来自新建的 `CommandDispatcherChainComponentBenchmark`（隔离基准，剥离 gateway/bus/aggregate/event-store）以及对 `MongoCommandWriteE2EBenchmark`（端到端，真实 MongoDB，已纳入 `SchedulerStrategy` 参数）的测量，在本机用 JMH 快速档采集，为**方向性结论**。正式性能结论需以 Full 档基准为准。

## 动机

此前，命令调度链的开销只能通过端到端（E2E）基准的差值**间接推断**：

- `sendCommandFireAndForget`（经 gateway+bus+dispatcher）：~518K ops/s
- `handleAggregateAndNotifyProcessedWithoutWait`（直调 handler，绕过调度）：~632K ops/s

差值暗示调度链有开销，但无法区分：

1. `groupBy`/`concatMap` 的**结构开销**（分组、协调、ack）；
2. `publishOn(scheduler)` 的**跨线程切换开销**（线程调度、park/unpark、缓存失效）；
3. 调度器池与 `parallelism` 不匹配导致的**线程争用**（"过度订阅"假设：`flatMap(64×cores)` 争抢 `cores` 线程）。

本报告通过隔离基准把三者分开。

## 环境

| 项 | 值 |
|----|-----|
| OS | macOS（darwin 25.5.0 arm64） |
| CPU | Apple M4 Pro（14 核） |
| JDK | OpenJDK 17.0.7 |
| 基准 JVM 参数 | `-Xmx2g`（冒烟/诊断用；正式档用 `-Xmx4g -Xms4g -XX:+UseG1GC -XX:+AlwaysPreTouch`） |
| JMH 配置 | warmup=1×2s, measurement=2×2s, fork=1, mode=thrpt |
| 基线代码 | `perf/dispatcher-chain-benchmark` 分支 `fe98c8786` |

## 方法：隔离基准

### 新增基准

`CommandDispatcherChainComponentBenchmark` 直接实例化 `AggregateCommandDispatcher`，注入可控的 `Sinks.Many` 作为 `messageFlux`，用一个空操作 `CommandHandler`（可选注入模拟 CPU 成本）。每个 exchange 携带一个完成 `Sinks.Empty`（存为 exchange attribute），调度链处理完后基准方法 `await().block()` 返回——测的是**入队到调度链处理完毕的完整往返**。

**完全剥离**：command gateway、command bus、aggregate processor、event store、event bus。

### 参数化

| 参数 | 取值 | 目的 |
|------|------|------|
| `aggregateIdCardinality` | 1 / 16 / 256 | distinct group key 数。`1`=单热点（所有命令串行于一个 `concatMap` group）；`>1`=多 group 并发 |
| `handlerCost` | NOOP / SIMULATED | NOOP=纯调度开销；SIMULATED=加固定 CPU 预算，暴露调度占比 |
| `schedulerStrategy` | PARALLEL / IMMEDIATE | PARALLEL=生产默认（`newParallel` 池，每次 dispatch 跨线程）；IMMEDIATE=`Schedulers.immediate()`，`publishOn` 不切线程 |

`PARALLEL` vs `IMMEDIATE` 的对照是关键：它把**跨线程切换开销**从**结构开销**中分离出来。

## 数据

### 对照一：PARALLEL vs IMMEDIATE（t1，跨线程归因）

| cardinality | handlerCost | PARALLEL (ops/s) | IMMEDIATE (ops/s) | 倍数 |
|------|------|------|------|------|
| 1 | NOOP | 187,599 | **3,855,817** | **20.6×** |
| 1 | SIMULATED | 147,624 | 3,649,228 | 24.7× |
| 16 | NOOP | 144,916 | 3,729,130 | 25.7× |
| 16 | SIMULATED | 163,738 | 3,749,800 | 22.9× |
| 256 | NOOP | 160,680 | 3,426,508 | 21.3× |
| 256 | SIMULATED | 164,265 | 3,511,112 | 21.4× |

**结论**：消除跨线程切换带来 **20–25 倍**吞吐提升。跨线程往返是调度链开销的**绝对主因**（~95%）。

### 对照二：aggregateIdCardinality 的影响（结构开销归因）

在 `PARALLEL` 下：cardinality 1（187K）vs 256（161K），差异 ~14%。
在 `IMMEDIATE` 下：cardinality 1（3.86M）vs 256（3.43M），差异 ~11%。

**结论**：group 数对吞吐的影响很小（~15%），且在消除跨线程后依然存在——说明这是 `groupBy`/`flatMap` 分组与协调的**结构开销**，量级远小于跨线程开销。"过度订阅"假设（group 数 >> 线程数导致争用）不成立：单 group（cardinality=1）同样只有 187K，瓶颈不在 group 争用。

### 对照三：多线程扩展性（t1 vs t4，PARALLEL）

| cardinality | handlerCost | t1 (ops/s) | t4 (ops/s) | 扩展比 |
|------|------|------|------|------|
| 1 | NOOP | 187,599 | 237,725 | 1.27× |
| 16 | NOOP | 144,916 | 206,382 | 1.43× |
| 256 | NOOP | 160,680 | 208,692 | 1.30× |

**结论**：t1→t4 仅扩展 ~1.3 倍（理想应接近 4×）。扩展性受限于每次操作的**固定跨线程同步成本**（emit→调度器线程→completion sink→回调用方），加线程不降低单次操作的同步成本，只增加并发度。这与 `sendAndWaitProcessed` E2E 的 t4→t8 回退（168K→155K）现象一致。

## 验证：真实 MongoDB 端到端

隔离基准证明了跨线程是调度链的 95% 开销。但真实命令写入包含 event store I/O——一个关键问题是：**I/O 延迟是否淹没跨线程开销？** 为此在 `MongoCommandWriteE2EBenchmark`（完整 `sendAndWaitForProcessed` + 真实 `MongoEventStore`）上启用 `SchedulerStrategy` 参数做 PARALLEL/IMMEDIATE 对照。

### 数据（本机，localhost MongoDB，快速档）

| 线程 | PARALLEL (ops/s) | IMMEDIATE (ops/s) | IMMEDIATE 优势 | 扩展比 (t4/t1) |
|------|------|------|------|------|
| t1 | 2,961 | 3,119 | +5.3% | — |
| t4 | 10,272 | 10,867 | +5.8% | PARALLEL 3.47× / IMMEDIATE 3.48× |

### 修正性结论

1. **I/O 场景下跨线程开销急剧缩小**：从隔离基准的 20–25 倍差距坍缩到 ~5%。Mongo 单次写入延迟（~300μs，由 1/3000 推算）远大于跨线程切换（~2–5μs），把后者淹没。
2. **扩展性瓶颈转移**：两个策略的 t1→t4 扩展比几乎相同（3.47× vs 3.48×），说明 I/O 场景下瓶颈是存储并发能力，不是线程切换。这与隔离基准（纯 CPU，扩展受跨线程限制）形成对照。
3. **IMMEDIATE 在 t4 也无显著优势**（+5.8%）：I/O 等待期间线程本就让出 CPU，跨线程切换的相对机会成本更低。

**对优化方向的修正**：跨线程消除（如 coroutine-first）的收益**取决于命令路径的 I/O 特性**：

- **无 I/O 路径**（纯内存聚合处理、投影/saga 的内存阶段、`sendAndWaitForSent`）：收益巨大（20–25×），是高价值优化目标。
- **I/O 主导路径**（`sendAndWaitProcessed` 持久化命令）：收益有限（~5%），不应作为主要优化理由；这类路径的优化应瞄准存储层（如 Mongo 文档转换单遍化、批量化）。

## 深挖：框架 vs I/O 的精确分解

为界定跨线程消除的绝对收益上限，运行了 `CommandWriteFrameworkBreakdownBenchmark`（此前仅有源码、从未记录结果），在四种 event store 下测同一 `sendAndWaitProcessed`：

| scenario | 吞吐 (ops/s) | 延迟 (µs/op) | 增量含义 |
|------|------|------|------|
| ceiling-noop（NoopEventStore + NoOp 幂等/校验） | 94,539 | 10.6 | 纯框架天花板（含跨线程往返 + 调度 + 等待） |
| noop-store（NoopEventStore + Bloom 幂等 + 校验） | 89,104 | 11.2 | + 幂等/校验 CPU（+0.6µs） |
| in-memory-new-aggregate（InMemoryEventStore） | 86,661 | 11.5 | + 内存 append CPU（+0.3µs） |
| mongo（MongoEventStore） | 2,984 | 335 | + Mongo append I/O（**+323µs**） |

**精确分解**：
- 框架开销天花板 ≈ **10.6µs**（含全部跨线程往返、调度、等待机制）
- Mongo I/O 增量 ≈ **323µs**，占 mongo 总延迟的 **96.5%**
- 跨线程往返（隔离基准占框架的 ~95%）≈ 10µs，占 mongo 总延迟的 **~3%**

**绝对收益上限**：跨线程消除最多省 ~10µs/命令。在 Mongo 场景（335µs）这仅占 ~3%——与 Mongo 跨线程基准的 ~5% 结论一致（后者含更多噪声）。

### 适用性边界（关键修正）

深挖代码路径（`sendAndWaitForProcessed` 全链追踪）确认了一个决定性事实：

> **`sendAndWaitForProcessed` 路径上，没有任何成功的、无 I/O 的生产命令子路径。** 每个到达 `SimpleCommandAggregate.process` 并产生事件的非 void、非重复命令都会调 `eventStore.append`（`SimpleCommandAggregate.kt:133`）。void 命令在调度前被过滤（`CommandDispatcher.kt:45-51`），重复命令在 gateway 被幂等拦截——两者都不产生 PROCESSED 信号。

这意味着"无 I/O 路径收益巨大"的结论，其生产适用场景**高度受限**：

1. **`StorageType.IN_MEMORY` 部署**（`EventStoreAutoConfiguration.kt:32-36`，配置 `wow.event-store.storage=in-memory`）：框架开销是全部开销，跨线程占 ~95%。这是真实的（虽非典型）生产配置——纯计算聚合、临时处理。**跨线程消除在此场景价值最大。**
2. **`sendAndWaitForSent`**：返回点在 gateway（`commandBus.send` 后），**不经调度器**，无跨线程往返可消除。已是快路径。
3. **Mongo/Redis 持久化部署**（主流场景）：跨线程占 ~3-5%，收益有限。
4. **PROJECTED / EVENT_HANDLED / SAGA_HANDLED 阶段**：这些阶段在 append 之后，由独立 dispatcher（各自 `publishOn`）处理。若 projector/handler 是纯 CPU，其调度链跨线程占比高（复用同一 `AggregateDispatcher` 基础设施）。但 `sendAndWait(projected)` 总延迟仍含 Mongo append（PROCESSED 先完成），投影阶段增量（几 µs）相对总延迟可忽略。**除非整条链路无 I/O（IN_MEMORY 部署），否则收益被 append 主导。**

**最终判断**：跨线程消除（coroutine-first 等）的实战价值**集中在 `StorageType.IN_MEMORY` 部署**。对于主流的 Mongo/Redis 持久化部署，优化收益 ≤5%，应优先投入存储层（Mongo 文档转换、批量化）或 I/O 并发度，而非跨线程消除。

## 结论与对优化方向的影响

### 已被推翻的假设

**"调度器池过度订阅是瓶颈"**——不成立。证据：单 group（cardinality=1，无争用可能）在 `PARALLEL` 下仍只有 187K ops/s，与多 group 几乎相同；消除跨线程后单 group 达 3.86M。瓶颈是**跨线程切换本身**，与 group/线程数比例无关。

因此，优化调度器池大小或 `parallelism` 参数的**预期收益有限**——它不消除跨线程往返。

### 真正的优化方向：减少/消除跨线程切换

1. **通知回传直达（已部分实现）**：`#2695 perf(core): dispatch local wait notifications directly` 已让 `LocalCommandWaitNotifier.notifyAndForget` 跳过 `Mono.fromRunnable` 订阅，本地通知同步完成。这优化了**信号回传**那一跳。
2. **命令入调度的直达（未实现）**：当命令经 `InMemoryCommandBus`（本地）且 handler 是纯 CPU 时，理论上可跳过 `publishOn(scheduler)`。但命令处理通常涉及 event store I/O，**需要调度器避免阻塞调用方**，故收益受限。
3. **coroutine-first 运行时（探索中）**：`coroutine-first-runtime-kernel` 分支用协程的虚线程特性从根本上避免真线程切换。这是根治方向，但工作量大。

### 测量能力的价值

本报告建立的隔离基准（`CommandDispatcherChainComponentBenchmark`）今后可用于：

- 任何调度层优化（MPSC sink、调度策略、coroutine-first）的 before/after 直接归因；
- 回归监控——跨线程开销若意外上升会立即显现。

## 产物清单

| 产物 | 说明 |
|------|------|
| `CommandDispatcherChainScenario.kt` | 隔离场景构建器（纯内存，剥离 gateway/bus/aggregate/event-store） |
| `CommandDispatcherChainComponentBenchmark.kt` | 隔离基准，3 参数（cardinality/handlerCost/schedulerStrategy） |
| `SchedulerStrategies.kt` | 共享的 `SchedulerStrategy` → `AggregateSchedulerSupplier` 映射 |
| `MongoCommandWriteE2EBenchmark.kt` / `RedisCommandWriteE2EBenchmark.kt` / `CommandWriteE2EBenchmark.kt` | 端到端基准（已在正式 suite），含 schedulerStrategy 参数 |
| `benchmarking.gradle.kts` | 注册隔离基准到 `componentSuite.includeClasses` |
| 本报告 | 跨线程开销归因、I/O 场景修正、优化方向结论 |

所有改动已提交至 `perf/dispatcher-chain-benchmark` 分支。

## 后续建议

1. 以 Full 档基准（`benchmarkFullComponent`、`benchmarkFullInfrastructureE2E`）产出正式数据，并用 `updateBenchmarkBaseline` 建立回归基线；
2. 评估 coroutine-first 运行时对**无 I/O 路径**跨线程开销的实际消除程度（用隔离基准对比；预期收益最大）；
3. I/O 主导路径的优化应转向存储层（Mongo 文档转换单遍化、批量化），而非跨线程消除。
