# Wow Benchmarks 分层重构设计

## 背景

`wow-benchmarks` 已经具备 JMH 基础设施、smoke 入口、framework E2E 入口、infrastructure E2E 入口和 diagnostics 入口。当前主要问题不是缺少 benchmark，而是 benchmark 的层级、命名、报告口径和性能结论来源还没有完全统一。

本设计目标是一次性把 `wow-benchmarks` 重构成长期可维护的性能基准体系：既能反映真实环境下的基准性能，又能快速定位 command write path 的性能瓶颈，并支持后续持续优化整体系统性能。

## 设计目标

1. 架构清洁，不保留临时分类、重复 catalog 或只靠文档解释的隐含规则。
2. 命名精准而简练，类名、任务名、报告名和目录名表达同一套层级语义。
3. 结构清晰，文件位置、Gradle 入口、报告分组和性能结论来源保持一致。
4. E2E benchmark 能反映真实 command write path 的基准性能，包含真实持久化和多线程场景。
5. Component benchmark 能解释 E2E 变慢或变快的原因，便于定位瓶颈。
6. Smoke benchmark 只用于 CI 快速发现 benchmark 入口损坏，不参与性能结论。
7. 快速反馈和完整结论使用同一套 benchmark catalog 的不同运行配置，避免语义漂移。

## 非目标

本次不改变 Wow 运行时公共 API，不改变 command/event 协议，不引入新的存储依赖，不调整 CI 发布流程。Redis 和 Mongo benchmark 仍依赖本地或 CI 显式提供的服务，不在 benchmark 模块内自动启动外部服务。

## 分层架构

### E2E Benchmark

E2E benchmark 用于判断框架性能目标是否达成。E2E 必须从真实入口进入，例如 command gateway 或 command dispatcher，并覆盖完整 command write path 中对用户可见的等待语义。

E2E 分为两类：

- Framework E2E：衡量框架自身 command write path 的基准能力。它可以包含 `NoopEventStore` ceiling 场景，但不能只依赖 ceiling 场景表达性能结论。
- Infrastructure E2E：衡量 Redis、Mongo 等真实持久化路径下的性能。它是反映真实环境基准性能的关键来源。

E2E 场景矩阵至少覆盖：

| 场景 | 目的 | 结论用途 |
| --- | --- | --- |
| Command write ceiling | 使用 noop 持久化测框架上限 | 判断框架内核开销 |
| Command write in-memory new aggregate | 使用内存事件存储和新聚合 | 判断本地完整写入开销 |
| Command write growing stream | 固定聚合、事件流增长 | 判断事件回放和增长流退化 |
| Command write Redis | 真实 Redis append/read | 判断 Redis 环境性能 |
| Command write Mongo | 真实 Mongo append/read | 判断 Mongo 环境性能 |

E2E 必须显式支持多线程场景。默认线程矩阵为 `1, 2, 4, 8`，并允许通过 Gradle 属性覆盖，例如 `-PbenchmarkThreads=1,4,8,16`。报告必须展示线程数，不能把不同线程数的结果混在同一行。

### Component Benchmark

Component benchmark 用于解释 E2E 结果，不用于单独声明框架性能达标。Component 的组织方式按 command write path 阶段，而不是按零散包名或历史文件名。

Component 分组如下：

| 分组 | 覆盖内容 |
| --- | --- |
| command-id | global id、aggregate id、request id |
| command-message | command message 创建、header 创建/读写 |
| validation | command validation |
| idempotency | request id 查重、BloomFilter/NoOp 对比 |
| aggregate-load | snapshot/event replay/state aggregate load |
| aggregate-handle | command aggregate 创建、handler lookup、command process |
| event-store | event stream append/load/last |
| event-publish | domain event bus、state event bus、event stream copy |
| wait-notify | wait strategy register、notify、local waiting |
| serialization | command payload、event stream serialization |
| pipeline | filter chain、aggregate/domain/state/processed notifier 阶段差异 |

当某个 E2E 场景回归时，报告应能指向最相关的 Component 分组。例如 in-memory new aggregate 回归时，优先查看 `aggregate-handle`、`event-store`、`event-publish`、`wait-notify`；growing stream 回归时，优先查看 `aggregate-load` 和 `event-store`。

### Smoke Benchmark

Smoke benchmark 用于 CI 快速发现以下问题：

