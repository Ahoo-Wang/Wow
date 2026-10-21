# React: `@ahoo-wang/wow-react`

Hooks that keep a query, its result, loading and error as React state, on the query types of `@ahoo-wang/wow-client`. React 19 or later (the package imports `react/compiler-runtime`); `@ahoo-wang/wow-client` of the same minor.

## With a query client (preferred)

`useSingleQuery`, `useListQuery`, `usePagedQuery`, `useCountQuery`, `useListStreamQuery` take an `execute` function receiving the query and the run's request options (its `signal` included); a generated client's method fits exactly:

```tsx
import type { SnapshotRecord } from '@ahoo-wang/wow-client';
import { filter, pagedQuery } from '@ahoo-wang/wow-client/dsl';
import { usePagedQuery } from '@ahoo-wang/wow-react';
import { type OrderState, type OrderStateField, OrderSnapshotQueryClient } from './generated/index.js';

export function PaidOrders({ client, page }: { client: OrderSnapshotQueryClient; page: number }) {
  const { result, loading, error } = usePagedQuery<SnapshotRecord<OrderState>, OrderStateField>({
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
- `requestOptions` adds the scope, the space or headers to every run.

## With an endpoint URL

`useFetcherSingleQuery`, `useFetcherListQuery`, `useFetcherPagedQuery`, `useFetcherCountQuery`, `useFetcherListStreamQuery` POST the query to a route (`/{context}/{aggregate}/snapshot/query` or `…/events/query`) through a Fetcher (or a registered Fetcher's name); use them where no generated client exists.

## Gaps

- No hook for aggregations or cursor pages yet: call `client.aggregate(query, { signal })` in an effect with an `AbortController`; run a cursor page through `useSingleQuery` with `CursorPage<R>` as the result and move on with `setQuery({ ...query, cursor: result.nextCursor })`.
- Commands have no hook: call the command client from the event handler, await it, and show the `WowProblemError`.
