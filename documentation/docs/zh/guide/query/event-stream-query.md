---
title: 事件流查询
description: 查询聚合事件历史、事件流字段路径及已发布的 HTTP 入口。
---

# 事件流查询

## 查询模型

`EventStreamQueryGateway` 查询 `DomainEventStream`。一个事件流是一次命令执行产生的事件集合，系统信封包含 `id`、`aggregateId`、`tenantId`、`ownerId`、`spaceId`、`version`、`createTime`、`requestId` 和 `commandId` 等字段；事件集合位于 `body`。它保留完整历史，不会自动追加快照的 `DELETION = ACTIVE` 条件。

## 根字段与事件 body

根字段直接查询，例如 `aggregateId`、`tenantId`、`version`、`createTime`。`body` 是事件数组；单个事件的元数据为 `body.id`、`body.name`、`body.revision`、`body.bodyType`，payload 位于 `body.body`。payload 字段必须由 Query Model Schema 声明，并受 MongoDB 可查询存储或 Elasticsearch `body.body` mapping 能力约束。

## JVM 查询

`EventStreamQueryGateway` 在 JVM 支持 typed 和 dynamic 的 single/list/paged/count；`dynamicQuery` 返回 `ObjectNode`。Gateway 也提供 JVM aggregation，其 JVM 与 HTTP/OpenAPI 合同和示例见[事件流聚合](./event-stream-aggregation.md)。

按根字段分页的示例：

```kotlin
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.event.EventStreamQueryGateway
import me.ahoo.wow.query.event.query

fun findRecentStreams(queryGateway: EventStreamQueryGateway) = pagedQuery {
    filter { tenantId("tenant-a") }
    sort { "createTime".desc() }
    pagination { index(1); size(20) }
}.query(queryGateway)
```

Spring 管理的聚合级 Gateway 会执行完整治理链；直接 Backend Factory 的绕过边界见[查询后端](./query-backend.md)与[查询网关](./query-gateway.md)。

## HTTP 路由

以下为 `sales-order` 当前发布的基础事件流数据查询路由：

```text
POST /sales-order/event/list
POST /sales-order/event/paged
POST /sales-order/event/count
```

相同的 list、paged 与 count 操作还发布 tenant 与 owner 作用域变体：

```text
POST /tenant/{tenantId}/sales-order/event/{list|paged|count}
POST /owner/{ownerId}/sales-order/event/{list|paged|count}
```

聚合与 Schema 是独立于上述数据查询形态的合同：

```text
POST /sales-order/event/aggregation
POST /tenant/{tenantId}/sales-order/event/aggregation
POST /owner/{ownerId}/sales-order/event/aggregation
GET /sales-order/event/schema
POST /sales-order/event/schema/refresh
```

当前仍没有事件流 `single` HTTP 路由，也没有 EventStream API Client。聚合请求与 JSON/SSE 响应见[事件流聚合](./event-stream-aggregation.md)；Schema 路由是无 tenant/owner 变体的模型级入口。精确路径以运行实例生成的 OpenAPI 为准。

## 按版本加载事件流

按聚合 ID 和连续版本范围加载使用 GET 路由，例如：

```http
GET /tenant/tenant-a/sales-order/order-1/event/3/8
Accept: application/json
```

它按 `aggregateId` 与 `version` 范围构造列表查询；`sales-order` 的已发布路径包含 tenant 前缀，不声明 owner 变体。列表加载可协商 JSON 或 SSE；其他聚合的作用域变体以应用生成的 OpenAPI 为准。

## 空结果

JVM single 无匹配时返回空 `Mono`；list 返回空 `Flux`，paged 返回空页。HTTP list、paged 和按版本加载在无匹配时返回空集合或空页；它们没有 single 的 404 语义。HTTP guard、Schema 解析或授权失败仍是错误，不能与空结果混淆。

## 与快照查询的差异

| 维度 | 事件流 | 快照 |
| --- | --- | --- |
| 业务数据根 | `body` 事件数组，payload 为 `body.body` | `state` 当前业务状态 |
| 删除默认值 | 不添加删除条件 | 默认 `DELETION = ACTIVE` |
| HTTP 数据查询 | list、paged、count、按版本加载 | single、list、paged、count 与 state-only |
| HTTP 聚合 | `event/aggregation`，JSON 或 SSE | `snapshot/aggregation`，JSON 或 SSE |
| HTTP Schema | `event/schema` 与 refresh | `snapshot/schema` 与 refresh |
| API Client | 无 | 有独立快照合同 |

## 何时使用事件流查询

当问题需要完整事件历史、一次命令产生的事件、版本范围或事件 payload 时使用事件流查询。读取当前业务状态或按当前状态筛选时，使用[快照查询](./snapshot-query.md)。
