---
title: 查询服务
description: 通过 wow-mongo 和 wow-elasticsearch 模块提供的查询服务能力。
---

# 查询服务

:::tip
目前 `wow-mongo` 模块 与 `wow-elasticsearch` 模块支持查询服务。
:::

## 操作符

| 操作符           | 描述                                                                                                                                  |
|---------------|-------------------------------------------------------------------------------------------------------------------------------------|
| AND           | 对提供的条件列表执行逻辑与                                                                                                                       |
| OR            | 对提供的条件列表执行逻辑或                                                                                                                       |
| NOR           | 对提供的条件列表执行逻辑或非                                                                                                                      |
| ID            | 匹配`id`字段值等于指定值的所有文档                                                                                                                 |
| IDS           | 匹配`id`字段值等于指定值列表中的任何值的所有文档                                                                                                          |
| AGGREGATE_ID  | 匹配聚合根ID等于指定值的文档                                                                                                                     |
| AGGREGATE_IDS | 匹配聚合根ID等于指定值列表中的任何值的所有文档                                                                                                            |
| TENANT_ID     | 匹配`tenantId`字段值等于指定值的所有文档                                                                                                           |
| OWNER_ID      | 匹配`ownerId`字段值等于指定值的所有文档                                                                                                            |
| SPACE_ID      | 匹配`spaceId`字段值等于指定值的所有文档                                                                                                            |
| DELETED       | 匹配`deleted`字段值等于指定值的所有文档                                                                                                            |
| ALL           | 匹配所有文档                                                                                                                              |
| EQ            | 匹配字段名称值等于指定值的所有文档                                                                                                                   |
| NE            | 匹配字段名称值不等于指定值的所有文档                                                                                                                  |
| GT            | 匹配给定字段的值大于指定值的所有文档                                                                                                                  |
| LT            | 匹配给定字段的值小于指定值的所有文档                                                                                                                  |
| GTE           | 匹配给定字段的值大于或等于指定值的所有文档                                                                                                               |
| LTE           | 匹配给定字段的值小于或等于指定值的所有文档                                                                                                               |
| CONTAINS      | 匹配给定字段的值包含指定值的所有文档                                                                                                                  |
| IN            | 匹配字段值等于指定值列表中的任何值的所有文档                                                                                                              |
| NOT_IN        | 匹配字段值不等于任何指定值或不存在的所有文档                                                                                                              |
| BETWEEN       | 匹配字段值在指定值范围区间的所有文档                                                                                                                  |
| ALL_IN        | 匹配所有文档，其中字段值是包含所有指定值的数组                                                                                                             |
| STARTS_WITH   | 匹配字段值以指定字符串开头的文档                                                                                                                    |
| ENDS_WITH     | 匹配字段值以指定字符串结尾的文档                                                                                                                    |
| MATCH         | 全文匹配。与后端相关：MongoDB 使用 `text` 在已配置的文本索引上检索；Elasticsearch 使用 `match` 在指定字段上检索                                                                  |
| ELEM_MATCH    | 条件与包含数组字段的所有文档相匹配，其中数组中至少有一个成员与给定的条件匹配。                                                                                             |
| NULL          | 匹配字段值在指定值为`null`的所有文档                                                                                                               |
| NOT_NULL      | 匹配字段值在指定值不为`null`的所有文档                                                                                                              |
| TRUE          | 匹配字段值在指定值为`true`的所有文档                                                                                                               |
| FALSE         | 匹配字段值在指定值为`false`的所有文档                                                                                                              |
| EXISTS        | 匹配文档是否存在字段                                                                                                                          |
| RAW           | 原始操作符，将条件值直接作为原始的数据库查询条件                                                                                                            |
| TODAY         | 匹配字段在今天范围区间的所有文档。比如：`today` 为 `2024-06-06`，匹配范围 `2024-06-06 00:00:00.000` ~ `2024-06-06 23:59:59.999` 的所有文档                         |
| BEFORE_TODAY  | 匹配字段在今天_time_之前的所有文档                                                                                                                |
| TOMORROW      | 匹配字段在明天范围区间的所有文档。比如：`today` 为 `2024-06-06`，匹配范围 `2024-06-07 00:00:00.000` ~ `2024-06-07 23:59:59.999` 的所有文档                         |
| THIS_WEEK     | 匹配字段在本周范围区间的所有文档                                                                                                                    |
| NEXT_WEEK     | 匹配字段在下周范围区间的所有文档                                                                                                                    |
| LAST_WEEK     | 匹配字段在上周范围区间的所有文档                                                                                                                    |
| THIS_MONTH    | 匹配字段在本月范围区间的所有文档。比如：`today` : `2024-06-06`，匹配范围 : `2024-06-01 00:00:00.000` ~ `2024-06-30 23:59:59.999` 的所有文档                       |
| LAST_MONTH    | 匹配字段在上月范围区间的所有文档。比如：`today` : `2024-06-06`，匹配范围 : `2024-05-01 00:00:00.000` ~ `2024-05-31 23:59:59.999` 的所有文档                       |
| RECENT_DAYS   | 匹配字段在指定值最近天数范围区间的所有文档。比如：`today` : `2024-06-06`，近三天，匹配范围 : `2024-06-04 00:00:00.000` ~ `2024-06-06 23:59:59.999` 的所有文档。即 : 今天、昨天、前天 |
| EARLIER_DAYS  | 匹配字段在指定值之前天数范围的所有文档。比如：`today` : `2024-06-06`，前三天，匹配范围 : 小于`2024-06-04 00:00:00.000`的所有文档                                           |

