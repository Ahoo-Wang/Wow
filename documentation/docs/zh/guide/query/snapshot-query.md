---
title: 快照查询
description: 查询聚合当前物化状态、快照字段路径及已发布的 HTTP 入口。
---

# 快照查询

## 查询模型

`SnapshotQueryService<S>` 查询 `MaterializedSnapshot<S>`：它包含 `aggregateId`、`tenantId`、`ownerId`、`spaceId`、`version`、事件时间、`deleted` 等系统字段，以及当前业务状态 `state`。快照适合读取聚合的当前状态，不是完整事件历史；公共请求形态见[数据查询](./data-query.md)。

## 字段路径

业务字段从 `state` 开始，例如 `state.status`、`state.total`。Kotlin DSL 的 `pathState { ... }` 是该根路径的简写：

```kotlin
import me.ahoo.wow.query.dsl.pagedQuery
import me.ahoo.wow.query.snapshot.SnapshotQueryService
import me.ahoo.wow.query.snapshot.pathState
import me.ahoo.wow.query.snapshot.query

fun findPaidOrders(queryService: SnapshotQueryService<OrderState>) = pagedQuery {
    filter {
        pathState { "status" eq "PAID" }
    }
    pagination { index(1); size(20) }
}.query(queryService)
```

同一请求的 HTTP JSON 使用完整逻辑路径：

```json
{
  "filter": { "op": "EQ", "field": "state.status", "value": "PAID" },
  "pagination": { "index": 1, "size": 20 }
}
```

字段是否可查询仍由运行时 Schema 与后端能力决定；不要因 state-only 响应而把请求字段写成 `status`。

## 游标分页

游标分页使用稳定排序继续读取，适合深分页。首次请求省略 `cursor`，后续请求原样提交上一页的 `nextCursor`，并保持相同的 `filter` 与 `sort`：

```json
{
  "filter": { "op": "EQ", "field": "state.status", "value": "PAID" },
  "sort": [{ "field": "snapshotTime", "direction": "DESC" }],
  "size": 20,
  "cursor": null
}
```

```json
{
  "list": [{ "aggregateId": "order-1", "state": { "status": "PAID" } }],
  "nextCursor": "AQ...opaque-encrypted-token..."
}
```

当 `nextCursor == null` 时遍历结束。`CursorPage` 不提供 `total` 或 previous cursor。调用方必须保持 filter 与 sort 不变；token 不包含二者的指纹，服务端不会校验它们与上一页是否绑定，但会重新执行请求作用域、安全过滤并校验后端 cursor 结构。user sort 与追加唯一键后的 effective sort 都最多 32 个字段。

内置 MongoDB/Elasticsearch cursor 使用 JDK AES-256-GCM 加密，必须配置一个 Base64URL 编码的 32-byte `wow.query.cursor.encryption-key`。缺少该配置时应用与既有查询仍可启动/执行，但 CursorQuery 从第一页起返回 `UnsupportedOperationException`；轮换单 key 会使所有既有 cursor 失效。不要把真实 key 写入文档、源码或日志，配置示例见[基础设施配置](../../reference/config/infrastructure.md)。

游标不创建 PIT，也不保证多次请求间的快照一致性。并发写入时结果按后端的当前排序视图推进。游标查询不能修复缺失索引或昂贵 filter；MongoDB 需要匹配过滤与排序的复合索引，Elasticsearch 使用 `search_after`。

## 默认删除条件

快照查询默认追加 `DELETION = ACTIVE`，因此不会返回已删除快照。根表达式或根 `AND` 合取树中的显式 `DELETION` 会覆盖默认范围；位于 `OR` 或 `NOR` 内的删除条件不会移除 ACTIVE guard。需要 `DELETED` 或 `ALL` 时显式使用该操作符，详见[过滤条件](./filter-expression.md#删除标记与全文搜索)。

## JVM 查询

注入聚合级 `SnapshotQueryService<S>` 后，可通过扩展执行 typed 的 single/list/cursor/paged/count；`dynamicQuery` 返回 `DynamicDocument`，适用于 projection 改变返回形状的场景。服务经 Spring `QueryGateway` 的策略边界、直接 Factory 的绕过条件见[查询后端](./query-backend.md)与[查询网关](./query-gateway.md)。

## HTTP 路由

以下为 `sales-order` 已发布的基础快照数据查询路由；聚合与 Schema 不在本表中：

```text
POST /sales-order/snapshot/single
POST /sales-order/snapshot/single/state
POST /sales-order/snapshot/list
POST /sales-order/snapshot/list/state
POST /sales-order/snapshot/cursor
POST /sales-order/snapshot/cursor/state
POST /sales-order/snapshot/paged
POST /sales-order/snapshot/paged/state
POST /sales-order/snapshot/count
```

相同的 single、single/state、list、list/state、cursor、cursor/state、paged、paged/state 和 count 操作还发布 tenant 与 owner 作用域变体：

```text
POST /tenant/{tenantId}/sales-order/snapshot/{operation}
POST /owner/{ownerId}/sales-order/snapshot/{operation}
```

其中 `{operation}` 是上述九种操作之一。list 可以协商 JSON 或 SSE；single、cursor 与 paged 只返回 JSON。聚合路由及 [Query Model Schema（当前说明）](./query-model-schema.md) 路由是独立合同；精确路径以运行实例生成的 [OpenAPI](../open-api.md) 为准。HTTP guard 仍可能限制本来有效的 DTO。

## 完整快照、state-only 与动态结果

- 完整快照返回 `MaterializedSnapshot<S>`，用于同时读取状态和系统元数据。
- `state-only` 路由只解包 `S`；它只改变响应，不改变 `state.*` 请求字段。
- dynamic 结果返回 `DynamicDocument`，用于自定义 projection，但不保留 `S` 的编译期字段类型。

响应式与同步 API Client 的 typed、state-only、dynamic 调用见[API 客户端](./query-api-client.md)。

## 空结果与 404

JVM single 无匹配时返回空 `Mono`；list 返回空 `Flux`，paged 返回空页；配置 encryption key 后，cursor 无匹配时返回 `list = []` 且 `nextCursor = null`。未配置 key 属于不支持 cursor，不是空结果。HTTP `snapshot/single` 与 `snapshot/single/state` 无匹配时为 404。API Client 会把 single 的 404 转为响应式空 `Mono` 或同步 `null`；其他错误继续传播。

## 何时使用快照查询

当问题是“当前订单是什么状态”“当前余额是多少”或需要按当前业务状态筛选时，选择快照查询。需要命令产生的完整历史、事件版本或事件 payload 时，选择[事件流查询](./event-stream-query.md)。
