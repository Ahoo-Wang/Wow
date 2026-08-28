---
title: Data Queries
description: Use shared query shapes to read snapshot or event-stream data.
---

# Data Queries

“Data Queries” is a documentation category, not a source type named `DataQuery`. The shared query contract consists of `SingleQuery`, `ListQuery`, `PagedQuery`, and count requests that use a `FilterExpression` directly. See [Filter Expressions](./filter-expression.md) for filter JSON and the Kotlin DSL.

## Four Query Shapes

| Shape | Request focus | Result |
| --- | --- | --- |
| `SingleQuery` | `filter`, `projection`, `sort` | At most one item |
| `ListQuery` | `filter`, `projection`, `sort`, `limit` | A list of items |
| `PagedQuery` | `filter`, `projection`, `sort`, `pagination` | Current-page data and total |
| Count | A `FilterExpression` directly | An exact count (`Long`) |

These shapes can operate on different data models. See [Snapshot Queries](./snapshot-query.md) and [Event Stream Queries](./event-stream-query.md) for their field paths and model-specific defaults. This page does not assume `state.*` or `body.*` paths.

## SingleQuery

`SingleQuery` returns at most one matching item. `filter` selects matches, `projection` controls returned fields, and `sort` determines which item comes first when several items match. The empty or error semantics for no match are defined by the concrete query entry point.

## ListQuery

`ListQuery` returns a list and can cap the number of items with `limit`. For JVM queries, `limit = 0` means unlimited; an HTTP entry point may still apply a request-protection limit. It has no page index; use `PagedQuery` when pagination is required.

## PagedQuery

`PagedQuery` returns a `PagedList`: `total` is the total number of matching items and `list` contains the current page. Page indexes start at 1 and `size` is the page size. Provide a stable `sort` to avoid results moving between pages.

```kotlin
val query = PagedQuery(
    filter = filterExpression { "status" eq "READY" },
    projection = Projection(include = listOf("id", "status")),
    sort = listOf(Sort("updatedAt", Sort.Direction.DESC)),
    pagination = Pagination(index = 1, size = 20)
)
```

The equivalent JSON request shape is below. `status` and `updatedAt` are neutral examples; the data-model page defines the actual logical fields:

```json
{
  "filter": { "op": "EQ", "field": "status", "value": "READY" },
  "projection": { "include": ["id", "status"] },
  "sort": [{ "field": "updatedAt", "direction": "DESC" }],
  "pagination": { "index": 1, "size": 20 }
}
```

Here, `index` is the 1-based page number and `size` is the page size; `sort` names logical fields and directions; and `filter` is a `FilterExpression`. `projection` can use `include` or `exclude`; an empty projection returns all fields.

## Count

The count body is a `FilterExpression` directly, without an outer `filter` property:

```json
{ "op": "EQ", "field": "status", "value": "READY" }
```

On the JVM, use `filter.count(queryService)`. Execution and exactness follow the selected backend contract; HTTP cost protection may reject expensive or unfiltered requests. Count does not return a data list.

## Sorting and Pagination

Sort fields must be logical fields supported by the current query model, with direction `ASC` or `DESC`. Pagination changes the returned window but not `total`; when data can change between requests, use a stable ordering that sufficiently distinguishes records. `ListQuery.limit` and `PagedQuery.pagination` are alternative retrieval modes and should not be mixed in one shape.

## Results and Empty Results

Queries may return typed, state-only, or dynamic results; the concrete entry point determines which forms and unwrapping rules are available. Empty-result behavior is also entry-point-specific: JVM, WebFlux, and API Client 404, empty-value, or empty-list semantics are explained in their child pages and client page. This page does not generalize one transport semantic to every entry point.

## Choosing Snapshot or Event Stream

| Need | Entry |
| --- | --- |
| Read current aggregate state and query business-state fields | [Snapshot Queries](./snapshot-query.md) |
| Read the complete event history and query event-stream fields | [Event Stream Queries](./event-stream-query.md) |

Both models support the shared data-query shapes, but their field roots, deletion semantics, available transport entries, and result models may differ. Choose the model from the source of truth first, then confirm field paths, entry points, and empty-result behavior on its page.
