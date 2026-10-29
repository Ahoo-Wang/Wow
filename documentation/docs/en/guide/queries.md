---
title: Query Snapshots
description: Find aggregates by their state, with the Kotlin DSL and the generated field constants or over HTTP; lists, pages, cursors, counts and aggregations, streaming, indexes and masking.
outline: deep
---

# Query Snapshots

Every aggregate keeps a snapshot of its latest state, and the snapshots are a read model you can query without writing a projection: filter by any field of the state, sort, page, count and aggregate. This page writes queries in Kotlin and over HTTP, and declares the indexes and masks they need. [Query Language](../reference/query-language) is the full grammar; [Read State](./reading-state) reads one aggregate by its id.

## Write a query in Kotlin

The DSL of `me.ahoo.wow.api.query.dsl` (in `wow-query-api`) builds queries. With `wow-query-api` on the domain module's class path, the compiler generates `<State>Properties` beside each state: one `QueryField` constant per path a query can name, so a renamed field is a compile error, not a query that silently matches nothing. The catalog's storefront lists the published products of a category from five onwards, cheapest first:

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

- A `filter { }` block's conditions combine with `AND`; `and { }`, `or { }`, `nor { }` and `not { }` nest. A value is compared as the JSON it writes as, so an enum matches its name.
- `ProductStateProperties.STATE__PRICE` is `state.price`; the system fields (`AGGREGATE_ID`, `OWNER_ID`, `VERSION`, `LAST_EVENT_TIME`, `TAGS`, …) come from `SnapshotProperties`.
- `snapshots<ProductState>()` decodes each row as a `Snapshot<ProductState>`: its `ref`, `version`, `scope`, `tags` and typed `state`. A query that projects fields cannot fill a typed row: run it through `dynamic<Map<String, Any?>>()` instead.
- `of(caller)` runs the query as that caller sees the model; `trusted()` as trusted code ([Read State](./reading-state#in-kotlin)).

## The kinds of query

| Builder | Answers |
| --- | --- |
| `singleQuery { }` | The first match, or `null` |
| `listQuery { }` | Up to `limit` matches |
| `pagedQuery { }` | One page (`pagination { index(1); size(20) }`), with a total as `total(...)` asks |
| `cursorQuery { }` | A keyset page after a cursor, with the next cursor; needs a `sort` |
| `countQuery { }` | How many match, with whether the count is exact |
| `aggregation { }` | Groups and metrics: terms, histograms, dates; count, sum, average, … Experimental: opt in with `@OptIn(ExperimentalWowApi::class)` |

The DSL is how Kotlin builds a query; Java, which has no DSL, builds the same types with their factories, such as `ListQuery.of(filter)` and `Sort.of(field, Sort.Direction.ASC)`. The query types are plain values: compare them, but build a changed one anew rather than copying it.

A list read whole is bounded by its limit. To read every row of a large result, stream it: `stream(query)` reads keyset chunks, each its own query, and gives each row a position from which the same query can go on, on this node or another.

## Over HTTP

Each aggregate type serves its snapshot queries at `POST /<context>/<aggregate>/snapshot/query`. The body is the query in JSON, its operation in `kind`:

```bash
curl -X POST localhost:8080/eshop-catalog/product/snapshot/query \
  -H 'Content-Type: application/json' \
  -d '{"kind":"paged","filter":{"op":"EQ","field":"state.status","value":"PUBLISHED"},
       "sort":[{"field":"state.price","direction":"ASC"}],"pagination":{"index":1,"size":20}}'
```

`GET` on the same path answers the model's capability descriptor: its fields and their types, the operators each takes, and the limits; its version is its `ETag`. Clients and the view engine read it to offer only what the model can answer.

A `list` with `Accept: text/event-stream` streams its rows as server-sent events, one `row` per record, then an `end`:

<!-- sample: examples/eshop/catalog/catalog-server/src/test/kotlin/me/ahoo/wow/example/eshop/catalog/server/CatalogQueryContract.kt#stream -->

```kotlin
val events = catalog.stream(PRODUCTS, """{"kind":"list","filter":${published(category)}}""", BUYER)
// One row per record, then the end of a resumable stream.
events.map { it.first }.assert().containsExactly("row", "row", "row", "end")
WowJson.mapper.readTree(events.last().second)["reason"].asString().assert().isEqualTo("complete")
val streamed = events.dropLast(1).map { (_, data) -> WowJson.mapper.readTree(data)["aggregateId"].asString() }
streamed.assert().containsExactlyInAnyOrderElementsOf(filed)
```

Each `row` event's `id` is its position. A stream ends with `{"reason":"complete"}`, or, after `wow.query.stream.max-hold` (30 s), with `{"reason":"hold-expired","resumable":true}`; the client resumes it by sending the last id as `Last-Event-ID`.

## Who sees what

A query is admitted as its caller:

- The caller's tenant, owner and space are added to every query. A tenant-level caller, without an owner, sees every aggregate of its tenant; an aggregate that belongs to its tenant (`Ownership.TENANT`) is not restricted by owner.
- With attribute-based access on, the caller's attributes are matched against each aggregate's tags.
- Deleted aggregates are left out unless the query names their deletion state, `deletion(DeletionState.ALL)` or `DELETED`.
- A query that is refused answers `400 wow.query.invalid`, its violations naming each rule it broke; a `single` that matches nothing `404 wow.query.not-found`.

## Bounds

Every query runs under a budget: `wow.query.budgets.http.*` for HTTP, `wow.query.budgets.in-process.*` in Kotlin. A list returns at most `max-list-size` (1,000), 100 when it names no limit; a page at most `max-page-size` (100) rows; a count stops at `count-limit` and says whether it is exact; a query runs at most `timeout` (30 s). A query over the bound is refused, not truncated without notice. A query that runs out of time answers `504 wow.query.timeout`: narrow it rather than run it again. A store that has as many queries running as it allows answers `429 wow.query.busy` with `Retry-After`.

## Declare indexes

A query that filters or sorts by a field without an index scans the store. `@QueryIndex` on a state field declares the index the backend builds at startup, as the `ddl` of its store says (`wow.ddl` by default; `validate` refuses to start while one is missing); `with` adds the fields that follow it, for the usual filter and sort together. The storefront filters products by category, brand and status, and sorts them by price:

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

The annotations go on the field (`@field:`), since the compiler reads them from the compiled state.

## Describe what types cannot

A few more annotations of `me.ahoo.wow.api.query.annotation` tell the query model what a field's type alone does not:

| Annotation | Declares |
| --- | --- |
| `@QueryTemporal` | An integer holding an epoch time in a unit, or a string formatted with a pattern |
| `@QueryDecimal`, `@QueryMoney` | A fixed-point decimal; an amount of money in a currency |
| `@QueryDuration` | A length of time counted in a unit |
| `@QueryReference` | The id of another aggregate |
| `@QueryAlias` | Other paths a query may use for the field, such as its name before a rename |
| `@Sensitive` | A string masked in every result: `DISPLAY` may still be filtered and sorted, `CONFIDENTIAL` never is |

A masked field is masked for every caller, in queries, state reads and traces alike, and in the warehouse's catalog.

## On which stores

Snapshots are queried on PostgreSQL and MongoDB, in place, and on Elasticsearch when a type's snapshots are indexed there. On the memory backend the snapshot routes answer 404, unless `wow.query.memory.enabled` serves them from memory for local runs and tests.