- JMH jar 无法构建。
- Wow metadata 合并失败。
- SPI service 合并失败。
- 核心 benchmark 类无法启动。
- 关键 command write benchmark 入口已损坏。

Smoke 只使用短测量、单 fork、单线程，不输出性能结论，不更新 baseline，不参与性能比较。

## 运行配置

同一套 benchmark catalog 派生三类运行配置：

| 配置 | 目的 | 典型设置 | 结论用途 |
| --- | --- | --- | --- |
| smoke | CI 健康检查 | warmup=0, measurement=1x1s, fork=1, threads=1 | 入口是否可运行 |
| quick | 快速性能反馈 | warmup=1x3s, measurement=2x5s, fork=1, threads=1,4 | 初筛回归 |
| full | 正式性能结论 | warmup=3x10s, measurement=5x20s, fork=3, threads=1,2,4,8 | 性能目标和趋势 |

quick 和 full 必须选择同一批 E2E/Component benchmark 类，只允许 JMH 参数不同。这样快速反馈不会和正式结论产生两套语义。

## Gradle 任务

任务命名采用层级 + 配置 + 范围：

| 任务 | 说明 |
| --- | --- |
| `benchmarkSmoke` | CI benchmark 健康检查 |
| `benchmarkQuickE2E` | 快速 E2E 回归初筛 |
| `benchmarkFullE2E` | 正式 E2E 性能结论 |
| `benchmarkQuickComponent` | 快速 Component 瓶颈初筛 |
| `benchmarkFullComponent` | 正式 Component 瓶颈分析 |
| `benchmarkQuickInfrastructureE2E` | 快速 Redis/Mongo E2E 初筛 |
| `benchmarkFullInfrastructureE2E` | 正式 Redis/Mongo E2E 结论 |
| `benchmarkCompare` | 比较 E2E throughput、allocation、latency |
| `generateBenchmarkReport` | 生成 E2E 主报告 |
| `generateQuickBenchmarkReport` | 生成快速 E2E + Component 分组反馈报告 |
| `generateGroupedBenchmarkReport` | 生成正式 Full E2E + Component 分组报告 |
| `updateBenchmarkBaseline` | 更新正式 E2E baseline |

旧的 diagnostic task 应重命名为 Component 语义，并从主任务入口中移除。实现时必须同步更新文档和 CI 说明，不保留默认兼容 wrapper，避免旧入口继续传播。

## 命名规则

Benchmark 类名采用短而稳定的命名：

- E2E：`CommandWriteE2EBenchmark`、`RedisCommandWriteE2EBenchmark`、`MongoCommandWriteE2EBenchmark`
- Component：`CommandMessageComponentBenchmark`、`AggregateHandleComponentBenchmark`、`EventStoreComponentBenchmark`
- Smoke 不新增独立 benchmark 类，使用 catalog 中的代表性 benchmark 方法。

方法名必须表达具体操作和场景，例如：

- `sendAndWaitProcessed`
- `sendAndWaitProcessedForNewAggregate`
- `sendAndWaitProcessedWithGrowingStream`
- `appendSingleEventStream`
- `loadGrowingEventStream`

避免使用泛化词作为主要分类名，例如 `Diagnostic`、`Internal`、`Misc`、`PerfTest`。如果一个名字需要 README 才能解释清楚，说明名字不合格。

## 目录结构

目标结构按 benchmark 层级组织：

```text
wow-benchmarks/src/jmh/kotlin/me/ahoo/wow/benchmark/
  catalog/          Benchmark catalog、分组、include pattern
  fixture/          可复用数据和测试领域对象
  scenario/         E2E 和 Component 共享的真实运行场景
  e2e/              Framework E2E benchmark
  infrastructure/   Redis/Mongo E2E 和 component benchmark
  component/        按 command write path 阶段组织的 component benchmark
```

`fixture` 只能创建稳定、可复用的数据。`scenario` 负责搭建真实运行链路。benchmark 类只负责选择场景、调用操作、消费结果，不在 benchmark 方法中拼装复杂运行时。

## 报告与比较

报告必须清楚区分性能结论和瓶颈分析：

- Primary E2E Results：正式性能结论来源。
- Infrastructure E2E Results：真实持久化环境结论来源。
- Component Bottlenecks：解释 E2E 变化，不作为单独性能目标。
- Smoke Results：只显示入口健康，不显示为性能报告。

