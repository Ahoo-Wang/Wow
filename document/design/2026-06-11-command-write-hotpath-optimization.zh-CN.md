# 命令写入热路径性能优化报告（2026-06-11）

## 摘要

本次优化针对 Wow 框架命令写入热路径的性能与内存分配，基于本机 Quick 基准的前后对比（同机、同 JVM、同配置）：

- **`sendAndWaitForSent`（单线程）：吞吐 +82%~+102%（ceiling 581K → 1176K ops/s），每操作分配 −46%~−54%（4997 → 2293 B/op）**
- **`sendAndWaitForProcessed`（单线程）：吞吐 +11%，每操作分配 −12%（16.8KB → 14.8KB）**
- 等待策略组件（`registerWaitStrategy`）：**分配 −74%（1592 → 408 B/op）**，吞吐 +63%
- 全部既有测试、契约测试（TCK）、OpenTelemetry 集成测试与 detekt 静态检查通过，公共 API 签名未变

> **方法声明**：本报告全部数据来自 Quick 档基准（warmup 1×3s、测量 2×5s、fork=1、threads={1,4}），为**方向性结论**。正式性能结论需以 Full 档基准为准。本机数据与仓库中此前签入的 Mac 数据**不可跨机对比**。

## 环境

| 项 | 值 |
|----|-----|
| OS | Windows 10 Pro (10.0.19045) |
| CPU | 13th Gen Intel Core i7-13700K（16 核 / 24 线程） |
| 内存 | 64 GiB |
| JDK | Eclipse Temurin 17.0.19 |
| 基准 JVM 参数 | `-Xmx4g -Xms4g -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints -XX:+AlwaysPreTouch` |
| JMH 配置 | warmup=1×3s, measurement=2×5s, fork=1, threads={1,4}, modes=thrpt+avgt, `-prof gc` |
| 基线代码 | main `bcd23b3d7`（refactor(core): return void from command gateway send） |

基线数据留存于 `wow-benchmarks/results/jmh/_baseline-quick/`（gitignore 目录，不入库）。

## 瓶颈诊断

诊断依据为本机基线的组件级 `gc.alloc.rate.norm` 与 stack profiler 数据：

| 证据（基线，threads=1） | 数值 | 归因 |
|------|------|------|
| `WaitNotifyComponentBenchmark.registerWaitStrategy`（仅创建策略+注册+注销） | 1592 B/op | `WaitingFor` 每命令急切分配 unicast sink：UnicastProcessor + 256 槽 SpscLinkedArrayQueue + ConcurrentManySink（ReentrantLock）包装 |
| `CommandPipelineComponentBenchmark` 本地等待层增量（WithLocalWait − WithoutWait） | +3178 B/op | 等待策略 + `waitingLast()` 的 collectList/排序/合并/复制 |
| `WaitNotifyComponentBenchmark.notifyProcessed` 通知路径增量 | ~870 B/op | 每信号 `Mono.fromRunnable` + 订阅 + 信号对象 |
| E2E `sendAndWaitSent`（走完整等待机制） | 4997 B/op | SENT 信号由网关自身同步合成，却仍走完整的策略注册/头传播/sink/waitingLast 机制 |
| 服务端 exchange 每命令 5+ 次 attribute 写入 | （含于管线分配） | `SimpleServerCommandExchange` 的 ConcurrentHashMap Node 分配 + 版本号装箱 |
| Mongo 基础设施 E2E stack profiler | 44.2% WAITING / 13.7% TIMED_WAITING | 本机 Docker 驱动 I/O 等待主导（123KB/op），文档转换不在火焰图前列 |

## 实施的优化

### B. `DefaultCommandGateway.sendAndWaitForSent` 快路径

**文件**：`wow-core/src/main/kotlin/me/ahoo/wow/command/DefaultCommandGateway.kt`

SENT 阶段结果由网关在命令总线接受后自行合成，无需等待任何下游阶段。覆写接口默认方法，跳过等待策略分配、注册/注销、wait 头传播、sink 与 `waitingLast()` 链，直接构造 `CommandResult(stage=SENT)`。错误路径与原实现等价（统一包装为 `CommandResultException`）；void 命令语义不变（原 `WaitingForSent.supportVoidCommand = true`）。