:::info Elasticsearch 字符串字段
`CONTAINS`、`STARTS_WITH` 和 `ENDS_WITH` 是字面量操作，在 Elasticsearch 中应作用于 `keyword`、`wildcard` 等 term-level 字段；`*`、`?` 和 `\` 按普通字符匹配，三者均支持 `ignoreCase`。全文检索请使用 `MATCH`。
:::

## Query DSL

`Query DSL` 旨在提供一种简洁而灵活的方式来构建查询条件。

### ConditionDsl

```kotlin
condition {
    deleted(DeletionState.ALL)
    and {
        tenantId("tenantId")
        all()
    }
    nor {
        all()
    }
    id("id")
    ids("id", "id2")
    "field1" eq "value1"
    "field2" ne "value2"
    "filed3" gt 1
    "field4" lt 1
    "field5" gte 1
    "field6" lte 1
    "field7" contains "value7"
    "field8" isIn listOf("value8")
    "field9" notIn listOf("value9")
    "field10" between (1 to 2)
    "field100" between 1 to 2
    "field11" all listOf("value11")
    "field12" startsWith "value12"
    "field12" endsWith "value12"
    "field13" elemMatch {
        "field14" eq "value14"
    }
    "field15".isNull()
    "field16".notNull()
    "field17".isTrue()
    "field18".isFalse()
    and {
        "field3" eq "value3"
        "field4" eq "value4"
    }
    or {
        "field3" eq "value3"
        "field4" eq "value4"
    }
    "field19".today()
    "field20".tomorrow()
    "field21".thisWeek()
    "field22".nextWeek()
    "field23".lastWeek()
    "field24".thisMonth()
    "field25".lastMonth()
    "field26".recentDays(1)
    raw("1=1")
    "state" nested {
        "field27" eq "value27"
        "field28" eq "value28"
        "child" nested {
            "field29" eq "value29"
        }
        nested("")
        "field30" eq "value30"
    }
}
```

### SortDsl

```kotlin
sort {
    "field1".asc()
    "field2".desc()
}
```

### PaginationDsl

```kotlin
pagination {
    index(1)
    size(1)
}
```

### ProjectionDsl

```kotlin
projection {
    include("field1")
    exclude("field2")
}
```

### ListQueryDsl

```kotlin
listQuery {
    limit(1)
    sort {
        "field1".asc()
    }
    condition {
        "field1" eq "value1"
        "field2" eq "value2"
        and {
            "field3" eq "value3"
        }
        or {
            "field4" eq "value4"
        }
    }
}
```

### PagedQueryDsl

```kotlin
pagedQuery {
    pagination {
        index(1)
        size(10)
    }
    sort {
        "field1".asc()
    }
    condition {
        "field1" eq "value1"
        "field2" ne "value2"
        "filed3" gt 1
        "field4" lt 1
        "field5" gte 1
        "field6" lte 1
        "field7" contains "value7"
        "field8" isIn listOf("value8")
        "field9" notIn listOf("value9")
        "field10" between (1 to 2)
        "field11" all listOf("value11")
        "field12" startsWith "value12"
        "field13" elemMatch {
            "field14" eq "value14"
        }
        "field15".isNull()
        "field16".notNull()
        and {
            "field3" eq "value3"
            "field4" eq "value4"
        }
        or {
            "field3" eq "value3"
            "field4" eq "value4"
        }
    }
}
```

## 执行查询

```kotlin
listQuery {
    limit(1)
    sort {
        "field1".asc()
    }
    condition {
        "field1" eq "value1"
        and {
            "field3" eq "value3"
        }
        or {
            "field4" eq "value4"
        }
    }
}.query(queryService)
```

## 执行分页查询

```kotlin
pagedQuery {
    pagination {
        index(1)
        size(10)
    }
    sort {
        "field1".asc()
    }
    condition {
        and {
            "field3" eq "value3"
            "field4" startsWith "value4"
        }
        or {
            "field3" eq "value3"
            "field4" startsWith "value4"
        }
    }
}.query(queryService)
```

## 重写查询

```kotlin
@Component
@Order(ORDER_FIRST)
@FilterType(SnapshotQueryHandler::class)
class DataFilterSnapshotQueryFilter : SnapshotQueryFilter {

