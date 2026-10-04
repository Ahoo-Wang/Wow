---
title: 框架测试与基准
description: Wow 框架贡献者如何选择本地、契约、集成、覆盖率与 JMH 任务，并正确解释证据。
outline: deep
---

# 框架测试与基准

本页面只服务于 Wow 框架仓库：修改框架源码、TCK、Adapter、构建逻辑或基准时，用这里的任务收集证据。业务应用发布请使用[Wow 应用测试](./application-testing.md)，不要复制本仓库的根任务、Codecov flag 或 JMH 结论。

::: tip 完成信号
框架变更完成时，受影响模块的 `check` 以及对应测试层已通过；若涉及基准入口，smoke 已通过；若声称性能变化，还要有同一工作负载、可比环境和完整 provenance 的基线/确认结果。下一层是代码审查与 CI，不是把历史数字改写成产品承诺。
:::

## 先按依赖选测试层

| 层 | Source set | 根任务 | 运行条件 | 证明范围 |
| --- | --- | --- | --- | --- |
| Local | `src/test` | `allLocalTest` | 不需要容器 | 本地安全的单元、领域和组件行为 |
| Contract | `src/contractTest` | `allContractTest` | 不需要容器 | 已注册 TCK 实现满足共享契约 |
| Integration | `src/integrationTest` | `allIntegrationTest` | 需要 Docker/Testcontainers | 中间件 Adapter 与端到端集成 |

根构建当前只为 `:wow-core`、`:wow-opentelemetry` 和 `:wow-mock` 注册 `contractTest`；只为 `:wow-bi`、`:wow-mongo`、`:wow-redis`、`:wow-kafka`、`:wow-elasticsearch` 和 `:wow-it` 注册 `integrationTest`。不要为不存在的模块猜任务名。

`check` 运行标准 `test`，并在已配置的模块中包含 `contractTest`；它不会自动运行容器型 `integrationTest`。因此“`check` 绿色”不能推导出所有存储与 Broker 集成均已验证。

## 最窄的本地反馈

先运行直接受影响模块：

```bash
./gradlew :wow-core:check
./gradlew :wow-test:check :example-domain:check
```

需要整个本地安全层时再扩大：

```bash
./gradlew allLocalTest
./gradlew allContractTest
./gradlew check
```

领域规格仍使用[领域测试套件](./test-suite.md)中的 `AggregateSpec` 和 `SagaSpec`。它们位于所属模块的 `src/test`，属于 Local 层，不是单独的应用发布证明。

## 架构护栏

两类测试守护模块边界与装饰器，都属于 Local 层：

- `DependencyRulesTest`（`:wow-core:test`）扫描生产源码。`wow-core` 与 `wow-query` 不引用 HTTP、Spring 或存储驱动类型。后端模块（`wow-mongo`、`wow-redis`、`wow-kafka`、`wow-elasticsearch`）互不引用，也不引用其他后端的驱动。`wow-mongo` 与 `wow-elasticsearch` 在 `query` 包之外不引用查询包；`me.ahoo.wow.api.query` 中的协议类型允许使用。`test` 任务把被扫描的源码目录声明为输入，任一模块变化都会重新运行它。
- `MetricDecoratorContractTest`（`:wow-core:test`）与 `TracingDecoratorContractTest`（`:wow-opentelemetry:test`）要求每个 `Metric*`、`Tracing*` 装饰器覆盖 SPI 中所有带默认实现的成员。否则调用会落到接口默认实现，绕过被装饰对象，例如会加载整个事件流的 `EventStore.existsRequestId`。`ElasticsearchEventStoreContractTest` 对 Elasticsearch 事件存储的 `existsRequestId` 做同样检查。每个测试都有一份 `KNOWN_GAPS` 清单，自 9.2.3 起为空。新增缺口会失败，已修复但仍留在清单里的缺口也会失败；只有同时写明负责移除它的工作项，才能加入新条目。

## 线上格式黄金样本

v9 的线上格式已冻结：滚动升级期间，9.2.x 节点和更新的 9.x 节点共用主题、流、存储和命令等待端点，双方都必须能读懂对方写出的内容。黄金样本测试锁住这些字节：每个测试把固定样本（`me.ahoo.wow.tck.wire.WireSamples`）交给生产代码序列化，与模块 `src/test/resources/wire/v9/` 下提交的文件逐字节比较，再用生产代码的读取路径反序列化这个文件。

| 模块 | 测试 | 锁住的内容 |
| --- | --- | --- |
| `wow-core` | `WireFormatGoldenTest` | 命令消息 JSON（阶段等待和链式等待的消息头）、领域事件流 JSON、状态事件 JSON |
| `wow-kafka` | `KafkaWireFormatGoldenTest` | 命令、事件、状态事件总线的 Kafka 记录：主题、分区、时间戳、键、记录头和值 |
| `wow-redis` | `RedisWireFormatGoldenTest` | 命令、事件、状态事件总线的 Redis 流键和条目字段 |
| `wow-spring-boot-starter` | `WaitSignalWireFormatGoldenTest` | 发往远端命令等待端点的 `WaitSignal` 请求体 |

