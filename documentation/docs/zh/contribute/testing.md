---
title: 测试层次
description: Wow 自身测试的各个层次：单元、容器、契约和端到端，改动需要哪些，以及拉取请求要通过的 CI 关卡。
---

# 测试层次

Wow 的每个改动都附带测试，放在能覆盖改动内容的最窄层次上。JVM 测试使用 JUnit Jupiter、MockK 和 FluentAssert（`.assert()`，而不是 AssertJ 的 `assertThat()`）。

## 层次 {#the-layers}

| 层次 | 源码集 | 运行方式 | 需要 |
| --- | --- | --- | --- |
| 单元 | `src/test` | `<module>:test`，以及覆盖每个模块的 `:check` | JVM 之外什么都不需要 |
| 容器 | `src/integrationTest` | `<module>:integrationTest`，以及覆盖每个有这个源码集的模块的 `:integrationTest` | Docker，通过 Testcontainers |
| 契约 | 后端中继承 TCK spec 的容器测试 | 该后端的 `integrationTest` | Docker |
| 端到端 | `examples/eshop/e2e` | `:eshop-e2e:integrationTest` | Docker：PostgreSQL、Kafka、ClickHouse |

`:check` 运行每个单元测试、detekt、ABI 检查和 `:architecture:test`，但不运行容器测试。对存储适配器或消息代理的改动，要显式运行它所在模块的容器测试：

```bash
./gradlew :kernel:test
./gradlew :postgres:integrationTest --stacktrace
./gradlew :eshop-e2e:integrationTest --stacktrace
```

只有模块在 `build.gradle.kts` 中注册的层次，对它才存在。

## 领域测试 {#domain-tests}

领域用测试 DSL `:test-dsl`（制品 `wow-test`）测试：`aggregateSpec`、`sagaSpec` 以及投影和反应器的 spec，在内存存储上运行生成的模型：给定事件、一条命令，然后是期望的事件、拒绝或状态（[测试领域代码](../guide/testing)）。eShop 的 domain 模块是参照，domain 模块用 Jacoco 强制最低覆盖率。

## 契约测试套件 {#the-contract-suites}

后端实现 `:spi` 的端口，它的正确性由契约决定，而不是由它自己的测试决定。TCK 为每个端口提供一个抽象 spec：`:tck` 中的 `EventLogSpec`、`SnapshotStoreSpec`、`CheckpointStoreSpec`、`LeaseStoreSpec`、`FailureLedgerSpec`、`ReservationStoreSpec`、`TimerStoreSpec`、`SinkSpec` 和 `TopicTransportSpec`，以及 `:query-tck` 中的 `QueryBackendSpec`、`EventQueryBackendSpec` 和 `DatasetQueryBackendSpec`。`StoreTck` 对整个存储运行它们，像宿主那样经由提供者打开存储。后端的容器测试为它提供的每种存储类型运行 `StoreTck`，并继承它实现的每个 spec，提供它自己的全新存储：PostgreSQL 和 MongoDB 运行七个存储 spec 以及快照与事件的查询 spec。`:tck-containers` 启动共享的容器，镜像版本固定。[编写后端](./backends)展示怎样做。

## 文档与脚本 {#documentation-and-scripts}

- 文档的检查在仓库根目录用 Node 运行：`node .github/scripts/docs-samples.mjs` 让每个示例与其源码保持一致，另有 `docs-layout.mjs`、`docs-consistency.mjs` 和 `repo-hygiene.mjs`。
- `cd documentation && pnpm docs:build` 构建站点，遇到死链时失败。
- `node --test documentation/test/*.test.mjs` 运行站点的测试，其中包括编译 TypeScript 示例。
- `pnpm test:ci-scripts` 测试 CI 脚本本身。
- `pnpm docs:reference` 重新生成来自代码的参考页面；对配置元数据、错误码或注解的改动要提交它的输出。

## CI 关卡 {#the-ci-gates}

`ci.yml` 在每个拉取请求上运行，按它改动的路径确定范围：只改文档或只改 TypeScript 的改动会跳过 JVM 作业。

- **JVM：** 分三个分片的 `:check`（其中一个带发布检查），以及分四个分片的 `:integrationTest`。
- **TypeScript：** 静态检查、单元测试矩阵、包检查、Storybook 交互测试、eShop Web 应用和各个控制台。
- **文档：** 站点、它的布局和仓库卫生。
- **覆盖率：** Codecov 要求改动的主代码有 90% 被覆盖，并且任何模块的覆盖率下降都不能超过 1%。

`ci-gate` 是唯一的必需检查：所有运行了的作业都通过，并且在这次运行上传了覆盖率时 Codecov 的 patch 状态也通过，它才通过。在 CI 中，失败的测试最多再运行两次，以显示它是否不稳定，但只有重试才通过的测试仍然会让构建失败：不稳定的测试要修复，而不是容忍。
