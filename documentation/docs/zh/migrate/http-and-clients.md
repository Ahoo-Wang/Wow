---
title: HTTP 与 TypeScript 客户端
description: 对 9.x 服务的调用方来说有哪些变化：路由、请求头、错误和 TypeScript 包，以及前端怎样与它的服务一起迁移到 V10。
---

# HTTP 与 TypeScript 客户端

V10 改变了路由、请求头、错误和客户端包，并且不保留兼容层：服务的每个调用方都要随它一起迁移。顺序是固定的。服务的源码迁移先产出它的 V10 Manifest；再从这个 Manifest 重新生成 TypeScript 客户端；然后按类型检查报告的问题修复应用。`wow-migrate` Skill 也负责这一部分，依据是它的参考文档 `http-and-clients.md`。

## 路由 {#routes}

| 9.x | V10 |
| --- | --- |
| `POST /<context>/[tenant/{tenantId}/][owner/{ownerId}/]<aggregate>/{id}/<action>`，以及 `PUT`、`PATCH` 和自定义动作 | `POST /<context>/<aggregate>/{id}/<command>`，命令名为 kebab 形式；创建命令也可以不带 `{id}`，ID 即所有者 ID 的聚合（9.x `OwnerPolicy.AGGREGATE_ID`）的每条命令也可以，指调用方自己的聚合。租户和所有者来自调用方，从不来自路径 |
| `DELETE …/{id}`、`PUT …/{id}/recover`、`PUT …/{id}/tags` | `DELETE /<context>/<aggregate>/{id}`、`POST …/{id}/wow.lifecycle.restore-aggregate`、`POST …/{id}/wow.lifecycle.apply-tags` |
| `…/snapshot/{single,list,paged,cursor,count,aggregation}`、它们的 `/state` 形式、`…/event/…` | `POST /<context>/<aggregate>/snapshot/query` 和 `…/events/query`，操作写在请求体的 `kind` 中 |
| `GET …/snapshot/schema` | 对同一个查询路由发 `GET`：能力描述符，带着它的 `ETag` |
| `GET …/{id}/state/{version}`、`…/state/tracing` | `GET /<context>/<aggregate>/{id}/state`，带 `?version=`、`?at=` 或 `?minVersion=`，以及 `…/state/trace` |
| `PUT …/{id}/snapshot`（重新生成） | `POST /<context>/snapshot/<aggregate>/wow.snapshot.rebuild`，请求体为 `{"ids": [...]}` |
| 状态事件重发、按版本重新分发 | 10.x 之前都没有；失败的版本是一个停放条目，在 `/wow/ops/parked` 下重试 |
| `/wow/command/send`、`/wow/command/wait` | 每个命令各自的路由 |
| `/dataset/…` | `GET /wow/dataset`、`POST /wow/dataset/<context>/<dataset>/query` |

从服务生成的 9.x OpenAPI 读取 9.x 的路径，而不是从它的注解读取。每条提到 9.x 路径的网关规则、CoSec 策略和 API 文档都要随之改变。[HTTP API](../reference/http) 列出了 V10 的路由。

## 请求头 {#headers}

| 9.x | V10 |
| --- | --- |
| `Command-Wait-Stage`、`-Context`、`-Processor`、`-Function`、`Command-Wait-Tail-*` | `Wow-Wait`：由各跳组成的计划，`accepted`、`committed`、`snapshot`、`handled([context/]Processor.function)`，用 `>` 连接；默认为 `snapshot` |
| `Command-Wait-Timeout` | `Wow-Wait-Timeout`：`500ms`、`10s` 或 ISO-8601 |
| `Command-Request-Id` | `Wow-Request-Id` |
| `Command-Aggregate-Version` | `If-Match: "<version>"`；读取以 `ETag` 回答 |
| `Command-Tenant-Id`、`Command-Owner-Id`、`Command-Aggregate-Id` | 调用方和路由；被允许在别处行事的调用方发送 `Wow-Scope`；`Wow-Space` 选择调用方的某个空间，CoSec 宿主上是 `CoSec-Space-Id` |
| `Command-Local-First`、`Command-Type`、`Command-Header-*` | 已去掉；服务作为请求事实保留的请求头列在 `wow.http.request-facts` 中 |
| 作为别名读取的 `CoSec-Request-Id`、`CoSec-Space-Id` | 只在 CoSec 宿主上读取：`CoSec-Request-Id` 是没有 `Wow-Request-Id` 的命令的请求 ID，空间是 CoSec 从 `CoSec-Space-Id` 接受的那个（那里不读取 `Wow-Space`）。没有 CoSec 时，用 `Wow-Request-Id` 和 `Wow-Space` |

9.x 的等待阶段按含义映射：`SENT` 是 `accepted`，`PROCESSED` 是 `committed`，`SNAPSHOT` 是 `snapshot`，`PROJECTED`、`EVENT_HANDLED` 和 `SAGA_HANDLED` 是指明订阅的 `handled` 跳。

## 错误 {#errors}

9.x 的错误体 `errorCode`、`errorMsg` 和 `bindingErrors`，在 V10 中是 RFC 9457 problem，`application/problem+json`：`type` 为 `urn:wow:error:<code>`、`title`、`status`、`detail`、`code`、`violations`，命令到达聚合之后还有命令的完整 `result`。每个错误码都变了：框架的错误码是 `wow.*`（`wow.aggregate.not-found`、`wow.aggregate.version-mismatch`、`wow.command.busy`），领域拒绝是 `<context>.<aggregate>.<code>`，除非服务声明了另一个状态，否则为 422。在应用中搜索它匹配的每个 9.x 错误码，并逐一映射（[错误码](../reference/error-codes)）。

## TypeScript 包 {#typescript-packages}

