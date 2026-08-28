---
title: 贡献者指南
description: 用仓库实际模块、Gradle 任务和 CI 工作流判断一次 Wow 贡献是否已准备好提交评审。
---

# 贡献者指南

本页只回答一个问题：**我的变更是否已准备好进入 Pull Request 评审？**

当范围明确、所属模块正确、行为有回归证据、相关检查通过，而且差异中没有无关文件时，答案才是“是”。本地通过不等于远端 CI 已通过，更不等于变更已获准合并。

## 决策输入

开始前先核对四项可验证输入：

1. **问题与范围**：记录要改变的行为、明确不改变的兼容性范围，以及可观察的完成条件。公开 API、生成契约、新依赖、模块边界或破坏性变化应先讨论。
2. **所属模块**：以 [`settings.gradle.kts`](https://github.com/Ahoo-Wang/Wow/blob/main/settings.gradle.kts) 为项目清单。契约通常属于 `wow-api`，运行时行为属于 `wow-core`，Spring 装配属于 `wow-spring*`，存储或传输属于对应扩展模块。
3. **现有调用与测试**：在编辑前查找定义、调用者、实现、测试与生成消费者。行为变化必须留下能在修复前失败、修复后通过的最小证据。
4. **工作树基线**：先读 `git status --short` 和目标 diff，保留用户已有改动，不把 `.gradle/`、`node_modules/`、构建输出或 IDE 状态带入提交。

完整协作约定见 [`CONTRIBUTING.md`](https://github.com/Ahoo-Wang/Wow/blob/main/CONTRIBUTING.md)。框架概念与运行时细节分别由[核心概念](../guide/core-concepts.md)和[架构](../guide/advanced/architecture.md)维护，本页不复制它们。

## 最短贡献路径

### 1. 从一个垂直切片开始

领域行为通常按 API 契约 → Aggregate 决策 → State sourcing → Spec 追踪。购物车与订单示例位于 `example/example-api` 和 `example/example-domain`；测试约定使用 Wow DSL 与 FluentAssert `.assert()`。

先运行最窄的真实测试，例如：

```bash
./gradlew :wow-core:test --tests "me.ahoo.wow.command.DefaultCommandGatewayTest"
./gradlew :example-domain:test --tests "me.ahoo.wow.example.domain.order.OrderSpec"
```

若修改行为，先保留 RED，再实现最小修复并运行同一命令得到 GREEN。纯文档变化改为保存可核对的源码、配置、工作流或可运行示例证据。

### 2. 扩到所属模块

```bash
./gradlew <module>:check
```

按 [`settings.gradle.kts`](https://github.com/Ahoo-Wang/Wow/blob/main/settings.gradle.kts) 使用真实模块路径，例如 `:wow-api`、`:wow-core`、`:wow-spring-boot-starter`、`:wow-compensation-domain`、`:example-domain` 或 `:wow-test`。

不要用一个相邻模块的成功替代实际所属模块检查。跨模块变化应逐一运行被改变契约的生产者与消费者检查。

### 3. 对齐受影响的 CI

Pull Request 工作流是 CI 事实来源：

| 变化范围 | 本地对应命令 | 工作流 |
|---|---|---|
| JVM 本地测试 | `./gradlew allLocalTest :code-coverage-report:localCoverageReport --stacktrace` | [`local-test.yml`](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/local-test.yml) |
| 契约测试 | `./gradlew allContractTest :code-coverage-report:contractCoverageReport --stacktrace` | [`contract-test.yml`](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/contract-test.yml) |
| 集成测试 | `./gradlew allIntegrationTest :code-coverage-report:integrationCoverageReport --stacktrace` | [`integration-test.yml`](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/integration-test.yml) |
| Kotlin 静态分析 | `./gradlew detekt --stacktrace` | [`static-analysis.yml`](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/static-analysis.yml) |
| 补偿模块 | `./gradlew :wow-compensation-core:check :wow-compensation-domain:check --stacktrace` | [`compensation-test.yml`](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/compensation-test.yml) |
| Java 示例 | `./gradlew :example-transfer-api:build :example-transfer-domain:build :example-transfer-server:build --stacktrace` | [`example-java-test.yml`](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/example-java-test.yml) |
| Benchmark smoke | `./gradlew :wow-benchmarks:test :wow-benchmarks:benchmarkSmoke --stacktrace` | [`benchmark-smoke.yml`](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/benchmark-smoke.yml) |

只运行变化所需的层级。涉及共享运行时、TCK 或多个后端时再扩到聚合任务；不要把未运行的任务写成“已通过”。

Dashboard 与文档使用各自的原生命令：

```bash
pnpm --dir compensation/dashboard test
pnpm --dir compensation/dashboard lint
pnpm --dir compensation/dashboard build
pnpm --dir documentation docs:build
```

Dashboard 的完整 CI 还运行 coverage 与浏览器测试，精确命令见 [`dashboard-test.yml`](https://github.com/Ahoo-Wang/Wow/blob/main/.github/workflows/dashboard-test.yml)。不要把 `compensation/dashboard/src/generated/` 当作首选修复点；能修改 OpenAPI 或生成器输入时，应从源头修复。

## 完成证据

进入 PR 前，应能给出以下证据，而不是只有“看起来没问题”：

- 一句话说明变更的所属边界与兼容性范围；
- 行为变化的 RED → GREEN，或文档事实的当前来源；
- 最窄测试与所属模块 `check` 的准确命令、退出状态和失败数；
- 受影响 CI 层的本地结果，未运行项明确标记；
- `git diff --check` 通过；
- `git status --short` 与最终 diff 只包含预期文件；
- PR 描述列出验证证据、风险、回滚或迁移边界，以及仍缺少的环境证据。

远端 CI、评审和合并状态只能由对应远端结果证明。

## 优先下一步

1. **准备首次贡献**：阅读[测试套件](../guide/test-suite.md)，从一个现有 Spec 和所属模块检查开始。
2. **改变公开或运行时边界**：先阅读 [Staff Engineer 指南](./staff-engineer-guide.md)，把 source、binary、wire 与运营风险分开。
3. **只修改文档**：以当前源码为事实源，并运行完整 VitePress 构建。