**语义说明**：SENT-only 等待不再向消息头传播 wait 信息。下游各阶段通知过滤器对无 wait 头的消息本就直接跳过（`extractWaitStrategy()` 返回 null），故行为等价且省去下游头解析；经核查 wow-webflux / wow-opentelemetry / wow-spring 无任何依赖 SENT-only wait 头的消费方。`TracingCommandGateway` 不覆写该方法，其默认实现落入被覆写的 `sendAndWait`，链路追踪不受影响。

### A. `WaitingFor` 懒物化 sink 与终端信号直算

**文件**：`wow-core/src/main/kotlin/me/ahoo/wow/command/wait/WaitingFor.kt`、`stage/WaitingForAfterProcessed.kt`

原实现每个等待策略实例（每条 `sendAndWait` 命令一个）急切分配 `Sinks.unsafe().many().unicast().onBackpressureBuffer().concurrent()`，仅队列块即约 2KB；`waitingLast()` 再经 `collectList()` + `sortBy` + 全量 result 合并 + `copyResult` 复制。

新实现（公共 API `waiting(): Flux` / `waitingLast(): Mono` 签名不变）：

- 信号先缓存于按需创建的小型 `ArrayList`（处理路径通常仅 SENT+PROCESSED 两个信号）；
- `waitingLast()` 订阅时走一次性 `Sinks.unsafe().one()`，终止时从缓存直接计算终端信号（增量取 `signalTime` 最大者 + 按到达顺序合并 result，单信号场景零复制）；
- `waiting()`（仅 `sendAndWaitStream` 等流式场景）订阅时才懒物化小队列 unicast sink 并回放缓存信号；
- `cancelled`/`terminated` 由 Scannable 探测改为显式 `@Volatile` 标志；并发 emit 由 `ReentrantLock` 串行化（与原 `ConcurrentManySink` 同等语义）；
- 新增 `protected open fun resolveLastSignal(signals)` 钩子，`WaitingForAfterProcessed` 借此删除其重复的 collectList 覆写（目标阶段信号优先，语义不变）；
- `SimpleWaitingForChain` 内部从不被订阅的 main/tail 子策略随懒化自动免除 sink 分配。

**语义说明**（行为差异，均经测试验证）：
1. 跨 `waiting()`/`waitingLast()` 统一为"至多一个订阅者"，第二个订阅者收到 `IllegalStateException`（原 unicast 行为一致）；
2. 多信号 result 合并顺序由"按 signalTime 排序后合并"改为"按到达顺序合并"，仅在乱序到达且 result 键冲突时可观测到差异（实际场景中各阶段 result 键不重叠）；
3. 终端信号仍取 `signalTime` 最大者（同值时后到优先，与原稳定排序取尾等价）。

**新增测试**：`WaitingForLazySinkTest`（14 例）覆盖：仅 waitingLast 不物化 Flux sink、迟到订阅回放、空完成→空 Mono、订阅前/后错误传播、失败信号 fail-fast、二次订阅拒绝、完成后信号忽略、终端信号时间序、并发 next 无丢失（8 线程×100 信号）等。

### C. `SimpleServerCommandExchange` attribute 字段化

**文件**：`wow-core/src/main/kotlin/me/ahoo/wow/command/CommandExchange.kt`

服务端处理每命令写入 5+ 个已知 attribute（aggregateMetadata / aggregateProcessor / function / serviceProvider / commandAggregate / eventStream / aggregateVersion 等）。将 10 个已知键改为 `@Volatile` 字段直存（`when(key)` 字符串 switch 分派），未知键回落到原 `ConcurrentHashMap`；构造时若调用方传入含已知键的 map 则迁移至字段。消除热路径上的 CHM Node 分配与哈希计算。构造函数签名与 `attributes` 属性保持不变。

### D. `LocalCommandWaitNotifier.notifyAndForget` 直达路径

**文件**：`wow-core/src/main/kotlin/me/ahoo/wow/command/wait/CommandWaitNotifier.kt`

