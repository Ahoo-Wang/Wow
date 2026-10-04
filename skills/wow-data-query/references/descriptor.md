# Descriptor and read-only queries

In depth: [Query](https://wow.ahoo.me/guide/query.html), [Filter Expressions](https://wow.ahoo.me/guide/query/filter-expression.html), [Snapshot Queries](https://wow.ahoo.me/guide/query/snapshot-query.html), [Event Stream Queries](https://wow.ahoo.me/guide/query/event-stream-query.html), [Snapshot Aggregation](https://wow.ahoo.me/guide/query/snapshot-aggregation.html), [Event Stream Aggregation](https://wow.ahoo.me/guide/query/event-stream-aggregation.html), [Query Model Schema](https://wow.ahoo.me/guide/query/query-model-schema.html) and [Field Masking](https://wow.ahoo.me/guide/query/masking.html). The running service's OpenAPI document (`/v3/api-docs`) is the authority on its exact paths.

## Where things are

`{base}` is the service root (or the gateway path to it). `{aggregate}` is the aggregate route prefix, for example `cart`, or `example/cart` behind a gateway that routes by bounded context. Tenant and owner variants put their segments **before** the aggregate: `{base}/tenant/{tenantId}/{aggregate}/…`, `{base}/owner/{ownerId}/{aggregate}/…`, and `{base}/tenant/{tenantId}/owner/{ownerId}/{aggregate}/…` (when the aggregate has both). The descriptor routes have no such variants.

| Purpose | Snapshot route | Event-stream route |
|---|---|---|
| Descriptor | `GET {base}/{aggregate}/snapshot/schema` | `GET {base}/{aggregate}/event/schema` |
| Count (body: a filter) | `POST …/snapshot/count` | `POST …/event/count` |
| Breakdown / trend | `POST …/snapshot/aggregation` | `POST …/event/aggregation` |
| A list | `POST …/snapshot/list` | `POST …/event/list` |
| One page with a total | `POST …/snapshot/paged` | `POST …/event/paged` |
| Rows after a cursor | `POST …/snapshot/cursor` | `POST …/event/cursor` |
| First match (404 when none) | `POST …/snapshot/single` | — |

- Snapshot `single`, `list`, `paged` and `cursor` also have a `/state` form that returns the state alone (the request still names `state.*` paths).
- One aggregate's event history by version: `GET {base}/{aggregate}/{id}/event/{headVersion}/{tailVersion}`, only when the user asked about that aggregate.
- Out of bounds for this Skill: command routes; the snapshot `PUT` routes (`{aggregate}/snapshot/{afterId}/{limit}` regeneration, `{aggregate}/{id}/snapshot`); the deprecated `POST …/schema/refresh` (it reloads the schema on that instance); state-replay and tracing routes unless the user asked for one aggregate's history.
- Ask for JSON (`Accept: application/json`); `list` and `aggregation` can also stream SSE.

The descriptor answers an `ETag` equal to its `version`. Send `If-None-Match` with the held version to get `304` while it is unchanged.

## Reading the descriptor

- `model`, `version`, `timeZone`: the model, a content hash of what it admits, and the server's default zone.
- `fields[]`, one per canonical logical path:
  - `path` and `aliases`: the names a query may use (results always use the canonical path);
  - `types`, `kind`, `nullable`, `enum` (never listed for protected fields), and `semantic` (time encoding, `DECIMAL` scale, `MONEY` currency);
  - `sensitivity`: `level` is DISPLAY or CONFIDENTIAL, and `comparable` says whether it may be filtered or sorted;
  - `project`: whether a projection may select it;
  - `filter.operators`: the operators allowed on this field;
  - `sort.paged` and `sort.cursor`;
  - `aggregate`: `groups`, `functions`, `distinctCount`, `percentile`, `any`, `firstLast`, `missingKey`, `expressionInput`, `inMetricFilter`;
  - `scope`: the element this field lives in. Filter it only inside an `ELEMENT_MATCH` on that element, with the path written relative to it;
  - `deprecated` (`{ "message": … }`): still queryable; use the field the message names.
- `elements[]`: the element paths `ELEMENT_MATCH` can scope (`filter`), aggregation can expand (`aggregate`), with `search` when full-text search inside the element is allowed.
- `dynamic[]`: map-key patterns such as `state.attributes.{key}`, with their operators and `excludedKeys`.
- `record`: `identity`, `paging` modes (`LIST`, `PAGED`, `CURSOR`), `defaultScope` (usually active aggregates only), `rootOperators` (id, tenant, owner, space and deletion filters), and `search`.
- `limits`: maximum list and page size, page window, filter nodes and values, sort fields, and `aggregation` limits (groups, metrics, elements, `maxLimit`, expression depth). The descriptor's limits win over anything remembered.
- `analysis`: metric kinds, `approximate`, `expressions`, `having`, `sort` (by group, by metric), `dense` fill, `dateUnits` for `DATE_HISTOGRAM`, `dateParts` for `DATE_PART`, and `dateDiffUnits`.
- `constraints[]`: rules that span fields. Apply them before sending: `CURSOR_UNIQUE_SORT` (the cursor sort ends with the `appended` identity), `COUNT_REQUIRES_FILTER` (a count or paged query must not match everything), `STARTS_WITH_REQUIRES_PREFIX`, `PARALLEL_ARRAY_SORT`, `NULL_OR_EMPTY_AS_MISSING`, `ARRAY_EQUALITY`.
- `variants` (event streams): the `element` holding the events (`body`), its `discriminator` (`bodyType`), and `values[]`: each event type's `value` with its payload `fields` relative to the event.

## Query shapes

A filter is a `FilterExpression`: `{"op": "<OPERATOR>", "field": "<path>", …}`, combined with `{"op": "AND", "operands": [...]}` (`OR`, `NOR` alike). Operator keys: `value` (`EQ`, `NE`, `GT`…, `CONTAINS`), `values` (`IN`, `NOT_IN`, `CONTAINS_ALL`), `lowerBound`/`upperBound` (`BETWEEN`), `days` and `zoneId` (`RECENT_DAYS`, `EARLIER_DAYS`), `zoneId` on the calendar operators (`TODAY`, `LAST_WEEK`, `THIS_MONTH`, …), `state` (`DELETION`: `ACTIVE`, `DELETED`, `ALL`). The published schema is `schema/query/v2/filter-expression.schema.json` in the Wow repository. The REST entry still accepts the 8.x `condition`/`operator` shape until 10.0; write `FilterExpression`.

- **Count** sends the filter itself as the body, with no `filter` wrapper.
- **Rows** (`list`, `paged`, `cursor`, `single`): `{"filter", "projection": {"include": [...]}, "sort": [{"field", "direction"}], "pagination": {"index", "size"}}` (`limit` for a list, a 1-based `index` for a page). Project only what the answer needs. On an event stream, a projection that selects `body.body` (the payload) must also select `body.bodyType` (`EVENT_PROJECTION_TYPE_REQUIRED`); `include: ["body"]` covers both.
- **Aggregation**: `{"filter", "elements", "groupBy", "metrics", "sort", "limit", "having"}`. `metrics` is required; `sort` needs at least one `groupBy`; `limit` is 1 to the descriptor's `aggregation.maxLimit` (default 100). Group types: `TERMS`, `HISTOGRAM`, `DATE_HISTOGRAM` (`unit`, `timeZone`), `DATE_PART`; metric types: `COUNT`, `NUMERIC` (`function`, `expression`), `DISTINCT_COUNT`, `PERCENTILE`, `ANY`, `FIRST`, `LAST`, `DERIVED` (over other metrics' aliases) — only as the descriptor grants them. `sort` names group or metric aliases; `having` is its own tree (`{"type": "AND", "operands": [...]}`), not a filter.