样本带有运行时实际写入的消息头：`command_operator`、`user_agent`、`remote_ip`、`local_first`、`trace_id`、`upstream_*`，以及由真实等待计划写入的 `command_wait_*`。这些样本由 9.2.2 生成。黄金样本测试失败说明线上格式变了，不要为了让它通过而重新生成文件。修改黄金样本需要先有覆盖混部集群的设计决定；决定之后，用 `WOW_GOLDEN_UPDATE=true` 重新运行测试并审阅差异。

## 容器型集成测试

运行全部已注册集成任务：

```bash
./gradlew allIntegrationTest --stacktrace
```

也可以只运行受影响 Adapter：

```bash
./gradlew :wow-mongo:integrationTest --stacktrace
./gradlew :wow-redis:integrationTest --stacktrace
./gradlew :wow-kafka:integrationTest --stacktrace
./gradlew :wow-elasticsearch:integrationTest --stacktrace
./gradlew :wow-it:integrationTest --stacktrace
```

这些任务依赖 Docker/Testcontainers，并有意不挂到 `check`。`:wow-it` 验证 Wow 仓库内的集成组合，不能替代某个业务应用的配置、协议、恢复与安全门禁。

### 新旧版本混部测试

`:wow-it` 中的 `MixedVersionClusterTest` 检查滚动升级：已发布的示例服务镜像和由当前源码构建的示例服务共用一个 Kafka 和一个 MongoDB，加入同样的消费组，双方都必须能处理对方发出的内容。测试关闭本地优先路由，并按分区归属挑选聚合 ID，因此两个方向都会覆盖：

- 发给一个版本的命令由另一个版本处理，`PROCESSED` 等待结果回到发送方；
- 一个版本追加的状态事件由另一个版本生成快照，`SNAPSHOT` 等待跨版本返回；
- 一个版本上的 Saga（`CartSaga`）处理另一个版本追加的领域事件；
- 两个版本读到相同的聚合状态和事件流。

只有环境变量同时指明两个节点时测试才运行，所以 `allIntegrationTest` 会跳过它。`Mixed-Version` 工作流在拉取请求改动线上格式相关模块（`wow-api`、`wow-core`、`wow-kafka`、`wow-redis`、`wow-mongo`、`wow-webflux`、`wow-spring`、`wow-spring-boot-starter`）、示例服务、依赖版本或测试本身时运行它，并拉取已发布镜像（工作流中的 `PREVIOUS_IMAGE`）。如果只想在本地检查测试框架而不拉取该镜像，可以用当前构建的两份副本运行：

```bash
./gradlew :example-server:installDist
home=$PWD/example/example-server/build/install/example-server
WOW_MIXED_CURRENT_HOME=$home WOW_MIXED_PREVIOUS_HOME=$home \
  ./gradlew :wow-it:integrationTest --tests 'me.ahoo.wow.it.mixed.MixedVersionClusterTest'
```

把 `WOW_MIXED_PREVIOUS_HOME` 换成 `WOW_MIXED_PREVIOUS_IMAGE` 即可像 CI 一样运行已发布镜像。示例服务没有 Redis 总线，所以 Redis 由线上格式测试覆盖，不在这个集群里。

## 覆盖率是分层证据

当前聚合与分层报告任务为：

```bash
./gradlew codeCoverageReport
./gradlew :code-coverage-report:localCoverageReport
./gradlew :code-coverage-report:contractCoverageReport
./gradlew :code-coverage-report:integrationCoverageReport
./gradlew :example-domain:jacocoTestCoverageVerification
```

聚合 XML 输出到：

```text
test/code-coverage-report/build/reports/jacoco/codeCoverageReport/codeCoverageReport.xml
```

分层报告分别位于同名 `localCoverageReport`、`contractCoverageReport` 和 `integrationCoverageReport` 目录。PR 工作流以 `local`、`contract`、`integration` flag 分别上传；`main` 或手动触发的 `Codecov` 工作流用 `codeCoverageReport` 上传 `full` flag。

`:example-domain`、`:example-transfer-domain` 和 `:wow-compensation-domain` 当前各自配置 `0.8` 的 Jacoco verification 下限。该阈值仅在显式运行对应的 `jacocoTestCoverageVerification` 任务时执行；这些模块的 `check` 与当前 CI 工作流都没有自动挂载 verification task。它是可选择执行的仓库门禁，不是 Wow 对业务应用覆盖率的保证。覆盖率只表示执行过代码，不能替代事件、状态、拒绝和恢复断言。

## 基准分三种用途

