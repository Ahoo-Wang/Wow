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

- A `filter { }` block's conditions combine with `and`; `and { }`, `or { }`, `nor { }` and `not { }` nest. A value is compared as the JSON it writes as, so an enum matches its name.
- `ProductStateProperties.STATE__PRICE` is `state.price`; the system fields (`AGGREGATE_ID`, `OWNER_ID`, `VERSION`, `LAST_EVENT_TIME`, `ACCESS`, …) come from `SnapshotProperties`, and `SnapshotProperties.access(name)` is the access attribute `access.<name>`.
- `snapshots<ProductState>()` decodes each row as a `Snapshot<ProductState>`: its `ref`, `version`, `scope`, `access` and typed `state`. A query that projects fields cannot fill a typed row: run it through `dynamic<Map<String, Any?>>()` instead.
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
curl -X POST localhost:8081/eshop-catalog/product/snapshot/query \
  -H 'Content-Type: application/json' \
  -d '{"kind":"paged","filter":{"op":"eq","field":"state.status","value":"PUBLISHED"},
       "sort":[{"field":"state.price","direction":"asc"}],"pagination":{"index":1,"size":20}}'
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

- The caller's tenant is added to every query, and so are its owner and space where it has them: the owner on an aggregate of `Ownership.OWNER`, the space on every aggregate. A tenant-level caller, without an owner, sees every aggregate of its tenant within its space; an aggregate that belongs to its tenant (`Ownership.TENANT`) is seen by every caller of the tenant within its space, owner-level ones included ([Who reads it](./ownership#who-reads-it)).
- An aggregate with an access policy, an `AccessPolicy` bean of the service's own, is read by what its `read` grants the caller, in place of the owner and space: the predicate is a filter the store applies ([Where a policy applies](./ownership#where-a-policy-applies)).
- Before any of this, the service's read authorizers (`ReadAuthorizer` beans) may refuse the caller, by who it is, never by what it asks. That is how a service keeps an aggregate of its tenant to some roles ([Authorize reads](../operate/security#authorize-reads)).
- Deleted aggregates are left out unless the query names their deletion state, `deletion(DeletionState.ALL)` or `deleted`.
- A query the read side refuses answers `400 wow.query.invalid`, its violations naming each rule it broke; a caller a read authorizer refuses, or one that names a `Wow-Scope` it may not, `403 wow.auth.forbidden`; a `single` that matches nothing `404 wow.query.not-found`.

## Bounds

Every query runs under a budget: `wow.query.budgets.http.*` for HTTP, `wow.query.budgets.in-process.*` in Kotlin. A list returns at most `max-list-size` (1,000), 100 when it names no limit; a page at most `max-page-size` (100) rows; a count stops at `count-limit` and says whether it is exact; a query runs at most `timeout` (30 s). A query over the bound is refused, not truncated without notice. A page is bounded the same way in process, `trusted()` included: a paged query of more than `max-page-size` rows fails with `In-process page size[200] must be between 1 and 100.`, and a 9.x job that read "the first page of 200" fails on its first run. As any exception of a handler, it is retried within the handler's budget and then its entry is parked ([Failures and Retries](./failures)). Page by at most `max-page-size`, raise `wow.query.budgets.in-process.max-page-size` if the job's pages must be larger, or read with `unbounded()` or a stream. So is 9.x's `limit(Int.MAX_VALUE)`: code that must read every match, a job or a migration step rather than a read for a caller, uses `NodeQueryApi.trusted().unbounded()`, which drops the in-process budget for its own timeout (`wow.query.unbounded-timeout`) and reads every tenant, or streams them. A query that runs out of time answers `504 wow.query.timeout`: narrow it rather than run it again. A store that has as many queries running as it allows answers `429 wow.query.busy` with `Retry-After`.

## Declare indexes

A query that filters or sorts by a field without an index scans the store. `@QueryIndex` on a state field declares the index the backend builds at startup, as the `ddl` of its store says (`wow.ddl` by default; `validate` refuses to start while one is missing, or is not the index the backend plans: one of another definition, such as one built by an earlier release, which `auto` rebuilds once); `with` adds the fields that follow it, for the usual filter and sort together. The storefront filters products by category, brand and status, and sorts them by price:

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

