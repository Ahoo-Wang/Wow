# `@ahoo-wang/wow-react`

React hooks for [Wow](https://github.com/Ahoo-Wang/Wow) queries: single, list,
paged, count, aggregate, cursor and list-stream. They keep a query, its result, loading and error
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

The version follows Wow, so a minor release may contain breaking changes: keep the Wow packages on one minor with `save-prefix=~` or `--save-exact`, as [version ranges](https://wow.ahoo.me/typescript/compatibility#version-ranges) explains.

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
run. Create the client once, outside the component or memoized, not inside
`execute`: one client serves every request.

## Reading page by page

`useCursorQuery` reads a forward-only cursor query and keeps the rows of every
page read; `fetchNext()` reads the next page and appends it. The hook owns the
cursor, so its query has none (a `cursor` there does not compile): every run
starts at the first page, and `execute` receives the query with the cursor of
the page it reads.

```tsx
import type {
  SnapshotQueryClient,
  SnapshotRecord,
} from '@ahoo-wang/wow-client';
import { filter } from '@ahoo-wang/wow-client/dsl';
import { useCursorQuery } from '@ahoo-wang/wow-react';

interface OrderState {
  status: string;
}
type OrderFields = 'state.status';

export function PaidOrderPages({
  client,
}: {
  client: SnapshotQueryClient<OrderState, OrderFields>;
}) {
  const { items, hasMore, loading, error, fetchNext } = useCursorQuery<
    SnapshotRecord<OrderState>,
    OrderFields
  >({
    query: { filter: filter.eq('state.status', 'PAID'), size: 50 },
    execute: (query, options) => client.cursor(query, options),
  });

  if (error) return <p role="alert">{error.message}</p>;
  return (
    <>
      <ul>
        {items.map(order => (
          <li key={order.aggregateId}>{order.state.status}</li>
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

`fetchNext()` does nothing while a page is loading or after the last page. A
failed `fetchNext()` keeps the rows and the cursor, so calling it again
retries that page; a new query and `execute()` start again from the first
page, and `reset()` empties the rows.

`useAggregateQuery` runs an aggregation, typically `client.aggregate`, and
keeps its rows as `result`, one per group.

## Streaming a list

`useStreamQuery` reads a list as server-sent events and keeps the records as
they arrive. Pass a client's `streamRows` as `execute`: it sends
`Accept: text/event-stream`, resumes a stream the service cuts off where it
stopped, and answers each record with its position, which the hook keeps as
`position`.

```tsx
import type {
  SnapshotQueryClient,
  SnapshotRecord,
} from '@ahoo-wang/wow-client';
import { filter, listQuery } from '@ahoo-wang/wow-client/dsl';
import { useStreamQuery } from '@ahoo-wang/wow-react';

interface OrderState {
  status: string;
}
type OrderFields = 'state.status';

export function PaidOrderFeed({
  client,
}: {
  client: SnapshotQueryClient<OrderState, OrderFields>;
}) {
  const { items, done, loading, error, abort } = useStreamQuery<
    SnapshotRecord<OrderState>,
    OrderFields
  >({
    initialQuery: listQuery({
      filter: filter.eq('state.status', 'PAID'),
      limit: 100,
    }),
    execute: (query, options) => client.streamRows(query, options),
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

| Field      | Meaning                                                                                                                         |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------- |
| `items`    | The records of the current query received so far, in order. A new query starts empty; `reset()` empties it.                     |
| `position` | The position of the last record received that had one; a new query and `reset()` clear it.                                      |
| `done`     | The stream ended normally: `items` holds every record. The same as `status === 'success'`.                                      |
| `loading`  | From the request until the stream ends, fails or is aborted.                                                                    |
| `problem`  | The `WowProblemError` of a failed request, or of the error event the service sends midway.                                      |
| `abort()`  | Stops the stream and keeps the rows received. `reset()` stops it and empties `items`. `execute()` runs the current query again. |

The hook owns the stream: it reads it, renders the records at most about once
a frame (every 16 ms) however many arrive, and cancels it when a newer query
starts, on `abort()` or `reset()`, and on unmount. Components never hold a
reader, so the hook is safe under StrictMode.

To go on where a stream stopped after the component remounts (a page change,
a reload), keep `position` somewhere that outlives it and hand it back as
`requestOptions: { resume: { lastEventId: saved } }`; the first request then
asks for the records after it.

## State and errors

Every hook runs its query on mount and whenever `query` or `requestOptions`
(both compared by content) or `setQuery()` changes it; set
`autoExecute: false` to run it only through `execute()`. `requestOptions`
counts because the scope, the space and the headers can change the answer: a
header whose value changes on every render runs the query again on every
render, so keep such values stable. A newer query aborts the request in
flight, so a late response never overwrites a newer result. A controlled
`query` set back to `undefined` (`id ? singleQuery(…) : undefined`) aborts the
request in flight and goes `idle`, keeping the last result; nothing runs until
a query is given again. `onSuccess` and `onError` are called with the result
and the error.

A failed request and `abort()` keep the last `result`, so a failed refresh does
not blank the screen; `reset()` aborts the request in flight and clears
`result` and `error`. A hook that runs on mount renders its first frame, on the
server too, as `loading`, and sends no request during server rendering. There
is no `initialResult` option: server-rendered HTML holds the loading state, and
the query runs once the page hydrates.

Every hook returns `status`, `loading`, `result` (`items` for the stream and
cursor hooks), `error`, `problem`, `execute`, `abort`, `reset`, `getQuery` and
`setQuery`. The option and return types of each hook extend
`QueryHookOptions` and `QueryHookReturn`, which this package declares and
exports with `QueryStatus`, `QueryExecutor` and `StreamExecutor`. `status` is
the plain string union `'idle' | 'loading' | 'success' | 'error'`, so a
component's props or a test can write a literal.

`error` is an `Error`: what the run failed with, a thrown value that is not an
`Error` wrapped in one with the value as its `cause`. `problem` is that same
error when a Wow service answered with a problem, a `WowProblemError` with the
service's `code`, its `status` and retry hint, and `undefined` for a failure no
service answered, such as a network error:

```tsx
import type { SnapshotQueryClient } from '@ahoo-wang/wow-client';
import { FrameworkErrorCodes } from '@ahoo-wang/wow-client';
import { filter } from '@ahoo-wang/wow-client/dsl';
import { useCountQuery } from '@ahoo-wang/wow-react';

export function OrderCount({ client }: { client: SnapshotQueryClient }) {
  const { result, problem, error } = useCountQuery({
    query: { filter: filter.matchAll() },
    execute: (query, options) => client.count(query, options),
  });
  if (problem?.code === FrameworkErrorCodes.QUERY_BUSY)
    return <p>Busy, retry in {problem.retry.afterMs} ms</p>;
  if (error) return <p role="alert">{error.message}</p>;
  return <p>{result?.count ?? '…'} orders</p>;
}
```

## Hooks

| Hook                | Query                         | Result                                               |
| ------------------- | ----------------------------- | ---------------------------------------------------- |
| `useSingleQuery`    | `singleQuery(…)`              | `result`: one item                                   |
| `useListQuery`      | `listQuery(…)`                | `result`: the rows                                   |
| `usePagedQuery`     | `pagedQuery(…)`               | `result`: `{ total, exact, list }`                   |
| `useCountQuery`     | `{ filter, limit? }`          | `result`: `{ count, exact }`                         |
| `useAggregateQuery` | `{ groupBy?, metrics, … }`    | `result`: the rows, one per group                    |
| `useCursorQuery`    | `{ filter, sort?, size? }`    | `items` of every page read, `hasMore`, `fetchNext()` |
| `useStreamQuery`    | `listQuery(…)`, as SSE events | `items` as they arrive, `position`, then `done`      |

The queries are built with the DSL of `@ahoo-wang/wow-client/dsl`.

Moved from `@ahoo-wang/fetcher-react` (its Wow hooks) in Wow 9; the version
follows Wow.

## Documentation

- [TypeScript guide](https://wow.ahoo.me/typescript/)
- [Interactive query hook stories](https://wow.ahoo.me/storybook/?path=/docs/react-hooks-wow-queries--docs)

[中文](./README.zh-CN.md) · [License](https://github.com/Ahoo-Wang/Wow/blob/main/LICENSE)
