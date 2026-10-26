---
title: 查询快照
description: 按状态查找聚合：用 Kotlin DSL 和生成的字段常量，或经 HTTP；列表、分页、游标、计数和聚合，流式读取，索引和脱敏。
outline: deep
---

# 查询快照

每个聚合都保存它最新状态的快照，而快照是一个不用写投影就能查询的读模型：按状态的任意字段过滤、排序、分页、计数和聚合。本页用 Kotlin 和经 HTTP 编写查询，并声明它们需要的索引和脱敏。完整的语法见[查询语言](../reference/query-language)；按 ID 读取一个聚合，见[读取状态](./reading-state)。

## 用 Kotlin 编写查询 {#write-a-query-in-kotlin}

`me.ahoo.wow.api.query.dsl`（位于 `wow-query-api`）中的 DSL 构建查询。领域模块的类路径上有 `wow-query-api` 时，编译器会在每个状态旁边生成 `<State>Properties`：查询能指明的每条路径一个 `QueryField` 常量，所以字段改名会成为编译错误，而不是一个悄悄匹配不到任何东西的查询。catalog 的店面列出某个分类中已上架的商品，从第五个开始，价格从低到高：

<!-- sample: example/eshop/catalog/catalog-server/src/test/kotlin/me/ahoo/wow/example/eshop/catalog/server/CatalogQueryContract.kt#dsl -->

```kotlin
val query = listQuery {
    filter {
        ProductStateProperties.STATE__CATEGORY_ID eq category
        ProductStateProperties.STATE__STATUS eq ProductStatus.PUBLISHED
        ProductStateProperties.STATE__PRICE gte BigDecimal("5.00")
    }
    sort { ProductStateProperties.STATE__PRICE.asc() }
}
// In process, typed: the node's QueryApi, as trusted code (decision D259).
val products = catalog.context.getBean(QueryApi::class.java).trusted().snapshots<ProductState>()
val rows = runBlocking { products.list(query).toList() }
rows.map { it.ref.id }.assert().containsExactly(five, seven)
rows.map { it.state.price }.assert().containsExactly(BigDecimal("5.00"), BigDecimal("7.00"))
```

