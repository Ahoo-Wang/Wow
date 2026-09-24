# `@ahoo-wang/wow-react`

React hooks for [Wow](https://github.com/Ahoo-Wang/Wow) queries: single, list,
paged, count and list-stream. They build on the query state of
`@ahoo-wang/fetcher-react` and the query types of `@ahoo-wang/wow-client`.

## Install

```bash
pnpm add react @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream \
  @ahoo-wang/fetcher-react @ahoo-wang/wow-client @ahoo-wang/wow-react
```

`@ahoo-wang/fetcher-react` 5.1.3 or later is required: these hooks import only
its `/core` and `/fetcher` subpaths, so `@ahoo-wang/fetcher-wow` is not needed.

## Example

```tsx
import { filter } from '@ahoo-wang/wow-client';
import { useFetcherPagedQuery } from '@ahoo-wang/wow-react';

interface Order {
  id: string;
  status: string;
}

export function Orders() {
  const { loading, result, error } = useFetcherPagedQuery<Order>({
    url: '/order/snapshot_state/paged',
    initialQuery: {
      filter: filter.eq('status', 'PAID'),
      pagination: { index: 1, size: 20 },
    },
    autoExecute: true,
  });

  if (error) return <p role="alert">Unable to load orders</p>;
  if (loading || !result) return <p>Loading…</p>;
  return (
    <ul>
      {result.list.map(order => (
        <li key={order.id}>{order.status}</li>
      ))}
    </ul>
  );
}
```

## Hooks

| Hook                                              | Query                                |
| ------------------------------------------------- | ------------------------------------ |
| `useSingleQuery`, `useFetcherSingleQuery`         | One snapshot                         |
| `useListQuery`, `useFetcherListQuery`             | A list                               |
| `usePagedQuery`, `useFetcherPagedQuery`           | A page with the total                |
| `useCountQuery`, `useFetcherCountQuery`           | A count                              |
| `useListStreamQuery`, `useFetcherListStreamQuery` | A list as a server-sent event stream |

The `use*Query` hooks take your own `execute` function; the `useFetcher*Query`
hooks send the request through a Fetcher instance.

Moved from `@ahoo-wang/fetcher-react` (its `wow` hooks) in Wow 9; the version
follows Wow.
