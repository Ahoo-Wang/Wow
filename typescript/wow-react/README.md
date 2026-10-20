# `@ahoo-wang/wow-react`

React hooks for [Wow](https://github.com/Ahoo-Wang/Wow) queries: single, list,
paged, count and list-stream. They keep a query, its result, loading and error
as React state, on the query types of `@ahoo-wang/wow-client`.

## Requirements

- **React 19.0 or later.** The package is compiled with the React Compiler and
  imports `react/compiler-runtime`, which only React 19 has; React 18 is not
  supported.
- Node.js 22.12 or later for tooling and server rendering; any browser React 19
  supports.
- `@ahoo-wang/wow-client` of the same minor version as this package.
- TypeScript 6 or later (CI tests 6.0 through the latest 7.x) with
  `"moduleResolution": "bundler"` or `"module": "nodenext"`.

## Install

```bash
pnpm add react react-dom @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream \
  @ahoo-wang/wow-client @ahoo-wang/wow-react
```

`@ahoo-wang/fetcher-eventstream` is a peer of `@ahoo-wang/wow-client`, not of
this package.

The version follows Wow, so a minor release may contain breaking changes: keep the Wow packages on one minor with `save-prefix=~` or `--save-exact`, as [version ranges](https://wow.ahoo.me/guide/typescript/compatibility#version-ranges) explains.

The hooks run their own request state machine: `@ahoo-wang/fetcher-react` is
not needed, and neither is `@ahoo-wang/fetcher-wow`.

The package ships ES modules only. CommonJS code on Node.js 22.12+ can still
`require('@ahoo-wang/wow-react')`, because Node.js loads ES modules through
`require`.

## With a query client

The `use*Query` hooks take an `execute` function: the query and the request
options of the run, its `signal` included. A query client of
`@ahoo-wang/wow-client` — a generated `…SnapshotQueryClient`, or a
`QueryModelClient` — takes exactly those as its last parameters, so the client
supplies the route, the headers and the response handling, and nothing is
hand-written:

```tsx
import type {
  SnapshotQueryClient,
  SnapshotRecord,
} from '@ahoo-wang/wow-client';
import { filter, pagedQuery } from '@ahoo-wang/wow-client/dsl';
import { usePagedQuery } from '@ahoo-wang/wow-react';

interface OrderState {
  status: string;
}
// The state fields a query may name; a generated client exports this type.
type OrderFields = 'state.status';

export function PaidOrders({
  client,
  page,
}: {
  client: SnapshotQueryClient<OrderState, OrderFields>;
  page: number;
}) {
  const { result, loading, error } = usePagedQuery<
    SnapshotRecord<OrderState>,
    OrderFields
  >({
    query: pagedQuery({
      filter: filter.eq('state.status', 'PAID'),
      pagination: { index: page, size: 20 },
    }),
    execute: (query, options) => client.paged(query, options),
  });

  if (error) return <p role="alert">Unable to load orders</p>;
  if (loading || !result) return <p>Loading…</p>;
  return (
    <ul>
      {result.list.map(order => (
        <li key={order.aggregateId}>{order.state.status}</li>
      ))}
    </ul>
  );
}
```

Pass the client's fields type as the second type argument, as above: a
generated client's fields are narrower than `string`, and TypeScript does not
infer them from `execute` once the first type argument is written. Hand the
options on: a newer query, `abort()`, `reset()` and an unmount then cancel the
request. Hand a method over through an arrow function, as above: a method
passed alone loses its `this`. A snapshot query filters the snapshot record,
whose state lives under `state`, so a state field is `state.status`, not
`status`. `requestOptions` adds the scope, the space or more headers to every
run.

Aggregations have no hook yet. Call `client.aggregate(query, { signal })` in
an effect with an `AbortController` and abort it on cleanup.

Cursor queries have no hook yet either. Run one through `useSingleQuery`, with
the page as the result: pass `CursorPage<R>` and `CursorQuery<Fields>` as the
type arguments and `client.cursor` as `execute`, and move to the next page with
`setQuery({ ...query, cursor: result.nextCursor })` while `nextCursor` is not
`null`.

## With an endpoint URL

The `useFetcher*Query` hooks POST the query to a query route through a
Fetcher instead:

```tsx
import { Fetcher } from '@ahoo-wang/fetcher';
import type { SnapshotRecord } from '@ahoo-wang/wow-client';
import { filter, pagedQuery } from '@ahoo-wang/wow-client/dsl';
import { useFetcherPagedQuery } from '@ahoo-wang/wow-react';

const fetcher = new Fetcher({ baseURL: 'https://api.example.com' });

export function PaidOrders() {
  const { result, loading, error } = useFetcherPagedQuery<
    SnapshotRecord<OrderState>
  >({
    fetcher,
    url: '/shop/order/snapshot/query',
    initialQuery: pagedQuery({
      filter: filter.eq('state.status', 'PAID'),
      pagination: { index: 1, size: 20 },
    }),
  });
  // …render as above
}
```

`fetcher` is a Fetcher or the name of a registered one; without it the default
Fetcher sends the request. An aggregate's snapshots are queried at
`/{context}/{aggregate}/snapshot/query`, its events at
`/{context}/{aggregate}/events/query`; the hook names the operation (`single`,
`list`, `paged`, `count`) in the query's `kind`.

A request is identified by `url` and the Fetcher: a named Fetcher by its name,
an unnamed one by its `baseURL`. Two unnamed Fetchers with the same `baseURL`
count as one endpoint even when their interceptors differ (for example one per
tenant), so switching between them does not run the query again. Give each a
name, or pass the name of a registered Fetcher, to tell them apart.

## Streaming a list

`useListStreamQuery` and `useFetcherListStreamQuery` read a list as
server-sent events and keep the rows as they arrive. A stream the service
cuts off is resumed where it stopped, as wow-client's `listStream` does.

```tsx
import type { SnapshotRecord } from '@ahoo-wang/wow-client';
import { filter, listQuery } from '@ahoo-wang/wow-client/dsl';
import { useFetcherListStreamQuery } from '@ahoo-wang/wow-react';

export function PaidOrderFeed() {
  const { items, done, loading, error, abort } = useFetcherListStreamQuery<
    SnapshotRecord<OrderState>
  >({
    fetcher,
    url: '/shop/order/snapshot/query',
    initialQuery: listQuery({
      filter: filter.eq('state.status', 'PAID'),
      limit: 100,
    }),
  });

  if (error) return <p role="alert">{error.message}</p>;
  return (
    <>
      <ul>
        {items.map(order => (
          <li key={order.aggregateId}>{order.state.status}</li>
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
| `error`   | The `WowProblemError` of a failed request, or of the error event the service sends midway.                                      |
| `abort()` | Stops the stream and keeps the rows received. `reset()` stops it and empties `items`. `execute()` runs the current query again. |

The hook owns the stream: it reads it, renders the rows at most about once a
frame (every 16 ms) however many arrive, and cancels it when a newer query starts, on `abort()` or
`reset()`, and on unmount. Components never hold a reader, so the hook is safe
under StrictMode. `useFetcherListStreamQuery` sends `Accept: text/event-stream`,
which a Wow service needs to answer with a stream; with `useListStreamQuery`,
pass a client's `listStream` as `execute`.

## State and errors

Every hook runs its query on mount and whenever `query` (compared by content)
or `setQuery()` changes it, and a `useFetcher*` hook also when `url` or
`fetcher` changes; set `autoExecute: false` to run it only through
`execute()`. A newer query aborts the request in flight, so a late response
never overwrites a newer result. A controlled `query` set back to `undefined`
(`id ? singleQuery(…) : undefined`) aborts the request in flight and goes
`idle`, keeping the last result; nothing runs until a query is given again. `onSuccess` and `onError` are called with the
result and the error.

A failed request and `abort()` keep the last `result`, so a failed refresh does
not blank the screen; `reset()` aborts the request in flight and clears
`result` and `error`. A hook that runs on mount renders its first frame, on the
server too, as `loading`, and sends no request during server rendering. There
is no `initialResult` option: server-rendered HTML holds the loading state, and
the query runs once the page hydrates.

Every hook returns `status`, `loading`, `result` (`items` and `done` for a
stream), `error`, `execute`, `abort`, `reset`, `getQuery` and `setQuery`. The
option and return types of each hook extend `QueryHookOptions` and
`QueryHookReturn`, which this package declares and exports with `QueryStatus`
and `QueryExecutor`. `status` is the plain string union
`'idle' | 'loading' | 'success' | 'error'`, so a component's props or a test
can write a literal.

`error` is typed `Error` unless you pass the `E` type argument: a failed
request, and an error event in a stream, reject with wow-client's
`WowProblemError`, and your own `execute` with whatever it throws. The problem
carries the service's `code` and its `status`:

```ts
import { FrameworkErrorCodes, WowProblemError } from '@ahoo-wang/wow-client';

declare const error: unknown;
if (
  error instanceof WowProblemError &&
  error.code === FrameworkErrorCodes.AGGREGATE_NOT_FOUND
) {
  // …
}
```

## Hooks

| Hook                                              | Query                         | Result                              |
| ------------------------------------------------- | ----------------------------- | ----------------------------------- |
| `useSingleQuery`, `useFetcherSingleQuery`         | `singleQuery(…)`              | `result`: one item                  |
| `useListQuery`, `useFetcherListQuery`             | `listQuery(…)`                | `result`: the rows                  |
| `usePagedQuery`, `useFetcherPagedQuery`           | `pagedQuery(…)`               | `result`: `{ total, exact, list }`  |
| `useCountQuery`, `useFetcherCountQuery`           | `{ filter, limit? }`          | `result`: `{ count, exact }`        |
| `useListStreamQuery`, `useFetcherListStreamQuery` | `listQuery(…)`, as SSE events | `items` as they arrive, then `done` |

The queries are built with the DSL of `@ahoo-wang/wow-client/dsl`.

Moved from `@ahoo-wang/fetcher-react` (its Wow hooks) in Wow 9; the version
follows Wow.

## Documentation

- [TypeScript guide](https://wow.ahoo.me/guide/typescript/)
- [Interactive query hook stories](https://wow.ahoo.me/storybook/?path=/docs/react-hooks-wow-queries--docs)

[中文](./README.zh-CN.md) · [License](https://github.com/Ahoo-Wang/Wow/blob/main/LICENSE)
