# React: `@ahoo-wang/wow-react`

Hooks that keep a query, its result, loading and error as React state, on the query types of `@ahoo-wang/wow-client`. React 19 or later (the package imports `react/compiler-runtime`); `@ahoo-wang/wow-client` of the same minor.

## With a query client

`useSingleQuery`, `useListQuery`, `usePagedQuery`, `useCountQuery`, `useAggregateQuery`, `useCursorQuery`, `useStreamQuery` take an `execute` function receiving the query and the run's request options (its `signal` included); a generated client's method fits exactly:

```tsx
import type { Snapshot } from '@ahoo-wang/wow-client';
import { filter, pagedQuery } from '@ahoo-wang/wow-client/dsl';
import { usePagedQuery } from '@ahoo-wang/wow-react';
import { type OrderState, type OrderStateField, OrderSnapshotQueryClient } from './generated/index.js';

export function PaidOrders({ client, page }: { client: OrderSnapshotQueryClient; page: number }) {
  const { result, loading, error } = usePagedQuery<Snapshot<OrderState>, OrderStateField>({
    query: pagedQuery({ filter: filter.eq('state.status', 'PAID'), pagination: { index: page, size: 20 } }),
    execute: (query, options) => client.paged(query, options),
  });
  if (error) return <p role="alert">Orders are unavailable</p>;
  if (loading || !result) return <p>Loading…</p>;
  return <ul>{result.list.map(o => <li key={o.aggregateId}>{o.state.status}</li>)}</ul>;
}
```

- Pass the client's fields type as the second type argument: TypeScript does not infer it from `execute` once the first is written.
- Hand the options on and a newer query, `abort()`, `reset()` or an unmount cancels the request; pass a method through an arrow function, or it loses its `this`.
- Create the client once, outside the component or memoized; never build one inside `execute`.
- `requestOptions` adds the scope, the space or headers to every run; a change by content runs the query again, so keep header values stable across renders.
- `error` is an `Error`; `problem` is the same error when the service answered with a `WowProblemError` (its `code`, `status`, retry hint), else `undefined`.

## Cursor pages and aggregations

`useCursorQuery` owns the cursor: its query has none, `execute: (query, options) => client.cursor(query, options)` receives the cursor of each page, and it returns `items` (every page read), `hasMore` and `fetchNext()`. `useAggregateQuery` keeps the rows of `client.aggregate` as `result`.

## Streams

`useStreamQuery` takes `execute: (query, options) => client.streamRows(query, options)` and returns `items`, `done` and `position`, the last record's position. To resume after a remount, save `position` and pass `requestOptions: { resume: { lastEventId: saved } }`.

## Datasets

A dataset has no generated client: `const sales = new DatasetClient(options).dataset('eshop-ordering', 'sales')`, created once, then `execute: (query, options) => sales.paged(query, options)`.

## Gaps

- Commands have no hook: call the command client from the event handler, await it, and show the `WowProblemError`.
