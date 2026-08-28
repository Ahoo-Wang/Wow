---
title: 快速上手
description: 使用当前项目模板证明 Wow 命令、事件、快照与版本化状态的完整链路。
outline: deep
---

# 快速上手

使用 [Wow 项目模板](https://github.com/Ahoo-Wang/wow-project-template)，在替换 Demo 领域前证明一条完整垂直链路：

```text
领域测试 → 生成路由 → HTTP 命令 → 等待 SNAPSHOT → 读取版本 1 状态
```

这条路径使用模板内置的内存总线、事件存储和快照存储，不需要 Kafka、MongoDB 或 Redis。

## 已验证基线

本页于 2026-08-27 在模板提交 [`1dc9267b7f276c8c3bd9b2fad3186e3e3c3e82f9`](https://github.com/Ahoo-Wang/wow-project-template/tree/1dc9267b7f276c8c3bd9b2fad3186e3e3c3e82f9) 上实际执行：

| 组件 | 已验证值 |
| --- | --- |
| 模板 Wow 版本 | `8.13.0` |
| Spring Boot | `4.1.1` |
| Kotlin | `2.4.10` |
| KSP | `2.3.11` |
| Gradle Wrapper | `9.7.1` |
| 验证使用的 JDK | `17.0.7` |

当前 Wow 文档源码版本是 `8.15.0`，已验证模板固定的是 `8.13.0`；两个仓库独立演进。每次开始前都要检查克隆模板的 [`gradle/libs.versions.toml`](https://github.com/Ahoo-Wang/wow-project-template/blob/main/gradle/libs.versions.toml)，并把选定 [Wow Release](https://github.com/Ahoo-Wang/Wow/releases) 的相关版本作为一个整体兼容性基线评审。

## 开始前

- JDK 17 或更高版本
- Git
- `curl`
- 仓库中的 Gradle Wrapper；无需全局安装 Gradle

首次验证建议使用一次性克隆，避免本地运行文件污染应用仓库：

[![Use this template](https://img.shields.io/badge/Use%20this%20template-2ea44f?style=for-the-badge&logo=github)](https://github.com/new?template_name=wow-project-template&template_owner=Ahoo-Wang)

```shell
git clone https://github.com/Ahoo-Wang/wow-project-template.git
cd wow-project-template
git fetch --depth 1 origin 1dc9267b7f276c8c3bd9b2fad3186e3e3c3e82f9
git checkout --detach FETCH_HEAD
git rev-parse HEAD
grep '^wow = ' gradle/libs.versions.toml
```

上述 checkout 命令会固定本文预期结果对应的精确提交。如果有意保留不同的模板 `HEAD`，不要把它与本文固定预期混用：应针对该 `HEAD` 重新执行领域检查、启动、路由、命令与版本化状态验证，并记录新的基线。如果通过模板按钮创建正式仓库，请在首次成功后再修改 `settings.gradle.kts` 中的 `rootProject.name`。

## 30 分钟目标路径

以下功能链路已经完成端到端演练。由于尚未测量新开发者首次完成的墙钟时间，30 分钟仍是目标时长；这不是已经完成的人类可用性研究。

### 1. 证明领域模型

运行模板定义的精确模块检查：

```shell
./gradlew :domain:check --console=plain
```

该检查会编译 KSP 元数据，运行 `DemoSpec` 与 `DemoSagaSpec`，并执行领域覆盖率门禁。成功时结尾为 `BUILD SUCCESSFUL`。

在本次验证环境中，首次运行在 `:api:kspKotlin` 阶段耗尽了 Gradle 默认的 `384 MiB` Metaspace。仅当错误明确为 `OutOfMemoryError: Metaspace` 时，可不修改模板、用下面的命令重试：

```shell
./gradlew :domain:check --console=plain \
  -Dorg.gradle.jvmargs='-Xmx1g -XX:MaxMetaspaceSize=1g'
```

不要用该重试掩盖编译、测试、覆盖率或依赖失败。

被验证的行为直接来自模板源码：

- [`Demo`](https://github.com/Ahoo-Wang/wow-project-template/blob/1dc9267b7f276c8c3bd9b2fad3186e3e3c3e82f9/domain/src/main/kotlin/me/ahoo/wow/template/domain/demo/Demo.kt) 返回 `DemoCreated` 与 `DemoUpdated`；
- [`DemoState`](https://github.com/Ahoo-Wang/wow-project-template/blob/1dc9267b7f276c8c3bd9b2fad3186e3e3c3e82f9/domain/src/main/kotlin/me/ahoo/wow/template/domain/demo/DemoState.kt) 应用这些事件；
- [`DemoSpec`](https://github.com/Ahoo-Wang/wow-project-template/blob/1dc9267b7f276c8c3bd9b2fad3186e3e3c3e82f9/domain/src/test/kotlin/me/ahoo/wow/template/domain/demo/DemoSpec.kt) 验证命令 → 事件 → 状态；
- [`DemoSaga`](https://github.com/Ahoo-Wang/wow-project-template/blob/1dc9267b7f276c8c3bd9b2fad3186e3e3c3e82f9/domain/src/main/kotlin/me/ahoo/wow/template/domain/demo/DemoSaga.kt) 在创建后发送更新命令。

### 2. 使用版本化配置启动服务

模板的 `:server:run` 任务以 `server/` 为工作目录，并读取 `server/config/`。将该路径指向受版本控制的 `server/src/main/resources`，不要复制配置：

```shell
mkdir -p server/logs
test -e server/config || ln -s src/main/resources server/config
./gradlew :server:run --console=plain
```

如果上一节出现过 Metaspace 失败，请对 `:server:run` 使用同一个命令行 JVM 设置。等待日志同时出现：

```text
Netty started on port 8080 (http)
Started ServerKt
```

实际读取的配置是 [`server/src/main/resources/application.yaml`](https://github.com/Ahoo-Wang/wow-project-template/blob/1dc9267b7f276c8c3bd9b2fad3186e3e3c3e82f9/server/src/main/resources/application.yaml)。它为命令/事件总线、事件存储、快照存储和状态事件总线选择 `in_memory`，并为单个本地实例配置手动 CosId machine ID。

`server/config` 与 `server/logs/` 是本地运行产物，已验证模板并未忽略它们。Metaspace 崩溃还可能在仓库根目录留下 `java_pid*.hprof` 等未跟踪堆转储。正式仓库提交前，用下面的命令一起检查三类产物：

```shell
git status --short -- '*.hprof' server/config server/logs
```

检查后只删除列出的本地产物。Windows 上可创建等价目录链接，或在本地运行时把 `spring.config.location` 指向受版本控制的资源目录。

打开 [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html)。生成的 OpenAPI 应包含 `POST /tenant/{tenantId}/demo` 与 `/tenant/{tenantId}/demo/{id}/state/{version}`。

<a id="提交第一条真实命令"></a>

### 3. 提交第一条真实命令

保持服务运行，在另一个终端执行：

```shell
curl -sS -X POST \
  'http://localhost:8080/tenant/tenant-1/demo' \
  -H 'accept: application/json' \
  -H 'Command-Wait-Stage: SNAPSHOT' \
  -H 'Command-Aggregate-Id: demo-1' \
  -H 'Command-Request-Id: quickstart-demo-1' \
  -H 'Content-Type: application/json' \
  -d '{"data":"hello-wow"}'
```

生成路由来自模板的 [`CreateDemo`](https://github.com/Ahoo-Wang/wow-project-template/blob/1dc9267b7f276c8c3bd9b2fad3186e3e3c3e82f9/api/src/main/kotlin/me/ahoo/wow/template/api/demo/CreateDemo.kt)。不要只看 HTTP `200`，还要验证以下响应字段：

```json
{
  "stage": "SNAPSHOT",
  "aggregateId": "demo-1",
  "aggregateVersion": 1,
  "requestId": "quickstart-demo-1",
  "errorCode": "Ok",
  "succeeded": true
}
```

响应还包含每次运行都会变化的生成 ID 与时间字段。

### 4. 读取版本化溯源状态

精确读取聚合版本 `1`：

```shell
curl -sS \
  'http://localhost:8080/tenant/tenant-1/demo/demo-1/state/1' \
  -H 'accept: application/json'
```

已验证响应为：

```json
{"id":"demo-1","data":"hello-wow"}
```

这同时证明 `CreateDemo` 路由、聚合决策、`DemoCreated` 持久化、溯源函数、快照等待和版本化状态重建。固定版本还能避开 `DemoSaga` 带来的竞态：Saga 随后发送 `UpdateDemo(data = "updated")`，因此不带版本的当前状态最终返回：

```shell
curl -sS \
  'http://localhost:8080/tenant/tenant-1/demo/demo-1/state' \
  -H 'accept: application/json'
```

```json
{"id":"demo-1","data":"updated"}
```

::: tip 重复执行
`Command-Request-Id` 是幂等键。重新试验时同时更换请求 ID 与聚合 ID，或重启服务清空内存数据。
:::

## 完成门禁

只有以下五项观察全部成立，才算完成首次链路：

- `:domain:check` 成功；
- 启动时加载生成的 `META-INF/wow-metadata.json`，并监听 8080；
- Swagger/OpenAPI 包含生成的命令与版本化状态路由；
- HTTP 命令返回 `succeeded: true`、`stage: SNAPSHOT` 和聚合版本 `1`；
- 版本 `1` 状态精确为 `{"id":"demo-1","data":"hello-wow"}`。

## 安全替换 Demo

按模板模块职责迁移：

| 模块/路径 | 替换或保留 |
| --- | --- |
| `api` | 替换 Demo 命令/事件，并同步更新 `DemoService` 聚合元数据 |
| `domain` | 一起替换 `Demo`、`DemoState`、`DemoSaga` 及其规约 |
| `server` | 保留运行时装配，只加入目标环境真正需要的扩展 |
| `server/src/main/resources` | 保持配置受版本控制，按环境拆分值且不提交密钥 |
| `gradle/libs.versions.toml` | 固定一套经过验证的依赖基线 |

每次领域变更后重跑 `./gradlew :domain:check`。只有在持久化、多实例消息或特定查询后端成为真实需求时，才引入 Kafka、MongoDB、Redis 或 Elasticsearch。

模板还通过 [CosId](https://github.com/Ahoo-Wang/CosId) 提供 ID，并包含由 [Fetcher](https://github.com/Ahoo-Wang/fetcher) 生成的 TypeScript 客户端；是否保留应由应用的发布契约决定。

## 下一步

- 理解本文术语：[核心概念](./core-concepts.md)
- 替换 Demo 模型：[聚合建模](./modeling.md)
- 选择完成语义：[命令网关](./command-gateway.md)
- 建立应用门禁：[Wow 应用测试](./application-testing.md)
- 建立读模型：[投影](./projection.md)与[查询](./query.md)
- 选择运行时集成：[配置](./configuration.md)与 [Spring Boot Starter](./extensions/spring-boot-starter.md)
