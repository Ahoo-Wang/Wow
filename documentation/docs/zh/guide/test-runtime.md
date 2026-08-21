---
title: 测试运行体系
description: 如何运行 Wow 的本地测试、契约测试、集成测试、覆盖率与基准 smoke。
---

# 测试运行体系

Wow 按运行时依赖拆分测试，让本地检查保持快速，并让容器依赖场景显式运行。

## 测试分层

| 分层 | Source set | 根任务 | 运行时依赖 |
| --- | --- | --- | --- |
| 本地测试 | `src/test` | `allLocalTest` | 本地安全的框架、扩展、领域和服务端测试。 |
| 契约测试 | `src/contractTest` | `allContractTest` | 本地安全的 TCK 实现者测试。 |
| 集成测试 | `src/integrationTest` | `allIntegrationTest` | 基于 Testcontainers 的中间件和端到端测试。 |

`check` 运行本地安全的验证任务：标准 `test` 任务以及已配置的契约测试。它不会启动 Docker 容器。

标准 `src/test` 执行统一使用 `allLocalTest` 根任务。

## 本地快速检查

```bash
./gradlew allLocalTest
./gradlew allContractTest
./gradlew check
```

日常开发和不需要 Docker 的 Pull Request 反馈优先使用这些命令。

## 领域测试

领域行为测试继续使用[测试套件](./test-suite.md)中记录的继承式 `AggregateSpec` 和 `SagaSpec` API。它们位于各自领域模块的标准 `src/test` source set，并归属于本地测试层。

```bash
./gradlew allLocalTest
./gradlew :example-domain:test
./gradlew :example-transfer-domain:test
./gradlew :wow-compensation-domain:test
```

函数式 DSL 规划在后续迁移阶段推进，因此本次测试运行分层不要求修改现有领域规格。

## 集成测试

```bash
./gradlew allIntegrationTest
./gradlew :wow-mongo:integrationTest
./gradlew :wow-redis:integrationTest
./gradlew :wow-kafka:integrationTest
./gradlew :wow-elasticsearch:integrationTest
./gradlew :wow-it:integrationTest
```

集成测试使用 Testcontainers，需要 Docker。它们有意不接入 `check`。

### 业务应用的最小 HTTP 集成测试

上面的 `:wow-it` 是 Wow 仓库自身的验证任务。业务应用还需要一条由自己拥有的测试，覆盖 KSP 元数据、Spring 装配、WebFlux 路由、命令等待和事件溯源状态。下面的测试使用[接入现有项目](./existing-project.md)中的内存配置，不需要 Docker：

```kotlin
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WowCommandFlowIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `http command reaches sourced state`() {
        val aggregateId = "it-${UUID.randomUUID()}"
        val client = WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:$port")
            .build()

        client.post()
            .uri("/tenant/test/demo")
            .header("Command-Wait-Stage", "SNAPSHOT")
            .header("Command-Aggregate-Id", aggregateId)
            .header("Command-Request-Id", "request-$aggregateId")
            .bodyValue(mapOf("data" to "integration"))
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.succeeded").isEqualTo(true)
            .jsonPath("$.stage").isEqualTo("SNAPSHOT")
            .jsonPath("$.aggregateId").isEqualTo(aggregateId)

        client.get()
            .uri("/tenant/test/demo/$aggregateId/state/1")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.id").isEqualTo(aggregateId)
            .jsonPath("$.data").isEqualTo("integration")
    }
}
```

这条测试已经证明：生成的元数据进入运行时、路由存在、命令被聚合处理、`SNAPSHOT` 等待成功，并且历史事件能重建状态。它**没有**证明 Kafka 投递、MongoDB/Redis/Elasticsearch 持久化、重启恢复或生产鉴权。

对实际生产适配器再增加一个容器层测试，并至少验证：

1. 使用与生产相同的 Starter capabilities 和配置；
2. 命令写入真实 EventStore，重启应用后仍能读取状态；
3. 重复 `requestId` 被权威存储拒绝；
4. Broker 重投不会重复执行投影或外部副作用；
5. tenant、owner、授权和查询索引按生产边界生效。

不要用框架仓库的 `:wow-it:integrationTest` 代替应用自己的发布门禁。

## 覆盖率

```bash
./gradlew codeCoverageReport
./gradlew :code-coverage-report:localCoverageReport
./gradlew :code-coverage-report:contractCoverageReport
./gradlew :code-coverage-report:integrationCoverageReport
```

聚合覆盖率报告包含本地、契约和集成测试的执行数据。XML 报告输出到：

