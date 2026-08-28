---
title: 事件流聚合
description: 用六个业务场景说明事件流根文档与展开事件的 JVM 聚合查询。
---

# 事件流聚合

事件流聚合由 JVM `EventStreamQueryGateway` 与 `EventStreamQueryService` 支持。当前没有对应的 WebFlux HTTP 聚合路由、OpenAPI 操作、Schema HTTP 路由或 API Client；因此本页只提供 Kotlin DSL 与结果行，不提供会暗示这些入口存在的 HTTP JSON 请求。

## 能力与入口

- **JVM Gateway**：`EventStreamQueryGateway.aggregate(namedAggregate, query)` 通过策略链执行聚合。
- **JVM Service**：聚合级 `EventStreamQueryService` 可通过 `query.query(queryService)` 执行；Spring 管理的服务通常经 [QueryGateway](./query-gateway.md) 进入策略链，直接 Factory 与自定义 Bean 的绕过边界见[查询后端](./query-backend.md)。
- **公共合同**：Elements、group、metric、alias、排序与限制见[聚合查询](./aggregation-query.md)，根过滤的 Kotlin DSL 见[过滤条件](./filter-expression.md)，字段能力以 [Query Model Schema（当前说明）](../query.md#json-schema)为准。

聚合返回 `Flux<DynamicDocument>`；下面的结果只是代表性动态行，不是固定业务数据。

## 根文档、body 与统计单位

没有 Elements 时，一条记录是一份 `DomainEventStream` 根文档。根 filter、group 和 metric 使用绝对路径，例如 `tenantId`、`ownerId` 与 `createTime`。

`body` 是事件数组。调用 `expand("body")` 后，一条记录变为展开后的单个事件；Element filter、group、metric 与表达式字段都相对该事件，因此使用 `name`、`revision`、`bodyType` 和 `body.data`，不能重复根前缀写成 `body.name` 或 `body.body.data`。根 filter 仍使用根文档的绝对路径。`COUNT` 统计当前作用域，所以根事件流数量与展开后的事件数量不是同一指标。

## 场景 1：事件名称频次

**业务问题**

租户 `tenant-a` 的历史事件中，各事件名称分别出现多少次？

**统计单位**

展开后的单个事件；同一事件流中的多个事件分别计数。

**Kotlin DSL**

```kotlin
import me.ahoo.wow.query.dsl.aggregation
import me.ahoo.wow.query.event.EventStreamQueryService
import me.ahoo.wow.query.event.query

fun eventNameFrequency(queryService: EventStreamQueryService) = aggregation {
    filter { tenantId("tenant-a") }
    expand("body")
    terms("name", "eventName")
    count("eventCount")
    sort { "eventCount".desc() }
    limit(10)
}.query(queryService)
```

**结果解读**

```json
[
  {"eventName": "OrderCreated", "eventCount": 84},
  {"eventName": "OrderPaid", "eventCount": 61}
]
```

`eventName` 是事件名称分组，`eventCount` 是各组的事件条数；排序引用 metric alias。

**边界**

`body` 必须具备 Element scope，展开后的 `name` 必须具备 TERMS 能力。

## 场景 2：修订版本与消息类型

**业务问题**

历史事件在“修订版本 × 消息类型”两个维度上如何分布？

**统计单位**

展开后的单个事件；每个事件进入一个 `revision` 与 `bodyType` 组合。

**Kotlin DSL**

```kotlin
val query = aggregation {
    expand("body")
    terms("revision", "revision")
    terms("bodyType", "bodyType")
    count("eventCount")
}
```

**结果解读**

```json
[
  {"revision": "0.0.1", "bodyType": "me.ahoo.wow.example.api.order.OrderCreated", "eventCount": 132},
  {"revision": "0.0.2", "bodyType": "me.ahoo.wow.example.api.order.OrderCreated", "eventCount": 27}
]
```

两个 group alias 构成两级分组，`eventCount` 是组合内的事件条数。

**边界**

展开后的相对路径是 `revision` 与 `bodyType`；真实字段路径、值类型和 TERMS 能力必须与当前 Query Model Schema 匹配，不能把示例值当作固定协议。

## 场景 3：事件流创建趋势

**业务问题**

每天创建了多少份事件流？

**统计单位**

事件流根文档；不展开 `body`，一次命令产生的一份事件流计数一次。

**Kotlin DSL**

```kotlin
val query = aggregation {
    dateHistogram(
        "createTime",
        AggregationDateUnit.DAY,
        "day",
        ZoneOffset.UTC,
    )
    count("streamCount")
}
```

**结果解读**

```json
[
  {"day": 1787846400000, "streamCount": 31},
  {"day": 1787932800000, "streamCount": 24}
]
```

`day` 是 UTC 日期桶起点的 epoch 毫秒，`streamCount` 是首个事件创建时间落在桶内的根事件流数量。

**边界**

`createTime` 是根字段，不是展开事件字段；`DomainEventStream.createTime` 来自 `body.first().createTime`，不代表后端 append 或 ingestion 时间。日期直方图能否执行取决于 Schema 暴露的时间聚合能力以及后端对该字段的实际映射。

## 场景 4：租户与所有者活跃度

**业务问题**

各租户及所有者分别产生了多少份历史事件流？

**统计单位**

事件流根文档；每份事件流进入一个 `tenantId` 与 `ownerId` 组合，不等价于事件条数。

**Kotlin DSL**

```kotlin
val query = aggregation {
    terms("tenantId", "tenantId")
    terms("ownerId", "ownerId")
    count("streamCount")
}
```

**结果解读**

```json
[
  {"tenantId": "tenant-a", "ownerId": "user-1", "streamCount": 48},
  {"tenantId": "tenant-a", "ownerId": "user-2", "streamCount": 19}
]
```

`streamCount` 表示历史写入活动对应的事件流份数。

**边界**

查询没有展开 `body`；若一份事件流包含多个事件，它仍只计数一次。`tenantId` 与 `ownerId` 必须具备 TERMS 能力，授权与作用域策略仍须在聚合执行前完成。

## 场景 5：事件流数量与事件数量

**业务问题**

同一租户写入了多少份事件流，这些事件流又包含多少个事件？

**统计单位**

第一条查询统计事件流根文档；第二条查询统计展开后的单个事件。

**Kotlin DSL**

```kotlin
val streamCountQuery = aggregation {
    filter { tenantId("tenant-a") }
    count("streamCount")
}

val eventCountQuery = aggregation {
    filter { tenantId("tenant-a") }
    expand("body")
    count("eventCount")
}
```

**结果解读**

`streamCountQuery` 返回：

```json
[{"streamCount": 120}]
```

`eventCountQuery` 返回：

```json
[{"eventCount": 438}]
```

相同根过滤得到 `120` 份事件流与其中 `438` 个事件。

**边界**

Elements 决定统计单位，这两个数不能用同一条含混的 `COUNT` 表达；需要两个指标时分别执行两条查询，并在调用方按明确名称组合结果。

## 场景 6：事件载荷分析

**业务问题**

示例事件载荷中的 `data` 值分别出现多少次？

**统计单位**

展开后的单个事件；每个具有可聚合 `data` 值的事件参与分组。

**Kotlin DSL**

示例 Query Model Schema 中载荷字段的根路径为 `body.body.data`。展开 `body` 后，group 字段必须使用相对路径：

```kotlin
val query = aggregation {
    expand("body")
    terms("body.data", "data")
    count("eventCount")
}
```

**结果解读**

```json
[
  {"data": "APPROVED", "eventCount": 73},
  {"data": "REJECTED", "eventCount": 11}
]
```

`data` 是 payload 值分组，`eventCount` 是各值对应的事件条数。

**边界**

`body.body.data` 不是系统字段的通配承诺，必须由实际 Query Model Schema 声明并验证 TERMS 能力；MongoDB 还需以可查询形态存储该 payload，Elasticsearch 还需为 `body.body` 提供保持事件关联且可聚合的 mapping。

## 字段可用性与后端边界

- 系统 Schema 声明根 `createTime`、`tenantId`、`ownerId` 以及事件元数据 `body.name`、`body.revision`、`body.bodyType`；具体操作能力仍由运行时 Schema 与 MongoDB / Elasticsearch adapter 共同解析。
- `body` 展开后，Element filter、group、metric 与表达式字段相对单个事件；payload 的 Schema 根路径仍写作 `body.body.*`，查询中的相对路径写作 `body.*`。
- MongoDB 与 Elasticsearch 共享公共 AST，但不承诺物理 pipeline、mapping、空值或桶细节完全一致。Elasticsearch 需要能保持同一事件内字段关联的 `body` nested mapping。
- 自定义 `EventStreamQueryService` 可能沿用默认的“不支持聚合”实现；普通事件流数据查询可用不能单独证明该自定义后端会执行聚合。
- 本页能力仅限 JVM。当前没有事件流聚合 HTTP、OpenAPI、Schema HTTP 或 API Client 入口；不要从 Kotlin DSL 或结果 JSON 推断这些入口存在。
