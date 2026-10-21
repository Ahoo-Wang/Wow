---
title: V10 Query Language
description: The filters, combinators, field paths, query kinds, paging, cursors, capability descriptor, Kotlin DSL, generated field constants and query annotations of a Wow V10 service.
outline: deep
---

# V10 Query Language

A V10 service answers queries of its read models at `POST /<context>/<aggregate>/<model>/query` (see [HTTP API](./http-api.md#queries)). This page is the reference of the query a body carries: its filter, sort, projection and paging, each kind's request and response, the capability descriptor, the Kotlin DSL that builds the same queries in process, and the annotations that tune the snapshot model.

The protocol is plain data in `wow-query-api` (`me.ahoo.wow.api.query`); `wow-query` reads and writes it as JSON. The snippets below are request bodies of the `snapshot` model.

## A request

A body is one JSON object: `kind` names the operation, the other properties are the query of that kind.

```json
{
  "kind": "paged",
  "filter": {
    "op": "AND",
    "operands": [
      { "op": "EQ", "field": "state.categoryId", "value": "books" },
      { "op": "BETWEEN", "field": "state.price", "lowerBound": 10, "upperBound": 50 }
    ]
  },
  "sort": [{ "field": "state.price", "direction": "ASC" }],
  "pagination": { "index": 1, "size": 20 }
}
```

A property the kind does not have is 400 `wow.query.malformed` with the violation code `UNKNOWN_PROPERTY`; a body without `kind` or with another value is `INVALID_VALUE` or `UNKNOWN_VALUE`.

## Field paths

A field is named by its logical path, a JSON string of segments joined by `.`: `state.address.city`. A segment starts with a letter or `_` and holds letters, digits, `_` and `-`; a segment after the first may also be an array index (`state.lines.0`).

The snapshot model's records hold the system fields and the state under `state`:

| Field | Type | Holds |
| --- | --- | --- |
| `tenantId` | string | The aggregate's tenant |
| `aggregateId` | string | The aggregate's id, unique within its tenant; the record's identity |
| `version` | integer | The number of its events |
| `ownerId`, `spaceId` | string or `null` | Its owner and space |
| `deleted` | boolean | Whether it is deleted |
| `tags` | object of string arrays | Its tags; `tags.<key>` names one |
| `firstEventTime`, `lastEventTime` | integer | Its first and last event time, epoch milliseconds |
| `operator`, `firstOperator` | object or `null` | Who sent the last command and the first: `{id, tenantId, crossScope}`; `null` for an anonymous caller. Filter on `operator.id` |
| `state.*` | as the state schema says | The state, as the completed Manifest fragment's state schema describes it |

A path the model does not know is 400 `wow.query.invalid`, `UNKNOWN_FIELD`. The fields of a collection's elements are named only inside an `ELEMENT_MATCH` of that collection, relative to an element (`ELEMENT_SCOPE_REQUIRED` otherwise). The [capability descriptor](#the-capability-descriptor) lists every path with the operators, sorts and aggregations it takes.

The `events` model reads one aggregate's event streams: its filter is an `AND` of the aggregate (`aggregateId` `EQ`, or `ID` / `AGGREGATE_ID`), optionally `version` and `commitTime` bounds (`EQ`, `GT`, `GTE`, `LT`, `LTE`, `BETWEEN`; a time as epoch milliseconds or an ISO-8601 instant), and nothing else. It sorts by `version` ascending only, takes no projection and no `aggregate`. Each record is a stream: `aggregateId`, `tenantId`, `version`, `commitTime`, `commandId`, `requestId`, `commandType`, `ownerId` and `spaceId` when set, `operator` when known, `tags` (the aggregate's tags after that commit, `null` for a stream appended before the log recorded them), and `events`, each `{name, revision, body}`. With ABAC on, each stream is filtered by its own `tags`, so a timeline shows a stream as its aggregate was tagged when it was committed.

## Filters

A filter is a tree of nodes. Each node is a JSON object whose `op` names its operator, with that operator's properties beside it. An object without `op` (the 9.x `Condition`) is refused.

### Values

A value is a JSON scalar: a string, a number, a boolean, or `null` where the operator says so. Numbers keep their precision: `1.50` stays a decimal, and integers beyond 2^53 are exact. A value must match the field's type in the state schema (`VALUE_MISMATCH` otherwise): a string field compares with a string, a number field with a number. A time field (an `Instant`, a `LocalDate`, or an integer or string with `@QueryTemporal`) compares with its own encoding: epoch milliseconds for an `Instant`, `"2026-10-13"` for a `LocalDate`.

### Comparisons

| `op` | Properties | Matches |
| --- | --- | --- |
| `EQ` | `field`, `value`: a scalar or `null` | The field equals `value`; `null` asks whether the field is null |
| `NE` | `field`, `value` | The field differs from `value` |
| `GT`, `GTE`, `LT`, `LTE` | `field`, `value`: a scalar, not `null` | The field is greater, greater or equal, less, less or equal |
| `BETWEEN` | `field`, `lowerBound`, `upperBound`: scalars, not `null` | `lowerBound <= field <= upperBound`, both bounds included |
| `IN` | `field`, `values`: a non-empty array of scalars, no `null` | The field equals one of `values` |
| `NOT_IN` | `field`, `values` | The field equals none of `values` |

```json
{ "op": "IN", "field": "state.status", "values": ["PAID", "SHIPPED"] }
{ "op": "BETWEEN", "field": "state.price", "lowerBound": 10, "upperBound": 50 }
```

`IN` takes `values`, not `value`; `BETWEEN` takes `lowerBound` and `upperBound`, not an array.

### Strings

| `op` | Properties | Matches |
| --- | --- | --- |
| `CONTAINS` | `field`, `value`: a string, `stringComparison` | The field contains `value` |
| `STARTS_WITH` | `field`, `value`, `stringComparison` | The field starts with `value` |
| `ENDS_WITH` | `field`, `value`, `stringComparison` | The field ends with `value` |
| `IS_EMPTY_STRING` | `field` | The field is `""` |
| `IS_NOT_EMPTY_STRING` | `field` | The field is a string other than `""` |

`stringComparison` is `CASE_SENSITIVE` (the default) or `CASE_INSENSITIVE`.

### Collections

| `op` | Properties | Matches |
| --- | --- | --- |
| `CONTAINS_ALL` | `field`, `values`: a non-empty array of scalars | The array field holds every one of `values` |
| `IS_EMPTY` | `field` | The array field is empty |
| `ELEMENT_MATCH` | `field`, `predicate`: a filter | An element of the array field matches `predicate`, whose fields are relative to the element |

```json
{
  "op": "ELEMENT_MATCH",
  "field": "state.lines",
  "predicate": {
    "op": "AND",
    "operands": [
      { "op": "EQ", "field": "sku", "value": "B" },
      { "op": "GTE", "field": "quantity", "value": 2 }
    ]
  }
}
```

The predicate matches one element: both conditions hold on the same line. It cannot hold a system-field filter or a `SEARCH` of the whole model.

### Presence

A missing field and an explicit `null` are both null; only a missing field does not exist. A comparison with a value, a string operator or a range never matches a null field, while `NE` and `NOT_IN` do. On an array field, a scalar operand matches any element, and an `EQ` with an array operand (in process only) matches the whole array.

| `op` | Properties | Matches |
| --- | --- | --- |
| `IS_NULL` | `field` | The field is `null` or absent |
| `IS_NOT_NULL` | `field` | The field has a value |
| `EXISTS` | `field` | The field is present, `null` included |
| `NOT_EXISTS` | `field` | The field is absent |

### Relative time

Each takes `field`, and optionally `zoneId` (an IANA zone such as `Asia/Shanghai`; the server's zone by default), `datePattern` (a `DateTimeFormatter` pattern, for a field stored as a formatted string) and `timeUnit` (`MILLISECONDS`, the default, or another `TimeUnit` name, for a field stored as an epoch number).

| `op` | Further properties | Matches |
| --- | --- | --- |
| `TODAY`, `YESTERDAY`, `TOMORROW` | | That day |
| `THIS_WEEK`, `LAST_WEEK`, `NEXT_WEEK` | | That week |
| `THIS_MONTH`, `LAST_MONTH`, `NEXT_MONTH` | | That month |
| `THIS_YEAR`, `LAST_YEAR`, `NEXT_YEAR` | | That year |
| `BEFORE_TODAY` | `time`: `"18:00"` or `"18:00:30"` | Before that time of today |
| `RECENT_DAYS` | `days`: an integer | Within the last `days` days, today included |
| `EARLIER_DAYS` | `days` | Before the last `days` days |
| `BEFORE_NOW`, `AFTER_NOW` | `offset`: an ISO-8601 duration, `PT0S` by default | Before or after the server's now plus `offset` (`-PT30M` looks back half an hour) |

```json
{ "op": "RECENT_DAYS", "field": "lastEventTime", "days": 7, "zoneId": "Asia/Shanghai" }
```

### System fields

These name no `field`: each filters a system field the model defines.

| `op` | Properties | Matches |
| --- | --- | --- |
| `ID` | `value`: a string | The record's identity: `aggregateId` on the snapshot model |
| `IDS` | `values`: a non-empty array of strings | One of these identities |
| `AGGREGATE_ID`, `AGGREGATE_IDS` | `value` / `values` | The aggregate id |
| `TENANT_ID`, `OWNER_ID`, `SPACE_ID` | `value` | The tenant, the owner, the space |
| `DELETION` | `state`: `ACTIVE`, `DELETED` or `ALL` | The aggregates in that deletion state |
| `MATCH_ALL`, `MATCH_NONE` | | Every record, no record |

A snapshot query sees only aggregates that are not deleted, unless its filter names a `DELETION` itself. The caller's tenant, owner and space are always added (see [HTTP API](./http-api.md#queries)); a filter cannot widen them.

### Search and expressions

| `op` | Properties | Matches |
| --- | --- | --- |
| `SEARCH` | `query`: a non-blank string, `fields`: paths (none for every searchable field), `mode`: `TERMS` (default) or `PHRASE` | A full-text match, where the backend supports it (`MODEL_SEARCH_UNSUPPORTED` otherwise) |
| `EXPRESSION` | `expression`, `comparison`: `EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE`, `value`: a finite number | The computed `expression` of each record compares so; a record where it has no value does not match |

An `expression` is one of `{"type":"FIELD","field":…}`, `{"type":"CONSTANT","value":…}`, `{"type":"BINARY","operator":"ADD|SUBTRACT|MULTIPLY|DIVIDE","left":…,"right":…}` and `{"type":"DATE_DIFF","from":…,"to":…,"unit":"SECOND|MINUTE|HOUR|DAY"}`:

```json
{
  "op": "EXPRESSION",
  "expression": { "type": "DATE_DIFF", "from": "state.paidAt", "to": "state.shippedAt", "unit": "HOUR" },
  "comparison": "GT",
  "value": 24
}
```

### Combinators

| `op` | Properties | Matches |
| --- | --- | --- |
| `AND` | `operands`: a non-empty array of filters | Every operand |
| `OR` | `operands` | At least one operand |
| `NOR` | `operands` | No operand |

There is no `NOT`: a `NOR` of one operand negates it.

```json
{ "op": "NOR", "operands": [{ "op": "EQ", "field": "state.status", "value": "CANCELLED" }] }
```

### Limits and cost

A filter holds at most `wow.query.http.max-filter-nodes` nodes (128) and `max-filter-values` values (1000) over HTTP (`FILTER_TOO_LARGE`). Some operators cannot use an index: `NOR`, `NE`, `NOT_IN`, `CONTAINS`, `ENDS_WITH`, a case-insensitive or empty `STARTS_WITH`, `IS_EMPTY`, `IS_NOT_EMPTY_STRING`, `IS_NULL`, `IS_NOT_NULL`, `NOT_EXISTS` and `EXPRESSION`. With `wow.query.http.allow-expensive-operators: false` they are refused (`EXPENSIVE_OPERATOR_DISABLED`).

## Sort and projection

`sort` is an array of `{"field": …, "direction": "ASC" | "DESC"}`, at most 32, each field once. A field sorts only where the descriptor says it does (`sort.paged`, `sort.cursor`).

`projection` is `{"include": [paths], "exclude": [paths]}`; with neither, records come whole. An included path keeps its descendants.

```json
{ "kind": "list", "filter": { "op": "MATCH_ALL" }, "projection": { "include": ["aggregateId", "state.name"] }, "limit": 50 }
```

## Query kinds

| `kind` | Request properties | Response |
| --- | --- | --- |
| `single` | `filter`, `projection`, `sort` | The first record by `sort`; 404 `wow.query.not-found` when none matches |
| `list` | `filter`, `projection`, `sort`, `limit` | A JSON array of records; with `Accept: text/event-stream`, server-sent events |
| `paged` | `filter`, `projection`, `sort`, `pagination`, `total` | `{"total": …, "exact": …, "list": [records]}` |
| `cursor` | `filter`, `projection`, `sort`, `size`, `cursor` | `{"list": [records], "nextCursor": …}` |
| `count` | `filter`, `limit` | `{"count": …, "exact": …}` |
| `aggregate` | `filter`, `elements`, `groupBy`, `metrics`, `sort`, `limit`, `having` | A JSON array of rows |

`filter` is required in every kind but `aggregate` (where it defaults to `MATCH_ALL`); `{"op":"MATCH_ALL"}` matches every record.

### `list`

`limit` is the most records returned. `0` or none takes `wow.query.http.default-list-size` (100), and at most `max-list-size` (1000) are allowed (`SIZE_OUT_OF_RANGE`). With `Accept: text/event-stream` each record is an event `row` (`id` its index from 0); a failure after the first record ends the stream with an event `error` holding the problem.

### `paged`

`pagination` is `{"index": 1, "size": 10}` by default: `index` counts from 1, `size` is at most `max-page-size` (100), and `index × size` at most `max-page-window` (10000). `total` says how the total is counted:

- `BOUNDED` (the default): up to `wow.query.http.count-limit` (10000). `exact` is false when there are more, and `total` is the limit, read as "at least" (`10000+`).
- `EXACT`: every match; refused over HTTP unless `wow.query.http.allow-exact-count` is on.
- `NONE`: not counted; `total` is `null` and `exact` false.

```json
{ "total": 10000, "exact": false, "list": [{ "aggregateId": "p-1", "...": "..." }] }
```

### `cursor`

A cursor page is the next `size` records (10 by default, at most `max-page-size`) after `cursor`, the `nextCursor` of the previous page; the first page has none. The server appends the record's unique key to `sort` (`tenantId`, `aggregateId` on the snapshot model), so the order is total and a page never repeats or skips a record. `nextCursor` is `null` on the last page. A cursor is opaque and belongs to its model and sort: send it with the same sort and filter (another sort is `INVALID_CURSOR`). Endless scrolling uses a cursor; it never counts.

```json
{ "kind": "cursor", "filter": { "op": "MATCH_ALL" }, "sort": [{ "field": "lastEventTime", "direction": "DESC" }], "size": 20 }
```

```json
{ "list": [{ "aggregateId": "o-9", "...": "..." }], "nextCursor": "eyJ…" }
```

### `count`

`limit`, at least 1, bounds the count; none takes `count-limit`. `exact` is false when the count stopped at it. A count is for a filtered, bounded number; fixed statistics, such as orders per status, belong to a projection's counters or a dataset.

### `aggregate`

An aggregation groups the matches and computes metrics per group. `limit` is the most groups (100 by default).

```json
{
  "kind": "aggregate",
  "filter": { "op": "NE", "field": "state.status", "value": "CANCELLED" },
  "groupBy": [{ "type": "TERMS", "field": "state.status", "alias": "status" }],
  "metrics": [
    { "type": "COUNT", "alias": "count" },
    { "type": "NUMERIC", "function": "SUM", "expression": { "type": "FIELD", "field": "state.amount" }, "alias": "amount" }
  ],
  "sort": [{ "field": "count", "direction": "DESC" }]
}
```

```json
[{ "status": "PAID", "count": 2, "amount": 30.5 }, { "status": "NEW", "count": 1, "amount": 30.0 }]
```

Each row holds the group aliases and the metric aliases.

- **`elements`**: `[{"path": "state.lines", "filter": …}]` expands a collection so the groups and metrics run over its elements (at most 5 levels); a field is then relative to the innermost element, and a group with `"scope": "ROOT"` reads the record's absolute path instead.
- **`groupBy`**: `TERMS` (`field` or `expression`, optional `missingKey`), `HISTOGRAM` (`field` or `expression`, `interval`), `DATE_HISTOGRAM` (`field`, `unit`: `YEAR`, `QUARTER`, `MONTH`, `WEEK`, `DAY`, `HOUR`, `MINUTE`, `SECOND`, `timeZone`, `dense`) and `DATE_PART` (`field`, `part`: `DAY_OF_WEEK`, `DAY_OF_MONTH`, `HOUR_OF_DAY`, `MONTH_OF_YEAR`, `timeZone`, `dense`); each has an `alias`. `dense` fills the empty buckets and needs the group to be the only one.
- **`metrics`**: `COUNT`, `NUMERIC` (`function`: `SUM`, `AVG`, `MIN`, `MAX`, `STDDEV`, `VARIANCE`, `expression`), `DISTINCT_COUNT` (`expression`), `PERCENTILE` (`expression`, `percentile`), `ANY`, `FIRST` and `LAST` (`field`, `orderBy`, by default `lastEventTime`), and `DERIVED` (`expression` over other metrics: `METRIC_REF`, `CONSTANT`, `BINARY`, `TOTAL`, `RUNNING`, `PREVIOUS`, `RANK`). Each but `DERIVED` takes an optional `filter`, counting only what matches.
- **`sort`** names group and metric aliases; groups not named sort ascending after them.
- **`having`** keeps the groups whose metrics match: `CONDITION` (`metric`, `operator`, `value`), `BETWEEN` (`metric`, `lower`, `upper`), `IN` (`metric`, `values`), `IS_NULL` (`metric`, `negated`), `AND` and `OR` (`operands`).

The descriptor's `analysis` says which of these the backend runs. The `events` model has no aggregation.

## The capability descriptor

`GET /<context>/<aggregate>/<model>/query` answers what the model accepts, for the caller's entry:

```json
{
  "model": "SNAPSHOT",
  "version": "sha256:b3c7…",
  "timeZone": "UTC",
  "record": { "identity": "aggregateId", "paging": ["LIST", "PAGED", "CURSOR"], "defaultScope": "ACTIVE",
              "rootOperators": ["ID", "IDS", "AGGREGATE_ID", "AGGREGATE_IDS", "TENANT_ID", "OWNER_ID", "SPACE_ID", "DELETION", "EXPRESSION"] },
  "limits": { "maxListSize": 1000, "defaultListSize": 100, "maxPageSize": 100, "maxPageWindow": 10000, "maxFilterNodes": 128, "...": "..." },
  "analysis": { "metrics": ["COUNT", "NUMERIC", "..."], "...": "..." },
  "fields": [
    { "path": "aggregateId", "types": ["STRING"], "kind": "SCALAR", "nullable": false, "project": true,
      "filter": { "operators": ["EQ", "NE", "GT", "..."] }, "sort": { "paged": true, "cursor": true },
      "aggregate": { "groups": ["TERMS"], "...": "..." }, "aliases": [] }
  ],
  "elements": [{ "path": "state.lines", "filter": true, "aggregate": true }],
  "dynamic": [{ "pattern": "tags.{key}", "types": ["STRING"], "kind": "ARRAY", "filter": { "operators": ["EQ", "..."] } }],
  "constraints": [{ "type": "CURSOR_UNIQUE_SORT", "appended": "aggregateId", "fields": ["tenantId", "aggregateId"] }]
}
```

- `fields` lists each path with its value types, the operators its `filter` takes, whether it sorts in a page and in a cursor, and how it groups and aggregates. `elements` are the collections an `ELEMENT_MATCH` or `elements` entry reaches, and `dynamic` the patterns of map keys, such as `tags.{key}`.
- `record.rootOperators` are the operators that name no field; the combinators `AND`, `OR` and `NOR` apply everywhere.
- `version` is a digest of the descriptor. The response carries it as its `ETag`; a `GET` with `If-None-Match: "<version>"` answers 304 while the descriptor stays the same, so a client caches it and asks again cheaply.

## Errors

| Code | Status | When |
| --- | --- | --- |
| `wow.query.malformed` | 400 | The body does not read as a query: `EMPTY_BODY`, `BODY_NOT_OBJECT`, `INVALID_JSON`, `UNKNOWN_PROPERTY`, `UNKNOWN_TYPE` (an unknown `op` or `type`), `UNKNOWN_VALUE`, `INVALID_VALUE`, `INVALID_REQUEST` (a rule of the query type, such as an empty `operands`) |
| `wow.query.invalid` | 400 | The model refuses it; each violation names its rule, such as `UNKNOWN_FIELD`, `UNSUPPORTED_CAPABILITY`, `VALUE_MISMATCH`, `ELEMENT_SCOPE_REQUIRED`, `SIZE_OUT_OF_RANGE`, `FILTER_TOO_LARGE`, `EXPENSIVE_OPERATOR_DISABLED`, `INVALID_CURSOR`, `STORAGE_UNSUPPORTED` |
| `wow.query.failed` | 500 | The backend failed |
| `wow.query.timeout` | 504 | The query ran longer than `wow.query.http.timeout`; not retried |

Each violation of the problem's `violations` has a `path` (the JSON path in the body, such as `filter.operands`), a `code` and a `message`.

## The Kotlin DSL

`wow-query-api` holds a Kotlin DSL (`me.ahoo.wow.api.query.dsl`) that builds the same query objects as the JSON: a query built with it is the query its JSON reads as, so in-process callers (`QueryGateway`) and HTTP clients send one language.

<!-- sample: fragment -->

```kotlin
import me.ahoo.wow.api.query.dsl.pagedQuery

val query = pagedQuery {
    filter {
        "state.categoryId" eq "books"
        "state.price" between 10..50
        not { "state.status" eq "DISCONTINUED" }
    }
    sort { "state.price".asc() }
    pagination {
        index(1)
        size(20)
    }
}
```

| Builder | Builds | Sets |
| --- | --- | --- |
| `singleQuery { }` | `SingleQuery` | `filter`, `projection`, `sort` |
| `listQuery { }` | `ListQuery` | the same, `limit(n)` |
| `pagedQuery { }` | `PagedQuery` | the same, `pagination { index(); size() }`, `total(TotalMode.EXACT)` |
| `cursorQuery { }` | `CursorQuery` | the same, `size(n)`, `cursor(next)` |
| `countQuery { }` | `CountQuery` | `filter`, `limit(n)` |
| `aggregation { }` | `AggregationQuery` | `filter`, `expand`, groups, metrics, `sort`, `limit`, `having` |
| `filter { }` | `FilterExpression` | alone, to reuse in several queries |

Inside `filter { }` each call adds one node, and a block's nodes combine with `AND`. Every field operator takes the path as a string or as a `QueryField`, such as a [generated constant](#generated-field-constants):

| DSL | Wire |
| --- | --- |
| `"f" eq v`, `"f" ne v` | `EQ`, `NE`; `eq null` is `IS_NULL`, `ne null` is `IS_NOT_NULL` |
| `"f" gt v`, `gte`, `lt`, `lte` | `GT`, `GTE`, `LT`, `LTE` |
| `"f".between(a, b)`, `"f" between a..b` | `BETWEEN` |
| `"f" isIn list`, `"f" notIn list`, `"f" containsAll list` | `IN`, `NOT_IN`, `CONTAINS_ALL` |
| `"f".containsText(s)`, `startsWithText(s)`, `endsWithText(s)`, each with a `StringComparison` | `CONTAINS`, `STARTS_WITH`, `ENDS_WITH` |
| `"f".isNull()`, `isNotNull()`, `exists()`, `notExists()` | `IS_NULL`, `IS_NOT_NULL`, `EXISTS`, `NOT_EXISTS` |
| `"f".isEmptyCollection()`, `isEmptyString()`, `isNotEmptyString()` | `IS_EMPTY`, `IS_EMPTY_STRING`, `IS_NOT_EMPTY_STRING` |
| `"f".today()`, `recentDays(7)`, `beforeToday(LocalTime.NOON)`, `afterNow(Duration.ofMinutes(-30))`, … | the relative-time operators, with `zoneId`, `datePattern`, `timeUnit` |
| `"f".elementMatch { … }` | `ELEMENT_MATCH`, the block relative to an element |
| `search("q", mode, fields)`, `"f" search "q"` | `SEARCH` |
| `expression(expr, ComparisonOperator.GT, 24.0)` | `EXPRESSION` |
| `id`, `ids`, `aggregateId`, `aggregateIds`, `tenantId`, `ownerId`, `spaceId`, `deletion(DeletionState.ALL)`, `matchAll()`, `matchNone()` | the system-field operators |
| `and { }`, `or { }`, `nor { }`, `not { }` | `AND`, `OR`, `NOR`; `not` is a `NOR` of its block |
| `"state".path { "name" eq "x" }` | the block's relative fields under `state.` |

A value goes on the wire as it is. A runtime value, such as an enum, is compared as the JSON it writes as: `"state.status" eq OrderStatus.PAID` finds what `"PAID"` finds. `sort { "f".asc(); "g".desc() }` and `projection { include(…); exclude(…) }` take strings or `QueryField`s; `nested("state")` names the fields under a path.

An aggregation names its parts by alias:

```kotlin
val byStatus = aggregation {
    filter { "state.status" ne "CANCELLED" }
    terms("state.status", "status")
    count("count")
    sum("state.amount", "amount")
    derived("share") { ref("amount") / total("amount") }
    sort { "count".desc() }
    having { "count" gte 1.0 }
}
```

The 9.x DSL lives in `me.ahoo.wow.query.dsl` (`wow-query`); the V10 one has the same builders, minus `condition { }`, in `me.ahoo.wow.api.query.dsl`, plus `countQuery { }`, `not { }`, `between` with a range and the `QueryField` receivers.

## Generated field constants

When a domain module has `wow-query-api` on its compile class path, the compiler writes `<State>Properties` beside each aggregate's state: an object of `QueryField` constants, one per path a snapshot query can name, extending `SnapshotProperties`, which holds the system fields.

```kotlin
// Generated for OrderState
public object OrderStateProperties : me.ahoo.wow.api.query.SnapshotProperties() {
    public val STATE__ID = QueryField("state.id")
    public val STATE__ITEMS = QueryField("state.items")
    public val STATE__ITEMS__PRODUCT_ID = QueryField("state.items.productId")
    public val STATE__ADDRESS__CITY = QueryField("state.address.city")
    public val STATE__STATUS = QueryField("state.status")
    // ...
}
```

- **System fields** come from `SnapshotProperties`: `TENANT_ID`, `AGGREGATE_ID`, `VERSION`, `OWNER_ID`, `SPACE_ID`, `DELETED`, `TAGS` with `tag(name)`, `FIRST_EVENT_TIME`, `LAST_EVENT_TIME`, `OPERATOR`, `OPERATOR__ID`, `OPERATOR__TENANT_ID`, `OPERATOR__CROSS_SCOPE`, the same of `FIRST_OPERATOR`, and `STATE`. Without a state at hand they are on `SnapshotProperties` itself.
- **Names**: each constant is named after the Kotlin properties of its path, in upper snake case, a nested segment after `__`; its value is the path Jackson writes, `@JsonProperty` names included.
- **Which paths**: each public property with a backing field that Jackson writes (no `@JsonIgnore`, none a `@JsonIgnoreProperties` names), into application classes and the elements of collections. Maps, enums, interfaces, abstract, sealed and value classes, the JDK and Kotlin types, and a class already on the path are leaves. These are the paths the state schema in the completed fragment describes.
- **Elements**: an element's constant, such as `STATE__ITEMS__PRODUCT_ID`, is named inside an `ELEMENT_MATCH` of its collection. The DSL reads a field under the element's own path relative to it:

```kotlin
val query = listQuery {
    filter {
        OrderStateProperties.STATE__STATUS isIn listOf(OrderStatus.PAID, OrderStatus.SHIPPED)
        OrderStateProperties.STATE__ITEMS.elementMatch {
            OrderStateProperties.STATE__ITEMS__PRODUCT_ID eq "p-1"   // productId, relative to an item
        }
    }
    sort { OrderStateProperties.LAST_EVENT_TIME.desc() }
}
```

The 9.x `<State>Properties` held `const val` strings relative to the state (`ITEMS__PRODUCT_ID = "items.productId"`); the V10 ones are `QueryField`s of the whole path, so `ITEMS__PRODUCT_ID` becomes `STATE__ITEMS__PRODUCT_ID`.

## Query annotations

The annotations of `me.ahoo.wow.api.query.annotation` declare what the snapshot model cannot infer from a state's types. They live in `wow-query-api`, which the domain module adds beside the kernel:

```kotlin
dependencies {
    implementation("me.ahoo.wow:wow-query-api")
}
```

They go on a Kotlin property with the `@field:` use-site target, since `manifest-tools` reads them from the field of the compiled state. `manifest-tools` writes them into the state schema of the module's Manifest fragment as `x-wow-*` keywords; the query model and the descriptor read them from there.

| Annotation | Attributes | Declares |
| --- | --- | --- |
| `@QueryIndex` | `name`, `on`, `with` | An index the backend builds; see below |
| `@QueryTemporal` | `unit` (a `TimeUnit`, `MILLISECONDS` by default), `pattern` | An integer field holding an epoch time in `unit`, or a string field formatted with `pattern`; standard time types need none |
| `@QueryDecimal` | `scale` | A fixed-point decimal with `scale` fraction digits |
| `@QueryMoney` | `currency` or `currencyField`, `scale` | An amount of money, in a fixed ISO 4217 currency or the one a sibling string field holds |
| `@QueryDuration` | `unit` | A length of time counted in `unit` |
| `@QueryReference` | `aggregateName`, `contextName` | The id of another aggregate (in this context by default) |
| `@QueryAlias` | `value`: paths | Other full paths a query may use for the field, such as its name before a rename |
| `@Sensitive` | `level`: `DISPLAY` or `CONFIDENTIAL`, `mask: Mask(keepPrefix, keepSuffix, strategy)` | A string field masked in every result; `DISPLAY` may still be filtered and sorted in a page (`wow.query.sensitivity.display-comparable`), `CONFIDENTIAL` is never compared |

A field has one semantic type: `@QueryTemporal`, `@QueryDecimal`, `@QueryMoney`, `@QueryDuration` and `@QueryReference` do not combine.

```kotlin
@QueryIndex(on = "ownerId", with = ["lastEventTime"])
class ProductState(val id: String) {
    @field:QueryIndex(with = ["state.status", "state.price"])
    var brandId: String = ""
        private set

    @field:QueryDecimal(scale = 2)
    var price: BigDecimal = BigDecimal.ZERO
        private set

    @field:QueryTemporal(unit = TimeUnit.SECONDS)
    var listedAt: Long = 0
        private set

    @field:QueryAlias("state.title")
    var name: String = ""
        private set
}
```

### `@QueryIndex`

- **On a property**, the property leads the index (`on` stays empty), and `with` lists the further keys in order. **On the state class**, `on` names the leading key. The annotation repeats: a field can lead several indexes.
- **Keys** are full logical paths of the model: a system field (`ownerId`, `lastEventTime`, …) or `state.<path>`, as JSON names them. A key must take an exact match, a range or a sort; on PostgreSQL it holds one value (an array path cannot be a key), and MongoDB cannot index two arrays in one index.
- **Every index** starts with the tenant, which admission adds to every query, and is partial on the snapshots that are not deleted.
- **`name`**, empty by default, is derived: `ix_<aggregate>_<keys>`, each key in snake case with its dots as `_`, such as `ix_product_state_brand_id_state_status_state_price`. A name longer than 63 characters keeps its first 54 and appends `_` and 8 hex digits of its SHA-256. Names are unique within a bounded context.
- **`wow.query.index.ddl`** manages them: `auto` creates the missing ones and rebuilds invalid ones (PostgreSQL's `CREATE INDEX CONCURRENTLY`), `validate` only reports, `none` leaves the schema alone.

**On PostgreSQL** the index is an expression index on the aggregate's snapshot table, `wow_snapshot__<context>__<aggregate>`, named `<table>_<name>`: `wow_snapshot__eshop_catalog__product_ix_product_state_brand_id_…`. That is again limited to 63 characters, the first 54 then `_` and 8 hex digits of the SHA-256 of the whole name, so a long name ends in a hash: to match a Manifest index with the database, compute `<table>_<name>` and limit it so, or compare the definitions. Each key is the very expression the SQL compiler emits for that path, typed by its schema (a `CASE WHEN jsonb_typeof(...)` over the `state` column, cast to `numeric` or `boolean`, text in the `"C"` collation), so the planner uses the index:

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS "<table>_<name>" ON "<table>"
    (tenant_id, <key expression> NULLS FIRST, ...) WHERE NOT deleted
```

**On MongoDB** it is a compound index of the aggregate's snapshot collection, `{tenant_id: 1, <key>: 1, …}` with `partialFilterExpression: {deleted: false}`, under its own `name`, since MongoDB names indexes per collection.
