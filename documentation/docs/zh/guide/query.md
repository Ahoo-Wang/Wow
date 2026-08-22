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

以下示例查询 `tenant-1` 的 `sales-order` 聚合。四个请求都描述同一条模拟快照，因此查询条件与响应数量保持一致。

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

```kotlin
aggregationQuery {
    condition { "tenantId" eq tenantId }
    expand("state.orders") {
        condition { "status" eq "PAID" }
        expand("lines") {
            condition { "cancelled" eq false }
            groupBy("sku", "sku")
            sum("amount", "totalAmount")
            count("lineCount")
            sort { "totalAmount".desc() }
            limit(100)
        }
    }
}
```

- `expand` 按从外到内声明严格父子对象集合链；每层最多一个子 `expand`，不支持兄弟集合笛卡尔积。
- DSL 块内字段为相对路径，生成的 `AggregationQuery` 使用绝对路径；JSON 请求也必须使用绝对路径。
- `groupBy` 支持 `Terms`、无 offset 的 `Histogram` 和 `DateHistogram`；指标支持 `Count`、`Sum`、`Avg`、`Min`、`Max`，数值指标首版只接受 `Field`。
- 根 `ELEM_MATCH` 只筛选快照，不会筛选展开行；展开行条件必须写入对应 `AggregationElement.condition`。
- 无分组时固定返回一行：空集 `Count=0`、`Sum=0.0`，其余数值指标为 `null`；有分组且无 bucket 时返回空流。
- Elasticsearch 的每层 Elements 字段必须映射为 `nested`，`DateHistogram` 字段必须映射为 `date`/`date_nanos`；MongoDB 使用逐层 `$unwind`。两端都不执行用户脚本。
- Elements、无过滤聚合和指标排序属于高成本操作，HTTP 需要启用 `allow-expensive-operators`；配置 Snapshot masker 时聚合会在访问后端前失败。

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