    override fun filter(
        context: QueryContext<*, *>,
        next: FilterChain<QueryContext<*, *>>,
    ): Mono<Void> {

        return Mono.deferContextual {
            /**
             * 重写查询，将仓库ID附加到查询条件中。
             */
            context.asRewritableQuery().rewriteQuery { query ->
                val warehouseIdCondition = condition {
                    nestedState()
                    WarehouseIdCapable::warehouseId.name eq warehouseId
                }
                query.appendCondition(warehouseIdCondition)
            }
            next.filter(context)
        }
    }
}
```

## OpenAPI

**Wow** 除了为命令(`Command`)自动生成了 _OpenAPI_ 端点，另外还提供了查询(`Query`) _OpenAPI_ 端点。
这意味着开发人员通常只需专注于编写领域模型，即可完成服务开发，而无需费心处理查询逻辑的实现，极大提升了开发效率。

以下示例使用 `tenant-1` 的 `sales-order` 聚合，依次演示五种查询入口。

![Query Service](/images/query/open-api-query.png)

### 分页查询

::: code-group

```shell [OpenAPI]
  curl -X 'POST' \
  'http://localhost:8080/tenant/tenant-1/sales-order/snapshot/paged' \
  -H 'accept: application/json' \
  -H 'Content-Type: application/json' \
  -H 'Wow-Space-Id: space-1' \
  -d '{
  "sort": [
    {
      "field": "_id",
      "direction": "DESC"
    }
  ],
  "pagination": {
    "index": 1,
    "size": 10
  },
  "condition": {
    "field": "state.status",
    "operator": "EQ",
    "value": "CREATED",
    "children": []
  }
}'
```

```json [响应（已省略部分字段）]
{
  "total": 1,
  "list": [
    {
      "aggregateId": "order-1",
      "tenantId": "tenant-1",
      "version": 3,
      "state": {
        "id": "order-1",
        "status": "CREATED"
      }
    }
  ]
}
```

```typescript [Typescript]
import { eq } from "@ahoo-wang/fetcher-wow";

