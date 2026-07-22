# 命令调度链跨线程开销归因报告（2026-07-22）

## 摘要

本次分析为命令调度层（`AggregateCommandDispatcher` 的 `groupBy → publishOn → concatMap` 链）建立了**隔离基准**，并通过 `SchedulerStrategy`（`PARALLEL`/`IMMEDIATE`）对照实验，将调度链开销**直接归因**到跨线程往返。

核心结论：

- **跨线程往返占调度链开销的约 95%**。消除 `publishOn` 的线程切换（`IMMEDIATE`）后，吞吐提升 **20–25 倍**。
- **"调度器池过度订阅"假设被推翻**。aggregate-id 基数（1 vs 256）在 `PARALLEL` 下仅带来 ~15% 差异，远小于跨线程本身的量级。
- 调度链结构开销（`groupBy`/`flatMap`/`concatMap` 的分配与协调）相比之下**可忽略**。

> **方法声明**：本报告数据来自新建的 `CommandDispatcherChainComponentBenchmark`（隔离基准，剥离 gateway/bus/aggregate/event-store），在本机用 JMH 快速档（warmup 1×2s、measurement 2×2s、fork=1）采集，为**方向性结论**。正式性能结论需以 Full 档基准为准。

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
| `CommandDispatcherChainScenario.kt` | 隔离场景构建器 |
| `CommandDispatcherChainComponentBenchmark.kt` | JMH 基准，3 参数（cardinality/handlerCost/schedulerStrategy） |
| `benchmarking.gradle.kts` | 注册到 `componentSuite.includeClasses` |
| 本报告 | 跨线程开销归因与优化方向结论 |

所有改动已提交至 `perf/dispatcher-chain-benchmark` 分支。

## 后续建议

1. 以 Full 档基准（`benchmarkFullComponent`）产出正式数据，并用 `updateBenchmarkBaseline` 建立回归基线；
2. 评估 coroutine-first 运行时对跨线程开销的实际消除程度（用本基准对比）；
3. 评估"命令入调度直达"在 handler 纯 CPU 场景下的可行性（需区分 I/O-bound 与 CPU-bound handler）。
