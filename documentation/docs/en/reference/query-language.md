---
title: Query Language
description: The JSON query language of the snapshot, events and dataset models, its fields, filters, sort and projection, the query kinds and their answers, the capability descriptor and the errors.
outline: deep
---

# Query Language

Every query model of a service, the snapshots and the events of each aggregate type and each dataset, takes the same JSON query at `POST …/query`. This page is the language's reference. The examples query the eShop catalog's products, and each one runs against the catalog service in its tests. [Query Snapshots](../guide/queries) shows how to use it from Kotlin and over HTTP.

## A request

A body is one JSON object: `kind` names the operation, the other properties are the query of that kind.

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/request.json -->

```json
{
  "kind": "paged",
  "filter": {
    "op": "AND",
    "operands": [
      { "op": "EQ", "field": "state.status", "value": "PUBLISHED" },
      { "op": "BETWEEN", "field": "state.price", "lowerBound": 5, "upperBound": 50 }
    ]
  },
  "sort": [{ "field": "state.price", "direction": "ASC" }],
  "pagination": { "index": 1, "size": 20 }
}
```

A property the kind does not have is 400 `wow.query.malformed` with the violation code `UNKNOWN_PROPERTY`; a body without `kind` or with another value is `INVALID_VALUE` or `UNKNOWN_VALUE`.

## Across tenants

A query reads the caller's tenant. A caller with `crossScope` may read other tenants on a query route by naming them in `Wow-Scope`: `tenant=*` for every tenant, or an inner list, `tenant=("t1" "t2")`. This is off until `wow.query.cross-tenant.enabled` is on, and while it is off such a query is 403 `wow.auth.forbidden`.

- An aggregate type whose tenant is fixed is still read in that tenant alone. A list that leaves it out is 400 `wow.query.malformed`.
- Every cross-tenant query is logged at INFO on `me.ahoo.wow.query.CrossTenantAudit`, whether or not a query observer audits.
- Commands and state reads never cross tenants: a `tenant` of `*` or a list is 400 there.
- In process, `QueryApi.trusted()` reads every tenant.

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

The `events` model holds one record per committed stream: `aggregateId`, `tenantId`, `version`, `commitTime`, `commandId`, `requestId`, `commandType`, `ownerId`, `spaceId`, `tags`, `operator`, and `events`, each `{name, revision, body}`. A filter on the events is an `ELEMENT_MATCH` on `events`, and an aggregation over them expands `events` first; [Query Event Streams](../guide/event-queries) shows both. A deployment may require every events query to name its aggregates (`wow.query.events.unpinned`).

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

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/in.json -->

