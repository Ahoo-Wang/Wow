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

### Local Test 两分片合同

Local Test 的目标是在不改变测试、覆盖率或必需检查名称的前提下降低 Pull Request 等待时间。它采用两个 test-only Gradle 分片，传递 raw JaCoCo execution data 与已编译 main classes，再由后置 job 生成唯一 XML；不引入第三个分片、更大 runner、新依赖或自定义调度器。

#### 决策证据

[原始慢任务](https://github.com/Ahoo-Wang/Wow/actions/runs/33380885897/job/99452754237?pr=3115)的 Gradle 阶段为 9 分 29 秒，184 个 actionable task 中 178 个实际执行、6 个来自缓存。启用正确的 Gradle 缓存拓扑后，[代表性 Pull Request](https://github.com/Ahoo-Wang/Wow/actions/runs/33451289472) 的 Gradle 阶段仍需 9 分 29 秒，但执行数降为 135、缓存命中增为 49；对应 [`main` 任务](https://github.com/Ahoo-Wang/Wow/actions/runs/33455593795) 为 6 分 09 秒。这证明缓存有效，但冷执行的测试与编译仍是关键路径。

[强制全量 Profile](https://github.com/Ahoo-Wang/Wow/actions/runs/33460101373/job/99708267226) 在 commit `8651f2bbcb05fb48e23e24283dfa83e667df9516` 上运行了全部 184 个 task：Gradle wall time 为 8 分 57 秒，累计 task time 为 26 分 59 秒。其中测试占 17 分 18 秒（约 64%），编译、KSP 与 KAPT 占 9 分 27 秒（约 35%），有效并行度约为 3.36。Profile 来自已关闭且未合并的 [PR #3134](https://github.com/Ahoo-Wang/Wow/pull/3134)，只作为本设计的测量证据。

首个双 XML 候选在已关闭且未合并的 [PR #3138](https://github.com/Ahoo-Wang/Wow/pull/3138) 上完成[强制测量](https://github.com/Ahoo-Wang/Wow/actions/runs/33467569094)：生产路径为 382 秒，分片差为 7.65%，但累计 runner time 为 714 秒，超过 664 秒上限；Codecov 总覆盖率从未分片基线的 88.20% 降为 88.15%。排除 Codecov 将 complexity 归零的表示差异后，仍有 14 个文件、15 个行状态漂移。两个分片 XML 只有 branch/instruction 汇总计数，无法保留互补 JaCoCo probe 身份，因此该架构被拒绝；本设计改为先合并 raw execution data，再生成单一 XML。

#### Gradle 分片

`test/code-coverage-report/build.gradle.kts` 定义 `localTestShard1` 与 `localTestShard2`。它们只依赖所属分片的 `test` task，不配置 JaCoCo report，也不通过完整 classDirectories 拉入另一分片的编译任务。`wow-compensation-server:test` 仍属于分片 2，但与现在一样不进入覆盖率报告。

分片 1 固定为以下 15 个测量后较重或与其平衡的项目：

```text
:wow-core
:wow-openapi
:wow-bi
:wow-elasticsearch
:wow-query
:wow-kafka
:wow-opentelemetry
:wow-cocache
:wow-compensation-core
:example-transfer-domain
:wow-tck
:wow-spring
:wow-compensation-api
:example-api
:example-transfer-api
```

分片 2 是 `localTestTaskProjects - shard1`。初始集合为：

```text
:wow-spring-boot-starter
:wow-compiler
:wow-mongo
:wow-webflux
:wow-schema
:wow-redis
:wow-compensation-server
:example-domain
:wow-models
:wow-api
:wow-compensation-domain
:wow-cosec
:wow-apiclient
:wow-test
:wow-mock
:wow-it
```

配置期守卫要求分片 1 的路径全部存在、两个分片非空、无交集，且并集严格等于 `localTestTaskProjects`。新 Local Test 项目因此自动进入分片 2；观察到失衡后再移动一个已有路径，而不是增加配置层。

同一 build script 另外定义 `verifyLocalCoverageArtifacts` 与 `localCoverageReportFromArtifacts`。验证任务按当前 source set 判断应存在的 raw `build/jacoco/test.exec` 与 main class 文件；报告任务只读取下载后的显式文件路径，不依赖任何编译或测试 task，并复用现有 `localCoverageReport` 的完整 source/class 项目范围。原始 `localCoverageReport` 保留为未分片基线。

#### Workflow 与覆盖率

`.github/workflows/local-test.yml` 先运行一个两项 matrix，每项执行对应的 test-only Gradle task，并用 `actions/upload-artifact@v4` 上传该 runner 已产生的 `**/build/classes/kotlin/main/**`、`**/build/classes/java/main/**` 与 `**/build/jacoco/test.exec`。多路径 artifact 以工作区公共祖先为根保留相对目录；`if-no-files-found: error` 禁止空 artifact。当前本地产物按分片归属估算为 16.5 MiB 与 14.2 MiB（未压缩），最终仍以托管 runner 实测为准。

后置 job 的名称仍为 `Local Test`，以保持 branch protection 使用的必需检查名。它使用 `if: always()` 读取 matrix 汇总结果，并首先要求 result 为 `success`；随后按精确名称分别下载两个 artifact 到工作区根目录，运行 artifact 验证与 detached JaCoCo report，最后只上传这一份 XML 到 Codecov，继续使用 `local` flag、OIDC、`disable_search: true` 和 `fail_ci_if_error: true`。任一分片失败、取消、artifact 缺失、覆盖输入不完整、报告失败或 Codecov 失败都会使 `Local Test` 失败；禁止上传部分覆盖率。两个分片继续使用 `gradle/actions/setup-gradle`，并保留 `main` push、Pull Request trigger 与现有缓存种子语义。

#### 验证与验收

实现必须依次提供以下证据：

1. Gradle 配置期分区守卫通过；两个 test-only 分片的 `--dry-run` 只包含预期 test task；artifact report 的 `--dry-run` 只能包含验证与报告 task，不能出现 compile、KSP、KAPT 或 test task。
2. `actionlint`、两个分片的完整执行、raw artifact 恢复、单 XML 生成以及原始 `allLocalTest :code-coverage-report:localCoverageReport` 回归通过。
3. 诊断 PR 在同一 SHA 同时运行 forced 分片生产路径与独立 forced 未分片基线。comparison job 下载两份 XML，只移除非语义性的 JaCoCo `sessioninfo` 后比较完整 package、class、method、source line、branch 与 counter；任何漂移都失败。未分片基线与 comparison 只属于诊断，不进入最终 workflow。
4. 临时 `--rerun-tasks`、基线 job、comparison job 和诊断 XML artifact 只用于一次真实 runner 测量，取得证据后必须从最终 diff 删除。
5. 生产路径从 workflow `createdAt` 到最终 `Local Test` 的 `completedAt` 不超过 7 分钟；两个分片 wall time 差距不超过较慢分片的 15%；两个分片与最终 `Local Test` 的累计 runner time 不超过旧 Local Test 8 分 51 秒中位数的 125%，即 11 分 04 秒。诊断基线与 comparison 不计入生产指标。
6. 最终候选必须只有一个 Codecov `local` session，且其唯一 XML 来自 raw execution data 聚合。

合并后观察接下来 10 个触发 Local Test 的代码 Pull Request；按耗时升序排列后的第 9 个值作为 nearest-rank P90，并要求它不超过 7 分钟。若 raw 方案在合并前仍出现覆盖漂移、必需检查失真或 runner 成本越界，放弃分片并恢复单 job；合并后违反持续门禁时，用一个 commit 回滚 workflow、Gradle task 与本节文档。本方案不预留第三分片抽象。

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
