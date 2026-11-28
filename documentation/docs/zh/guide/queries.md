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
- `"state".path { }` 把块中的字段放在 `state` 之下，`filter`、`sort` 与 `projection` 里写法相同：`sort { "state".path { "price".asc() } }`。已在该路径之下的字段（如 `ProductStateProperties.STATE__PRICE`）保持不变。
- `beforeNow` 与 `afterNow` 把字段与服务器的时钟加上一个 `kotlin.time.Duration` 比较：`"state.createdAt".afterNow((-30).minutes)` 就是最近半小时之内。
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

对同一路径发 `GET`，返回模型的能力描述：它的字段及其类型、每个字段接受的操作符，以及上限；它的版本就是它的 `ETag`。和查询一样，先询问服务的读取授权器：被拒绝的调用方得到授权器的拒绝，看不到模型的任何东西。客户端和视图引擎读取它，只提供模型能回答的东西。

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

每个 `row` 事件的 `id` 就是它的位置。流以 `{"reason":"complete","resumable":false}` 结束；或者在 `wow.query.stream.max-hold`（30 秒）之后以 `{"reason":"hold-expired","resumable":true}` 结束，客户端把最后一个 id 作为 `Last-Event-ID` 发送来续读。

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

之后的 10.x 版本新增的级别，保护程度不超过脱敏：保护程度高于 `CONFIDENTIAL` 的级别只会随大版本出现，因为每个节点都得先能读懂它。所以节点遇到不认识的级别（比如另一个服务的目录行里的），会把它当作 `CONFIDENTIAL`：脱敏且永不比较，绝不会比该级别要求的保护得更少。