```json
{
  "kind": "list",
  "filter": { "op": "IN", "field": "state.status", "values": ["PUBLISHED", "DELISTED"] },
  "limit": 50
}
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

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/strings.json -->

```json
{
  "kind": "list",
  "filter": { "op": "CONTAINS", "field": "state.name", "value": "mug", "stringComparison": "CASE_INSENSITIVE" }
}
```

### Collections

| `op` | Properties | Matches |
| --- | --- | --- |
| `CONTAINS_ALL` | `field`, `values`: a non-empty array of scalars | The array field holds every one of `values` |
| `IS_EMPTY` | `field` | The array field is empty |
| `ELEMENT_MATCH` | `field`, `predicate`: a filter | An element of the array field matches `predicate`, whose fields are relative to the element |

<!-- sample: fragment -->

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

The products without a brand:

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/presence.json -->

```json
{
  "kind": "list",
  "filter": { "op": "IS_NULL", "field": "state.brandId" }
}
```

### Relative time

Each takes `field`, and optionally `zoneId` (an IANA zone such as `Asia/Shanghai`, or an offset such as `+08:00`; UTC by default, never the server's own zone), `datePattern` (a `DateTimeFormatter` pattern, for a field stored as a formatted string) and `timeUnit` (`MILLISECONDS`, the default, or another `TimeUnit` name, for a field stored as an epoch number).

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

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/relative-time.json -->

```json
{
  "kind": "list",
  "filter": { "op": "RECENT_DAYS", "field": "lastEventTime", "days": 7, "zoneId": "Asia/Shanghai" }
}
```

Instants travel as instants: an epoch number, or an ISO-8601 string with its offset (`2026-10-21T08:00:00Z`). Where a day's boundaries matter, the request names its zone; the TypeScript DSL sends `zoneId` whenever the caller gives one.

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

A snapshot query sees only aggregates that are not deleted, unless its filter names a `DELETION` itself. The products, deleted or not, that the employee `erin` changed last:

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/system-fields.json -->

```json
{
  "kind": "list",
  "filter": {
    "op": "AND",
    "operands": [
      { "op": "DELETION", "state": "ALL" },
      { "op": "EQ", "field": "operator.id", "value": "erin" }
    ]
  }
}
```

 The caller's tenant, owner and space are always added; a filter cannot widen them.

### Search and expressions

| `op` | Properties | Matches |
| --- | --- | --- |
| `SEARCH` | `query`: a non-blank string, `fields`: paths (none for every searchable field), `mode`: `TERMS` (default) or `PHRASE` | A full-text match, where the backend supports it (`MODEL_SEARCH_UNSUPPORTED` otherwise) |
| `EXPRESSION` | `expression`, `comparison`: `EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE`, `value`: a finite number | The computed `expression` of each record compares so; a record where it has no value does not match |

An `expression` is one of `{"type":"FIELD","field":…}`, `{"type":"CONSTANT","value":…}`, `{"type":"BINARY","operator":"ADD|SUBTRACT|MULTIPLY|DIVIDE","left":…,"right":…}` and `{"type":"DATE_DIFF","from":…,"to":…,"unit":"SECOND|MINUTE|HOUR|DAY"}`:

<!-- sample: fragment -->

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

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/nor.json -->

```json
{
  "kind": "list",
  "filter": { "op": "NOR", "operands": [{ "op": "EQ", "field": "state.status", "value": "DELISTED" }] }
}
```

### Limits and cost

A filter holds at most `wow.query.http.max-filter-nodes` nodes (128) and `max-filter-values` values (1000) over HTTP (`FILTER_TOO_LARGE`). Some operators cannot use an index: `NOR`, `NE`, `NOT_IN`, `CONTAINS`, `ENDS_WITH`, a case-insensitive or empty `STARTS_WITH`, `IS_EMPTY`, `IS_NOT_EMPTY_STRING`, `IS_NULL`, `IS_NOT_NULL`, `NOT_EXISTS` and `EXPRESSION`. With `wow.query.http.allow-expensive-operators: false` they are refused (`EXPENSIVE_OPERATOR_DISABLED`).

## Sort and projection

`sort` is an array of `{"field": …, "direction": "ASC" | "DESC"}`, at most 32, each field once. A field sorts only where the descriptor says it does (`sort.paged`, `sort.cursor`).

`projection` is `{"include": [paths], "exclude": [paths]}`; with neither, records come whole. An included path keeps its descendants.

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/projection.json -->

```json
{
  "kind": "list",
  "filter": { "op": "MATCH_ALL" },
  "projection": { "include": ["aggregateId", "state.name", "state.price"] },
  "limit": 50
}
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

<!-- sample: fragment -->

```json
{ "total": 10000, "exact": false, "list": [{ "aggregateId": "p-1", "...": "..." }] }
```

### `cursor`

A cursor page is the next `size` records (10 by default, at most `max-page-size`) after `cursor`, the `nextCursor` of the previous page; the first page has none. The server appends the record's unique key to `sort` (`tenantId`, `aggregateId` on the snapshot model), so the order is total and a page never repeats or skips a record. `nextCursor` is `null` on the last page. A cursor is opaque and belongs to its model and sort: send it with the same sort and filter (another sort is `INVALID_CURSOR`). Endless scrolling uses a cursor; it never counts.

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/cursor.json -->

