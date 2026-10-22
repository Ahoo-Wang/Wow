---
title: Read and Query
description: Read an aggregate's state through a generated client, after a command or at a version, query snapshots and event streams with the typed DSL, stream long lists, and query datasets.
---

# Read and Query

A generated client reads an aggregate in two ways: its state client reads one aggregate by id, and its query clients run queries over all of its snapshots or its event streams. The query DSL, `@ahoo-wang/wow-client/dsl`, builds the queries, typed with the fields the aggregate's state has. This page uses the eShop's generated ordering clients ([Generate a Client](./generate)).

<!-- typecheck-generated: example/eshop/web/src/generated -->

## Read one aggregate

<!-- typecheck-context
import { OrderStateClient } from './generated/ordering/index.js';
declare const orderStates: OrderStateClient;
-->

```ts
const order = await orderStates.get('order-1');
console.log(order.version, order.state.status, order.state.totalAmount);
```

The view holds the state with the aggregate's `ref`, `version`, `scope`, `tags` and whether it is `deleted`. An aggregate that does not exist rejects with 404 `wow.aggregate.not-found`. `totalAmount` is a `BigDecimal` on the server, so it is a `string` here, with its digits exactly as written.

The options pick another state:

| Option | Reads |
| --- | --- |
| `minVersion` | The latest state at this version or later: read your own write |
| `version` | The state committed at this version |
| `at` | The state as of this instant, an ISO-8601 text or a `Date` |

After a command, read with the version its result names. A snapshot that lags behind is caught up with the log first:

<!-- typecheck-context
import { OrderCommandClient, OrderStateClient } from './generated/ordering/index.js';
declare const orders: OrderCommandClient;
declare const orderStates: OrderStateClient;
-->

```ts
const placed = await orders.submitOrder({
  items: [{ productId: 'p-1', quantity: 1, unitPrice: '19.90' }],
});
const order = await orderStates.get(placed.ref.id, {
  minVersion: placed.version ?? 1,
});
```

A version the aggregate does not reach in time rejects with 409 `wow.query.min-version-not-reached`; the problem's `version` is the current one.

`read` answers with the state's entity tag, `etag`; pass it back as `ifNoneMatch` and an unchanged state answers `{ notModified: true }` without a body. `trace(id, { from, to, limit })` reads the commits in between, each with its events and the state after it: 100 per page by default, at most 1,000.

## Query snapshots

`<Aggregate>SnapshotQueryClient` queries the aggregate's snapshots, at `POST /<context>/<aggregate>/snapshot/query`. A snapshot record holds the state under `state`, beside the system fields (`aggregateId`, `version`, `ownerId`, `tags`, `lastEventTime` and the rest), so a filter names `state.status`, not `status`:

<!-- typecheck-context
import { OrderSnapshotQueryClient } from './generated/ordering/index.js';
declare const orderQueries: OrderSnapshotQueryClient;
-->

```ts
import { desc, filter, pagedQuery } from '@ahoo-wang/wow-client/dsl';

const page = await orderQueries.paged(
  pagedQuery({
    filter: filter.and([
      filter.isIn('state.status', ['PAID', 'SHIPPED']),
      filter.afterNow('lastEventTime', '-P7D'),
    ]),
    sort: [desc('lastEventTime')],
    pagination: { index: 1, size: 20 },
  }),
);
console.log(page.total, page.list.map(record => record.state.status));
```

The field names are typed from the generated `<Aggregate>StateField` and the system fields, so a misspelt field does not compile. Each client method runs one kind of query:

| Method | Answers |
| --- | --- |
| `single` | The one record that matches; 404 `wow.query.not-found` when none does |
| `list` | At most `limit` records; the service's default limit when absent |
| `paged` | One page, `{ total, exact, list }` |
| `cursor` | One page and the `nextCursor` of the next, or `null` after the last |
| `count` | `{ count, exact }` |
| `aggregate` | The rows of an aggregation: groups and metrics |
| `listStream` | The records as server-sent events, one at a time |
| `describe` | The query model's descriptor: the fields, the operators, sorts and aggregations each admits, and the limits |

The [Query Language](../reference/query-language) defines each operator, the request of each kind and its answer.

### Projections narrow the result

A query whose `projection.include` lists literal paths answers records with only those paths, and the result's type says so:

<!-- typecheck-context
import { OrderSnapshotQueryClient } from './generated/ordering/index.js';
declare const orderQueries: OrderSnapshotQueryClient;
-->

```ts
import { filter } from '@ahoo-wang/wow-client/dsl';

const statuses = await orderQueries.list({
  filter: filter.eq('state.status', 'PAID'),
  projection: { include: ['aggregateId', 'state.totalAmount'] },
  limit: 50,
});
for (const record of statuses) console.log(record.aggregateId, record.state.totalAmount);
```

`record.state.status` does not compile here: it was not included. The narrowing needs the query written in the call, with its paths literal. A query built with `listQuery` or `pagedQuery`, or one with only `exclude`, answers whole records in its type.

### Relative time and time zones

`filter.beforeNow` and `filter.afterNow` compare a field with the server's clock plus an offset, never the browser's. Calendar filters (`today`, `thisWeek`, `lastMonth` and the others) read their days in a time zone: the `zoneId` you pass, else UTC. For the user's own days, pass their zone:

```ts
import { filter } from '@ahoo-wang/wow-client/dsl';

const zoneId = Intl.DateTimeFormat().resolvedOptions().timeZone;
const placedToday = filter.today('firstEventTime', { zoneId });
```

### Long lists

`listStream` reads a list as server-sent events, for a list too long to answer in one response. When the service ends the stream to be resumed, when the connection is cut off, or when it fails with a retryable problem, the client resumes after the last record it read (`Last-Event-ID`), so the reader sees each record once:

<!-- typecheck-context
import { OrderSnapshotQueryClient } from './generated/ordering/index.js';
declare const orderQueries: OrderSnapshotQueryClient;
-->

```ts
import { filter, listQuery } from '@ahoo-wang/wow-client/dsl';

const stream = await orderQueries.listStream(
  listQuery({ filter: filter.eq('state.status', 'SHIPPED'), limit: 10_000 }),
);
const reader = stream.getReader();
for (;;) {
  const { value, done } = await reader.read();
  if (done) break;
  console.log(value.aggregateId);
}
```

A failure the client cannot resume errors the stream with its `WowProblemError`.

## Query event streams

`<Aggregate>EventQueryClient` runs the same queries over the aggregate's event streams, one record per commit, its events in `events`, typed by the generated `<Aggregate>Event` union:

<!-- typecheck-context
import { OrderEventQueryClient } from './generated/ordering/index.js';
declare const orderEvents: OrderEventQueryClient;
-->

```ts
import { asc, filter, listQuery } from '@ahoo-wang/wow-client/dsl';

const commits = await orderEvents.list(
  listQuery({
    filter: filter.eq('aggregateId', 'order-1'),
    sort: [asc('version')],
  }),
);
for (const commit of commits)
  for (const event of commit.events) console.log(commit.version, event.name);
```

An older event is answered at its current revision, upgraded the way the service upgrades it ([Event Queries](../guide/event-queries)).

## Query datasets

A dataset is a table a service publishes for analysis ([Publish Datasets](../guide/datasets)). Datasets are registered at run time, so they are not in the Manifest and have no generated client: `DatasetClient` lists those the caller sees and queries one by name:

```ts
import { Fetcher } from '@ahoo-wang/fetcher';
import { DatasetClient } from '@ahoo-wang/wow-client';
import { filter } from '@ahoo-wang/wow-client/dsl';

const datasets = new DatasetClient({
  fetcher: new Fetcher({ baseURL: 'https://ordering.example.com' }),
});
const sales = datasets.dataset<{ status: string; totalAmount: number }>(
  'eshop-ordering',
  'sales',
);
const rows = await sales.list({ filter: filter.matchAll(), limit: 100 });
console.log(rows.length);
```

Dataset queries are experimental in 10.0: their API may still change.

## When a query fails

A rejected query rejects with a `WowProblemError`. 400 `wow.query.invalid` lists a violation per rule it broke: its `code` one of `QueryErrorCodes` (from `/dsl`), its `path`, and a `message`. That list only grows, so fall back to the problem's `detail` for a code you do not handle. 429 `wow.query.busy` means the store runs as many queries as it allows: `error.retry.afterMs` says when to try again. Every method takes the request options last, with the `signal` that cancels the query.

React components run these queries through the hooks of `@ahoo-wang/wow-react`, which keep a query, its result and its loading state.
