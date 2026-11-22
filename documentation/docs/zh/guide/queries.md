---
title: 查询快照
description: 按状态查找聚合：用 Kotlin DSL 和生成的字段常量，或经 HTTP；列表、分页、游标、计数和聚合，流式读取，索引和脱敏。
outline: deep
---

# 查询快照

每个聚合都保存它最新状态的快照，而快照是一个不用写投影就能查询的读模型：按状态的任意字段过滤、排序、分页、计数和聚合。本页用 Kotlin 和经 HTTP 编写查询，并声明它们需要的索引和脱敏。完整的语法见[查询语言](../reference/query-language)；按 ID 读取一个聚合，见[读取状态](./reading-state)。

## 用 Kotlin 编写查询 {#write-a-query-in-kotlin}

`me.ahoo.wow.api.query.dsl`（位于 `wow-query-api`）中的 DSL 构建查询。领域模块的类路径上有 `wow-query-api` 时，编译器会在每个状态旁边生成 `<State>Properties`：查询能指明的每条路径一个 `QueryField` 常量，所以字段改名会成为编译错误，而不是一个悄悄匹配不到任何东西的查询。catalog 的店面列出某个分类中已上架的商品，从第五个开始，价格从低到高：

<!-- sample: examples/eshop/catalog/catalog-server/src/test/kotlin/me/ahoo/wow/example/eshop/catalog/server/CatalogQueryContract.kt#dsl -->

```kotlin
val query = listQuery {
    filter {
        ProductStateProperties.STATE__CATEGORY_ID eq category
        ProductStateProperties.STATE__STATUS eq ProductStatus.PUBLISHED
        ProductStateProperties.STATE__PRICE gte BigDecimal("5.00")
    }
    sort { ProductStateProperties.STATE__PRICE.asc() }
}
// In process, typed: the node's own queries (NodeQueryApi), as trusted code.
val products = catalog.context.getBean(NodeQueryApi::class.java).trusted().snapshots<ProductState>()
val rows = runBlocking { products.list(query).toList() }
rows.map { it.ref.id }.assert().containsExactly(five, seven)
rows.map { it.state.price }.assert().containsExactly(BigDecimal("5.00"), BigDecimal("7.00"))
```

- `filter { }` 块中的条件以 `and` 组合；`and { }`、`or { }`、`nor { }` 和 `not { }` 可以嵌套。值按它写成的 JSON 比较，所以枚举按它的名字匹配。
- `ProductStateProperties.STATE__PRICE` 就是 `state.price`；系统字段（`AGGREGATE_ID`、`OWNER_ID`、`VERSION`、`LAST_EVENT_TIME`、`ACCESS` 等）来自 `SnapshotProperties`，`SnapshotProperties.access(name)` 就是访问属性 `access.<name>`。
- `snapshots<ProductState>()` 把每一行解码为 `Snapshot<ProductState>`：它的 `ref`、`version`、`scope`、`access` 和类型化的 `state`。投影了部分字段的查询无法填充类型化的行：改用 `dynamic<Map<String, Any?>>()` 执行它。
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
curl -X POST localhost:8081/eshop-catalog/product/snapshot/query \
  -H 'Content-Type: application/json' \
  -d '{"kind":"paged","filter":{"op":"eq","field":"state.status","value":"PUBLISHED"},
       "sort":[{"field":"state.price","direction":"asc"}],"pagination":{"index":1,"size":20}}'