```json
{
  "kind": "cursor",
  "filter": { "op": "EQ", "field": "state.status", "value": "PUBLISHED" },
  "sort": [{ "field": "lastEventTime", "direction": "DESC" }],
  "size": 20
}
```

<!-- sample: fragment -->

```json
{ "list": [{ "aggregateId": "o-9", "...": "..." }], "nextCursor": "eyJ…" }
```

### `count`

`limit`, at least 1, bounds the count; none takes `count-limit`. `exact` is false when the count stopped at it. 
<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/count.json -->

```json
{
  "kind": "count",
  "filter": { "op": "EQ", "field": "state.status", "value": "PUBLISHED" },
  "limit": 1000
}
```

A count is for a filtered, bounded number; fixed statistics, such as orders per status, belong to a projection's counters or a dataset.

### `aggregate`

An aggregation groups the matches and computes metrics per group. `limit` is the most groups (100 by default).

<!-- sample: example/eshop/catalog/catalog-server/src/test/resources/docs/query-language/aggregate.json -->

```json
{
  "kind": "aggregate",
  "filter": { "op": "NE", "field": "state.status", "value": "DRAFT" },
  "groupBy": [{ "type": "TERMS", "field": "state.status", "alias": "status" }],
  "metrics": [
    { "type": "COUNT", "alias": "count" },
    { "type": "NUMERIC", "function": "AVG", "expression": { "type": "FIELD", "field": "state.price" }, "alias": "averagePrice" }
  ],
  "sort": [{ "field": "count", "direction": "DESC" }]
}
```

<!-- sample: fragment -->

```json
[{ "status": "PUBLISHED", "count": 3, "averagePrice": 7.5 }, { "status": "DELISTED", "count": 1, "averagePrice": 4.0 }]
```

Each row holds the group aliases and the metric aliases.

- **`elements`**: `[{"path": "state.lines", "filter": …}]` expands a collection so the groups and metrics run over its elements (at most 5 levels); a field is then relative to the innermost element, and a group with `"scope": "ROOT"` reads the record's absolute path instead.
- **`groupBy`**: `TERMS` (`field` or `expression`, optional `missingKey`), `HISTOGRAM` (`field` or `expression`, `interval`), `DATE_HISTOGRAM` (`field`, `unit`: `YEAR`, `QUARTER`, `MONTH`, `WEEK`, `DAY`, `HOUR`, `MINUTE`, `SECOND`, `timeZone`, `dense`) and `DATE_PART` (`field`, `part`: `DAY_OF_WEEK`, `DAY_OF_MONTH`, `HOUR_OF_DAY`, `MONTH_OF_YEAR`, `timeZone`, `dense`); each has an `alias`. `dense` fills the empty buckets and needs the group to be the only one.
- **`metrics`**: `COUNT`, `NUMERIC` (`function`: `SUM`, `AVG`, `MIN`, `MAX`, `STDDEV`, `VARIANCE`, `expression`), `DISTINCT_COUNT` (`expression`), `PERCENTILE` (`expression`, `percentile`), `ANY`, `FIRST` and `LAST` (`field`, `orderBy`, by default `lastEventTime`), and `DERIVED` (`expression` over other metrics: `METRIC_REF`, `CONSTANT`, `BINARY`, `TOTAL`, `RUNNING`, `PREVIOUS`, `RANK`). Each but `DERIVED` takes an optional `filter`, counting only what matches.
- **`sort`** names group and metric aliases; groups not named sort ascending after them.
- **`having`** keeps the groups whose metrics match: `CONDITION` (`metric`, `operator`, `value`), `BETWEEN` (`metric`, `lower`, `upper`), `IN` (`metric`, `values`), `IS_NULL` (`metric`, `negated`), `AND` and `OR` (`operands`).

The descriptor's `analysis` says which of these the backend runs.

## The capability descriptor

`GET /<context>/<aggregate>/<model>/query` answers what the model accepts, for the caller's entry:

<!-- sample: fragment -->

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
