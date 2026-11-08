---
title: 查询事件流
description: 查询某个聚合类型已提交的事件流，每次提交一行；按它们包含的事件过滤，并按事件分析；可见性与未固定聚合的查询。
outline: deep
---

# 查询事件流

除了快照，每个聚合类型还把它的日志作为第二个查询模型 `events` 提供：每条已提交的流一行，带着它的事件。它回答只靠状态回答不了的问题，例如一个聚合的时间线、谁在什么时候改了什么，或者某个事件发生得有多频繁。本页查询它。查询语言与快照的相同（[查询快照](./queries)）；只有字段不同。

## 每次提交一行 {#one-row-per-commit}

每个聚合类型在 `POST /<context>/<aggregate>/events/query` 上提供它的事件流，在 Kotlin 中则通过 `QueryApi` 的 `events(type)`。一行就是一次提交：

| 字段 | 包含 |
| --- | --- |
| `aggregateId`、`tenantId`、`version` | 聚合，以及提交之后它的版本 |
| `commitTime` | 提交的时间，单位是纪元毫秒 |
| `commandId`、`requestId`、`commandType` | 产生它的命令 |
| `ownerId`、`spaceId`、`access`、`operator` | 聚合的作用域、它当前的访问属性（属主与空间也在其中；未盖章时为 `null`），以及这次提交以谁的名义记录 |
| `events` | 它的事件，按顺序，每个带着 `name`、`revision` 和 `body` |

catalog 读取一个商品的时间线，统计它包含某个事件的提交数，并按名字统计它的事件：

<!-- sample: examples/eshop/catalog/catalog-server/src/test/kotlin/me/ahoo/wow/example/eshop/catalog/server/CatalogQueryContract.kt#events -->

```kotlin
val events = catalog.post(PRODUCT_EVENTS, """{"kind":"list","filter":{"op":"id","value":"$product"}}""", BUYER)
    .assertOk().json
events.values().map { it["version"].asInt() }.assert().containsExactly(1, 2)
events.values().map { it["events"][0]["name"].asString() }.assert()
    .containsExactly("eshop-catalog.product.ProductCreated", "eshop-catalog.product.ProductPublished")
// On the unified engine: an event condition is an element match; per-event analytics expand.
val published = """{"op":"element-match","field":"events","predicate":""" +
    """{"op":"eq","field":"name","value":"eshop-catalog.product.ProductPublished"}}"""
catalog.post(
    PRODUCT_EVENTS,
    """{"kind":"count","filter":{"op":"and","operands":[{"op":"id","value":"$product"},$published]}}""",
    BUYER,
).assertOk().json["count"].asLong().assert().isEqualTo(1)
val perEvent = catalog.post(
    PRODUCT_EVENTS,
    """{"kind":"aggregate","filter":{"op":"id","value":"$product"},"elements":[{"path":"events"}],""" +
        """"groupBy":[{"type":"terms","field":"name","alias":"event"}],"metrics":[{"type":"count","alias":"n"}]}""",
    BUYER,
).assertOk().json
perEvent.values().map { it["event"].asString() to it["n"].asLong() }.assert().containsExactly(
    "eshop-catalog.product.ProductCreated" to 1L,
    "eshop-catalog.product.ProductPublished" to 1L,
)
```

- **时间线**是一个按聚合 ID（`id`）过滤、按版本排序的 `list`。
- **对事件的条件**是在 `events` 上的 `element-match`：一次提交中只要有一个事件匹配谓词，这次提交就匹配，谓词相对于事件来写（`name`、它的事件体中的某个字段）。
- **按事件的分析**要先展开事件：`"elements":[{"path":"events"}]` 把每个事件变成单独的一行，用于分组和指标。不展开就对事件字段计算指标会被拒绝，因为一次提交可能包含多个事件。

事件按它们当前的修订号读取，像聚合读取它们时那样升级。

## 谁能看到什么 {#who-sees-what}

事件查询只显示调用方现在能看到的那些聚合的提交。每条流都记下它的聚合当前的访问属性 `access`，其中包括所有者和空间；节点按这些属性过滤流，就像过滤快照那样：

- 有访问策略的聚合：它的策略的 `read` 允许谁读这个聚合，谁就能看到它的流（[策略在哪里生效](./ownership#where-a-policy-applies)）。
- 其他聚合：在调用方的租户之内显示它的流；对以所有者身份或在某个空间里行事的调用方，按聚合现在的所有者和空间（`access.owner`、`access.space`）判定。转移之后，新所有者看到全部历史，原所有者一条也看不到。查询自己对 `ownerId` 或 `spaceId` 的过滤，读的是提交时记下的值。
- 尚未盖章的流 `access` 为 null：beta 或导入追加的流，在其类型的访问遍历完成之前都是这样。它在访问策略下不显示，对以所有者身份或在某个空间里行事的调用方也不显示（[重建与重放](../operate/replay#access-attributes)）。

聚合的访问属性改变时，它更早的流会被重新盖章，通常在几秒之内完成；在那之前，事件查询按这些流原先盖的属性读取它们。状态读取，以及按版本、按时间和 trace 的读取，都在聚合的最新状态上检查，所以从不滞后（[读取状态](./reading-state)）。

拒绝某个调用方的读取授权器，同样在读取任何东西之前拒绝它的事件查询（[授权读取](../operate/security#authorize-reads)）。状态 schema 脱敏的字段，在事件中同样脱敏。

## 未固定聚合的查询 {#unpinned-queries}

按 ID 指明聚合的查询开销很小：它按键读取每个聚合的流。在 PostgreSQL 和 MongoDB 上，日志还为访问属性建了索引（属主、空间，以及聚合声明的每个属性），所以访问策略下的查询读取其属性的索引。其他什么都不指明的查询会扫描聚合类型的日志，而且是在追加写入的那个存储上。这样的查询默认允许运行，受预算和存储的查询舱壁限制。不希望写存储上出现这类查询的部署，可以拒绝它们：

| 键 | 适用于 |
| --- | --- |
| `wow.query.events.unpinned: refuse` | 每个事件查询 |
| `wow.stores.<name>.query.events.unpinned` | 一个存储上的聚合类型 |
| `wow.aggregates.[<context>.<aggregate>].query.events.unpinned` | 一个聚合类型 |

被拒绝的查询返回 `400 wow.query.invalid`（`aggregateId` 上的 `storage-unsupported`），模型的能力描述也会列出约束 `aggregate-required`，这样客户端就知道要固定它们的查询。在 MongoDB 上，`wow.mongo.query-read-preference` 可以把事件查询改发到从节点（[配置项](../reference/configuration#wow-query-events)）。