```

对同一路径发 `GET`，返回模型的能力描述：它的字段及其类型、每个字段接受的操作符，以及上限；它的版本就是它的 `ETag`。客户端和视图引擎读取它，只提供模型能回答的东西。

带 `Accept: text/event-stream` 的 `list` 以服务端推送事件流式返回它的行，每条记录一个 `row`，然后是一个 `end`：

<!-- sample: examples/eshop/catalog/catalog-server/src/test/kotlin/me/ahoo/wow/example/eshop/catalog/server/CatalogQueryContract.kt#stream -->

```kotlin
val events = catalog.stream(PRODUCTS, """{"kind":"list","filter":${published(category)}}""", BUYER)
// One row per record, then the end of a resumable stream.
events.map { it.first }.assert().containsExactly("row", "row", "row", "end")
WowJson.mapper.readTree(events.last().second)["reason"].asString().assert().isEqualTo("complete")
val streamed = events.dropLast(1).map { (_, data) -> WowJson.mapper.readTree(data)["aggregateId"].asString() }
streamed.assert().containsExactlyInAnyOrderElementsOf(filed)
```

每个 `row` 事件的 `id` 就是它的位置。流以 `{"reason":"complete"}` 结束；或者在 `wow.query.stream.max-hold`（30 秒）之后以 `{"reason":"hold-expired","resumable":true}` 结束，客户端把最后一个 id 作为 `Last-Event-ID` 发送来续读。

## 谁能看到什么 {#who-sees-what}

查询以它的调用方的身份准入：

- 调用方的租户会加进每个查询，它有所有者和空间时，这两项也会加进去：所有者只对 `Ownership.OWNER` 的聚合加上，空间对每个聚合都加上。没有所有者的租户级调用方，能看到它租户里、它空间之内的每个聚合；属于租户的聚合（`Ownership.TENANT`），租户里的每个调用方在其空间之内都能看到，所有者级的调用方也一样（[谁能读取它](./ownership#who-reads-it)）。
- 有访问策略（服务自己的 `AccessPolicy` bean）的聚合，按它的 `read` 授予调用方的读取，取代所有者与空间：谓词是存储执行的过滤条件（[策略在哪里生效](./ownership#where-a-policy-applies)）。
- 在这一切之前，服务的读取授权器（`ReadAuthorizer` bean）可以拒绝调用方：按它是谁拒绝，从不按它问的是什么拒绝。服务正是这样把属于租户的聚合限定给某些角色（[授权读取](../operate/security#authorize-reads)）。
- 已删除的聚合不包含在内，除非查询指明了它们的删除状态：`deletion(DeletionState.ALL)` 或 `deleted`。
- 读取侧拒绝的查询返回 `400 wow.query.invalid`，它的 violations 指明违反的每条规则；读取授权器拒绝的调用方，或者写了它无权写的 `Wow-Scope` 的调用方，返回 `403 wow.auth.forbidden`；什么都没匹配到的 `single` 返回 `404 wow.query.not-found`。

## 上限 {#bounds}

每个查询都在一个预算下运行：HTTP 用 `wow.query.budgets.http.*`，Kotlin 中用 `wow.query.budgets.in-process.*`。列表至多返回 `max-list-size`（1,000）行，不指明 limit 时为 100；一页至多 `max-page-size`（100）行；计数在 `count-limit` 处停止，并说明是否精确；查询至多运行 `timeout`（30 秒）。超出上限的查询会被拒绝，而不是不声不响地截断。进程内的一页同样有上限，`trusted()` 也不例外：多于 `max-page-size` 行的分页查询会失败，报 `In-process page size[200] must be between 1 and 100.`，9.x 里读「前 200 行一页」的任务第一次运行就失败。它和处理函数的任何异常一样，在处理函数的预算内重试，然后停放它的条目（[失败与重试](./failures)）。每页至多读 `max-page-size` 行，任务的页必须更大时调高 `wow.query.budgets.in-process.max-page-size`，或者用 `unbounded()` 或流读取。9.x 的 `limit(Int.MAX_VALUE)` 同样会被拒绝：必须读全部匹配的代码（任务或迁移步骤，而不是代表调用方的读取）用 `NodeQueryApi.trusted().unbounded()`，它不受进程内预算限制、改用自己的超时（`wow.query.unbounded-timeout`），并读取所有租户；或者以流读取。超时的查询返回 `504 wow.query.timeout`：收窄它，而不是再运行一次。存储上运行的查询已达到允许的数量时，返回 `429 wow.query.busy` 并带上 `Retry-After`。

## 声明索引 {#declare-indexes}

按没有索引的字段过滤或排序的查询，会扫描存储。状态字段上的 `@QueryIndex` 声明后端在启动时建立的索引，按所在存储的 `ddl` 管理（默认 `wow.ddl`；`validate` 下缺失索引，或现有索引不是后端规划的那个时，会拒绝启动：例如早先版本建立的另一种定义的索引，`auto` 会重建它一次）；`with` 添加跟在它后面的字段，用于常见的过滤与排序组合。店面按分类、品牌和状态过滤商品，并按价格排序：

<!-- sample: examples/eshop/catalog/catalog-domain/src/main/kotlin/me/ahoo/wow/example/eshop/catalog/domain/product/ProductState.kt#indexes -->

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

### 排序方向 {#index-sort-direction}

索引在两个方向上都能服务按其键的排序：存储可以正向读，也可以反向读。所以 `@QueryIndex` 没有方向，“最新在前”的列表也不需要方向：

- **全部按最新在前：** 只给时间建索引。按快照最后变更的时间排，就在状态类上写 `@QueryIndex(on = "lastEventTime")`；按状态里的时间字段排（例如 `createdAt`），就在该字段上写 `@field:QueryIndex`。
- **某个状态内按最新在前：** 在 `status` 上写 `@field:QueryIndex(with = ["lastEventTime"])`。查询对领头键是等值条件时，下一个键按顺序读，两个方向都行。

```kotlin
@QueryIndex(on = "lastEventTime")
data class OrderState(
    @field:QueryIndex(with = ["lastEventTime"])
    val status: OrderStatus,
)
```

只有当排序让两个键朝相反方向排、且两者都没有被等值条件固定时，方向才有意义，例如先按状态升序，再在每个状态内按最新在前。建在这两个键上的索引给不出这种顺序：存储要对读到的匹配记录排序，匹配越多，代价越高。

## 描述类型说明不了的东西 {#describe-what-types-cannot}

`me.ahoo.wow.api.query.annotation` 中还有几个注解，告诉查询模型字段的类型本身说明不了的东西：

| 注解 | 声明 |
| --- | --- |
| `@QueryTemporal` | 一个以某种单位保存纪元时间的整数，或一个按某种格式化模式写成的字符串 |
| `@QueryDecimal`、`@QueryMoney` | 定点小数；某种货币的金额 |
| `@QueryDuration` | 以某种单位计量的一段时长 |
| `@QueryReference` | 另一个聚合的 ID |
| `@QueryAlias` | 查询可以用于这个字段的其他路径，例如它改名之前的名字；只用于输入，响应把字段放在它自己的路径下 |
| `@QueryText` | 供人按词检索的文字，例如名称或描述：`search` 可以指定它，不指定字段的 `search` 也会读它（[Elasticsearch 上](../operate/elasticsearch#full-text-search)） |
| `@Sensitive` | 在每个结果中脱敏的字符串：`DISPLAY` 仍可以过滤和排序，`CONFIDENTIAL` 永远不可以 |

脱敏的字段对每个调用方都脱敏：在查询、状态读取、事件查询和轨迹中都一样，在数仓的目录中、在[状态视图缓存](./extensions/cocache)中也一样。它在 Wow 作为读取返回它的地方脱敏，而不是在 Wow 存储或发布它的地方：见[脱敏止步之处](#where-masking-stops)。唯一的例外是服务自己明确要求读取的 `DISPLAY` 值（[见下文](#read-a-display-value-raw)）。

### 读取 `DISPLAY` 值的原值 {#read-a-display-value-raw}

服务自己的代码可能需要 `DISPLAY` 值本身：要发短信的手机号，要写信的邮箱地址。它要明确说出来。`revealed()` 给出的查询会返回每个 `DISPLAY` 值的原值：

- 节点的 `NodeQueryApi` 上的 `trusted().revealed()`；
- 订阅处理函数里的 `context.queries.revealed()`，仍固定在它所处理提交的租户上。

<!-- sample: fragment -->

```kotlin
val customer = queries.trusted().revealed().snapshots<CustomerState>().state(ref) ?: return
sms.send(customer.state.phone, text)
```

- **仍然脱敏的。**
  - 每个 `CONFIDENTIAL` 值。
  - 聚合历史中的状态（按版本或时间读取的状态）以及轨迹。
  - 调用方的查询 `of(caller)`，它没有 `revealed()`，所以为用户做的读取永远不会揭示，不论由谁转发。
  - 策略或缓存加载器在查询内部发起的查询。状态视图缓存里存的是普通读取返回的值（[通过 CoCache 共享状态](./extensions/cocache)）。
- **可以组合。** `revealed().unbounded()` 与 `unbounded().revealed()` 是同一组查询，处理函数的 `revealed().trusted()` 也会揭示。
- **数据集也一样。** 数据集的查询共用同样的遮蔽，所以揭示读取会返回数据集中受 `DISPLAY` 保护的列的原值。
- **不调用策略。** 被揭示的值不会调用自定义的 `MaskStrategy`。
- **对端。** 处理函数的 `revealed()` 查询也会向对端要 `DISPLAY` 值（`Wow-Reveal`，在对端的内部端口上）。对端返回原值，除非它关掉了 `wow.query.sensitivity.display-shared`，那时它以 403 拒绝，而不是返回脱敏值；以用户模式访问的对端是为终端用户读取，会拒绝。Wow 不在内部端口上认证服务，所以能访问它的任何工作负载都能这样要（[内部端口上的 `DISPLAY` 值](../operate/security#display-values-on-the-internal-port)）。
- **审计。** 每次揭示读取都会被记录：它的审计把原样返回的字段（`revealedFields`）与脱敏的字段分开列出。

遮蔽 `DISPLAY` 值是一种显示控制，不是保密：过滤条件默认就会比较它的原值，`@Sensitive` 管的是查询和读取返回什么，不管 Wow 存储什么（[脱敏止步之处](#where-masking-stops)）。即便如此仍必须明确说出来，原因是：受信读取的结果常常直接写进响应，默认揭示的读取会在某个字段被标为 `DISPLAY` 的那天把它泄露出去。不要把揭示查询的结果返回给调用方。Java 代码写作 `JavaQueryApi.of(nodeQueryApi).trusted().revealed()`。

### 服务需要读回的密钥 {#a-secret-a-service-reads-back}

`CONFIDENTIAL` 值对每个读取方都脱敏，服务自己也不例外：进程内的读取，`of(caller)`、`trusted()` 及其 `unbounded()` 或 `revealed()`、订阅处理函数的 `context.queries`，都无法解除，也没有任何设置能解除。原始值只出现在 Wow 把状态本身交给代码的地方，而不是对它的查询：decide 函数持有的状态，以及订阅处理函数收到的 `StateEvent`。

所以服务必须读回的密钥，比如它向第三方出示的 API key，不是状态里的 `@Sensitive` 字段。状态里只放一个引用，即查找密钥所用的名字，密钥本身另存：放在服务能读到的配置或密钥库里，或者以密文放在不是 `@Sensitive` 的字段里，由服务解密。Wow 按写入时的样子保存状态与事件，不对它们加密，所以状态里的原始密钥会留在日志和每一份快照里，不论查询在上面加了什么遮蔽。

### 脱敏止步之处 {#where-masking-stops}

`@Sensitive` 决定的是一次读取返回什么：查询、状态读取、事件查询、轨迹、状态视图缓存的条目。它不改变 Wow 存储或发布的内容，也不是静态加密。下面这些地方按写入时的样子保存每个 `@Sensitive` 值，`CONFIDENTIAL` 的也一样：

| 位置 | 保存什么 | 边界 |
| --- | --- | --- |
| 日志与快照 | 写入时的每个事件和每个状态 | 存储的授权 |
| [发布的事件流](./published-streams#sensitive-values) `wow.<context>.<aggregate>.state` | 每次提交的事件及其后的状态，按写入时的样子：另一个上下文的处理函数、另一个服务以及主题的任何消费者拿到的都是原始值 | 主题的 ACL（[Kafka](../operate/kafka#topics)） |
| [ClickHouse Sink 的表](../operate/clickhouse#the-tables) | 写入时的状态与事件；对它们的查询在返回时脱敏 | 数据库的授权 |
| [Elasticsearch 事件 Sink 的索引](../operate/elasticsearch#event-search) | 写入时的事件；`events` 查询在返回时脱敏 | 索引的权限 |
| [`migrate-data compare`](../migrate/data#_5-compare) 的报告 | 有差异的字段的值，9.x 的和 V10 的 | 谁读这份报告 |

直接读这些地方的人，无论用 SQL、Kafka 消费者还是存储的客户端，读到的都是原始值。对发布的事件流脱敏已在计划中（[#6586](https://github.com/Ahoo-Wang/Wow/issues/6586)）；在它落地之前，事件流携带的是写入时的值。

### 属性继承的注解 {#annotations-a-property-inherits}

属性还会从它覆盖的成员上读这些注解：它实现的接口的 getter、它的父类的属性，不限层数。所以读模型接口可以把它们写在 getter 上（`@get:Sensitive val accessKeySecret: String`），覆盖它们的状态就带着它们。

- 只要任何一个被覆盖的成员声明了 `@Sensitive`，它就成立，覆盖它的属性去不掉它。属性可以声明自己的 `@Sensitive`，用来选择掩码或收紧级别（`DISPLAY` 到 `CONFIDENTIAL`）；声明更宽松的级别会使构建失败。
- 其余注解（`@QueryTemporal`、`@QueryDecimal`、`@QueryMoney`、`@QueryDuration`、`@QueryReference`、`@QueryAlias`、`@QueryText`、`@QueryIndex`）以属性自己声明的为准，自己没有才取被覆盖成员声明的。两个被覆盖的成员声明了不同的值时，构建失败并点名两处：在属性上声明它，说明哪一个成立。

## 在哪些存储上 {#on-which-stores}

快照在 PostgreSQL 和 MongoDB 上原地查询；某个类型的快照被索引到 Elasticsearch 时，也在那里查询。在内存后端上，快照从内存中查询，用于本地运行和测试；`wow.query.memory.enabled: false` 关闭它，此时快照路由返回 404。