| 用途 | 入口 | 可以得出的结论 |
| --- | --- | --- |
| Smoke | `benchmarkSmoke` | 选定 JMH jar 与路径可以编译、启动并完成 |
| Quick | `benchmarkQuick*` | 当前机器上的有边界回归线索 |
| Baseline / confirmation | `benchmarkBaseline*`、`benchmarkConfirm*` | 在匹配方法、参数、fork 与环境下可比较的证据 |

Smoke 不是性能报告，Quick 不是生产容量模型，隔离组件结果也不是框架端到端吞吐承诺。

### PR 安全检查

```bash
./gradlew :wow-benchmarks:test :wow-benchmarks:benchmarkSmoke --stacktrace
```

这与当前 `Benchmark Smoke` CI 工作流一致。根别名也可用：

```bash
./gradlew benchmarkSmoke
```

完成信号是选定路径执行成功，不是产生或更新性能基线。

### 快速回归与诊断

框架 E2E 快速报告：

```bash
./gradlew :wow-benchmarks:benchmarkQuickE2E \
  :wow-benchmarks:generateBenchmarkReport
```

批量命令写入的成对工作负载：

```bash
./gradlew :wow-benchmarks:benchmarkQuickBatchE2E \
  :wow-benchmarks:generateBatchBenchmarkReport
```

需要定位瓶颈时，按层选择任务，而不是一次跑完整 catalog：

```bash
./gradlew :wow-benchmarks:benchmarkQuickComponent
./gradlew :wow-benchmarks:benchmarkQuickWebFlux -PbenchmarkQuickWebFluxThreads=1
./gradlew :wow-benchmarks:benchmarkQuickInfrastructureE2E
```

WebFlux suite 不启动真实 Netty server。当前 `benchmarkQuickInfrastructureE2E` 同时包含 Redis 与 Mongo workload，因此本地 Redis 和 MongoDB 都是 required services，缺少任一服务都不满足该 suite 的运行条件。报告必须保留工作负载、线程、JVM、服务和源码 provenance，不能把层间数字直接横向解释。

改动框架热路径的工作，在同一台机器上对重构目录跑两次并分别加标签：改动前一次，改动后一次。`benchmarkQuickRefactorHotPaths` 不需要外部服务，覆盖事件分发（1 个与 8 个处理器、指标开与关、内存总线与本地优先总线）、本地优先的命令发送、命令 HTTP 入口（请求头 appender、整路由分发）、每条命令的聚合处理，以及事件流查询的结果交付。`benchmarkQuickRefactorTransport` 测 Redis Streams 的接收与确认，需要基准用的 Redis。Kafka 的接收与确认（`KafkaEventReceiveAckBenchmark`）不在 Gradle suite 中，因为基准还没有 Kafka 服务；用 JMH jar 对一个 broker 运行。

```bash
./gradlew :wow-benchmarks:benchmarkQuickRefactorHotPaths \
  :wow-benchmarks:generateQuickRefactorHotPathsBenchmarkReport \
  -PbenchmarkRefactorReportLabel=9.2.2-baseline
```

### 正式回归证据

对精确的框架 E2E 工作负载建立可比较证据：

```bash
./gradlew :wow-benchmarks:benchmarkBaselineE2E --no-parallel
./gradlew :wow-benchmarks:benchmarkCompare
```

`benchmarkCompare` 的阈值越界只是回归或改进候选。用相同 JVM、线程、参数、fork、预热、测量和 profiler 对受影响方法运行 `benchmarkConfirmE2E` 后，才能形成确认结论。

`updateBenchmarkBaseline` 只接受当前 clean `HEAD` 产生的 clean manifest。不要在脏工作树、不同服务配置或缺少 manifest 时更新基线。

## 如何读取历史报告

`wow-benchmarks/results/reports/` 中的报告绑定于生成它们的源码、运行规格、机器、JVM 和服务配置。它们可以作为限定条件下的历史证据或调查起点，但不是跨版本、跨机器、跨存储的普适承诺。

遵守三条规则：

1. 不手工改报告行或 frontier JSON，使用对应生成任务；
2. 不用 Quick 点估计宣称正式吞吐变化；
3. 不用组件或模拟 I/O 结果宣称生产端到端容量。

## CI 与本地证据对应关系

| 工作流 | 当前命令 |
| --- | --- |
| `Local Test` | `allLocalTest` + `localCoverageReport` |
| `Contract Test` | `allContractTest` + `contractCoverageReport` |
| `Integration Test` | `allIntegrationTest` + `integrationCoverageReport` |
| `Mixed-Version` | `:example-server:installDist` + 使用已发布镜像运行 `:wow-it:integrationTest --tests 'me.ahoo.wow.it.mixed.MixedVersionClusterTest'` |
| `Benchmark Smoke` | `:wow-benchmarks:test` + `:wow-benchmarks:benchmarkSmoke` |
| `Codecov` | `codeCoverageReport` |

本地验证应按变更风险选择这些层。CI 只是另一环境中的新证据；本地通过、CI 通过、应用发布和生产验证仍是不同完成条件。
