---
title: Query Event Streams
description: Query an aggregate type's committed event streams, one row per commit, filter by the events they hold, and analyze them per event; visibility and unpinned queries.
outline: deep
---

# Query Event Streams

Beside its snapshots, every aggregate type serves its log as a second query model, `events`: one row per committed stream, with its events. It answers what the state alone cannot, such as an aggregate's timeline, who changed what and when, or how often an event happens. This page queries it. The query language is the snapshots' ([Query Snapshots](./queries)); only the fields differ.

## One row per commit

Each aggregate type serves its event streams at `POST /<context>/<aggregate>/events/query`, and in Kotlin through `QueryApi`'s `events(type)`. A row is one commit:

| Field | Holds |
| --- | --- |
| `aggregateId`, `tenantId`, `version` | The aggregate and its version after the commit |
| `commitTime` | When it committed, in epoch milliseconds |
| `commandId`, `requestId`, `commandType` | The command that produced it |
| `ownerId`, `spaceId`, `tags`, `operator` | The aggregate's scope and tags, and in whose name the commit is recorded |
| `events` | Its events in order, each with its `name`, `revision` and `body` |

The catalog reads a product's timeline, counts its commits that hold a given event, and counts its events by name:

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

- **A timeline** is a `list` filtered by the aggregate's id (`id`), sorted by version.
- **A condition on the events** is an `element-match` on `events`: a commit matches when one of its events matches the predicate, written relative to the event (`name`, a field of its body).
- **Analytics per event** expand the events first: `"elements":[{"path":"events"}]` turns each event into a row of its own for the groups and metrics. A metric over an event field without the expansion is refused, since a commit may hold several events.

Events are read at their current revision, upgraded as the aggregate reads them.

## Who sees what

An events query shows only the commits of aggregates the caller may see. The node checks the aggregates the rows belong to against the snapshot model's admission and policies (tenant, owner, space, attribute-based access) before it answers, so the log never shows what the snapshots hide. Fields the state schema masks are masked in the events too.

## Unpinned queries

A query that names its aggregates by id is cheap: it reads each aggregate's streams by their key. A query that names none scans the aggregate type's log, on the store the appends write to. Such queries run by default, bounded by the budget and the store's query bulkhead. A deployment that wants none of them on its write store refuses them:

| Key | Applies to |
| --- | --- |
| `wow.query.events.unpinned: refuse` | Every events query |
| `wow.stores.<name>.query.events.unpinned` | The aggregate types on one store |
| `wow.aggregates.[<context>.<aggregate>].query.events.unpinned` | One aggregate type |

A refused query answers `400 wow.query.invalid` (`storage-unsupported` on `aggregateId`), and the model's descriptor lists the constraint `aggregate-required`, so clients know to pin their queries. On MongoDB, `wow.mongo.query-read-preference` sends events queries to a secondary instead ([Configuration Keys](../reference/configuration#wow-query-events)).
