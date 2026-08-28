---
title: 事件流查询
description: 查询聚合事件历史、事件流字段路径及已发布的 HTTP 入口。
---

# 事件流查询

## 查询模型

`EventStreamQueryService` 查询 `DomainEventStream`。一个事件流是一次命令执行产生的事件集合，系统信封包含 `id`、`aggregateId`、`tenantId`、`ownerId`、`spaceId`、`version`、`createTime`、`requestId` 和 `commandId` 等字段；事件集合位于 `body`。它保留完整历史，不会自动追加快照的 `DELETION = ACTIVE` 条件。

## 根字段与事件 body

根字段直接查询，例如 `aggregateId`、`tenantId`、`version`、`createTime`。`body` 是事件数组；单个事件的元数据为 `body.id`、`body.name`、`body.revision`、`body.bodyType`，payload 位于 `body.body`。payload 字段必须由 Query Model Schema 声明，并受 MongoDB 可查询存储或 Elasticsearch `body.body` mapping 能力约束。

## JVM 查询

`EventStreamQueryService` 在 JVM 支持 typed 和 dynamic 的 single/list/paged/count；`dynamicQuery` 返回 `DynamicDocument`。服务接口也有 JVM aggregation，但其语义与示例由事件流聚合页面说明，不能据此推断 HTTP 路由。

按根字段分页的示例：

```kotlin
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.query

fun findRecentStreams(queryService: EventStreamQueryService) = pagedQuery {
    filter { tenantId("tenant-a") }
    sort { "createTime".desc() }
    pagination { index(1); size(20) }
}.query(queryService)
```

通过 Spring 管理的服务会进入 QueryGateway；直接 Factory 的绕过边界见[查询后端](./query-backend.md)与[查询网关](./query-gateway.md)。

## HTTP 路由

以下为 `sales-order` 当前实际发布的事件流路由：

```text
POST /sales-order/event/list
POST /sales-order/event/paged
POST /sales-order/event/count
GET /sales-order/{id}/event/{headVersion}/{tailVersion}
```

当前没有事件流 `single`、聚合或 Schema HTTP 路由，也没有事件流 API Client。JVM 聚合能力由事件流聚合页说明；不要用不存在的 HTTP 请求来表达它。

## 按版本加载事件流

按聚合 ID 和连续版本范围加载使用 GET 路由，例如：

```http
GET /sales-order/order-1/event/3/8
Accept: application/json
```

它按 `aggregateId` 与 `version` 范围构造列表查询；对 tenant 聚合，生成的 OpenAPI 可能要求 tenant 路径前缀。列表加载可协商 JSON 或 SSE，实际路径和作用域变体以应用的 OpenAPI 为准。

## 空结果

JVM single 无匹配时返回空 `Mono`；list 返回空 `Flux`，paged 返回空页。HTTP list、paged 和按版本加载在无匹配时返回空集合或空页；它们没有 single 的 404 语义。HTTP guard、Schema 解析或授权失败仍是错误，不能与空结果混淆。

## 与快照查询的差异

| 维度 | 事件流 | 快照 |
| --- | --- | --- |
| 业务数据根 | `body` 事件数组，payload 为 `body.body` | `state` 当前业务状态 |
| 删除默认值 | 不添加删除条件 | 默认 `DELETION = ACTIVE` |
| HTTP 数据查询 | list、paged、count、按版本加载 | single、list、paged、count 与 state-only |
| HTTP 聚合/Schema/API Client | 都没有 | 有独立快照合同 |

## 何时使用事件流查询

当问题需要完整事件历史、一次命令产生的事件、版本范围或事件 payload 时使用事件流查询。读取当前业务状态或按当前状态筛选时，使用[快照查询](./snapshot-query.md)。