| 9.x | V10 |
| --- | --- |
| `@ahoo-wang/fetcher-wow`，或 9.x 的 `@ahoo-wang/wow-client` | V10 版本的 `@ahoo-wang/wow-client`，以及 peer 依赖 `@ahoo-wang/fetcher` 和 `@ahoo-wang/fetcher-eventstream` |
| `@ahoo-wang/fetcher-generator`，或读取 `/v3/api-docs` 的 9.x `wow-generator` | V10 版本的 `@ahoo-wang/wow-generator`，读取服务的 Manifest：`GET /wow/manifest` 或它提交的 `manifest/baseline.json`。从 OpenAPI 到 Wow 的路径、`fetcher-generator` 命令和 `fetcher-generator.config.json` 都已去掉 |
| `@ahoo-wang/fetcher-react` 的 Wow hooks，或 9.x 的 `wow-react` | V10 版本的 `@ahoo-wang/wow-react` |
| `@ahoo-wang/fetcher-viewer`（冻结在 5.x），以及它用到的 `fetcher-react` 数据监视器 | `@ahoo-wang/wow-view-engine`，用一份定义和一个宿主重建 |
| 9.x 视图存储的路由 | `@ahoo-wang/wow-view-store` 的 `WowViewStore`，基于 V10 的视图存储服务 |

每个 Wow 包都保持在同一个 V10 次版本上（[兼容性与版本](../typescript/compatibility)）。fetcher 仓库中针对这些包的 Agent Skill，`fetcher-wow-cqrs` 和 `fetcher-openapi-generator`，已经变成 Wow 的 `wow-client` Skill。`@ahoo-wang/fetcher` 核心包留在它们自己的仓库中，是每个 Wow 包的 peer 依赖；没有哪个 fetcher 包依赖 Wow 包。

## 客户端代码 {#client-code}

| 9.x | V10 |
| --- | --- |
| 带 `commandHeaders` 和 `waitStrategy({ stage: CommandStage.SNAPSHOT })` 的 `CommandClient.send(…)` | 生成的 `<Aggregate>CommandClient.<command>(body, { id, wait: WaitPlan.SNAPSHOT })` |
| `sendAndWaitStream` | 生成的 `…Stream` 方法 |
| `QueryClientFactory`、`<aggregate>QueryClientFactory` | 生成的 `<Aggregate>SnapshotQueryClient`、`<Aggregate>EventQueryClient` 和 `<Aggregate>StateClient` |
| `pagedState`、`listState`、`singleState` | `paged`、`list`、`single`，回答完整的记录：读取 `record.state`，或者用字面的 `include` 投影 |
| `getStateById(id)` | `<Aggregate>StateClient.get(id)`，命令之后带上 `minVersion` |
| `EventStreamQueryClient`，记录为带 `bodyType` 的 `body[]` | `EventQueryClient`：提交带有 `{ name, revision, body }` 组成的 `events[]`，`name` 为 `<context>.<aggregate>.<Event>` |
| `@ahoo-wang/wow-client/legacy`（`Condition`） | 已去掉：使用 `@ahoo-wang/wow-client/dsl` 的 `filter.*` |
| `urlParams.path.{ tenantId, ownerId }`、`contextAlias` | 不需要，由调用方决定；网关前缀用 `basePath` |
| `toWowError`、`ErrorCodes`、`errorCode`、`bindingErrors` | `error instanceof WowProblemError`：`code`、`status`、`violations`、`retry`；`FrameworkErrorCodes` 和生成的 `<Context>Contract.<Aggregate>.Errors` |
| 失败的命令带着它的错误码 resolve | 每条没有成功的命令都会 reject：被拒绝的以 `CommandRejectedError`（带 `code` 和 `result`），其他情况以 `WowProblemError` |

[TypeScript](../typescript/) 介绍 V10 的客户端。

## 不会产生类型错误的变化 {#what-changes-without-a-type-error}

- **错误码**是编译器无法检查的字符串：逐个 grep 每个 9.x 错误码。
- **精确数字。** `Long`、`BigInteger` 或 `BigDecimal` 字段在 V10 的类型中是 `string`，精确地读取。对 `number` 做算术的代码，如果不修复，现在会拼接字符串。
- **时区。** 不带 `zoneId` 的相对时间过滤按 UTC 读取日期，而 9.x 用的是服务器的时区：在本意是本地日期的地方传入 `zoneId`。
- **事件名。** 9.x 的 `bodyType` 类名变成 `name` 值；以它们为键保存的过滤和界面要随之改变。
- **快照字段。** 9.x 的 `eventId` 和 `snapshotTime` 已去掉；`firstEventTime`、`lastEventTime`、`operator`、`firstOperator` 和 `tags` 仍然保留。

## 步骤 {#steps}

1. 服务的 V10 Manifest 已经存在，并通过了它的兼容性基线。
2. 替换包，从 Manifest 把客户端生成到同一个目录，提交 `.wow-generator.json`，并删除 9.x 生成器的配置和脚本（[生成客户端](../typescript/generate)）。
3. 运行 `tsc`，按上面的表格修复每个错误，永远不要用类型断言或 `any`；然后运行 linter 和应用的测试。
4. 搜索类型检查看不到的东西：9.x 错误码、`bodyType`、fetch 中的 9.x 路径、网关规则和 CoSec 策略、不带 `zoneId` 的相对时间、对精确数字的算术。
5. 在开发或预发环境中，让应用对着 V10 服务运行：一条带等待的命令、一次状态读取、一次查询，以及一次展示给用户的拒绝。

调用 9.x 服务路由的 Kotlin 和 Java 调用方，即 `wow-apiclient` 和 `CommandGateway`，改用 `CommandClient` 和 `QueryApi` 客户端（[调用其他服务](../guide/calling-services)）。
