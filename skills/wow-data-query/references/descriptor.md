# Descriptor and read-only queries

`{base}` is the service root (or the gateway path to it). Confirm every route against the service: `GET {base}/wow/manifest` lists its aggregates and their query models, and the descriptor is the authority on what each admits.

| Purpose | Request |
| --- | --- |
| A model's descriptor | `GET {base}/{context}/{aggregate}/{snapshot\|events}/query` (`ETag` = version; `If-None-Match` answers 304 while unchanged) |
| A query | `POST` on the same route, a JSON body with `kind` |
| The datasets the caller sees | `GET {base}/wow/dataset` (`name`, `context`, `dataset`, `title`, `path`) |
| A dataset's descriptor and queries | `GET` / `POST {base}/wow/dataset/{context}/{dataset}/query` |
| One aggregate's state, at a version or time, and its trace | `GET {base}/{context}/{aggregate}/{id}/state`, `?version=`, `?at=`, `…/state/trace` |

Ask for JSON (`Accept: application/json`); `list` can stream as server-sent events, which an answer rarely needs. Out of bounds: command routes, `POST …/wow.snapshot.rebuild`, `/wow/ops/…`, anything that writes.

## Reading the descriptor

- `model`, `version`, `timeZone`.
- `fields[]`: `path` and `aliases`; `types`, `kind`, `nullable`, `enum` (never listed for protected fields), `semantic` (time encoding, decimal scale, money); `sensitivity` (`level`, `comparable`); `project`; `filter.operators`; `sort.paged`, `sort.cursor`; `aggregate` (`groups`, `functions`, `distinctCount`, `percentile`, `any`, `firstLast`, `missingKey`, `expressionInput`, `inMetricFilter`); `scope` (the element it lives in: filter it only inside an `element-match` on that element); `deprecated` (use the field its message names).
- `elements[]`: collections an `element-match` (`filter`) or an aggregation's `elements` (`aggregate`) reaches.
- `dynamic[]`: map-key patterns such as `tags.{key}`.
- `record`: `identity`, `paging` (`list`, `paged`, `cursor`), `defaultScope`, `rootOperators` (operators that name no field: `id`, `aggregate-id`, `tenant-id`, `owner-id`, `space-id`, `deletion`, …).
- `limits`: list, page, page window, filter nodes and values, sort fields, aggregation limits. They win over anything remembered.
- `analysis`: metric kinds, `approximate`, `expressions`, `having`, `sort`, `dense`, `dateUnits`, `dateParts`, `dateDiffUnits`, `windows`.
- `constraints[]`: `cursor-unique-sort`, `count-requires-filter` (a count or paged total must not match everything), `aggregate-required` (an `events` query must name the aggregate), `starts-with-requires-prefix`, `parallel-array-sort`, `null-or-empty-as-missing`, `array-equality`.

## Fields by model

- **snapshot:** system fields at the root (`tenantId`, `aggregateId`, `version`, `ownerId`, `spaceId`, `deleted`, `tags`, `firstEventTime`, `lastEventTime`, `operator`, `firstOperator`), the state under `state.` (`state.status`).
- **events:** `aggregateId`, `tenantId`, `version`, `commitTime`, `commandId`, `requestId`, `commandType`, `ownerId`, `spaceId`, `operator`, `tags`, and `events[]` of `{name, revision, body}`; event names are `<context>.<aggregate>.<Name>`.
- **dataset:** columns at the root, no `state.` prefix, no event elements; `record.identity` may be absent (then no `cursor`).

## Request shapes

| `kind` | Properties | Response |
| --- | --- | --- |
| `single` | `filter`, `projection`, `sort` | The first record; 404 `wow.query.not-found` when none |
| `list` | `filter`, `projection`, `sort`, `limit` | An array |
| `paged` | `filter`, `projection`, `sort`, `pagination` `{index (from 1), size}`, `total` (`bounded` default, `exact` usually refused over HTTP, `none`) | `{total, exact, list}` |
| `cursor` | `filter`, `projection`, `sort`, `size`, `cursor` | `{list, nextCursor}` |
| `count` | `filter`, `limit` | `{count, exact}` |
| `aggregate` | `filter`, `elements`, `groupBy`, `metrics`, `sort`, `limit`, `having` | An array of rows keyed by the aliases |

