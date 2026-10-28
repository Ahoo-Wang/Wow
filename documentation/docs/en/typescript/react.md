---
title: React Hooks
description: Run Wow queries from React components with wow-react, through a generated query client, keeping the query, its result, its loading state and its error as React state.
---

# React Hooks

`@ahoo-wang/wow-react` keeps a Wow query in a component: the query, its result, whether it is loading, and the error it failed with. A hook runs the query on mount and again whenever the query changes, cancels a request a newer query replaces, and cancels it on unmount. The queries are the ones of [Read and Query](./queries), run through a generated query client.

<!-- typecheck-generated: examples/eshop/web/src/generated -->

## Install

```bash
pnpm add react react-dom @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream \
  @ahoo-wang/wow-client @ahoo-wang/wow-react
```

React 19 or later: the package is built with the React Compiler and imports `react/compiler-runtime`, which React 18 does not have.

## Run a query through a client

A `use*Query` hook takes the query and an `execute` function that runs it; a generated client's method takes exactly the query and the request options, so the client supplies the route and the response handling:

```tsx
import type { SnapshotRecord } from '@ahoo-wang/wow-client';
import { desc, filter, pagedQuery } from '@ahoo-wang/wow-client/dsl';
import { usePagedQuery } from '@ahoo-wang/wow-react';
import type {
  OrderSnapshotQueryClient,
  OrderState,
  OrderStateField,
} from './generated/ordering/index.js';

export function PaidOrders({
  orders,
  page,
}: {
  orders: OrderSnapshotQueryClient;
  page: number;
}) {
  const { result, loading, error } = usePagedQuery<
    SnapshotRecord<OrderState>,
    OrderStateField | 'lastEventTime'
  >({
    query: pagedQuery({
      filter: filter.eq('state.status', 'PAID'),
      sort: [desc('lastEventTime')],
      pagination: { index: page, size: 20 },
    }),
    execute: (query, options) => orders.paged(query, options),
  });

  if (error) return <p role="alert">{error.message}</p>;
  if (loading || !result) return <p>Loading…</p>;
  return (
    <ul>
      {result.list.map(order => (
        <li key={order.aggregateId}>
          {order.aggregateId}: {order.state.totalAmount}
        </li>
      ))}
    </ul>
  );
}
```

- Pass the record type and the fields the query names as the type arguments: TypeScript does not infer the fields from `execute` once the first argument is written.
- Hand the options on to the client, as above: they carry the `signal` the hook cancels a request with.
- Pass the method through an arrow function: a method passed alone loses its `this`.

| Hook | Runs | `result` |
| --- | --- | --- |
| `useSingleQuery` | `singleQuery(…)` | One record |
| `useListQuery` | `listQuery(…)` | The records |
| `usePagedQuery` | `pagedQuery(…)` | `{ total, exact, list }` |
| `useCountQuery` | `{ filter, limit? }` | `{ count, exact }` |
| `useAggregateQuery` | `{ groupBy?, metrics, … }` | The rows, one per group |
| `useCursorQuery` | `{ filter, sort?, size? }`, page by page | `items` of every page read, `hasMore` and `fetchNext()` |
| `useStreamQuery` | `listQuery(…)`, as server-sent events | `items` as they arrive, `position`, then `done` |

## Read page by page

`useCursorQuery` reads a forward-only cursor query and keeps the rows of every page read; `fetchNext()` reads the next page and appends it. The hook owns the cursor: its query has none, every run starts at the first page, and a failed `fetchNext()` keeps the rows, so calling it again retries that page:

```tsx
import type { SnapshotRecord } from '@ahoo-wang/wow-client';
import { filter } from '@ahoo-wang/wow-client/dsl';
import { useCursorQuery } from '@ahoo-wang/wow-react';
import type {
  OrderSnapshotQueryClient,
  OrderState,
  OrderStateField,
} from './generated/ordering/index.js';

export function PaidOrderFeed({ orders }: { orders: OrderSnapshotQueryClient }) {
  const { items, hasMore, loading, error, fetchNext } = useCursorQuery<
    SnapshotRecord<OrderState>,
    OrderStateField
  >({
    query: { filter: filter.eq('state.status', 'PAID'), size: 50 },
    execute: (query, options) => orders.cursor(query, options),
  });

  if (error) return <p role="alert">{error.message}</p>;
  return (
    <>
      <ul>
        {items.map(order => (
          <li key={order.aggregateId}>{order.aggregateId}</li>
        ))}
      </ul>
      {hasMore && (
        <button disabled={loading} onClick={() => void fetchNext()}>
          More
        </button>
      )}
    </>
  );
}
```

## Stream a long list

`useStreamQuery` reads a list as server-sent events and keeps the rows as they arrive, rendering at most about once a frame however many come. Pass the client's `streamRows`, which answers each record with its position: a stream the service cuts off is resumed where it stopped, and the hook keeps the position of the last record read as `position`:

```tsx
import type { SnapshotRecord } from '@ahoo-wang/wow-client';
import { filter, listQuery } from '@ahoo-wang/wow-client/dsl';
import { useStreamQuery } from '@ahoo-wang/wow-react';
import type {
  OrderSnapshotQueryClient,
  OrderState,
  OrderStateField,
} from './generated/ordering/index.js';

export function ShippedOrders({ orders }: { orders: OrderSnapshotQueryClient }) {
  const { items, done, loading, error, abort } = useStreamQuery<
    SnapshotRecord<OrderState>,
    OrderStateField
  >({
    query: listQuery({ filter: filter.eq('state.status', 'SHIPPED'), limit: 1000 }),
    execute: (query, options) => orders.streamRows(query, options),
  });

  if (error) return <p role="alert">{error.message}</p>;
  return (
    <>
      <p>
        {items.length} orders{done ? '' : '…'}
      </p>
      {loading && <button onClick={abort}>Stop</button>}
    </>
  );
}
```

`abort()` stops the stream and keeps the rows it read; `reset()` stops it and empties them.

To go on where a stream stopped after the component remounts, keep `position` somewhere that outlives it and hand it back as `requestOptions: { resume: { lastEventId: saved } }`: the first request then asks for the records after it.

## State and errors

Every hook returns `status` (`'idle' | 'loading' | 'success' | 'error'`), `loading`, `result` (or `items`), `error`, `problem`, and `execute`, `abort`, `reset`, `getQuery` and `setQuery`.

- A hook runs on mount and whenever its query or its `requestOptions` (the scope, the space, more headers) changes, compared by content; `autoExecute: false` runs it only on `execute()`. A query set to `undefined` aborts the request in flight and leaves the hook idle. A header whose value changes on every render runs the query again on every render, so keep such values stable.
- A failed request or `abort()` keeps the last `result`, so a failed refresh does not blank the screen.
- In server rendering a hook renders as `loading` and sends nothing; the query runs once the page hydrates.
- `error` is an `Error`. `problem` is the same error when the service answered with a problem, a `WowProblemError` with its `code`, `status`, `violations` and retry hint, and `undefined` for a failure no service answered, such as a network error.

## Datasets

A dataset is registered at run time, so no client is generated for it; `DatasetClient` gives its query client. Create the client once, outside the component, and hand its method on as for a generated client:

```tsx
import { DatasetClient } from '@ahoo-wang/wow-client';
import { pagedQuery } from '@ahoo-wang/wow-client/dsl';
import { usePagedQuery } from '@ahoo-wang/wow-react';

const sales = new DatasetClient().dataset('eshop-ordering', 'sales');

export function SalesPage({ page }: { page: number }) {
  const { result } = usePagedQuery<Record<string, unknown>>({
    query: pagedQuery({ pagination: { index: page, size: 20 } }),
    execute: (query, options) => sales.paged(query, options),
  });
  return <p>{result?.total ?? '…'} rows</p>;
}
```

The [Storybook](/storybook/?path=/docs/react-hooks-wow-queries--docs) runs every hook against in-memory data.