### Sort direction {#index-sort-direction}

An index serves a sort by its keys in both directions: the store reads it forward or backward. So `@QueryIndex` has no direction, and a "latest first" list needs none:

- **Latest first, over all of them:** index the time alone. That is `@QueryIndex(on = "lastEventTime")` on the state class for when a snapshot last changed, or `@field:QueryIndex` on a time field of the state, such as `createdAt`.
- **Latest first within a status:** `@field:QueryIndex(with = ["lastEventTime"])` on `status`. A query equal on the leading key reads the next one in order, either way.

```kotlin
@QueryIndex(on = "lastEventTime")
data class OrderState(
    @field:QueryIndex(with = ["lastEventTime"])
    val status: OrderStatus,
)
```

A direction would matter only for a sort that runs two keys in opposite directions, neither of them fixed by an equality: status ascending, then the newest first within each status. An index on those keys does not give that order: the store sorts the matches it reads, which costs more as they grow.

## Describe what types cannot

A few more annotations of `me.ahoo.wow.api.query.annotation` tell the query model what a field's type alone does not:

| Annotation | Declares |
| --- | --- |
| `@QueryTemporal` | An integer holding an epoch time in a unit, or a string formatted with a pattern |
| `@QueryDecimal`, `@QueryMoney` | A fixed-point decimal; an amount of money in a currency |
| `@QueryDuration` | A length of time counted in a unit |
| `@QueryReference` | The id of another aggregate |
| `@QueryAlias` | Other paths a query may use for the field, such as its name before a rename; input only, as a response holds the field under its own path |
| `@QueryText` | Prose a reader searches by words, such as a name or a description: a `search` may name it, and one that names no field reads it ([on Elasticsearch](../operate/elasticsearch#full-text-search)) |
| `@Sensitive` | A string masked in every result: `DISPLAY` may still be filtered and sorted, `CONFIDENTIAL` never is |

A masked field is masked for every caller, in queries, state reads, event queries and traces alike, in the warehouse's catalog, and in the [state view cache](./extensions/cocache). It is masked where Wow returns it as a read, not where Wow stores or publishes it: [Where masking stops](#where-masking-stops). The one exception is the service's own read of a `DISPLAY` value, which it asks for in so many words ([below](#read-a-display-value-raw)).

### Read a `DISPLAY` value raw {#read-a-display-value-raw}

The service's own code may need a `DISPLAY` value as it is: the phone number it sends a message to, or the e-mail address it writes to. It asks for it by name. `revealed()` gives queries that return every `DISPLAY` value raw:

- `trusted().revealed()` on the node's `NodeQueryApi`;
- `context.queries.revealed()` in a subscription handler, still pinned to the tenant of its commit.

<!-- sample: fragment -->

```kotlin
val customer = queries.trusted().revealed().snapshots<CustomerState>().state(ref) ?: return
sms.send(customer.state.phone, text)
```

- **What is still masked.**
  - Every `CONFIDENTIAL` value.
  - A state of the aggregate's history (a state read at a version or a time) and a trace.
  - A caller's queries, `of(caller)`, which have no `revealed()`, so a read for a user is never revealed, whoever relays it.
  - The queries a policy or a cache loader runs inside a query. The state view cache holds what a plain read returns ([Share State through CoCache](./extensions/cocache)).
- **It combines.** `revealed().unbounded()` and `unbounded().revealed()` are the same queries, and a handler's `revealed().trusted()` reveals too.
- **Datasets too.** A dataset's queries share the same masks, so a revealing read of a dataset returns its `DISPLAY`-protected columns raw.
- **No strategy.** A custom `MaskStrategy` is not called for a revealed value.
- **Peers.** A handler's `revealed()` queries ask a peer for its `DISPLAY` values too (`Wow-Reveal`, on its internal port). The peer answers them raw unless it turns `wow.query.sensitivity.display-shared` off, and then refuses with 403 rather than answer masked; a peer reached in user mode reads for an end user and refuses. Wow does not authenticate services on the internal port, so whoever reaches it can ask the same ([`DISPLAY` values on the internal port](../operate/security#display-values-on-the-internal-port)).
- **Audit.** Each revealing read is recorded as one: its audit names the fields it returned raw (`revealedFields`) apart from those it masked.

Masking a `DISPLAY` value is a display control, not secrecy: filters compare its raw value by default, and `@Sensitive` governs what queries and reads return, not what Wow stores ([Where masking stops](#where-masking-stops)). Why the word must be said all the same: the result of a trusted read often goes straight into a response, so a read that revealed by default would leak a field the day it is marked `DISPLAY`. Never return revealed queries' results to a caller. Java code writes `JavaQueryApi.of(nodeQueryApi).trusted().revealed()`.

### A secret a service reads back {#a-secret-a-service-reads-back}

A `CONFIDENTIAL` value is masked for every reader, the service itself included: no read in process, `of(caller)`, `trusted()` and its `unbounded()` or `revealed()`, a subscription handler's `context.queries`, and no setting lifts it. The raw value is only where Wow hands code the state itself, not a query of it: the state a decide function holds and the `StateEvent` a subscription handler receives.

So a secret the service must read back, such as the API key it presents to a third party, is not a `@Sensitive` field of a state. Keep a reference in the state, the name the secret is looked up under, and store the secret apart: in configuration or a secrets store the service reads, or as ciphertext in a field that is not `@Sensitive`, which the service decrypts. Wow keeps a state and its events as they were written and encrypts none of them, so a raw secret in a state is in the log and in every snapshot, whatever masks the queries put over it.

### Where masking stops {#where-masking-stops}

`@Sensitive` decides what a read returns: a query, a state read, an event query, a trace, an entry of the state view cache. It does not change what Wow stores or publishes, and it is not encryption at rest. These hold each `@Sensitive` value as it was written, `CONFIDENTIAL` ones included:

| Where | What it holds | The boundary |
| --- | --- | --- |
| The log and the snapshots | Each event and each state as written | The store's grants |
| A [published stream](./published-streams#sensitive-values), `wow.<context>.<aggregate>.state` | Each commit's events and the state after it, as written: another context's handler, another service and any consumer of the topic receive the raw values | The topic's ACL ([Kafka](../operate/kafka#topics)) |
| The [ClickHouse sink's tables](../operate/clickhouse#the-tables) | The states and events as written; a query of them masks what it returns | The database's grants |
| The [Elasticsearch event sink's index](../operate/elasticsearch#event-search) | The events as written; the `events` queries mask what they return | The index's privileges |
| The report of [`migrate-data compare`](../migrate/data#_5-compare) | The values of the fields that differ, 9.x's and V10's | Who reads the report |

Whoever reads one of these directly, with SQL, a Kafka consumer or a store's client, reads the raw values. Masking a published stream is planned ([#6586](https://github.com/Ahoo-Wang/Wow/issues/6586)); until it lands, the stream carries the values as written.

### Annotations a property inherits

A property also reads these annotations from the members it overrides: the getters of the interfaces it implements and the properties of its superclasses, through any number of levels. So a read model interface can keep them on its getters (`@get:Sensitive val accessKeySecret: String`) and the state that overrides them carries them.

- `@Sensitive` holds when any overridden member declares it, and the overriding property cannot remove it. It may declare its own, to choose the mask or tighten the level (`DISPLAY` to `CONFIDENTIAL`); declaring a looser level fails the build.
- Every other annotation (`@QueryTemporal`, `@QueryDecimal`, `@QueryMoney`, `@QueryDuration`, `@QueryReference`, `@QueryAlias`, `@QueryText`, `@QueryIndex`) is the property's own when it declares one, else the one the overridden members declare. When two overridden members declare different ones, the build fails and names both: declare it on the property to say which holds.

## On which stores

Snapshots are queried on PostgreSQL and MongoDB, in place, and on Elasticsearch when a type's snapshots are indexed there. On the memory backend they are queried from memory, for local runs and tests; `wow.query.memory.enabled: false` turns that off, and the snapshot routes then answer 404.
