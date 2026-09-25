# Descriptor and read-only queries

## Where things are

`{base}` is the service root (or the gateway path to it). `{aggregate}` is the aggregate route prefix, for example `cart` or `example/cart` behind a gateway that routes by bounded context. Tenant and owner routes add `tenant/{tenantId}/` or `owner/{ownerId}/` segments to the query routes; the descriptor routes never have them.

| Purpose | Route |
|---|---|
| Snapshot descriptor | `GET {base}/{aggregate}/snapshot/schema` |
| Event-stream descriptor | `GET {base}/{aggregate}/event/schema` |
| Count | `POST {base}/{aggregate}/snapshot/count` (body: a filter) |
| Breakdown / trend | `POST {base}/{aggregate}/snapshot/aggregation` |
| One page of rows | `POST {base}/{aggregate}/snapshot/paged` (or `paged/state` for state only) |
| Rows after a cursor | `POST {base}/{aggregate}/snapshot/cursor` |
| First match | `POST {base}/{aggregate}/snapshot/single` |
| Event history | the same shapes under `{aggregate}/event/` |

Everything else is out of bounds for this Skill: command routes, `PUT {aggregate}/snapshot/{afterId}/{limit}` (snapshot regeneration) and `PUT {aggregate}/{id}/snapshot`, and state-replay or tracing routes unless the user asked for one aggregate's history.

The descriptor answers `ETag`. Send `If-None-Match` with the held version to get `304` while it is unchanged.

## Reading the descriptor

- `model`, `version`, `timeZone`: the model, a content hash of what it admits, and the server's default zone.
- `fields[]`, one per logical path:
  - `path` and `aliases`: the names a query may use;
  - `types`, `kind`, `nullable`, `enum` (never listed for protected fields), and `semantic` (time encoding, `DECIMAL` scale, `MONEY` currency);
  - `sensitivity`: `level` is DISPLAY or CONFIDENTIAL, and `comparable` says whether it may be filtered or sorted;
  - `filter.operators`: the operators allowed on this field;
  - `sort.paged` and `sort.cursor`;
  - `aggregate`: `groups`, `functions`, `distinctCount`, `percentile`, `any`, `firstLast`, `missingKey`, `inMetricFilter`;
  - `scope`: the element this field lives in. Filter it only inside an `ELEMENT_MATCH` on that element, with the path written relative to it;
  - `deprecated`.
- `elements[]`: the element paths ELEMENT_MATCH can scope, with `search` when full-text search inside the element is allowed.
- `dynamic[]`: map-key patterns such as `state.attributes.{key}`, with their operators and `excludedKeys`.
- `record`: `identity`, `paging` modes, `defaultScope` (usually active aggregates only), `rootOperators` (id, tenant, owner, space and deletion filters), and `search`.
- `limits`: maximum list and page size, page window, filter nodes and values, and aggregation limits. The descriptor's limits win over anything remembered.
- `analysis`: metric kinds, `approximate`, `dateUnits` for DATE_HISTOGRAM, `dateParts` for DATE_PART, `dateDiffUnits`, and whether HAVING, metric sort and dense fill are admitted.
- `constraints[]`: rules that span fields, such as `CURSOR_UNIQUE_SORT`, `COUNT_REQUIRES_FILTER`, `PARALLEL_ARRAY_SORT`, `NULL_OR_EMPTY_AS_MISSING` and `ARRAY_EQUALITY`. Apply them before sending.
- `variants[]` (event streams): the event types by `bodyType`, each with its payload fields relative to the event.

## Query shapes

A filter is `{"op": "<OPERATOR>", "field": "<path>", ...}`; combine filters with `{"op": "AND", "operands": [...]}`. The operator-specific keys follow the published JSON schema `filter-expression.schema.json` (for example `value`, `values`, `days`, `zoneId`).

```json
{"filter": {"op": "AND", "operands": [
  {"op": "EQ", "field": "state.status", "value": "PAID"},
  {"op": "RECENT_DAYS", "field": "state.createdAt", "days": 7, "zoneId": "Asia/Shanghai"}
]},
 "groupBy": [{"type": "DATE_HISTOGRAM", "field": "state.createdAt", "alias": "day", "unit": "DAY", "timeZone": "Asia/Shanghai"}],
 "metrics": [{"type": "COUNT", "alias": "orders"}, {"type": "NUMERIC", "function": "SUM", "expression": {"type": "FIELD", "field": "state.amount"}, "alias": "revenue"}],
 "limit": 31}
```

The operators, groups and metric kinds in the query must all appear in the descriptor for the fields used. Add `"projection": {"include": [...]}` to row queries so they return only what the answer needs.

## Rejections

A rejected query answers `400` (or `403` for scope) with `errorCode` and `bindingErrors[{name, msg, code}]`. `name` is the field or JSON path.

- `UNKNOWN_FIELD` or `UNKNOWN_PROPERTY`: the field or key is not in the model. Re-read the descriptor.
- `UNSUPPORTED_CAPABILITY`, `VALUE_MISMATCH`, `NOT_COLLECTION`, `NOT_SINGLE_STRING`: the operator or value does not fit the field.
- `ELEMENT_SCOPE_REQUIRED`: move the condition inside an `ELEMENT_MATCH`.
- `PROTECTED_COMPARISON`, `PROTECTED_AGGREGATION`: a protected field was compared, grouped or used as a metric. Do not work around it.
- `PARALLEL_ARRAY_SORT`, `ARRAY_EQUALITY`, `CURSOR_NOT_ALLOWED`: a storage constraint the descriptor lists.
- `IllegalArgument` with an HTTP limit message: the query exceeds the entry budget. Narrow it.
- `IllegalAccessQueryScope` (403): the server requires an authenticated tenant scope; the credentials lack it.

A second rejection after one fix, a `5xx`, or a result that contradicts the data is a `wow-debug` task.
