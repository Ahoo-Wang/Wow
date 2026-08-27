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

## 覆盖率是分层证据

当前聚合与分层报告任务为：

```bash
./gradlew codeCoverageReport
./gradlew :code-coverage-report:localCoverageReport
./gradlew :code-coverage-report:contractCoverageReport
./gradlew :code-coverage-report:integrationCoverageReport
```

聚合 XML 输出到：

```text
test/code-coverage-report/build/reports/jacoco/codeCoverageReport/codeCoverageReport.xml
```

分层报告分别位于同名 `localCoverageReport`、`contractCoverageReport` 和 `integrationCoverageReport` 目录。PR 工作流以 `local`、`contract`、`integration` flag 分别上传；`main` 或手动触发的 `Codecov` 工作流用 `codeCoverageReport` 上传 `full` flag。

`:example-domain`、`:example-transfer-domain` 和 `:wow-compensation-domain` 当前各自配置 `0.8` 的 Jacoco verification 下限。这是这些模块当前的仓库门禁，不是 Wow 对业务应用覆盖率的保证。覆盖率只表示执行过代码，不能替代事件、状态、拒绝和恢复断言。

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

WebFlux suite 不启动真实 Netty server；Infrastructure suite 需要对应的本地 Redis/Mongo 等服务。报告必须保留工作负载、线程、JVM、服务和源码 provenance，不能把层间数字直接横向解释。

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
| `Benchmark Smoke` | `:wow-benchmarks:test` + `:wow-benchmarks:benchmarkSmoke` |
| `Codecov` | `codeCoverageReport` |

本地验证应按变更风险选择这些层。CI 只是另一环境中的新证据；本地通过、CI 通过、应用发布和生产验证仍是不同完成条件。