```json
{"filter": {"op": "AND", "operands": [
  {"op": "EQ", "field": "state.status", "value": "PAID"},
  {"op": "RECENT_DAYS", "field": "state.createdAt", "days": 7, "zoneId": "Asia/Shanghai"}
]},
 "groupBy": [{"type": "DATE_HISTOGRAM", "field": "state.createdAt", "alias": "day", "unit": "DAY", "timeZone": "Asia/Shanghai"}],
 "metrics": [{"type": "COUNT", "alias": "orders"}, {"type": "NUMERIC", "function": "SUM", "expression": {"type": "FIELD", "field": "state.amount"}, "alias": "revenue"}],
 "sort": [{"field": "day", "direction": "ASC"}],
 "limit": 31}
```

A `DATE_HISTOGRAM` bucket key is the bucket's start in epoch milliseconds, aligned to its `timeZone`: say the zone with the answer.

**Event payload conditions.** The predicate's paths are relative to the element: inside `ELEMENT_MATCH` on `body`, the type is `bodyType` and a payload field is `body.<field>`. Keep both in one predicate so they hold for the same event:

```json
{"op": "ELEMENT_MATCH", "field": "body", "predicate": {"op": "AND", "operands": [
  {"op": "EQ", "field": "bodyType", "value": "com.example.cart.CartItemAdded"},
  {"op": "GT", "field": "body.quantity", "value": 1}
]}}
```