```text
test/code-coverage-report/build/reports/jacoco/codeCoverageReport/codeCoverageReport.xml
```

分层报告分别输出到匹配的 `localCoverageReport`、`contractCoverageReport` 和 `integrationCoverageReport` 目录。Pull Request 工作流会把这些 XML 报告分别以 `local`、`contract` 和 `integration` flag 上传到 Codecov。主分支的 `Codecov` 工作流把聚合报告作为 `full` 基线 flag 上传。

领域模块也继续通过 `jacocoTestCoverageVerification` 执行既有覆盖率阈值校验，并使用标准 `test` 执行数据。

## 基准 Smoke

```bash
./gradlew :wow-benchmarks:benchmarkSmoke
```

基准 Smoke 用于确认选定 JMH 路径仍可编译并执行。它是 Pull Request 安全检查，不是性能报告。

## 快速基准测试

```bash
./gradlew :wow-benchmarks:benchmarkQuickE2E
./gradlew :wow-benchmarks:benchmarkQuickComponent
./gradlew :wow-benchmarks:benchmarkQuickInfrastructureE2E
./gradlew :wow-benchmarks:benchmarkQuickMongoBatchAppend
./gradlew :wow-benchmarks:benchmarkQuickElasticsearchBatchAppend
./gradlew :wow-benchmarks:benchmarkQuickMongoBatchOptionsPaired
./gradlew :wow-benchmarks:benchmarkQuickMongoBatchAppendCandidateE2E
./gradlew :wow-benchmarks:benchmarkQuickMongoBatchCoordinatorConcurrency
./gradlew :wow-benchmarks:benchmarkTuneElasticsearchBatchOptions
./gradlew :wow-benchmarks:generateQuickBenchmarkReport
```

快速基准测试使用有边界的代表性 catalog 和较短的 JMH 设置，适合本地快速发现回归；正式吞吐与分配结论仍以 Baseline E2E 为准。
Quick Component 默认仅运行单线程；扩展性行为由 Framework E2E 负责，而不是由隔离组件测量承担。
Infrastructure 基准测试需要对应的本地 Redis、MongoDB 或 Elasticsearch 服务。
Mongo 批处理参数的 quick 验证使用成对的代表性/突发负载、候选 E2E 和 coordinator lane 诊断；已停止的全量
`benchmarkTuneMongoBatchOptions` 实验仅作历史记录，不再出现在 quick 执行清单中。Elasticsearch 调优仍覆盖
单请求、突发、代表性和饱和负载。screening 仅用于筛选候选；
只有通过各存储独立的多 fork confirmation 后才能修改默认值。EventStore 的调优结论不适用于 SnapshotStore。

## 基线与诊断基准测试

```bash
./gradlew :wow-benchmarks:benchmarkBaselineE2E
./gradlew :wow-benchmarks:benchmarkLatencyE2E
./gradlew :wow-benchmarks:benchmarkDiagnosticComponent \
  -PbenchmarkDiagnosticComponentIncludes=me.ahoo.wow.benchmark.component.CommandPipelineComponentBenchmark.handleAggregateAndSendDomainEvent
./gradlew :wow-benchmarks:benchmarkExhaustiveComponent
./gradlew :wow-benchmarks:benchmarkBaselineInfrastructureE2E
./gradlew :wow-benchmarks:generateBaselineBenchmarkReport
```

Baseline E2E 是有边界的双 fork 吞吐与分配基线，用于正式框架对比。Latency E2E 为可选任务，不再把延迟测量成本强制叠加到每次基线运行。Diagnostic Component 支持精确 benchmark include；Exhaustive Component 仅作为极少执行的完整 catalog 逃生口。基准模块有意不提供泛化别名，调用方必须选择用途明确的任务。
Component 结果用于解释瓶颈，不应作为独立框架性能目标对外报告。
Infrastructure E2E 结果用于在 Redis 和 MongoDB 可用时暴露存储路径瓶颈。
`updateBenchmarkBaseline` 仅接受由当前 clean `HEAD` 生成的 clean manifest。Schema v2 记录 source、run specification、runtime 与 artifact hash，使陈旧或不完整证据直接失败。

## CI 工作流

Pull Request 分别运行 `Local Test`、`Contract Test`、`Integration Test`、`Benchmark Smoke` 和 `Static Analysis` 工作流。`Local Test`、`Contract Test` 和 `Integration Test` 工作流分别发布分层 Codecov flag。主 `Codecov` 工作流在 `main` 或手动触发时使用 `codeCoverageReport` 构建完整基线。