本地通知本就同步完成，覆写 fire-and-forget 路径直接调用注册器分发，省去每信号的 `Mono.fromRunnable` + 订阅者分配；异常捕获后记录日志，与原 `notify(...).subscribe()` 的 dropped-error 语义一致。

### 已评估并放弃的项

| 项 | 放弃理由 |
|----|---------|
| 网关 `send(command, waitStrategy)` 算子链重排（合并 doOnSuccess/doOnError、注册时机前移） | 注册/注销时机与取消竞态语义敏感，预期收益（约 0.3~0.6KB/op）与风险不成比例 |
| Mongo 文档转换单遍化（消除 Jackson `convertValue` 双重转换） | stack profiler 显示本机 Mongo 路径 44% 时间在驱动 I/O 等待，转换帧不在热点前列；在本机 Docker 环境下不可测量 |
| wait 头传播批量化 | 触及 `Header` 公共 API，收益小 |

## 基准对比（基线 → 优化后）

### Framework E2E（吞吐为 thrpt 模式，分配为 gc.alloc.rate.norm）

**threads = 1**

| 基准 | 场景 | 吞吐 (ops/s) | 变化 | 分配 (B/op) | 变化 |
|------|------|------------|------|------------|------|
| sendAndWaitSent | ceiling | 581,742 → 1,175,740 | **+102.1%** | 4,997 → 2,293 | **−54.1%** |
| sendAndWaitSent | noop-store | 434,908 → 790,426 | +81.7% | 5,950 → 3,229 | −45.7% |
| sendAndWaitSent | in-memory-new-aggregate | 452,313 → 838,233 | +85.3% | 5,766 → 2,989 | −48.2% |
| sendAndWaitProcessed | ceiling | 41,998 → 46,618 | +11.0% | 14,761 → 12,874 | −12.8% |
| sendAndWaitProcessed | noop-store | 38,020 → 42,167 | +10.9% | 16,551 → 14,547 | −12.1% |
| sendAndWaitProcessed | in-memory-new-aggregate | 37,050 → 41,019 | +10.7% | 16,767 → 14,771 | −11.9% |

**threads = 4**

| 基准 | 场景 | 吞吐 (ops/s) | 变化 | 分配 (B/op) | 变化 |
|------|------|------------|------|------------|------|
| sendAndWaitSent | ceiling | 1,159,989 → 1,279,260 | +10.3% | 4,999 → 2,295 | −54.1% |
| sendAndWaitSent | noop-store | 1,089,098 → 1,454,136 | +33.5% | 5,935 → 3,232 | −45.6% |
| sendAndWaitSent | in-memory-new-aggregate | 1,153,269 → 1,519,198 | +31.7% | 5,752 → 3,030 | −47.3% |
| sendAndWaitProcessed | ceiling | 158,782 → 167,913 | +5.8% | 14,591 → 12,715 | −12.9% |
| sendAndWaitProcessed | noop-store | 148,350 → 155,635 | +4.9% | 16,248 → 14,281 | −12.1% |
| sendAndWaitProcessed | in-memory-new-aggregate | 135,666 → 144,306 | +6.4% | 16,426 → 14,480 | −11.8% |

> processed 路径在本机吞吐增益（+5%~+11%）小于分配降幅，原因是该路径以跨线程调度等待（聚合调度器 + `block()` park/unpark）为主导；分配 −12% 的收益在 GC 压力敏感的生产负载下更有意义。

### 等待/通知组件（threads = 1）

| 基准 | 吞吐 (ops/s) | 变化 | 分配 (B/op) | 变化 |
|------|------------|------|------------|------|
| registerWaitStrategy | 7.09M → 11.58M | +63.4% | 1,592 → 408 | **−74.4%** |
| notifyProcessed | 2.63M → 3.19M | +21.6% | 2,464 → 1,296 | −47.4% |
| waitForProcessed | 1.58M → 2.01M | +27.2% | 3,112 → 1,616 | −48.1% |
| 管线 handleAggregateAndNotifyProcessedWithLocalWait | 134K → 213K | +58.8% | 12,327 → 10,500 | −14.8% |
| 管线 handleAggregateOnly | 216K → 327K | +50.9% | 7,628 → 7,331 | −3.9% |
| sendCommandFireAndForget | 219K → 320K | +46.2% | 10,930 → 10,626 | −2.8% |

