---
title: 快照查询
description: 查询聚合当前物化状态、快照字段路径及已发布的 HTTP 入口。
---

# 快照查询

## 查询模型

`SnapshotQueryGateway<S>` 查询 `MaterializedSnapshot<S>`：它包含 `aggregateId`、`tenantId`、`ownerId`、`spaceId`、`version`、事件时间、`deleted` 等系统字段，以及当前业务状态 `state`。快照适合读取聚合的当前状态，不是完整事件历史；公共请求形态见[数据查询](./data-query.md)。

## 字段路径

业务字段从 `state` 开始，例如 `state.status`、`state.total`。Kotlin DSL 的 `pathState { ... }` 是该根路径的简写：

```kotlin
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.snapshot.SnapshotQueryGateway
import me.ahoo.wow.query.snapshot.pathState
import me.ahoo.wow.query.snapshot.query

fun findPaidOrders(queryGateway: SnapshotQueryGateway<OrderState>) = pagedQuery {
    filter {
        pathState { "status" eq "PAID" }
    }
    pagination { index(1); size(20) }
}.query(queryGateway)
```

同一请求的 HTTP JSON 使用完整逻辑路径：

```json
{
  "filter": { "op": "EQ", "field": "state.status", "value": "PAID" },
  "pagination": { "index": 1, "size": 20 }
}
```

字段是否可查询仍由运行时 Schema 与后端能力决定；不要因 state-only 响应而把请求字段写成 `status`。

## 默认删除条件

快照查询默认追加 `DELETION = ACTIVE`，因此不会返回已删除快照。根表达式或根 `AND` 合取树中的显式 `DELETION` 会覆盖默认范围；位于 `OR` 或 `NOR` 内的删除条件不会移除 ACTIVE guard。需要 `DELETED` 或 `ALL` 时显式使用该操作符，详见[过滤条件](./filter-expression.md#删除标记与全文搜索)。

## JVM 查询

注入聚合级 `SnapshotQueryGateway<S>` 后，可通过扩展执行 typed 的 single/list/paged/cursor/count；`dynamicQuery` 返回 `ObjectNode`，适用于 projection 改变返回形状的场景。Backend 返回节点并完成通用结果 Filter 后，框架内建 `SchemaMaskQueryFilter` 按 Query Model Schema 自动脱敏，Gateway 最后用 Jackson 物化 typed 结果；typed、dynamic 与 state-only 使用同一条受管路径。详见[字段脱敏](./masking.md)；direct Factory 的原始值边界见[查询后端](./query-backend.md)与[查询网关](./query-gateway.md)。

## 游标查询

`CursorQuery` 使用 `filter`、`projection`、`sort`、`size` 和可选 `cursor`，返回只有 `list` 与 `nextCursor` 的 `CursorPage`。第一次请求省略 `cursor` 或传 `null`；后续请求保持 `filter` 与 `sort` 不变，并传回上一页的 `nextCursor`；`nextCursor == null` 时终止。

同一个 Snapshot Gateway 提供 typed、dynamic 与 state-only 结果：

```kotlin
import me.ahoo.wow.query.dsl.cursorQuery
import me.ahoo.wow.query.snapshot.dynamicQuery
import me.ahoo.wow.query.snapshot.pathState
import me.ahoo.wow.query.snapshot.query
import me.ahoo.wow.query.snapshot.toStateCursorPage

val query = cursorQuery {
    filter { pathState { "status" eq "PAID" } }
    sort { "version".desc() }
    size(20)
}

val typed = query.query(queryGateway)
val dynamic = query.dynamicQuery(queryGateway)
val stateOnly = query.query(queryGateway).toStateCursorPage()
```

对应的 HTTP 请求与响应为：

```json
{
  "filter": { "op": "EQ", "field": "state.status", "value": "PAID" },
  "projection": { "include": ["state.status", "version"] },
  "sort": [{ "field": "version", "direction": "DESC" }],
  "size": 20,
  "cursor": null
}
```

```json
{
  "list": [],
  "nextCursor": null
}
```

`POST /sales-order/snapshot/cursor` 返回完整快照，`POST /sales-order/snapshot/cursor/state` 返回 state-only；两者都只返回 JSON。响应式与同步 API Client 的显式 opt-in 示例见[API 客户端](./query-api-client.md)。后端执行与 token 边界见[查询后端](./query-backend.md)。

## HTTP 路由

以下为 `sales-order` 已发布的基础快照数据查询路由；聚合与 Schema 不在本表中：

```text
POST /sales-order/snapshot/single
POST /sales-order/snapshot/single/state
POST /sales-order/snapshot/list
POST /sales-order/snapshot/list/state
POST /sales-order/snapshot/paged
POST /sales-order/snapshot/paged/state
POST /sales-order/snapshot/cursor
POST /sales-order/snapshot/cursor/state
POST /sales-order/snapshot/count
```

相同的 single、single/state、list、list/state、paged、paged/state 和 count 操作还发布 tenant 与 owner 作用域变体：

```text
POST /tenant/{tenantId}/sales-order/snapshot/{operation}
POST /owner/{ownerId}/sales-order/snapshot/{operation}
```

其中 `{operation}` 是上述九种操作之一。list 可以协商 JSON 或 SSE；single、paged 与 cursor 返回 JSON。聚合路由及 [Query Model Schema（当前说明）](./query-model-schema.md) 路由是独立合同；精确路径以运行实例生成的 [OpenAPI](../open-api.md) 为准。HTTP guard 仍可能限制本来有效的 DTO。

## 完整快照、state-only 与动态结果

- 完整快照返回 `MaterializedSnapshot<S>`，用于同时读取状态和系统元数据。
- `state-only` 路由只解包 `S`；它只改变响应，不改变 `state.*` 请求字段。
- dynamic 结果返回 `ObjectNode`，用于自定义 projection，但不保留 `S` 的编译期字段类型。

响应式与同步 API Client 的 typed、state-only、dynamic 调用见[API 客户端](./query-api-client.md)。

## 空结果与 404

JVM single 无匹配时返回空 `Mono`；list 返回空 `Flux`，paged 与 cursor 返回空页。HTTP `snapshot/single` 与 `snapshot/single/state` 无匹配时为 404。API Client 会把 single 的 404 转为响应式空 `Mono` 或同步 `null`；其他错误继续传播。

## 何时使用快照查询

当问题是“当前订单是什么状态”“当前余额是多少”或需要按当前业务状态筛选时，选择快照查询。需要命令产生的完整历史、事件版本或事件 payload 时，选择[事件流查询](./event-stream-query.md)。
