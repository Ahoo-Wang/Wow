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
| `ownerId`、`spaceId`、`tags`、`operator` | 聚合的作用域和标签，以及这次提交以谁的名义记录 |
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

事件查询只显示调用方能看到的那些聚合的提交。节点在回答之前，用快照模型的准入和策略（租户、所有者、空间、基于属性的访问）检查这些行所属的聚合，所以日志绝不会显示快照隐藏的东西。状态 schema 脱敏的字段，在事件中同样脱敏。

## 未固定聚合的查询 {#unpinned-queries}

按 ID 指明聚合的查询开销很小：它按键读取每个聚合的流。什么都不指明的查询会扫描聚合类型的日志，而且是在追加写入的那个存储上。这样的查询默认允许运行，受预算和存储的查询舱壁限制。不希望写存储上出现这类查询的部署，可以拒绝它们：

| 键 | 适用于 |
| --- | --- |
| `wow.query.events.unpinned: refuse` | 每个事件查询 |
| `wow.stores.<name>.query.events.unpinned` | 一个存储上的聚合类型 |
| `wow.aggregates.[<context>.<aggregate>].query.events.unpinned` | 一个聚合类型 |

被拒绝的查询返回 `400 wow.query.invalid`（`aggregateId` 上的 `storage-unsupported`），模型的能力描述也会列出约束 `aggregate-required`，这样客户端就知道要固定它们的查询。在 MongoDB 上，`wow.mongo.query-read-preference` 可以把事件查询改发到从节点（[配置项](../reference/configuration#wow-query-events)）。
