# Reads: state, queries, datasets

Three ways to read, by what the screen needs:

| Need | Client | Route |
| --- | --- | --- |
| One aggregate, now, at a version or a time, or right after a command | `<Aggregate>StateClient` | `GET /{context}/{aggregate}/{id}/state` |
| Many aggregates: filter, sort, page, count, aggregate | `<Aggregate>SnapshotQueryClient` | `POST /{context}/{aggregate}/snapshot/query` |
| An aggregate's history: its commits and their events | `<Aggregate>EventQueryClient` | `POST /{context}/{aggregate}/events/query` |
| A wide or warehouse table the service publishes | `DatasetClient` (`list()`, `dataset(context, name)`) | `GET /wow/dataset`, then its query route |

## State reads

`get(id)`, or `read(id, { version | at | minVersion })`, and `trace(id)`. After a command, read with `minVersion: result.version`: the service waits briefly for the aggregate to reach it and answers 409 `wow.query.min-version-not-reached` (with the current version) rather than a stale state. A command sent with `wait.snapshot()` needs no `minVersion` for the snapshot query either.

## Queries and the DSL

The body names its operation; the client sets it: `single`, `list`, `listStream`, `paged`, `cursor`, `count`, `aggregate`. Build bodies with `@ahoo-wang/wow-client/dsl` (the root entry exports it too; `/dsl` alone loads no HTTP code):

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { desc, filter, pagedQuery } from '@ahoo-wang/wow-client/dsl';
import { OrderSnapshotQueryClient } from './generated/index.js';

const orders = new OrderSnapshotQueryClient({ fetcher: new Fetcher({ baseURL: 'https://api.example.com' }) });
const page = await orders.paged(
  pagedQuery({
    filter: filter.and([
      filter.eq('state.status', 'PAID'),
      filter.recentDays('state.paidAt', 7, { zoneId: 'Asia/Shanghai' }),
    ]),
    sort: [desc('state.paidAt')],
    pagination: { index: 1, size: 20 },
  }),
);
```

- **Fields:** a snapshot's state is under `state.` (`state.status`); system fields are at the root (`aggregateId`, `tenantId`, `version`, `ownerId`, `spaceId`, `deleted`, `tags`, `firstEventTime`, `lastEventTime`, `operator`). A generated client types them (`<Aggregate>StateField`), so a renamed field is a type error.
- **Events model:** one record per commit (`aggregateId`, `version`, `commitTime`, `commandId`, `requestId`, `commandType`, `events[]` of `{name, revision, body}`). A condition on an event's payload is one `filter.elementMatch('events', …)` holding the event's `name` and the payload condition, so both hold for the same event.
- **Relative time** is resolved by the server, never the browser's clock (`filter.beforeNow`, `afterNow`, `today`, `recentDays`, `beforeToday`, …). Without `zoneId` the server reads calendar days in UTC: pass `zoneId` for the user's day.
- **Projection:** a query written with a literal `projection: { include: ['state.status', 'aggregateId'] }` answers records typed to those paths (`Projected`); built by a factory or with `exclude` only, it answers whole records.
- **Totals:** `paged` counts its total `BOUNDED` by default (exact up to the server's limit); `total` with `exact` says which. An exact total over HTTP is refused unless the service allows it.
- **Cursor:** `cursor(cursorQuery({ … }))` answers `nextCursor`; pass it back unchanged with the same sort. Prefer it to deep `paged` pages.
- **Aggregation:** `aggregate(aggregation.query({ groupBy: [aggregation.terms('state.status', 'status')], metrics: [aggregation.count('orders')] }))`; `aggregation.query()` admits the whole query against the server's rules where it is built. The events model has no aggregation over HTTP unless its descriptor says so.
- **Streams:** `listStream(query)` reads a list as server-sent events and resumes where the server cut it (`Last-Event-ID`); `options.resume` tunes it, `false` turns it off. Its `limit` counts every record read.

## What the backend admits: the descriptor

`describe(version?)` reads the model's capability descriptor (`GET` on the query route, its `ETag` the version; pass the version held and a 304 answers `{ notModified: true }`): every field with the operators, sorts and aggregation it admits, the limits, and the constraints (`COUNT_REQUIRES_FILTER`, `AGGREGATE_REQUIRED`, …). Read it before offering a filter the backend refuses: the same query can be fine on MongoDB and refused on PostgreSQL or Elasticsearch.

## Exact numbers in reads

Generated query and state clients read the state's and events' `int64`, `bigint` and `decimal` fields as exact strings (`textNumbers`). Compare and sort them on the server, not after `Number(...)` in the browser.

## Refusals

A refused query is a `WowProblemError` with `code` `wow.query.invalid` or `wow.query.malformed` and `violations`, each with a `code` from `QueryErrorCodes` (`@ahoo-wang/wow-client/dsl`: `UNSUPPORTED_CAPABILITY`, `UNKNOWN_FIELD`, …), a `path` and a `message`; the list only grows, so fall back to `detail`. `wow.query.busy` (429) means back off by `Retry-After`; `wow.query.timeout` (504) a query too heavy for the budget; `wow.query.dataset-unavailable` (409) a dataset being revised.
