---
title: V10 HTTP API
description: Wow V10 服务的命令路由、Wow-Wait 请求头、状态读取、快照与事件查询、数据集与错误码。
outline: deep
---

# V10 HTTP API

宿主只依据服务的 Manifest，在应用的 WebFlux 服务器（`server.port`）上构建公开路由，不需要手写控制器。`GET /wow/manifest` 返回构建路由所依据的 Manifest（用 `wow.http.manifest.enabled: false` 关闭）。

## 调用方

每条路由都通过应用的 `CallerResolver` Bean（`me.ahoo.wow.http.caller`）认证请求。它返回请求的 `Caller`（`me.ahoo.wow.runtime.admission`）：租户、作用域（所有者与空间）、主体 `subject`、是否可以跨作用域操作（`crossScope`）及其他属性。请求没有可接受的凭据时它返回 `null`，请求得到 401 `wow.auth.unauthenticated`。

```kotlin
@Bean
fun callerResolver(): CallerResolver = CallerResolver { request ->
    request.headers().firstHeader("X-User")?.let { user ->
        Caller(scope = Scope(ownerId = user), subject = user)
    }
}
```

没有 `CallerResolver` Bean 时，每个请求都是匿名调用方：默认租户（`wow.tenant.default`，`(0)`），没有所有者与空间。宿主在启动时和第一个请求时给出警告。

命令的租户、所有者与空间来自调用方，从不来自路径或请求体。带 `crossScope` 的调用方可以用 `Wow-Scope` 指定另一个。

### 两级调用方

聚合一律属于它的租户，有所有者、空间时也属于它们。调用方分两级，由解析器决定：

| 级别 | 调用方作用域 | 能读写 |
| --- | --- | --- |
| 所有者级，例如买家 | 带所有者 | 只有自己所有者（与空间）的聚合 |
| 租户级，例如员工 | 不带所有者 | 本租户的全部聚合，例如任何买家的订单 |

调用方作用域里缺的部分从不检查，无论是内核检查命令，还是查询与状态读。所以租户级调用方可以给任何买家的订单发货，它的提交不改变订单的所有者。调用方能做什么由动作级授权（`CommandAuthorizer`，例如 CoSec 的）决定；级别只决定它能触达哪些聚合。

```kotlin
@Bean
fun callerResolver(): CallerResolver = CallerResolver { request ->
    val user = request.headers().firstHeader("X-User") ?: return@CallerResolver null
    val employee = request.headers().firstHeader("X-Role") == "employee"
    Caller(scope = if (employee) Scope.NONE else Scope(ownerId = user), subject = user)
}
```

接 CoSec（`wow-cosec`）时，`CoSecCallerResolver` 让主体成为自己的所有者，除非它持有 `wow.cosec.tenant-level.roles` 中的某个角色或 `wow.cosec.tenant-level.policies` 中的某个策略：这样的主体是租户级调用方。两种情况下主体 `subject` 都是它的 ID，所以它提交的内容都记下了操作人。

