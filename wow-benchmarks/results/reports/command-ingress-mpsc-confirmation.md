# Command Ingress MPSC 优化基准确认报告

## 摘要

> Evidence status: **CONFIRMED_CLEAN**（command ingress 配对 A/B）。t1/t4 manifest
> 记录了 `source.dirty=false`、同一 commit、同一 JMH JAR SHA-256 和同一 run ID。
> Scheduler pool 与 simulated-I/O 章节来自更早的诊断运行，只用于解释默认配置为何不变，
> 不承担 command ingress 收益验收。

本报告验证 `InMemoryCommandBus` 将命令入口从 `ConcurrentManySink` 的
`ReentrantLock` 串行化切换为原子 MPSC admission 后的吞吐量收益。

本轮 clean confirmation 使用同一个 JMH JAR 内的配对 A/B：

- `current-production`：当前默认 `InMemoryCommandBus`，使用 `MpscUnicastManySink`；
- `legacy-lock`：显式注入旧版 unsafe unicast sink，并用 `ConcurrentManySink` 包装。

四线程结果从 `161,912.38 ± 2,576.38 ops/s` 提升到
`200,570.23 ± 2,724.71 ops/s`，点估计提升 **23.88%**。两组 JMH error
区间不重叠，且规范化分配量只增加 **8.84 B/op（0.23%）**。

clean run 达到以下预设阈值：

| 验收项 | 标准 | 结果 | 判定 |
|---|---:|---:|---|
| 四线程吞吐量 | 提升至少 10% | +23.88% | PASS |
| 测量区分度 | JMH error 区间不重叠 | `[197,845.52, 203,294.94]` vs `[159,336.00, 164,488.76]` | PASS |
| 分配回归 | 不超过 1% | +0.23% | PASS |
| 正确性 | 相关 test/check 全部通过 | 后续章节记录 | PASS |

## 优化范围

旧实现会在 `ConcurrentManySink.tryEmitNext` 外层持有 `ReentrantLock`，多调用线程向
同一个本地命令总线发送命令时形成入口锁竞争。优化后的 MPSC sink：

1. 使用 Reactor `Queues.unboundedMultiproducer` 接收多生产者 offer；
2. 使用一个 `AtomicLong` 管理 active admission，以及 terminal/cancellation 的 claim、delegation、settlement 与 close 线性化；
3. terminal/cancellation claim 后立即拒绝新值；物理控制信号等待已 admission 的 `onNext` 返回，并在所有已委派路径返回后才完成 close settlement；
4. 用 opaque `Flux`/`Subscription` 隐藏 factory-owned queue，阻止下游直接 fusion 或暴露 raw sink；
5. 保留自定义 sink 的兼容行为：非 MPSC sink 仍由 `ConcurrentManySink` 包装。
6. close 期间不再暴露待结算 sink 的旧 subscriber；普通 sink 的 terminal callback 抛错时，
   仅在 `Scannable` 已确认终止或取消后移除，保留未接受 terminal 的重试能力。

### Reactor Sink 语义兼容

仓库和 Reactor API 中都不存在 `Skink` 类型；本轮将该要求解释为 Reactor
`Sinks.Many` 语义兼容。项目解析到的精确版本为 `reactor-core:3.8.6`，审计依据是对应
sources JAR 中的 `Sinks`、`InternalManySink` 与 `SinkManyUnicast` 实现，并使用同一个
MPSC queue 分别构造原生 unicast sink 与 Wow sink 做差分测试。

差分测试覆盖：

- 订阅前 warmup buffer、按 demand 排空以及 buffer 后延迟 terminal；
- 单订阅限制、late subscriber 与 terminal 后立即 cancel；
- `tryEmit*` 的 `FAIL_CANCELLED`/`FAIL_TERMINATED`，以及 `emit*` 的 drop/discard；
- error replay、callback 异常触发的取消，以及两个已 admission producer 的竞态；
- `ACTUAL`、`inners()`、`BUFFERED`、`CAPACITY`、`PREFETCH`、`CANCELLED`、
  `TERMINATED`、`ERROR` 和 `asFlux()` 视图扫描。

审计中实际复现并修复了四类差异：close settled 后的 late cancel 未传到 raw
subscription、`ACTUAL/inners()` 暴露内部 subscriber、逻辑取消到物理 cancel 之间仍可能
向下游传值、`asFlux()` 未转发安全 `Scannable` 属性。最终差分套件 `16/16` 通过。