eq("state.status", "CREATED")
```

:::

### 查询

::: code-group

```shell [OpenAPI]
  curl -X 'POST' \
  'http://localhost:8080/tenant/tenant-1/sales-order/snapshot/list' \
  -H 'accept: application/json' \
  -H 'Content-Type: application/json' \
  -H 'Wow-Space-Id: space-1' \
  -d '{
  "sort": [
    {
      "field": "_id",
      "direction": "DESC"
    }
  ],
  "limit": 1,
  "condition": {
    "field": "state.status",
    "operator": "EQ",
    "value": "CREATED",
    "children": []
  }
}'
```

```json [响应（已省略部分字段）]
[
  {
    "aggregateId": "order-1",
    "tenantId": "tenant-1",
    "version": 3,
    "state": {
      "id": "order-1",
      "status": "CREATED"
    }
  }
]
```

:::

### 计数(`Count`)

::: code-group

```shell [OpenAPI]
  curl -X 'POST' \
  'http://localhost:8080/tenant/tenant-1/sales-order/snapshot/count' \
  -H 'accept: application/json' \
  -H 'Content-Type: application/json' \
  -H 'Wow-Space-Id: space-1' \
  -d '{
    "field": "state.status",
    "operator": "EQ",
    "value": "CREATED",
    "children": []
  }'
```

```json [响应]
1
```

:::

### 获取单个模型

::: code-group

```shell [OpenAPI]
  curl -X 'POST' \
  'http://localhost:8080/tenant/tenant-1/sales-order/snapshot/single' \
  -H 'accept: application/json' \
  -H 'Content-Type: application/json' \
  -H 'Wow-Space-Id: space-1' \
  -d '{
  "sort": [],
  "condition": {
    "field": "_id",
    "operator": "EQ",
    "value": "order-1",
    "children": []
  }
}'
```

```json [响应（已省略部分字段）]
{
  "aggregateId": "order-1",
  "tenantId": "tenant-1",
  "version": 3,
  "state": {
    "id": "order-1",
    "status": "CREATED"
  }
}
```

:::

## 快照 Elements 聚合

快照聚合只暴露 MongoDB 与 Elasticsearch 都能精确执行的表格语义。HTTP 入口为
`POST {aggregate-path}/snapshot/aggregation`，支持 `application/json` 与
`text/event-stream`。结果中的分组键和指标均使用显式 alias。

### Kotlin DSL

```kotlin
aggregationQuery {
    condition { "state.status" eq "CREATED" }
    expand("state.items") {
        condition { "quantity" gt 0 }
        groupBy("productId", "productId")
        sum("totalPrice", "totalAmount")
        count("lineCount")
        sort { "totalAmount".desc() }
        limit(100)
    }
}
```

`expand` 块内的字段使用相对路径；构建出的 `AggregationQuery` 会统一转换为绝对路径。
`groupBy`、指标、排序和 limit 只能声明在最内层；每层最多一个子 `expand`。

### HTTP JSON

直接构造 Kotlin 模型或发送 JSON 时，Elements、条件、分组和指标中的字段都必须使用绝对路径。
`type` 是 Group、Metric 和 Expression 的 Jackson discriminator。
生成的 OpenAPI 会分别给出 Elements、Terms、Numeric 与 Temporal 字段枚举，避免客户端选择运行时必然拒绝的字段类型。
以下示例包含 Elements 和指标排序，调用前需设置
`wow.webflux.query.allow-expensive-operators=true`；默认配置会拒绝该请求。

::: code-group

```shell [请求]
curl -X POST \
  'http://localhost:8080/tenant/tenant-1/sales-order/snapshot/aggregation' \
  -H 'Accept: application/json' \
  -H 'Content-Type: application/json' \
  -H 'Wow-Space-Id: space-1' \
  -d '{
    "condition": {
      "field": "state.status",
      "operator": "EQ",
      "value": "CREATED",
      "children": []
    },
    "elements": [
      {
        "path": "state.items",
        "condition": {
          "field": "state.items.quantity",
          "operator": "GT",
          "value": 0,
          "children": []
        }
      }
    ],
    "groupBy": [
      {
        "type": "TERMS",
        "field": "state.items.productId",
        "alias": "productId"
      }
    ],
    "metrics": [
      {
        "type": "NUMERIC",
        "function": "SUM",
        "expression": {
          "type": "FIELD",
          "field": "state.items.totalPrice"
        },
        "alias": "totalAmount"
      },
      {
        "type": "COUNT",
        "alias": "lineCount"
      }
    ],
    "sort": [
      {
        "field": "totalAmount",
        "direction": "DESC"
      }
    ],
    "limit": 100
  }'