- `filter { }` 块中的条件以 `AND` 组合；`and { }`、`or { }`、`nor { }` 和 `not { }` 可以嵌套。值按它写成的 JSON 比较，所以枚举按它的名字匹配。
- `ProductStateProperties.STATE__PRICE` 就是 `state.price`；系统字段（`AGGREGATE_ID`、`OWNER_ID`、`VERSION`、`LAST_EVENT_TIME`、`TAGS` 等）来自 `SnapshotProperties`。
- `snapshots<ProductState>()` 把每一行解码为 `Snapshot<ProductState>`：它的 `ref`、`version`、`scope`、`tags` 和类型化的 `state`。投影了部分字段的查询无法填充类型化的行：改用 `dynamic<Map<String, Any?>>()` 执行它。
- `of(caller)` 按调用方所看到的模型执行查询；`trusted()` 以受信任的代码执行（[读取状态](./reading-state#in-kotlin)）。

## 查询的种类 {#the-kinds-of-query}

| 构建器 | 返回 |
| --- | --- |
| `singleQuery { }` | 第一个匹配项，或 `null` |
| `listQuery { }` | 至多 `limit` 个匹配项 |
| `pagedQuery { }` | 一页（`pagination { index(1); size(20) }`），按 `total(...)` 的要求带上总数 |
| `cursorQuery { }` | 游标之后的一个键集分页，带下一个游标；需要 `sort` |
| `countQuery { }` | 有多少个匹配项，以及计数是否精确 |
| `aggregation { }` | 分组和指标：terms、直方图、日期；计数、求和、平均值等。实验性：用 `@OptIn(ExperimentalWowApi::class)` 选择使用 |

Kotlin 用 DSL 构建查询；Java 没有 DSL，用同样类型的工厂方法构建，例如 `ListQuery.of(filter)` 和 `Sort.of(field, Sort.Direction.ASC)`。查询类型是普通的值：可以比较，但要改动时重新构建一个，而不是复制。

整体读取的列表受它的 limit 限制。要读取一个很大的结果的每一行，就流式读取：`stream(query)` 按键集分块读取，每块都是一次独立的查询，并给每一行一个位置，同一个查询可以从这个位置继续，在本节点或另一个节点上都可以。

## 经 HTTP {#over-http}

每个聚合类型在 `POST /<context>/<aggregate>/snapshot/query` 上提供快照查询。请求体是 JSON 形式的查询，操作写在 `kind` 中：

```bash
curl -X POST localhost:8080/eshop-catalog/product/snapshot/query \
  -H 'Content-Type: application/json' \
  -d '{"kind":"paged","filter":{"op":"EQ","field":"state.status","value":"PUBLISHED"},
       "sort":[{"field":"state.price","direction":"ASC"}],"pagination":{"index":1,"size":20}}'
```

对同一路径发 `GET`，返回模型的能力描述：它的字段及其类型、每个字段接受的操作符，以及上限；它的版本就是它的 `ETag`。客户端和视图引擎读取它，只提供模型能回答的东西。

带 `Accept: text/event-stream` 的 `list` 以服务端推送事件流式返回它的行，每条记录一个 `row`，然后是一个 `end`：

<!-- sample: example/eshop/catalog/catalog-server/src/test/kotlin/me/ahoo/wow/example/eshop/catalog/server/CatalogQueryContract.kt#stream -->

```kotlin
val events = catalog.stream(PRODUCTS, """{"kind":"list","filter":${published(category)}}""", BUYER)
// One row per record, then the end of a resumable stream (decision D253).
events.map { it.first }.assert().containsExactly("row", "row", "row", "end")
json.readTree(events.last().second)["reason"].asString().assert().isEqualTo("complete")
val streamed = events.dropLast(1).map { (_, data) -> json.readTree(data)["aggregateId"].asString() }
streamed.assert().containsExactlyInAnyOrderElementsOf(filed)
```

每个 `row` 事件的 `id` 就是它的位置。流以 `{"reason":"complete"}` 结束；或者在 `wow.query.stream.max-hold`（30 秒）之后以 `{"reason":"hold-expired","resumable":true}` 结束，客户端把最后一个 id 作为 `Last-Event-ID` 发送来续读。

## 谁能看到什么 {#who-sees-what}

查询以它的调用方的身份准入：

- 调用方的租户、所有者和空间会加进每个查询。没有所有者的租户级调用方，能看到它租户的每个聚合；属于租户的聚合（`Ownership.TENANT`）不按所有者限制。
- 打开基于属性的访问后，用调用方的属性匹配每个聚合的标签。
- 已删除的聚合不包含在内，除非查询指明了它们的删除状态：`deletion(DeletionState.ALL)` 或 `DELETED`。
- 被拒绝的查询返回 `400 wow.query.invalid`，它的 violations 指明违反的每条规则；什么都没匹配到的 `single` 返回 `404 wow.query.not-found`。

## 上限 {#bounds}

每个查询都在一个预算下运行：HTTP 用 `wow.query.budgets.http.*`，Kotlin 中用 `wow.query.budgets.in-process.*`。列表至多返回 `max-list-size`（1,000）行，不指明 limit 时为 100；一页至多 `max-page-size`（100）行；计数在 `count-limit` 处停止，并说明是否精确；查询至多运行 `timeout`（30 秒）。超出上限的查询会被拒绝，而不是不声不响地截断。超时的查询返回 `504 wow.query.timeout`：收窄它，而不是再运行一次。存储上运行的查询已达到允许的数量时，返回 `429 wow.query.busy` 并带上 `Retry-After`。

## 声明索引 {#declare-indexes}

按没有索引的字段过滤或排序的查询，会扫描存储。状态字段上的 `@QueryIndex` 声明后端在启动时建立的索引，按所在存储的 `ddl` 管理（默认 `wow.ddl`；`validate` 下缺失索引会拒绝启动）；`with` 添加跟在它后面的字段，用于常见的过滤与排序组合。店面按分类、品牌和状态过滤商品，并按价格排序：

<!-- sample: example/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/product/ProductState.kt#indexes -->

```kotlin
@field:QueryIndex
var price: BigDecimal = BigDecimal.ZERO
    private set

@field:QueryIndex(with = ["state.status", "state.price"])
var brandId: String? = null
    private set

@field:QueryIndex(with = ["state.status", "state.price"])
var categoryId: String? = null
    private set

@field:QueryIndex(with = ["state.price"])
var status: ProductStatus = ProductStatus.DRAFT
    private set
```

注解写在字段上（`@field:`），因为编译器从编译后的状态读取它们。

## 描述类型说明不了的东西 {#describe-what-types-cannot}

`me.ahoo.wow.api.query.annotation` 中还有几个注解，告诉查询模型字段的类型本身说明不了的东西：

| 注解 | 声明 |
| --- | --- |
| `@QueryTemporal` | 一个以某种单位保存纪元时间的整数，或一个按某种格式化模式写成的字符串 |
| `@QueryDecimal`、`@QueryMoney` | 定点小数；某种货币的金额 |
| `@QueryDuration` | 以某种单位计量的一段时长 |
| `@QueryReference` | 另一个聚合的 ID |
| `@QueryAlias` | 查询可以用于这个字段的其他路径，例如它改名之前的名字 |
| `@Sensitive` | 在每个结果中脱敏的字符串：`DISPLAY` 仍可以过滤和排序，`CONFIDENTIAL` 永远不可以 |

脱敏的字段对每个调用方都脱敏：在查询、状态读取和轨迹中都一样，在数仓的目录中也一样。

## 在哪些存储上 {#on-which-stores}

快照在 PostgreSQL 和 MongoDB 上原地查询；某个类型的快照被索引到 Elasticsearch 时，也在那里查询。在内存后端上，快照路由返回 404，除非 `wow.query.memory.enabled` 让它们从内存提供服务，用于本地运行和测试。
