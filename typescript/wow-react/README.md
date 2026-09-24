# `@ahoo-wang/wow-react`

React hooks for [Wow](https://github.com/Ahoo-Wang/Wow) queries: single, list,
paged, count and list-stream. They keep a query, its result, loading and error
as React state, on top of `@ahoo-wang/fetcher-react` and the query types of
`@ahoo-wang/wow-client`.

## Requirements

- **React 19.3 or later.** The package is compiled with the React Compiler and
  imports `react/compiler-runtime`, which only React 19 has; React 18 is not
  supported.
- Node.js 22.12 or later for tooling and server rendering; any browser React 19
  supports.
- `@ahoo-wang/fetcher-react` 5.1.3 or later, and `@ahoo-wang/wow-client` of the
  same minor version as this package.
- TypeScript with `"moduleResolution": "bundler"` or `"module": "nodenext"`.

## Install

```bash
pnpm add react react-dom @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream \
  @ahoo-wang/fetcher-react @ahoo-wang/wow-client @ahoo-wang/wow-react
```

The version follows Wow, so a minor release may contain breaking changes: keep the Wow packages on one minor with `save-prefix=~` or `--save-exact`, as [version ranges and the support window](https://wow.ahoo.me/guide/typescript/compatibility#version-ranges) explains for the TypeScript npm packages.

`@ahoo-wang/fetcher-react` also declares `@ahoo-wang/fetcher-cosec`,
`@ahoo-wang/fetcher-storage` and `@ahoo-wang/fetcher-eventbus` as peers. npm 7+
and pnpm 8+ install peers on their own; with Yarn, add them to the command.
These hooks import only fetcher-react's `/core` and `/fetcher` subpaths, so
`@ahoo-wang/fetcher-wow` is not needed.

The package ships ES modules only. CommonJS code on Node.js 22.12+ can still
`require('@ahoo-wang/wow-react')`, because Node.js loads ES modules through
`require`.

## With a query client

The `use*Query` hooks take an `execute` function. A query client method —
`SnapshotQueryClient` of `@ahoo-wang/wow-client`, or the one a generated
`…QueryClientFactory` creates — has exactly the `(query, attributes, abort)`
shape `execute` takes, so the client supplies the URL, the headers and the
response handling, and nothing is hand-written:

```tsx
import {
  filter,
  pagedQuery,
  type SnapshotQueryClient,
} from '@ahoo-wang/wow-client';
import { usePagedQuery } from '@ahoo-wang/wow-react';

interface OrderState {
  id: string;
  status: string;
}

export function PaidOrders({
  client,
  page,
}: {
  client: SnapshotQueryClient<OrderState>;
  page: number;
}) {
  const { result, loading, error } = usePagedQuery<OrderState>({
    query: pagedQuery({
      filter: filter.eq('state.status', 'PAID'),
      pagination: { index: page, size: 20 },
    }),
    execute: (query, attributes, abortController) =>
      client.pagedState(query, attributes, abortController),
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

Hand `abortController` on: a newer query, `abort()` and an unmount then cancel
the request. A snapshot query filters the snapshot document, whose state lives
under `state`, so a state field is `state.status`, not `status`.

## With an endpoint URL

The `useFetcher*Query` hooks POST the query to a URL through a Fetcher instead:

```tsx
import { Fetcher } from '@ahoo-wang/fetcher';
import { filter, pagedQuery } from '@ahoo-wang/wow-client';
import { useFetcherPagedQuery } from '@ahoo-wang/wow-react';

const fetcher = new Fetcher({ baseURL: 'https://api.example.com/' });

export function PaidOrders() {
  const { result, loading, error } = useFetcherPagedQuery<OrderState>({
    fetcher,
    url: 'order/snapshot/paged/state',
    initialQuery: pagedQuery({
      filter: filter.eq('state.status', 'PAID'),
      pagination: { index: 1, size: 20 },
    }),
  });
  // …render as above
}
```

`fetcher` is a Fetcher or the name of a registered one; without it the default
Fetcher sends the request. The snapshot routes of an aggregate are
`{aggregate}/snapshot/{single|list|paged}[/state]` and
`{aggregate}/snapshot/count`, behind the context alias and the owner or tenant
path when the aggregate has them.

## Streaming a list

`useListStreamQuery` and `useFetcherListStreamQuery` read a list as
server-sent events and keep the rows as they arrive:

```tsx
import { filter, listQuery } from '@ahoo-wang/wow-client';
import { useFetcherListStreamQuery } from '@ahoo-wang/wow-react';

export function PaidOrderFeed() {
  const { items, done, loading, error, abort } =
    useFetcherListStreamQuery<OrderState>({
      fetcher,
      url: 'order/snapshot/list/state',
      initialQuery: listQuery({ filter: filter.eq('state.status', 'PAID') }),
    });

  if (error) return <p role="alert">{error.message}</p>;
  return (
    <>
      <ul>
        {items.map(order => (
          <li key={order.id}>{order.id}</li>
        ))}
      </ul>
      {loading && <button onClick={abort}>Stop</button>}
      {done && <p>{items.length} orders</p>}
    </>
  );
}
```

| Field     | Meaning                                                                                                                         |
| --------- | ------------------------------------------------------------------------------------------------------------------------------- |
| `items`   | The rows of the current query received so far, in order. A new query starts empty; `reset()` empties it.                        |
| `done`    | The stream ended normally: `items` holds every row. The same as `status === 'success'`.                                         |
| `loading` | From the request until the stream ends, fails or is aborted.                                                                    |
| `error`   | A failed request (`FetcherError`), or a `WowError` with the server's `errorCode` when the server sends an error event midway.   |
| `abort()` | Stops the stream and keeps the rows received. `reset()` stops it and empties `items`. `execute()` runs the current query again. |

The hook owns the stream: it reads it, renders once per network chunk rather
than once per row, and cancels it when a newer query starts, on `abort()` or
`reset()`, and on unmount. Components never hold a reader, so the hook is safe
under StrictMode. `useFetcherListStreamQuery` sends `Accept: text/event-stream`,
which a Wow server needs to answer with a stream; with `useListStreamQuery`,
pass a client's `listStream` or `listStateStream` as `execute`.

## State and errors

Every hook runs its query on mount and whenever `query` or `setQuery()` changes
it; set `autoExecute: false` to run it only through `execute()`. A newer query
aborts the request in flight, so a late response never overwrites a newer
result. `onSuccess` and `onError` are called with the result and the error.

A failed request sets `error` to the fetcher's error. `toWowError` from
`@ahoo-wang/wow-client` reads the server's `ErrorInfo` from it:

```ts
import { ErrorCodes, toWowError } from '@ahoo-wang/wow-client';

const wowError = await toWowError(error);
if (wowError?.errorCode === ErrorCodes.NOT_FOUND) {
  // …
}
```

## Hooks

| Hook                                              | Query                         | Result                              |
| ------------------------------------------------- | ----------------------------- | ----------------------------------- |
| `useSingleQuery`, `useFetcherSingleQuery`         | `singleQuery(…)`              | `result`: one item                  |
| `useListQuery`, `useFetcherListQuery`             | `listQuery(…)`                | `result`: the rows                  |
| `usePagedQuery`, `useFetcherPagedQuery`           | `pagedQuery(…)`               | `result`: `{ total, list }`         |
| `useCountQuery`, `useFetcherCountQuery`           | a `filter.*` expression       | `result`: the count                 |
| `useListStreamQuery`, `useFetcherListStreamQuery` | `listQuery(…)`, as SSE events | `items` as they arrive, then `done` |

The query types default to the `filter` API of `@ahoo-wang/wow-client`. Each
hook also accepts the deprecated Condition queries of
`@ahoo-wang/wow-client/legacy`, which Wow 8.10 servers need; that overload goes
in v10.

Moved from `@ahoo-wang/fetcher-react` (its Wow hooks) in Wow 9; the version
follows Wow.

## Documentation

- [TypeScript guide](https://wow.ahoo.me/guide/typescript/)
- [wow-react reference](https://wow.ahoo.me/reference/typescript/wow-react/)
- [Interactive query hook stories](https://wow.ahoo.me/storybook/?path=/docs/react-hooks-wow-queries--docs)

[中文](./README.zh-CN.md) · [License](https://github.com/Ahoo-Wang/Wow/blob/main/LICENSE)