本租户所有调用方都要读的聚合，例如目录里的商品，声明 `@AggregateRoot(ownership = Ownership.TENANT)`（[所有者、空间与租户](./programming-model.md#所有者、空间与租户)）：它不记所有者，对它的读写都不按调用方的所有者限制，写只由动作级授权保护。

## 命令

| 方法与路径 | 发送 |
| --- | --- |
| `POST /<context>/<aggregate>/{id}/<command>` | 发给聚合 `{id}` 的命令 |
| `POST /<context>/<aggregate>/<command>` | 发给一个新聚合的创建类命令，ID 由服务端分配 |

`<command>` 是命令名的 kebab case：`AddCartItem` 为 `add-cart-item`。请求体是命令的 JSON；空请求体即 `{}`。

不带 ID 的创建类命令在准入时得到 ID：它的 `Wow-Request-Id` 已经（在调用方的作用域内）创建过聚合时，取那个聚合的 ID，不再取号；否则由聚合类型的生成器（`wow.aggregates.[<context>.<aggregate>].id-generator`）分配新 ID。所以用同一请求 ID 的重试得到同一个 ID，作为重复请求回答。已提交的 ID 永不改变。ID 稳定但不保证连号：同一请求 ID 的两个并发请求可能取了一个最终没用上的号。类路径上有 Bean Validation 实现（`spring-boot-starter-validation`）时，命令被决定之前先校验它的 Jakarta 约束。

### 生命周期路由

| 方法与路径 | 发送 |
| --- | --- |
| `DELETE /<context>/<aggregate>/{id}` | `DeleteAggregate` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.restore-aggregate` | `RestoreAggregate` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-owner` | `TransferOwner`，请求体 `{"ownerId": "…"}` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.transfer-space` | `TransferSpace`，请求体 `{"spaceId": "…"}` |
| `POST /<context>/<aggregate>/{id}/wow.lifecycle.apply-tags` | `ApplyTags`，请求体 `{"tags": {"name": ["value"]}}`，替换原有标签 |

只有聚合列出的生命周期命令（`@AggregateRoot(lifecycle)`）才有路由。

### 请求头

| 请求头 | 含义 |
| --- | --- |
| `Wow-Wait` | 回答之前等待到哪一步（见下文） |
| `Wow-Wait-Timeout` | 等待多久：`500ms`、`10s`、`1m` 或 ISO-8601（`PT10S`）；默认 `wow.wait.default-timeout`（10 秒），最多 `wow.wait.max-timeout`（60 秒） |
| `Wow-Request-Id` | 调用方给请求的 ID：带同一 ID 的重试被回答为已完成（`duplicate`），不会再次决定。同一 ID 配另一条命令是 `wow.command.request-id-reused` |
| `If-Match: "n"` | 聚合必须处于的版本；否则 `wow.aggregate.version-mismatch`（412） |
| `Accept: text/event-stream` | 每到达一跳发一个服务器发送事件，直到等待结束 |
| `Wow-Scope` | 供带 `crossScope` 的调用方使用：要操作的租户、所有者与空间，RFC 8941 字典：`tenant="t1", owner="u1", space="s1"`。在查询路由上，`wow.query.cross-tenant.enabled` 打开时 `tenant=*` 或 `tenant=("t1" "t2")` 可跨租户读取 |
| `Wow-Space` | 在调用方自己的空间中选择要操作的空间；解析器不授予时为 403 `wow.auth.forbidden` |

`wow.http.request-facts` 列出的请求头会复制到命令的 `headers` 中，并写入事件流：只在这里列请求事实（例如调用方应用），绝不要列个人信息或密钥。

### `Wow-Wait`

等待计划是一串由 `>` 分隔的跳：

```text
plan := hop (">" hop)*
hop  := "accepted" ["(" context ")"]
      | "committed" ["(" context ")"]
      | "snapshot" ["(" context ")"]
      | "handled(" [context "/"] subscription ")"
```

| 跳 | 何时到达 |
| --- | --- |
| `accepted` | 命令通过准入。只有经 Kafka 路由的命令，这一步才是持久的 |
| `committed` | 命令的事件已追加（或命令无需追加即已了结） |
| `snapshot` | 快照已包含这次提交，紧接着的读取能看到它 |
| `handled(sub)` | 订阅 `sub`（`processor.function`）已处理这次提交 |

不带上下文的跳属于命令所在的上下文。在 Saga 的 `handled` 跳之后，后续的跳作用于该 Saga 派生的命令，可能在另一个上下文中：

```text
Wow-Wait: committed > handled(example/OrderSaga.onOrderCreated) > committed(inventory)
```

在库存上下文提交了订单 Saga 发出的预留之后回答，或者在库存拒绝时立即回答。不带 `Wow-Wait` 时命令等待 `snapshot`，调用方能读到自己的写入。

- **跳数。** 一个计划最多 `wow.wait.max-hops`（16）跳，节点允许的上限是 32。等待链每经过一个 Saga 加两跳：它自己的 `handled` 与下一个 `committed`。一次结账经四个 Saga 一直等到支付，需要 15 跳。
- **节点不认识的上下文。** 发起等待的节点只校验它有 Manifest 的上下文，即自己的与 peer 的。其他上下文的跳，名字合法即放行，到达处再校验：写错的订阅或上下文永远收不到信号，等待超时并带回最后到达的跳。设置 `wow.wait.unknown-contexts: reject` 时，这样的跳使计划无效（`wow.wait.invalid`）。

### 响应

- **200** 带等待结束时的 `CommandResult`；计划止于 `accepted` 时为 **202**。只有当聚合类型经 Kafka 路由时（[Kafka Router](./deployment.md#kafka-router)），202 才表示命令不会丢失；否则命令在接收它的节点上脱离请求执行。
- 命令被拒绝或失败时返回 **RFC 9457 问题详情**（`application/problem+json`）：`type` 为 `urn:wow:error:<code>`，`code` 与 `status` 重复给出，正文带上结果。领域错误码的状态码取自它的 `@ErrorCode(status)`，默认 422，可用 `wow.http.errors.[<code>].status` 按错误码覆盖。
- **带 `Accept: text/event-stream`** 时由第一个结果决定：准入拒绝返回问题详情；否则返回 200，之后每个结果一个事件（事件名为该跳的阶段，数据为结果），直到等待结束。准入之后的拒绝或失败是最后一个事件。

`CommandResult` 包含：

| 字段 | 内容 |
| --- | --- |
| `commandId`、`requestId`、`correlationId` | 命令、调用方的请求、链路的根 |
| `ref` | 聚合：`context`、`name`、`tenantId`、`id` |
| `hop`、`function` | 到达的跳（`stage`、`context`、`subscription`）与产生结果的函数 |
| `status` | `SUCCEEDED`、`REJECTED` 或 `FAILED` |
| `version` | 提交后聚合的版本 |
| `duplicate` | 请求已提交过，没有再次决定 |
| `error` | 未成功时的 `code`、`message` 与字段 `violations` |
| `results` | 命令函数通过 `DecideScope.putResult` 放入的值；重复请求时为空 |
| `derived` | 在 Saga 的跳上，它派生的命令 ID |
| `signalTime` | 结果产生的时间（毫秒时间戳） |

## 状态读取

| 方法与路径 | 返回 |
| --- | --- |
| `GET /<context>/<aggregate>/{id}/state` | 最新状态，带 `ETag: "<version>"`；`If-None-Match` 匹配时 304 |
| `GET …/state?version=n` | 版本 `n` 的状态 |
| `GET …/state?at=<ISO-8601 时刻>` | 该时刻或之前最后一次提交之后的状态（`+` 写作 `%2B`） |
| `GET …/state?minVersion=n` | 版本 `n` 或之后的最新状态：读到自己的写，`n` 取命令结果的 `version`。快照落后时用日志补齐；在 `wow.query.state.min-version-wait`（2s）内仍未提交到该版本时返回 409 `wow.query.min-version-not-reached`，其 `version` 为当前版本，并带 `Retry-After` |
| `GET …/state/trace?from=&to=&limit=` | 从 `from`（1）到 `to`（最后一次）的提交，最多 `limit` 条，每条带事件及其后的状态 |

调用方作用域之外的状态返回 403 `wow.aggregate.forbidden`；不存在的返回 404 `wow.aggregate.not-found`。属于租户的聚合（`Ownership.TENANT`）不检查调用方的所有者。已删除聚合的状态照常读取，`deleted` 为真。

## 查询

每个聚合有两个查询模型：

| 模型 | 路径 | 读取 |
| --- | --- | --- |
| `snapshot` | `/<context>/<aggregate>/snapshot/query` | 聚合当前的快照 |
| `events` | `/<context>/<aggregate>/events/query` | 一个聚合按版本的事件流 |

`POST` 执行查询；`GET` 返回模型的能力描述（字段、操作符与限制），以其版本作为 `ETag`。请求体在 `kind` 中给出操作，与查询本身并列；[查询语言](./query.md)逐一说明筛选条件、查询种类与响应、Kotlin DSL 与查询注解：

| `kind` | 返回 |
| --- | --- |
| `list` | 最多 `limit` 条记录（未给出时为 `wow.query.http.default-list-size`，100）；`Accept: text/event-stream` 时流式返回 |
| `paged` | 一页：`{"pagination": {"index": 1, "size": 20}}` |
| `cursor` | 游标之后的一页及下一个游标；需要 `sort` |
| `single` | 一条记录，或 404 |
| `count` | `{count, exact}`；超过 `limit`（最多 `wow.query.http.count-limit`，10000）时 `exact` 为 false |
| `aggregate` | 分组聚合 |

```bash
curl -X POST localhost:8080/example/order/snapshot/query \
  -H 'Content-Type: application/json' \
  -d '{"kind":"cursor","filter":{"op":"MATCH_ALL"},"sort":[{"field":"lastEventTime","direction":"DESC"}],"size":20}'
curl -X POST localhost:8080/example/order/events/query \
  -H 'Content-Type: application/json' -d '{"kind":"list","filter":{"op":"ID","value":"<order id>"}}'
```

- 调用方只看到自己的聚合：自己的租户，以及它有值时的所有者与空间。不带所有者的租户级调用方看到本租户的全部聚合。查询准入从 Manifest 读取每个聚合的归属：属于租户的聚合（`Ownership.TENANT`）的查询不按调用方的所有者限制。除非查询指明删除状态，否则看不到已删除的聚合。
- 快照查询可以筛选与排序系统字段（`tenantId`、`aggregateId`、`version`、`ownerId`、`spaceId`、`deleted`、`tags`、`firstEventTime`、`lastEventTime`、`operator`、`firstOperator`）以及补全后的片段中状态 Schema 描述的 `state.*`。状态字段上的 `@QueryIndex`（`me.ahoo.wow.api.query.annotation`，在 `wow-query-api` 中）声明一个由后端创建的索引（`wow.query.index.ddl`）；见[查询注解](./query.md#查询注解)。
- `events` 模型的筛选条件指定一个聚合的 ID，可以再限定 `version` 或 `commitTime`，不能有其他条件。
- 模型拒绝的查询返回 400 `wow.query.invalid`，其中的违例逐条指出规则；无法读成查询的返回 400 `wow.query.malformed`；超时为 `wow.query.timeout`。
- 快照在 PostgreSQL 与 MongoDB 上可以查询。内存后端上没有查询后端提供快照，其路由返回 404。
- 每个 HTTP 查询都在 `wow.query.http.*` 的预算下执行（见[配置项](./configuration.md#查询)）。

## 数据集

数据集是声明出来的只读分析表，查询方式与快照模型相同：

| 方法与路径 | 返回 |
| --- | --- |
| `POST /wow/dataset/<context>/<dataset>/query` | 对数据集的查询 |
| `GET /wow/dataset/<context>/<dataset>/query` | 它的能力描述，带 ETag |
| `GET /wow/dataset` | 调用方能看到的数据集 |

数据集是一个名为 `<context>.<dataset>.json` 的 JSON 文件，放在类路径的 `META-INF/wow/dataset/` 下，或工作目录的 `<wow.query.dataset.config-directory>/wow/dataset/` 下（默认 `config/wow/dataset/`），后者优先。示例的 `example.orders` 数据集读取订单流在 ClickHouse 中的视图：

```json
{
  "title": "Orders",
  "identity": "id",
  "eventTime": "commit_time",
  "scope": { "tenant": "tenant_id", "owner": "owner_id", "space": "space_id" },
  "storage": { "table": "example_order_state_flat" },
  "fields": {
    "id": { "types": ["STRING"], "nullable": false },
    "aggregate_id": { "types": ["STRING"], "nullable": false, "description": "The order's id." },
    "version": { "types": ["INTEGER"], "nullable": false },
    "commit_time": { "types": ["STRING"], "semantic": { "type": "TEMPORAL_DATE" }, "nullable": false }
  }
}
```

只有接入了数据集后端时才提供数据集：目前是 ClickHouse 的后端，需要类路径上有 `wow-clickhouse` 并设置 `wow.clickhouse.endpoint`。ClickHouse Sink 把每条发布的流写入 `<context>_<aggregate>_state_events`，并生成视图 `<context>_<aggregate>_state`、`_events` 与 `_state_flat`（状态顶层的标量字段展开为带类型的列），数据集读取它们。

## 错误码

框架的错误码，即 Manifest 列出的那些（`FrameworkErrors`）：

| 错误码 | 状态码 | 含义 |
| --- | --- | --- |
| `wow.auth.unauthenticated` | 401 | 请求没有有效凭据 |
| `wow.auth.forbidden` | 403 | 调用方不能发送这条命令，或不能使用这个 `Wow-Space` 或 `Wow-Scope` |
| `wow.aggregate.forbidden` | 403 | 聚合在调用方的作用域之外 |
| `wow.command.malformed` | 400 | 请求体、请求头或参数无法读取 |
| `wow.command.invalid` | 400 | 命令未通过校验；问题详情列出违例 |
| `wow.wait.invalid` | 400 | 等待计划不是运行时能跟随的链；命令未执行 |
| `wow.command.unhandled` | 400 | 聚合不处理这条命令 |
| `wow.aggregate.not-found` | 404 | 聚合不存在 |
| `wow.aggregate.exists` | 409 | 聚合已存在 |
| `wow.aggregate.version-mismatch` | 409 | 聚合不在期望的版本；请求带 `If-Match` 时为 412 |
| `wow.command.request-id-reused` | 409 | 请求 ID 已被另一条命令使用 |
| `wow.command.contended` | 409 | 并发写入者持续抢到版本；用同一请求 ID 重试是安全的 |
| `wow.aggregate.deleted` | 410 | 聚合已删除，只接受 `RestoreAggregate` |
| `wow.command.busy` | 429 | 节点在途命令过多 |
| `wow.command.unknown` | 503 | 不确定命令是否已提交；用同一请求 ID 重试是安全的 |
| `wow.node.closing` | 503 | 节点正在关闭；请在其他节点重试 |
| `wow.subscription.parked` | 500 | 等待链上的某个订阅停放了这个聚合 |
| `wow.wait.timeout` | 504 | 等待超时；命令可能已提交，问题详情带有最后到达的阶段 |
| `wow.query.malformed` | 400 | 查询请求体无法读成查询 |
| `wow.query.invalid` | 400 | 查询未通过准入；违例逐条指出规则 |
| `wow.query.failed` | 500 | 查询后端执行失败 |
| `wow.query.timeout` | 503 | 查询超出了预算允许的时间 |

`GET /wow/manifest` 在 `errors` 下列出它们，并在每个上下文下列出该上下文自己的错误码。