fusion、`Fuseable.QueueSubscription`、实现类身份和默认 `stepName` 有意不与
`SinkManyUnicast` 对齐：`OpaqueFlux`/`OpaqueSubscription` 必须阻止外部取得并直接
`poll` factory-owned MPSC queue；这些不属于 `Sinks.Many` 的公开信号契约。

生产命令调度仍保留：

```text
groupBy(hash % parallelism)
  -> flatMap(concurrency = parallelism)
  -> publishOn(aggregateScheduler)
  -> concatMap(handleExchange)
```

本轮没有将生产默认改为 `Schedulers.immediate()`，也没有共享所有聚合类型的
Scheduler；`publishOn` 继续承担调用线程隔离。

## 基准方法

### 配对 A/B 场景

`CommandIngressE2EDiagnosticBenchmark.sendAndWaitProcessed` 使用真实的：

- `CommandGateway.sendAndWaitForProcessed`；
- `InMemoryCommandBus`；
- `CommandDispatcher` 和 `groupBy -> publishOn -> concatMap`；
- aggregate processor、domain/state event bus 和 processed notifier。

为了放大框架入口开销并避免 I/O 混淆，场景使用 `NoopEventStore`、
`NoOpValidator` 和 `NoOpIdempotencyChecker`。每次 invocation 创建新的 aggregate ID，
并在调用线程上等待 `PROCESSED` 结果。`@State(Scope.Benchmark)` 让所有 JMH 线程共享
同一个 dispatcher 和 command bus，因此 t4 能直接测量多生产者入口竞争。

### JMH 配置

| 项 | 值 |
|---|---|
| Project version | `8.9.1` |
| Source commit | `060a2ea297cda413d87da673375a450ce9eaada5` |
| Source dirty | `false` |
| JMH JAR SHA-256 | `30cb80352ed56f959179629274b8e928ef0056d6c9c285c4f2ee40b6248b84f2` |
| JVM | OpenJDK 17.0.7+7-LTS |
| OS | Mac OS X 26.5.2 aarch64 |
| CPU | 14 available processors |
| Memory | 24 GiB |
| Warmup | `2 × 3s` |
| Measurement | `3 × 5s` |
| Forks | 2 |
| Mode | `thrpt` |
| JVM args | `-Xmx4g -Xms4g -XX:+UseG1GC -XX:+AlwaysPreTouch` 及 profiling 辅助参数 |
| Profiler | `gc` |

同一个 run ID `8d49668d-386d-414b-a68e-89f8fa49ac46` 依次运行 t1 和 t4。

## 配对 A/B 结果

### Throughput

| Threads | Strategy | Score | JMH Error | Error interval |
|---:|---|---:|---:|---:|
| 1 | `current-production` | 94,194.12 ops/s | ±1,237.31 | `[92,956.82, 95,431.43]` |
| 1 | `legacy-lock` | 93,788.72 ops/s | ±1,634.11 | `[92,154.61, 95,422.84]` |
| 4 | `current-production` | **200,570.23 ops/s** | ±2,724.71 | `[197,845.52, 203,294.94]` |
| 4 | `legacy-lock` | 161,912.38 ops/s | ±2,576.38 | `[159,336.00, 164,488.76]` |

- t1 点估计为 `+0.43%`，error 区间重叠，单线程吞吐没有可区分变化；
- t4 点估计为 **+23.88%**，且 error 区间不重叠，是本轮主要收益证据；
- `current-production` 的 t4/t1 扩展比为 `2.13×`，`legacy-lock` 为 `1.73×`。

### Allocation

| Threads | Strategy | `gc.alloc.rate.norm` | JMH Error |
|---:|---|---:|---:|
| 1 | `current-production` | 3,937.38 B/op | ±0.26 |
| 1 | `legacy-lock` | 3,913.37 B/op | ±0.37 |
| 4 | `current-production` | 3,928.22 B/op | ±0.39 |
| 4 | `legacy-lock` | 3,919.38 B/op | ±0.51 |

t4 每条命令增加 `8.84 B/op`，即 `0.23%`；验收使用与主要吞吐结论相同的 t4 数据。

### 原始证据

| Threads | Result SHA-256 | Manifest SHA-256 |
|---:|---|---|
| 1 | `df9047ae1ad6533f2d4cd327a6526ab5197199068ecb7dbf8c9734835aea0531` | `7a180d1d2f3f169807b57375fdf7d2489a7f1155f3af215c0594d94a0bd4be09` |
| 4 | `0c8784b7b248c1dd79898eaccc45a531915bce7aaeee64b7b5078642a592bc5f` | `6a858cbd267d5ecaec5b93dda17a9e095d35de71734f7351abb172c8508572ed` |