A property a kind does not have is refused (`unknown-property`): send only its own. `filter` is required except in `aggregate`; `{"op": "match-all"}` matches everything. A count stops at its `limit` (or the server's count limit): `exact: false` means "at least".

**Filters** are `{"op", "field", …}`: `value` (`eq`, `ne`, `gt`, `gte`, `lt`, `lte`, `contains`, `starts-with`, …), `values` (`in`, `not-in`, `contains-all`), `lowerBound`/`upperBound` (`between`), presence (`is-null`, `is-not-null`, `exists`, `not-exists`), `element-match` with a `predicate` relative to the element, combinators `and`, `or`, `nor` with `operands`. Relative time: `today`, `yesterday`, `this-week`, `last-month`, `recent-days` (`days`), `earlier-days`, `before-today` (`time`), `before-now`/`after-now` (`offset`, ISO-8601), each with an optional `zoneId` (UTC when absent), `datePattern`, `timeUnit`.

**Aggregation:** `groupBy` `terms`, `histogram`, `date-histogram` (`unit`, `timeZone`, `dense`), `date-part`; `metrics` `count`, `numeric` (`function`, `expression`), `distinct-count`, `percentile`, `any`, `first`, `last`, `derived`; each metric but `derived` takes its own `filter`. `sort` names aliases; `having` is its own tree (`condition`, `between`, `in`, `is-null`, `and`, `or`). `limit` defaults to 100 groups. A `date-histogram` key is the bucket's start in epoch milliseconds in its `timeZone`.

```json
{"kind": "aggregate",
 "filter": {"op": "and", "operands": [
   {"op": "eq", "field": "state.status", "value": "PAID"},
   {"op": "recent-days", "field": "state.paidAt", "days": 7, "zoneId": "Asia/Shanghai"}]},
 "groupBy": [{"type": "date-histogram", "field": "state.paidAt", "alias": "day", "unit": "day", "timeZone": "Asia/Shanghai"}],
 "metrics": [{"type": "count", "alias": "orders"},
             {"type": "numeric", "function": "sum", "expression": {"type": "field", "field": "state.amount"}, "alias": "revenue"}],
 "sort": [{"field": "day", "direction": "asc"}],
 "limit": 31}
```

**Windows** (only the functions `analysis.windows` lists) are nodes of a `derived` metric's `expression` over an earlier metric's alias: `total` (`within`: a share), `running` (`order`, `frame`, `function`: a running total or moving average), `previous` (`along` a `date-histogram` alias, `shift` or `period`: the previous period), `rank` (`within`, `direction`; top N per group is `having` on it). The server computes them over every group before `having` and `limit`, which a hand computation over cut rows cannot.

**Events payload conditions** keep the name and the payload in one element predicate:

```json
{"kind": "count",
 "filter": {"op": "and", "operands": [
   {"op": "aggregate-id", "value": "order-42"},
   {"op": "element-match", "field": "events", "predicate": {"op": "and", "operands": [
     {"op": "eq", "field": "name", "value": "sales.order.OrderPaid"},
     {"op": "gt", "field": "body.amount", "value": 1000}]}}]}}
```

A `count` of the events model counts commits; counting events needs an aggregation that expands `"elements": [{"path": "events"}]`, where the descriptor's `analysis` and the field's `aggregate` allow it. Without the aggregate named, an `events` query may be refused (`aggregate-required`, or `storage-unsupported` on `aggregateId`).

## Problems

A refusal is `application/problem+json` with `code`, `status`, `detail` and `violations[{code, path, message}]`:

| `code` | Status | Means | Do |
| --- | --- | --- | --- |
| `wow.query.malformed` | 400 | The body does not read as a query (`unknown-property`, `invalid-value`, `unknown-type`, `invalid-json`, …) | Fix the shape |
| `wow.query.invalid` | 400 | The model refuses it (`unknown-field`, `unsupported-capability`, `value-mismatch`, `element-scope-required`, `protected-comparison`, `protected-aggregation`, `size-out-of-range`, `filter-too-large`, `expensive-operator-disabled`, `count-requires-filter`, `invalid-cursor`, `storage-unsupported`, …) | Fix within the descriptor; never work around protection |
| `wow.query.not-found` | 404 | A `single` matched nothing | Answer "none" |
| `wow.query.dataset-not-found` | 404 | No such dataset for this caller | Re-read `GET /wow/dataset` |
| `wow.query.dataset-unavailable` | 409 | The dataset is being revised | Try later, or answer from another source and say so |
| `wow.query.busy` | 429 | The store's query bulkhead is full | Wait `Retry-After`, then retry once |
| `wow.query.timeout` | 504 | Too heavy for the budget | Narrow the filter or the period |
| `wow.auth.unauthenticated` / `wow.auth.forbidden` | 401 / 403 | Credentials missing, or the caller may not read this (another tenant, a `Wow-Scope` it may not use) | Stop and tell the user |
| `wow.query.failed` | 500 | The backend failed | A diagnosis for `wow-develop`; do not change the query |
