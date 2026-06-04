---
title: 测试运行体系
description: 如何运行 Wow 的单元测试、领域测试、契约测试、集成测试、覆盖率与基准 smoke。
---

# 测试运行体系

Wow 按运行时依赖拆分测试，让本地检查保持快速，并让容器依赖场景显式运行。

## 测试分层

| 分层 | Source set | 根任务 | 运行时依赖 |
| --- | --- | --- | --- |
| 单元测试 | `src/unitTest` | `allUnitTest` | 本地安全的框架和扩展单元测试。 |
| 领域测试 | `src/domainTest` | `allDomainTest` | 使用现有 `AggregateSpec` 和 `SagaSpec` DSL 的领域行为测试。 |
| 契约测试 | `src/contractTest` | `allContractTest` | 本地安全的 TCK 实现者测试。 |
| 集成测试 | `src/integrationTest` | `allIntegrationTest` | 基于 Testcontainers 的中间件和端到端测试。 |

`check` 运行本地安全的验证任务：单元测试、领域测试和契约测试。它不会启动 Docker 容器。

## 本地快速检查

```bash
./gradlew allUnitTest
./gradlew allDomainTest
./gradlew allContractTest
./gradlew check
```

日常开发和不需要 Docker 的 Pull Request 反馈优先使用这些命令。

## 领域测试

领域测试继续使用[测试套件](./test-suite.md)中记录的继承式 `AggregateSpec` 和 `SagaSpec` API。它们仍然是本次运行时重构的兼容层。

```bash
./gradlew allDomainTest
./gradlew :example-domain:domainTest
./gradlew :example-transfer-domain:domainTest
./gradlew :wow-compensation-domain:domainTest
```

函数式 DSL 规划在后续迁移阶段推进，因此本次测试运行分层不要求修改现有领域规格。

## 集成测试

```bash
./gradlew allIntegrationTest
./gradlew :wow-mongo:integrationTest
./gradlew :wow-redis:integrationTest
./gradlew :wow-r2dbc:integrationTest
./gradlew :wow-kafka:integrationTest
./gradlew :wow-elasticsearch:integrationTest
./gradlew :wow-it:integrationTest
```

集成测试使用 Testcontainers，需要 Docker。它们有意不接入 `check`。

## 覆盖率

```bash
./gradlew codeCoverageReport
```

聚合覆盖率报告包含单元测试、领域测试、契约测试和集成测试的执行数据。XML 报告输出到：

```text
test/code-coverage-report/build/reports/jacoco/codeCoverageReport/codeCoverageReport.xml
```

领域模块也继续通过 `jacocoTestCoverageVerification` 执行既有覆盖率阈值校验，并使用 `domainTest` 执行数据。

## 基准 Smoke

```bash
./gradlew benchmarkSmoke
```

基准 smoke 用于确认选定 JMH 路径仍可编译并执行。它是 Pull Request 安全检查，不代表稳定性能报告。

## 完整基准测试

```bash
./gradlew :wow-benchmarks:jmh
```

完整 JMH 适合手动或定时性能分析。

## CI 工作流

Pull Request 分别运行 `Unit Test`、`Domain Test`、`Contract Test`、`Integration Test` 和 `Benchmark Smoke` 工作流。Codecov 使用 `codeCoverageReport` 构建聚合覆盖率报告。