本地原始文件位于：

```text
wow-benchmarks/results/jmh/confirmation/framework-e2e/
```

该目录按仓库规则被 `.gitignore` 排除；上表 hash 用于确认报告对应的原始 JSON 和
SUCCESS manifest。manifest 记录的 clean source commit 和 JMH JAR hash 可由复现命令验证。

## 历史生产基准复跑

原有 `CommandWriteE2EBenchmark.sendAndWaitProcessed` 曾在生产默认
`scenario=ceiling;schedulerStrategy=PARALLEL` 下复跑成功：

| Threads | Throughput | Allocation |
|---:|---:|---:|
| 1 | 91,632.93 ± 1,149.23 ops/s | 3,937.47 ± 0.51 B/op |
| 4 | 185,408.07 ± 26,230.25 ops/s | 3,928.55 ± 1.00 B/op |

该历史 dirty 复跑验证默认生产路径可执行且结果量级与配对 benchmark 一致；由于它早于
最终状态机修复、且没有在同一个 JAR 中包含旧实现，不承担最终正确性或收益因果归因，
收益确认仍以本报告最新配对 A/B 为准。

原始 result SHA-256：

- t1：`34b9688665f77658dc655d92d4c4e9a9dcaf1a4bc25b5ef372bcb68cbd135f80`
- t4：`956a34b51c1308e8c46dea683d2e48b1a899086e4e412117fe9318428bbc1478`

## Scheduler Pool 评估

### Ceiling 场景扫描

为了区分入口锁优化与 Scheduler 配置，保持 `current-production` MPSC、16 个 JMH
调用线程和同一命令链，只改变每个 aggregate Scheduler 的 pool size：

| Pool size | Throughput | JMH Error | Relative to CPU=14 |
|---:|---:|---:|---:|
| 1 | 206,563.93 ops/s | ±33,566.72 | -7.91% |
| 2 | 258,860.44 ops/s | ±59,646.81 | +15.41% |
| 4 | **315,041.62 ops/s** | ±54,909.81 | **+40.45%** |
| CPU=14 | 224,303.57 ops/s | ±7,230.12 | baseline |

pool size 4 的区间 `[260,131.81, 369,951.43]` 与 CPU=14 的
`[217,073.45, 231,533.69]` 不重叠。不过该结论仅适用于 16 个 closed-loop 调用线程、
Noop store 和短 CPU 路径；真实 Mongo/Redis I/O、CPU-heavy command function、热点分布、
连接池限制以及多 aggregate 类型尚未覆盖，因此本轮没有修改生产 Scheduler 默认值。

Scheduler sweep run ID：`e2d5dfa7-b1a5-458f-ae32-b4cf469a128a`，result SHA-256：
`9ab9a5f9def8397b2af22e2ca24c8e9a689233007e055f4f2a5bfdb7be41f4c4`。

### 可控模拟 I/O 矩阵

为了验证 ceiling 结论是否会随 I/O 延迟变化，`SimulatedIoCommandWriteBenchmark`
新增 `schedulerPoolSize` 参数，使用 `PARALLEL` 策略和 16 个 closed-loop JMH 调用线程，
配对比较 pool size 4 与本机 CPU=14。历史 `0` 行实际使用直接 `InMemoryEventStore`，非零行
使用通过 `delaySubscription` 增加异步 timer handoff 的 `DelayEventStore`；因此 `0` 行只能作为
direct ceiling，不能与非零行组成拓扑完全一致的延迟曲线。

| I/O delay | Pool 4 throughput | CPU=14 throughput | Relative | Error interval overlap | Pool 4 allocation delta |
|---:|---:|---:|---:|---:|---:|
| `direct`（历史参数 `0`） | 285,487.87 ± 10,252.47 ops/s | 208,519.11 ± 7,811.06 ops/s | **+36.91%** | No | +10.57 B/op (+0.23%) |
| `20us` | 132,016.24 ± 5,675.27 ops/s | 104,171.01 ± 1,649.26 ops/s | **+26.73%** | No | +32.45 B/op (+0.45%) |
| `100us` | 81,240.10 ± 575.98 ops/s | 79,395.92 ± 456.04 ops/s | +2.32% | No | -17.67 B/op (-0.24%) |
| `500us` | 18,196.00 ± 382.72 ops/s | 18,164.05 ± 384.93 ops/s | +0.18% | **Yes** | +32.65 B/op (+0.43%) |

关键区间：

