---
title: React Hooks
description: Run Wow queries from React components with wow-react, through a generated query client, keeping the query, its result, its loading state and its error as React state.
---

# React Hooks

`@ahoo-wang/wow-react` keeps a Wow query in a component: the query, its result, whether it is loading, and the error it failed with. A hook runs the query on mount and again whenever the query changes, cancels a request a newer query replaces, and cancels it on unmount. The queries are the ones of [Read and Query](./queries), run through a generated query client.

<!-- typecheck-generated: example/eshop/web/src/generated -->

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
| `useListStreamQuery` | `listQuery(…)`, as server-sent events | `items` as they arrive, then `done` |

Aggregations and cursor queries have no hook of their own. Run a cursor query through `useSingleQuery` with the page as its result, and an aggregation with `client.aggregate(query, { signal })` in an effect that aborts on cleanup.

## Stream a long list

`useListStreamQuery` reads a list as server-sent events and keeps the rows as they arrive, rendering at most about once a frame however many come. A stream the service cuts off is resumed where it stopped, as `stream` resumes it:

```tsx
import type { SnapshotRecord } from '@ahoo-wang/wow-client';
import { filter, listQuery } from '@ahoo-wang/wow-client/dsl';
import { useListStreamQuery } from '@ahoo-wang/wow-react';
import type {
  OrderSnapshotQueryClient,
  OrderState,
  OrderStateField,
} from './generated/ordering/index.js';

export function ShippedOrders({ orders }: { orders: OrderSnapshotQueryClient }) {
  const { items, done, loading, error, abort } = useListStreamQuery<
    SnapshotRecord<OrderState>,
    OrderStateField
  >({
    query: listQuery({ filter: filter.eq('state.status', 'SHIPPED'), limit: 1000 }),
    execute: (query, options) => orders.stream(query, options),
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

## State and errors

Every hook returns `status` (`'idle' | 'loading' | 'success' | 'error'`), `loading`, `result` (or `items` and `done`), `error`, and `execute`, `abort`, `reset`, `getQuery` and `setQuery`.

- A hook runs on mount and whenever its query changes, compared by content; `autoExecute: false` runs it only on `execute()`. A query set to `undefined` aborts the request in flight and leaves the hook idle.
- A failed request or `abort()` keeps the last `result`, so a failed refresh does not blank the screen.
- In server rendering a hook renders as `loading` and sends nothing; the query runs once the page hydrates.
- `error` is a `WowProblemError` for a request the service refused, with its `code`, `status` and `violations`; pass the error type as the third type argument to type it so.

## Without a generated client

The `useFetcher*Query` hooks post a query to a query route through a Fetcher instead of a client, for a route no client covers, such as a dataset's: `useFetcherPagedQuery({ fetcher, url: '/wow/dataset/eshop-ordering/sales/query', initialQuery })`. The [wow-react README](https://github.com/Ahoo-Wang/Wow/tree/main/typescript/wow-react) lists their options.

The [Storybook](/storybook/?path=/docs/react-hooks-wow-queries--docs) runs every hook against in-memory data.