```

```json [响应]
[
  {
    "productId": "product-1001",
    "totalAmount": 128.0,
    "lineCount": 4
  },
  {
    "productId": "product-1002",
    "totalAmount": 96.5,
    "lineCount": 2
  }
]
```

:::

将 `Accept` 改为 `text/event-stream` 时，每个结果行独立发送；查询语义与 JSON 响应完全相同。

### 来源与字段作用域

- `elements=[]` 表示在根 Snapshot 上聚合；否则按从外到内顺序声明严格父子对象集合链。
- Elements 只接受对象集合或对象数组；Map、标量集合、重复路径、跳过中间集合和兄弟集合笛卡尔积都会被拒绝。
- `groupBy`、指标及表达式字段必须属于最内层来源，不能隐式访问父级、兄弟或未展开的子集合。
- 每层 `AggregationElement.condition` 只能访问该层标量字段或非集合对象路径，并且不能使用 `ELEM_MATCH`。
- 根 `ELEM_MATCH` 只筛选“包含匹配元素的快照”，不会筛选随后展开的行；行过滤必须写入对应 Element condition。
- 缺失、`null` 或空集合不产生展开行；任一分组字段缺失或为 `null` 时，该行不进入 bucket。

### 分组与指标

| 类型 | 输入约束 | 结果 |
|---|---|---|
| `TERMS` | 字符串、枚举、UUID、Boolean 或数值标量；拒绝 temporal | 整数 key 归一化为 `Long`，浮点/Decimal key 归一化为 `Double` |
| `HISTOGRAM` | 数值标量；`interval` 必须有限且大于 0；首版无 offset | bucket key 为 `Double` |
| `DATE_HISTOGRAM` | 支持的 temporal/`Date` 字段；unit 为 `YEAR`、`QUARTER`、`MONTH`、`WEEK`、`DAY`、`HOUR`、`MINUTE`、`SECOND` | epoch milliseconds `Long`；`WEEK` 从 Monday 开始 |
| `COUNT` | 无字段 | 根查询统计快照数；Elements 查询统计最内层展开行，返回 `Long` |
| `NUMERIC` | `SUM`、`AVG`、`MIN`、`MAX` + `FIELD` 数值表达式 | `Double?`；缺失值被忽略，空集 `SUM=0.0`，其余为 `null` |

`DateHistogram.timeZone` 默认为 `UTC`，只接受 IANA ID（如 `Asia/Shanghai`）或
`±HH:MM`；`Z`、`UTC+08:00` 等非约定格式会被拒绝。任何非有限 Numeric metric 结果都会使整个查询失败。

### 结果、排序与空集

- 无 `groupBy` 时始终返回一行，禁止 sort，limit 仍需合法但不会改变单行结果。
- 有 `groupBy` 且没有 bucket 时返回空流。
- 默认按 groupBy 声明顺序升序；显式 sort 后会追加尚未出现的 group alias 升序，保证结果稳定。
- sort 字段必须唯一且只能引用输出 alias。升序时 `null` 在前，降序时 `null` 在后。
- 仅按 group alias 排序时，后端达到 limit 即可停止；按 metric alias 排序时必须完整遍历 bucket，再计算精确 Top-N。
- alias 在 groupBy 与 metrics 间全局唯一；不能为空、包含 `.`/NUL、以 `$` 或 `__wow_` 开头，也不能为 `_id`。

### 限制与 HTTP 防护

| 项目 | 公共模型 | HTTP 默认 |
|---|---:|---:|
| Elements 层数 | 5 | 3 |
| Element/group/expression 字段路径段数 | 10 | 10 |
| groupBy 数量 | 32 | 32 |
| metrics 数量 | 1..64 | 1..32 |
| limit | 默认 100，最大 10,000 | 有分组时还受 `max-list-size=1000` 限制 |
| 根条件与全部 Element conditions | — | 合计最多 `max-condition-nodes=64` 个节点 |

将 `max-aggregation-elements`、`max-aggregation-metrics` 或 `max-list-size` 设为 `0`
只会关闭对应 HTTP 上限，公共模型硬上限仍然生效。以下请求还要求
`allow-expensive-operators=true`：

- 包含任意 Elements；
- 用户提交的根 condition 为 match-all；
- sort 引用任意 metric alias。

HTTP guard 在 tenant/owner/space 和 ABAC 条件注入前统计用户条件；受信任条件不消耗用户预算，
也不会把用户提交的 match-all 自动变成低成本查询。Snapshot 配置 masker 时，聚合会在访问后端前 fail-closed。
HTTP 层不维护重复的字段白名单；聚合元数据 Validator 统一校验集合链、字段归属和可移植类型。
指标和分组没有脚本入口；HTTP `RAW` condition 仍遵循通用 `allow-raw` 开关且默认关闭。

### 后端失败与性能边界

- Elasticsearch 要求每层 Elements 映射为 `nested`，`DateHistogram` 映射为 `date`/`date_nanos`；普通 `object` 或 epoch `long` 会被拒绝。
- MongoDB 使用逐层 `$unwind`；字符串分组和排序固定使用 `simple` collation。
- timeout、分片失败、响应结构缺失、类型转换失败或非有限指标结果都会使整个查询失败，不返回部分结果。

当前单线程工程基线使用 10,000 个快照、每快照 100 个叶子元素。Elements group-key 排序约为
MongoDB `393–1,639 ms/op`、Elasticsearch `1–8 ms/op`；精确 metric Top-N 约为
MongoDB `1.61–1.84 s/op`、Elasticsearch `1.79 s/op`。这些数值是一次 JMH 运行的点估计区间
（1 fork、3 次测量，部分场景方差较高），仅用于识别昂贵操作，不代表生产 SLA、回归阈值或跨后端排名。参见
[完整基准测试报告](https://github.com/Ahoo-Wang/Wow/blob/main/wow-benchmarks/results/reports/snapshot-elements.md)。

## 查询服务注册器

`SnapshotQueryServiceRegistrar` 用于自动将所有本地聚合根查询服务注册到 `Spring` 容器中。
开发者可以通过指定的 `Bean Name` 从 `BeanFactory` 中获取相应的 `SnapshotQueryService`。

> `Bean Name` 命名规则：`聚合根名称 + ".SnapshotQueryService"`。

使用案例：

::: code-group

```kotlin [构造函数注入]
class OrderService(
    private val queryService: SnapshotQueryService<OrderState>
) {
    fun getById(id: String): Mono<OrderState> {
        return singleQuery {
            condition {
                id(id)
            }
        }.query(queryService).toState().throwNotFoundIfEmpty()
    }
}
```

```kotlin [字段注入]
@Autowired
private lateinit var queryService: SnapshotQueryService<OrderState>
```

```kotlin [根据 Bean Name 手动获取]
val queryService = applicationContext.getBean("example.order.SnapshotQueryService") as SnapshotQueryService<OrderState>
```

:::