- `direct`：pool 4 `[275,235.41, 295,740.34]`，CPU=14 `[200,708.05, 216,330.18]`；
- `20us`：pool 4 `[126,340.97, 137,691.51]`，CPU=14 `[102,521.75, 105,820.26]`；
- `100us`：pool 4 `[80,664.12, 81,816.08]`，CPU=14 `[78,939.88, 79,851.96]`；
- `500us`：pool 4 `[17,813.29, 18,578.72]`，CPU=14 `[17,779.11, 18,548.98]`。

矩阵说明 Scheduler pool 的影响有明确边界：direct ceiling 与 `20us` 异步链路中，14 个 rail
的调度与队列协调成本高于 4 个 rail；在相同 DelayEventStore 拓扑内，当每次 EventStore
operation 的配置延迟增加到 `500us`
时，pool size 差异无法区分，结果与 I/O 成本开始主导一致。因此，**不把生产默认从 CPU 改为
4** 是当前证据下更合理的选择：固定为 4 会过拟合这台 14 核机器上的短链路，
尚未验证 CPU-heavy command、真实 Mongo/Redis、多 aggregate 类型竞争与生产延迟分布。

本次矩阵 run ID：`52d83882-e413-414c-acdf-9b3c1426c189`。

| Artifact | SHA-256 |
|---|---|
| JMH JAR | `c6f7315de84263382c4a4bc0c04f501ec658fbc34d7e331885aaaa415d35648e` |
| Result JSON | `a65f5bc545e2199bb177d75da97005ec61455c3d2c4992f8715d1eed34bc810e` |
| Human output | `ac6d0a68eb4f94a1ef5ff5a8ea41cbfd80e9a9b6be09b6c910c447ff287fe2e0` |
| SUCCESS manifest | `ba6cdcdc791cca023f488d01b6cdb9765f8d47b3569d71ae5b1cb5f23d5ff68b` |

原始文件保存在被 `.gitignore` 排除的：

```text
wow-benchmarks/results/jmh/confirmation/scheduler-pool-simulated-io-e2e/
```

## 正确性验证

实现与回归过程中新增并发、terminal、取消和 Reactor 差分语义测试；最终树运行：

```bash
./gradlew :wow-core:detekt :wow-core:test :wow-core:contractTest \
  :wow-benchmarks:check :wow-benchmarks:jmhJar \
  --no-parallel --console=plain
```

结果：

| Suite | Tests | Failures |
|---|---:|---:|
| `wow-core:test` | 808 | 0 |
| `wow-core:contractTest` | 128 | 0 |
| `wow-benchmarks:test` | 13 | 0 |
| Total | 949 | 0 |

`wow-core:detekt`、核心单测/契约测试、benchmark check、JMH compilation 和
`git diff --check` 同时通过。

专项测试覆盖：

- 四生产者 exactly-once 与每生产者内部顺序；
- complete/error 竞争只允许一个 winner；
- active `onNext` 与 terminal、cancel、close 的竞态；
- terminal/cancel 物理委派返回、双控制路径 settlement 与 close CAS 线性化；
- exceptional close settlement 后移除终止 sink 并允许同 aggregate 重建；
- async close 期间隐藏旧 subscriber，避免 `LocalFirstMessageBus` 错误标记消息已本地处理；
- 普通 Reactor sink 的 terminal callback 抛错后移除已终止实例，未接受 terminal 时保留重试；
- late subscriber、第二订阅者、overflow、drop/discard 和 retry handler；
- Reactor native/custom 差分语义、callback failure cancellation 和 Flux scan；
- terminal claim/delegation 位状态不变量；
- 自定义 sink 继续使用 `ConcurrentManySink`。

## 复现命令

### 入口配对 A/B

```bash
./gradlew :wow-benchmarks:benchmarkConfirmE2E \
  -PbenchmarkConfirmE2EThreads=1,4 \
  '-PbenchmarkConfirmE2EIncludes=me.ahoo.wow.benchmark.e2e.CommandIngressE2EDiagnosticBenchmark.sendAndWaitProcessed' \
  '-PbenchmarkConfirmE2EParameters=ingressStrategy=current-production,legacy-lock;schedulerPoolSize=cpu' \
  --no-parallel --console=plain
```

### Scheduler pool 扫描

```bash
./gradlew :wow-benchmarks:benchmarkConfirmE2E \
  -PbenchmarkConfirmE2EThreads=16 \
  '-PbenchmarkConfirmE2EIncludes=me.ahoo.wow.benchmark.e2e.CommandIngressE2EDiagnosticBenchmark.sendAndWaitProcessed' \
  '-PbenchmarkConfirmE2EParameters=ingressStrategy=current-production;schedulerPoolSize=1,2,4,cpu' \
  --no-parallel --console=plain
```