To count events rather than streams (one stream holds one command's events), aggregate with `"elements": [{"path": "body"}]`: after the expansion, groups, metrics and element filters are relative to one event (`bodyType`, `name`, `body.<field>`), while the root filter stays absolute. A root `COUNT` counts streams, an expanded one events: say which.

## Rejections

A rejected query answers `400` (or `403` for scope) with `errorCode` and `bindingErrors[{name, msg, code}]`. `name` is the field or JSON path.

- `UNKNOWN_FIELD`, `UNKNOWN_PROPERTY`: the field or key is not in the model. Re-read the descriptor.
- `INVALID_JSON`, `BODY_NOT_OBJECT`, `EMPTY_BODY`, `UNKNOWN_TYPE`, `UNKNOWN_VALUE`, `INVALID_VALUE`, `INVALID_REQUEST`: the request's shape; fix it against the shapes above.
- `UNSUPPORTED_CAPABILITY`, `VALUE_MISMATCH`, `NOT_COLLECTION`, `NOT_SINGLE_STRING`, `MODEL_SEARCH_UNSUPPORTED`: the operator, search or value does not fit the field.
- `ELEMENT_SCOPE_REQUIRED`: move the condition inside an `ELEMENT_MATCH`. `METRIC_FILTER_SEARCH`, `METRIC_FILTER_ELEMENT_MATCH`, `METRIC_FILTER_ARRAY_FIELD`: a metric's own filter cannot hold that.
- `PROTECTED_COMPARISON`, `PROTECTED_AGGREGATION`: a protected field was compared, grouped or used as a metric. Do not work around it.
- `NOT_PROJECTABLE`, `INCOMPLETE_PROJECTION`, `EVENT_PROJECTION_TYPE_REQUIRED`: fix the projection.
- `PARALLEL_ARRAY_SORT`, `ARRAY_EQUALITY`, `CURSOR_NOT_ALLOWED`, `MISSING_KEY_REQUIRES_STRING`, `ANY_REQUIRES_SINGLE_VALUE`, `FIRST_LAST_REQUIRES_SINGLE_VALUE`, `FIRST_LAST_REQUIRES_ORDER_BY`: a storage or aggregation constraint the descriptor lists.
- `SIZE_OUT_OF_RANGE`, `FILTER_TOO_LARGE`, `EXPENSIVE_OPERATOR_DISABLED`, `COUNT_REQUIRES_FILTER`, `RESIDUAL_GROUPS_EXCEEDED`, `EXPLICIT_ENTRY_REQUIRED` (`errorCode` `IllegalArgument`): the query exceeds the entry budget. Narrow it (smaller limit or page, fewer conditions or values, a filter on a count) or drop the expensive feature `msg` names.
- `STORAGE_UNSUPPORTED`, `TEMPORAL_AGGREGATION_UNSUPPORTED`, `TEMPORAL_REPRESENTATION_REQUIRED`, `IDENTITY_UNDEFINED`: the feature is not available on this model or storage. Answer without it.
- `SORT_TOO_MANY`, `SORT_FIELD_DUPLICATE`, `CURSOR_SORT_TOO_MANY`, `CURSOR_SORT_DUPLICATE`: fix the sort (fewer fields, each once).
- `INVALID_CURSOR`: the cursor token does not fit this model and sort. Restart from the first page.
- `IllegalAccessQueryScope` (403): the server requires an authenticated tenant scope; the credentials lack it.

A `500` (`InternalServerError`, for example `Query storage failed.`) is a server fault, not a query error: retry, and do not change the query. A second rejection after one fix, a repeated `5xx`, or a result that contradicts the data is a `wow-develop` diagnosis.