脱敏的字段对每个调用方都脱敏：在查询、状态读取、事件查询和轨迹中都一样，在数仓的目录中、在[状态视图缓存](./extensions/cocache)中也一样。它在 Wow 作为读取返回它的地方和 Wow 把它发布给别的上下文的地方脱敏，而在 Wow 存储它的地方不脱敏：见[脱敏止步之处](#where-masking-stops)。`DISPLAY` 值有两个例外，都要明确说出来：服务自己明确要求读取的（[见下文](#read-a-display-value-raw)），以及属主声明为揭示它的发布事件流（[脱敏止步之处](#where-masking-stops)）。定时的命令在发出之前保存在它的事件 `wow.schedule.ScheduleSet` 里，按它的命令类型的请求体脱敏，用那条命令的 schema，发往别的上下文的命令用对端契约里的 schema（[定时](./scheduling)）。

节点找不到 schema 的内容整体脱敏后作答，绝不原样返回：节点的 Manifest 不认识的事件，或者是名字不认识（例如聚合没有对应事件类的事件，导入的 9.x 事件流里可能有），或者是修订比 Manifest 的新（例如滚动升级时更新的节点写入的事件）；以及没有任何 Manifest 列出其类型的定时命令。事件查询把它的 `body`（或定时的 `command`）答为 `null`，名字与修订照存储的给出。要继续读取这样的事件，就保留它的声明：聚合的一个事件类，或者某个事件类的旧名（`@Alias`，见[事件演进](./event-evolution)）。已退役的事件照旧不出现在事件查询里。轨迹要重放事件，所以对节点读不了的提交拒绝作答而不是返回它；发布事件流不写它：它的 sink 重试这条记录并停放该聚合，和对待任何读不了的记录一样。Wow 自己的事件（`wow.lifecycle.*`、`wow.schedule.*`）每个节点都认识。

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

`@Sensitive` 决定的是一次读取返回什么：查询、状态读取、事件查询、轨迹、状态视图缓存的条目。它也决定发布的事件流携带什么。它不改变 Wow 存储的内容，也不是静态加密。下面这些地方按写入时的样子保存每个 `@Sensitive` 值，`CONFIDENTIAL` 的也一样：

| 位置 | 保存什么 | 边界 |
| --- | --- | --- |
| 日志与快照 | 写入时的每个事件和每个状态 | 存储的授权 |
| [ClickHouse Sink 的表](../operate/clickhouse#the-tables) | 写入时的状态与事件；对它们的查询在返回时脱敏 | 数据库的授权 |
| [Elasticsearch 事件 Sink 的索引](../operate/elasticsearch#event-search) | 写入时的事件；`events` 查询在返回时脱敏 | 索引的权限 |
| [`migrate-data compare`](../migrate/data#_5-compare) 的报告 | 有差异的字段的值，9.x 的和 V10 的 | 谁读这份报告 |
| 服务自己声明了 `"lineage": "none"` 的[数据集](./datasets#protection) | 它的表里有什么就是什么：它只按声明为 `sensitive` 的字段脱敏 | 作者的断言：这张表里没有状态标为 `@Sensitive` 的值 |
| 节点日志 | 失败的完整文本，即它的消息和堆栈，可能引用它失败时的值；停放聚合的原因不含任何值（[停放的聚合](./failures#what-a-parked-aggregate-records)） | 日志的访问权限 |
| 失败账本、已经发出的停放 Webhook，以及运维上下文的事件和快照 | 10.0.0-beta.16 之前，停放聚合的原因就是失败的原始消息，可能引用某个值；这些原因按原样保留 | 存储的授权；Webhook 的接收方；运维上下文的存储 |

直接读这些地方的人，无论用 SQL 还是存储的客户端，读到的都是原始值。

[发布的事件流](./published-streams#sensitive-values) `wow.<context>.<aggregate>.state` 在写入时脱敏：状态和每个事件体里的每个 `@Sensitive` 值都按读取时的方式换成掩码，主题里不保存它。属主可以声明一个事件流揭示它的 `DISPLAY` 值（`@AggregateRoot(revealsDisplay = true)`）；它的 `CONFIDENTIAL` 值仍然脱敏。这样的事件流与揭示读取有三点不同：

- **说出这个词的是属主，不是读方。** 一条记录只写一次，给所有读者，所以由属主在契约里决定。Manifest 的 `published` 和对端契约都带着这个声明，消费者由此知道它拿到的是不是原值。
- **它对主题的每个读者都成立。** 别的上下文、别的服务和外部消费者拿到的都是原值。边界是主题的 ACL（[Kafka](../operate/kafka#topics)），对主题的读取也没有审计。
- **揭示出去的值留在 broker 上**，直到主题的保留期结束。

属主关掉 `wow.query.sensitivity.display-shared` 时，声明为揭示的事件流照样遮盖它的 `DISPLAY` 值。10.0.0-beta.14 及更早版本发布的记录保留写入时的每个值，直到主题的保留期删掉它们（[升级部署](../operate/upgrades#sensitive-values-on-a-published-stream)）。

### 属性继承的注解 {#annotations-a-property-inherits}

属性还会从它覆盖的成员上读这些注解：它实现的接口的 getter、它的父类的属性，不限层数。所以读模型接口可以把它们写在 getter 上（`@get:Sensitive val accessKeySecret: String`），覆盖它们的状态就带着它们。

- 只要任何一个被覆盖的成员声明了 `@Sensitive`，它就成立，覆盖它的属性去不掉它。属性可以声明自己的 `@Sensitive`，用来选择掩码或收紧级别（`DISPLAY` 到 `CONFIDENTIAL`）；声明更宽松的级别会使构建失败。
- 其余注解（`@QueryTemporal`、`@QueryDecimal`、`@QueryMoney`、`@QueryDuration`、`@QueryReference`、`@QueryAlias`、`@QueryText`、`@QueryIndex`）以属性自己声明的为准，自己没有才取被覆盖成员声明的。两个被覆盖的成员声明了不同的值时，构建失败并点名两处：在属性上声明它，说明哪一个成立。

## 在哪些存储上 {#on-which-stores}

快照在 PostgreSQL 和 MongoDB 上原地查询；某个类型的快照被索引到 Elasticsearch 时，也在那里查询。在内存后端上，快照从内存中查询，用于本地运行和测试；`wow.query.memory.enabled: false` 关闭它，此时快照路由返回 404。