### 生产默认基准

```bash
./gradlew :wow-benchmarks:benchmarkConfirmE2E \
  -PbenchmarkConfirmE2EThreads=1,4 \
  '-PbenchmarkConfirmE2EIncludes=me.ahoo.wow.benchmark.e2e.CommandWriteE2EBenchmark.sendAndWaitProcessed' \
  '-PbenchmarkConfirmE2EParameters=scenario=ceiling;schedulerStrategy=PARALLEL' \
  --no-parallel --console=plain
```

### Scheduler pool 模拟 I/O 矩阵

```bash
./gradlew :wow-benchmarks:benchmarkConfirmE2E \
  -PbenchmarkConfirmE2EThreads=16 \
  '-PbenchmarkConfirmE2EIncludes=me.ahoo.wow.benchmark.e2e.SimulatedIoCommandWriteBenchmark.sendAndWaitProcessed' \
  '-PbenchmarkConfirmE2EParameters=ioDelay=direct,async-0,20us,100us,500us;schedulerStrategy=PARALLEL;schedulerPoolSize=4,cpu' \
  --no-parallel --console=plain
```

clean rerun 的前置条件是 `git status --porcelain` 为空、实现位于可追溯 commit，
且 t1/t4 manifest 的 commit 与 JMH JAR SHA-256 完全一致。本轮 confirmation 已满足这些
条件。以上命令会使用同一个
`framework-e2e` 输出文件名，必须严格串行运行，并在下一组运行前保存 JSON、human output
和 SUCCESS manifest。当前 Gradle task 会在 JMH 取得全局锁前清理目标文件，不能并发执行
同一 suite/profile/thread 组合。

## 适用边界与后续工作

1. 收益适用于通过本地 `InMemoryCommandBus` 进入 dispatcher 的并发命令；远程 transport
   或 I/O 主导路径的相对收益可能更低。
2. MPSC 仍使用 unbounded queue，与旧实现的 backpressure buffer 语义相同，没有新增容量上限。
3. 本报告是 throughput + allocation confirmation，不包含 p95/p99 latency、context switch、
   queue depth 和真实 Mongo/Redis 数据。
4. 历史数据中 pool size 4 在 `direct/20us/100us` 中优于 CPU=14，但收益在 `500us`
   时降至无法区分；`async-0` 已加入基准以统一异步拓扑，但尚待 clean rerun。这证明不存在
   由当前基准支持的通用 pool size 新默认。后续应在真实
   工作负载下评估 Wow 专用 `schedulerPoolSize`/`stripeCount` 配置，避免依赖全局
   Reactor 系统属性。
5. 基准基础设施应将输出清理移到全局 JMH 锁之后，或使用 run-scoped 临时文件再原子发布，
   避免并发运行破坏旧证据。
6. command ingress 配对 A/B 已由 clean source confirmation 验收；Scheduler sweep 和
   simulated-I/O 矩阵仍是诊断数据。若未来修改热路径或 Reactor 版本，必须重新运行配对 A/B
   与语义差分套件。

## 变更产物

| 文件 | 作用 |
|---|---|
| `wow-core/.../infra/sink/MpscUnicastManySink.kt` | 原子 MPSC admission、控制路径结算状态机及 opaque publisher |
| `wow-core/.../command/InMemoryCommandBus.kt` | 默认使用 MPSC sink |
| `wow-core/.../messaging/InMemoryMessageBus.kt` | MPSC close settlement 协调、精确移除及失败保留 |
| `wow-core/.../infra/sink/MpscUnicastManySinkTest.kt` | sink 并发、终止及协议测试 |
| `wow-core/.../infra/sink/MpscUnicastManySinkSemanticsTest.kt` | Reactor 3.8.6 native/custom 差分语义测试 |
| `wow-core/.../command/InMemoryCommandBusAtomicSinkTest.kt` | command bus 集成并发测试 |
| `wow-benchmarks/.../CommandIngressE2EDiagnosticBenchmark.kt` | 同 JAR 入口 A/B 与 pool sweep |
| `wow-benchmarks/.../BenchmarkAggregateSchedulerSupplier.kt` | benchmark-only pool size 参数 |
| `wow-benchmarks/.../SchedulerStrategies.kt` | 为 PARALLEL benchmark supplier 传递 pool size |
| `wow-benchmarks/.../SimulatedIoCommandWriteBenchmark.kt` | 增加 `schedulerPoolSize` 维度，验证 I/O 延迟边界 |
| 本报告 | 收益验收、原始证据 hash、复现命令和适用边界 |