E2E benchmark 应同时采集吞吐和延迟。实现方式可以使用 JMH 多 mode，例如 throughput 加 average/sample time，也可以为同一场景生成独立 latency 运行配置；无论采用哪种方式，catalog 和场景定义只能有一份。

E2E 报告至少展示：

- benchmark 名称
- scenario
- thread count
- throughput
- throughput error
- latency 指标
- `gc.alloc.rate.norm`
- JVM、OS、CPU 架构、JMH 配置、日期、版本

`benchmarkCompare` 必须比较三类指标：

- throughput：低于 baseline 超过阈值视为回归。
- allocation：高于 baseline 超过阈值视为回归。
- latency：高于 baseline 超过阈值视为回归。

默认阈值为 throughput 下降 10%、allocation 上升 10%、latency 上升 10%，并允许通过 Gradle 属性覆盖。

## 多线程策略

多线程 benchmark 不应通过复制类实现，而应由 JMH `-t` 或任务配置控制。报告生成时必须把线程数作为结果维度解析和展示。

E2E 多线程用于衡量真实吞吐、等待语义和存储瓶颈。Component 多线程只用于并发敏感组件，例如 id 生成、event store、event bus、wait notify、Reactor sink。纯对象创建类 component 可以保留单线程，避免制造噪声。

## 错误处理

基础设施 benchmark 启动前继续检查 Redis/Mongo 端口。如果服务不可用，infrastructure 任务应快速失败并给出明确提示。Framework E2E、Component 和 Smoke 不应依赖 Redis/Mongo。

报告生成时，如果 required E2E 结果缺失，应失败；optional infrastructure/component 结果缺失时，应在 grouped report 中标记 unavailable，不应伪造空结果。

JMH JSON 解析必须验证 `benchmark`、`primaryMetric.score`、`scoreUnit` 等关键字段。无法解析的行应报告文件路径、group 和 row index。

## 测试与验证

重构完成后需要验证：

1. `./gradlew :wow-benchmarks:benchmarkSmoke --stacktrace`
2. `./gradlew :wow-benchmarks:benchmarkQuickE2E`
3. `./gradlew :wow-benchmarks:benchmarkQuickComponent`
4. `./gradlew :wow-benchmarks:generateQuickBenchmarkReport`
5. `./gradlew :wow-benchmarks:tasks --group benchmark`
6. `./gradlew :wow-benchmarks:detektJmh`

如果改动触及共享 fixture、scenario 或 Gradle catalog，还应运行 `./gradlew :wow-benchmarks:check`。

Redis/Mongo full benchmark 不作为默认本地验证命令，除非当前环境已明确启动 Redis 和 Mongo。

## 迁移策略

重构一次性完成，不分临时阶段交付。旧主入口、旧文档口径和旧报告分类必须同步移除或改名，不留下 deprecated wrapper 作为默认交付物。

迁移步骤：

1. 建立 benchmark catalog，统一定义 E2E、Component、Smoke include。
2. 调整目录和类名，使层级与命名一致。
3. 抽取 shared JMH run configuration，派生 smoke、quick、full。
4. 增加多线程配置和报告维度。
5. 将 diagnostics 重命名并收敛为 component。
6. 更新报告生成和 compare 逻辑。
7. 更新 `wow-benchmarks/README.md`、documentation benchmark 命令和 CI 说明。
8. 运行验证命令，确认 smoke、quick、report 和 task 列表全部可用。

## 验收标准

1. 没有 `Diagnostic` 作为主入口或主分类名。
2. E2E、Component、Smoke 的任务、目录、类名和报告分组语义一致。
3. E2E 报告能按场景和线程数展示 throughput、latency、allocation。
4. Full E2E 能覆盖 framework ceiling、in-memory、growing stream、Redis、Mongo 场景。
5. Quick E2E/Component 与 Full 使用同一 catalog，不存在重复维护的 benchmark 列表。
6. Component 分组能从 command write path 阶段解释 E2E 回归。
7. Smoke 通过，但 smoke 结果不会出现在性能结论报告中。
8. 文档中不再出现旧的误导性入口，完整性能分析必须指向分层 benchmark 任务。
9. 重构后 `git status` 中不包含生成 build output、JMH result output 或临时文件。