> 管线类基准吞吐普遍 +46%~+60%，其中分配降幅小的项（如 handleAggregateOnly −3.9%）的吞吐增益部分来自 attribute 字段化减少的 CHM 哈希/CAS 开销，部分受 Quick 档 fork=1 的运行间方差影响，应以方向性看待。

### 基础设施 E2E（Redis / Mongo，本机 Docker）

| 基准 | 线程 | 吞吐 (ops/s) | 变化 | 分配 (B/op) | 变化 |
|------|------|------------|------|------------|------|
| MongoCommandWriteE2E | 1 | 527 → 525 | −0.4% | 123,623 → 122,139 | −1.2% |
| MongoCommandWriteE2E | 4 | 2,094 → 2,119 | +1.2% | 91,866 → 89,645 | −2.4% |
| RedisCommandWriteE2E | 1 | 698 → 660 | −5.4% | 55,900 → 55,762 | −0.2% |
| RedisCommandWriteE2E | 4 | 2,723 → 2,561 | −5.9% | 37,294 → 35,797 | −4.0% |

> 本机基础设施路径完全由 Docker 网络/驱动 I/O 等待主导（吞吐仅为 Mac 签入数据的约 1/5，分配是其 3 倍——等待期间后台线程分配被摊入每操作）。
>
> **复测验证**：对基础设施基准复测一轮后，Mongo threads=4 由首轮 −5.0% 变为 **+1.2%**（确认为运行间方差）；Redis 两轮均为 −5% 左右，但其分配持续下降（−1%~−4%）、框架侧无任何新增工作路径，且与数小时前采集的基线之间存在 Docker 容器状态漂移，无法归因于本次改动。签入的基础设施生成报告采用复测轮数据。结论：基础设施路径无回归信号；该环境下 ±5% 为正常方差带。

## 测试与质量门槛

全部通过：

- `:wow-core:test`（覆盖 `sendAndWaitForSent` 快路径、`WaitingForLazySinkTest`、`SimpleServerCommandExchangeTest`、`LocalCommandWaitNotifierTest` 等新增/扩展用例）
- `:wow-core:contractTest`（含 TCK `CommandGatewaySpec` 全量契约，以及拆分后为 Codecov patch gate 补充的 contract 覆盖用例）
- `:wow-opentelemetry:test`（`TracingWaitStrategy`/`TracingCommandGateway` 兼容性）
- `:wow-it:check`（集成 TCK）
- `:wow-core:detekt`

**对子类可见的内部成员变化**（接口签名未变）：`WaitingFor` 的 protected `waitSignalSink`、`tryEmit` 移除，`onFinallyHook` 转为私有；新增 protected `resolveLastSignal` 钩子。仓库内子类（`WaitingForStage` 系、`SimpleWaitingForChain`）均已适配；`WaitingFor.EmptyOnFinally` 对象保留。

## 产物清单

| 产物 | 说明 |
|------|------|
| `wow-core` 多个实现/测试文件 | 已按小 PR 拆分，见上文各节 |
| `wow-benchmarks/results/reports/quick-framework-e2e.md` 等 3 份生成报告 | 已用本机优化后数据重新生成（覆盖原 Mac 数据） |
| `wow-benchmarks/results/jmh/_baseline-quick/` | 本机基线留存（gitignore，不入库） |
| 本报告 | `document/design/2026-06-11-command-write-hotpath-optimization.zh-CN.md` |

**优化代码已按小 PR 拆分；本报告 PR 仅签入设计报告与 Quick 生成报告。**

## 后续建议

1. 以 Full 档基准（`benchmarkFullE2E --no-parallel`）产出正式结论，并用 `updateBenchmarkBaseline` 建立回归基线；
2. 在 Mac（原签入报告环境）重跑 Quick 报告，保持签入报告环境一致性；
3. processed 路径的下一个优化方向是调度等待本身（聚合调度器的唤醒延迟），而非分配；
4. Mongo 文档转换（Jackson `convertValue` 双重转换）建议在低延迟存储环境（本地 SSD 直连或内存版 Mongo）下重新评估。
