---
title: 'wow-react query hooks'
description: 'Wow query hooks — @ahoo-wang/wow-react'
---

# wow-react query hooks

Wow hooks specialize the same query executor with request/result types. They do not create query clients or build conditions for you. Use service-based variants with an `execute` function, or Fetcher variants with a required `url` and optional named/instance Fetcher.

| Service hook       | Fetcher hook              | Request → result                                         |
| ------------------ | ------------------------- | -------------------------------------------------------- |
| useSingleQuery     | useFetcherSingleQuery     | SingleQueryRequest → R                                   |
| useListQuery       | useFetcherListQuery       | ListQueryRequest → R[]                                   |
| usePagedQuery      | useFetcherPagedQuery      | PagedQueryRequest → PagedList&lt;R&gt; (`total`, `list`) |
| useCountQuery      | useFetcherCountQuery      | FilterExpression, or legacy Condition → number           |
| useListStreamQuery | useFetcherListStreamQuery | ListQueryRequest → `items: R[]` as rows arrive           |

The query type defaults to `FilterSingleQuery`, `FilterListQuery`, `FilterPagedQuery`, or `FilterExpression`. A second overload of each hook accepts the deprecated `Condition` queries imported from `@ahoo-wang/wow-client/legacy`, which Wow 8.10 servers need; that overload is removed in v10. `SingleQueryRequest`, `ListQueryRequest`, and `PagedQueryRequest` in the signatures below are the unions of both kinds, also exported by `/legacy`. `FIELDS` restricts field names statically. Type arguments do not validate server JSON. Every `Use…Options` extends [`QueryHookOptions`](#api-QueryHookOptions) and every `Use…Return` extends [`QueryHookReturn`](#api-QueryHookReturn), both declared by this package; the options of the Fetcher variants take `url` and `fetcher` in place of `execute`. Fetcher variants POST the query to `url` and extract JSON; the stream variant sends the request through wow-client's `QUERY_STREAM_ENDPOINT` preset (`Accept: text/event-stream`), so the stream yields rows and ends with a `WowError` when the server sends an error event.

::: tip Write both type arguments with a generated client
A generated client's fields are narrower than `string`, so pass them as `FIELDS` next to the state: `` type CartFields = `${CartAggregatedFields}` ``, then `usePagedQuery<CartState, CartFields>({ execute: …, … })`. With only `<CartState>`, the query type falls back to `string` fields and the generated client's method does not fit `execute`: TypeScript reports a long TS2345 or TS2769 listing every field name. TypeScript does not infer `FIELDS` from `execute`: once `R` is written, every type argument after it takes its default. Without any type argument, `FIELDS` is inferred from the first query, and `setQuery()` then admits only the fields that query names.
:::

A newer query aborts the one in flight, and a late response never overwrites a newer one; an unmount aborts too. Pass the controller on to the service client so an abort stops the I/O: the last parameter of every wow-client query method, `abort`, takes the `AbortController` or its `signal`. A failed request sets `error` to the fetcher's error (`ExchangeError`); `await toWowError(error)` from `@ahoo-wang/wow-client` reads `errorCode`, `errorMsg`, and `status` from it.

### Options and state

| Option | Meaning |
| --- | --- |
| `query` | The query, controlled. The hook runs it again when it changes by content, compared deeply, so a new object with the same content does not run it. Set back to `undefined`, as in `id ? singleQuery(…) : undefined`, it aborts the request in flight and goes `idle` with the last result kept, like `abort()`; nothing runs until a query is given again. |
| `initialQuery` | The first query, uncontrolled; replace it with `setQuery()`. With neither `query` nor `initialQuery`, nothing runs until `setQuery()`. |
| `autoExecute` | `true` by default: the query runs on mount and whenever it changes. With `false` only `execute()` runs it. |
| `attributes` | Handed to `execute`, or to the Fetcher's interceptors in the Fetcher variants, on every run. Changing it does not run the query again. |
| `execute` | A [`QueryExecutor`](#api-QueryExecutor): `(query, attributes, abortController)`, resolving to the result. The Fetcher variants take `url` and `fetcher` instead. |
| `url`, `fetcher` | Fetcher variants only: the endpoint, resolved against the Fetcher's `baseURL`, and the Fetcher or the name of a registered one, the default Fetcher when omitted. A change of either runs the query again. A Fetcher is compared by its name, or by its `baseURL` when it has none, so one created inline in render does not run it on every render. A name that is not registered fails the request, and `error` says so. |
| `onSuccess`, `onError` | Called with the result of each run that succeeds and the error of each run that fails. |

| Returned | Meaning |
| --- | --- |
| `status` | A [`QueryStatus`](#api-QueryStatus): `'idle'`, `'loading'`, `'success'` or `'error'`. It is a plain string union, so props, tests and stories can write a literal. A hook that runs its query on mount renders its first frame as `loading`, on the server too, so the server's markup and the first client frame agree. |
| `loading` | The same as `status === 'loading'`. |
| `result` | The latest successful result, or `undefined`. A failed run, `abort()` and a new run keep it until a new result arrives; only `reset()` clears it. The list-stream hooks return `items` and `done` instead. |
| `error` | Why the latest run failed. `E` defaults to `Error`: a failed request rejects with a `FetcherError`, an error event in a stream with a `WowError`, and a custom `execute` with whatever it throws. Pass `E` to narrow it, as in `useSingleQuery<Order, OrderFields, FetcherError>`, or test it with `instanceof`. |
| `execute()` | Runs the current query again and aborts the request in flight. |
| `abort()` | Aborts the request in flight and returns to `idle`, keeping `result` (or the rows received); a late answer to that request is dropped. |
| `reset()` | Aborts the request in flight, returns to `idle`, and clears `result` (or `items`) and `error`; a late answer to that request is dropped, and neither callback runs for it. |
| `getQuery()`, `setQuery(query)` | Read and replace the current query; with `autoExecute`, `setQuery` runs it. |

### List streams

`useListStreamQuery` and `useFetcherListStreamQuery` own the stream: they read it, collect the `data` of each event into `items`, and cancel it when a newer query starts, on `abort()` or `reset()`, and on unmount. Components render `items` and never hold a reader, so the hooks are safe under StrictMode; they render the rows at most about once a frame (every 16 ms), however many rows or chunks arrive. They return no `result`; instead:

| Field | Meaning |
| --- | --- |
| `items` | The rows of the current query received so far, in order. A new query starts from an empty list, `reset()` empties it, and `abort()` or an error keeps the rows received before it. |
| `done` | The stream ended normally and `items` holds every row; the same as `status === 'success'`. |
| `loading` | From the request until the stream ends, fails, or is aborted. |
| `error` | A `FetcherError` when the request failed, or a `WowError` with its `errorCode` when the server sent an error event in the stream. `E` defaults to `Error`; test for `WowError` with `instanceof`. |
| `status`, `execute`, `abort`, `reset`, `getQuery`, `setQuery` | As for the other hooks; `execute()` runs the current query again and aborts the stream in flight, and `reset()` stops it and empties `items`. |

`onSuccess` receives every row once the stream has ended. The `execute` option of `useListStreamQuery` is a [`ListStreamExecutor`](#api-ListStreamExecutor): `(query, attributes, abortController)` resolving to a `ReadableStream<R>`, such as a query client's `listStateStream`. `useFetcherListStreamQuery` takes `url` and an optional `fetcher` instead; it has no `execute` or `resultExtractor` option.

### With a query client

A query client method, from wow-client's `SnapshotQueryClient` or from a generated `…QueryClientFactory` (`createSnapshotQueryClient(...)`), already has the `(query, attributes, abort)` shape `execute` takes, and the clients bind their methods. Pass it as is, `execute: client.pagedState`, so no URL is hand-written and an abort cancels the request. A method of an object of your own loses its `this` when handed over; pass `method.bind(object)` or an arrow function. There are no per-client hooks for this reason: a hook for every client method would multiply the public API without adding anything the `execute` option does not already give.

Use the `useFetcher*Query` hooks when you only have an endpoint URL. Snapshot endpoints take the form `order/snapshot/paged/state` (not `snapshot_state/paged`), and filters name state fields as `state.status` (not `status`), because snapshot queries filter on the snapshot document, whose aggregate state lives under `state`.

### Aggregations

There is no aggregation hook yet: a generic `useQuery` and `useAggregateQuery` / `useAggregateStreamQuery` are planned for 9.3. Until then, call the client's `aggregate` (or `aggregateStream`) in an effect and abort it when the arguments change or the component unmounts:

```tsx
import { useEffect, useState } from 'react';
import {
  aggregation,
  filter,
  toWowError,
  type SnapshotQueryClient,
} from '@ahoo-wang/wow-client';

interface OrderState {
  productId: string;
  quantity: number;
  status: string;
}
interface ProductTotal {
  product: string;
  quantity: number;
}

export function SoldByProduct({
  client,
  status,
}: {
  client: SnapshotQueryClient<OrderState>;
  status: string;
}) {
  const [rows, setRows] = useState<ProductTotal[]>();
  const [error, setError] = useState<string>();
  useEffect(() => {
    // A new status or client aborts the request of the old one, and so does
    // an unmount, so a late answer never lands.
    const controller = new AbortController();
    setError(undefined);
    client
      .aggregate<ProductTotal>(
        aggregation.query({
          filter: filter.eq('state.status', status),
          groupBy: [aggregation.terms('state.productId', 'product')],
          metrics: [
            aggregation.sum(aggregation.field('state.quantity'), 'quantity'),
          ],
          limit: 20,
        }),
        undefined,
        controller,
      )
      .then(setRows, async (thrown: unknown) => {
        if (controller.signal.aborted) return;
        const wowError = await toWowError(thrown);
        setError(wowError?.message ?? String(thrown));
      });
    return () => controller.abort();
  }, [client, status]);

  if (error) return <p role="alert">{error}</p>;
  if (!rows) return <p>Loading…</p>;
  return (
    <ul>
      {rows.map(row => (
        <li key={row.product}>
          {row.product}: {row.quantity}
        </li>
      ))}
    </ul>
  );
}
```

The effect's cleanup is what the hooks do for you: it aborts the request of the previous arguments, so an answer for an old `status` cannot overwrite the new one.

## Install

React 19.3 or later is required (peer `react ^19.3.0`). The package is built with the React Compiler and its bundle imports `react/compiler-runtime`, which only React 19 has; React 18 is not supported.

```sh
pnpm add react react-dom @ahoo-wang/fetcher @ahoo-wang/fetcher-eventstream \
  @ahoo-wang/wow-client @ahoo-wang/wow-react
```

The package runs its own request state machine and does not depend on `@ahoo-wang/fetcher-react`; its peers are `react`, `@ahoo-wang/fetcher`, `@ahoo-wang/fetcher-eventstream` and `@ahoo-wang/wow-client`, and it depends on `dequal`. `@ahoo-wang/wow-client` must be on the same minor version as `@ahoo-wang/wow-react`. The package declares Node >=22.12.0. These hooks were the Wow hooks of `@ahoo-wang/fetcher-react`; see the [migration guide](../../../guide/typescript/migration.md).

## Complete example

```tsx
import { usePagedQuery } from '@ahoo-wang/wow-react';
import {
  SnapshotQueryClient,
  pagedQuery,
  filter,
} from '@ahoo-wang/wow-client';
interface User {
  id: string;
  name: string;
}
const client = new SnapshotQueryClient<User>({ basePath: '/users' });
export function Users() {
  const query = usePagedQuery<User>({
    initialQuery: pagedQuery({ filter: filter.matchAll() }),
    execute: (request, attributes, controller) =>
      client.pagedState(request, attributes, controller),
  });
  return (
    <section>
      {query.error && <p role="alert">{query.error.message}</p>}
      <p>{query.result?.total ?? 0}</p>
      {query.result?.list.map(user => (
        <p key={user.id}>{user.name}</p>
      ))}
    </section>
  );
}
```

A list stream renders its rows as they arrive:

```tsx
import { useListStreamQuery } from '@ahoo-wang/wow-react';
import {
  filter,
  listQuery,
  type SnapshotQueryClient,
} from '@ahoo-wang/wow-client';
interface OrderState {
  id: string;
  status: string;
}
// The fields a query may name; a generated client exports this type.
type OrderFields = 'aggregateId' | 'state.status';
export function PaidOrders({
  client,
}: {
  client: SnapshotQueryClient<OrderState, OrderFields>;
}) {
  const { items, done, loading, error, abort } = useListStreamQuery<
    OrderState,
    OrderFields
  >({
    // Without a limit, Wow 8.11 to 9.1.3 answer 400; 9.1.5 applies its default.
    initialQuery: listQuery({
      filter: filter.eq('state.status', 'PAID'),
      limit: 100,
    }),
    execute: (query, attributes, abortController) =>
      client.listStateStream(query, attributes, abortController),
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

With only an endpoint URL, `useFetcherListStreamQuery<OrderState>({ url: 'order/snapshot/list/state', initialQuery })` returns the same fields.

Service URLs in examples require application endpoints; type checking does not imply an external service was contacted.

## Public signatures and types

These signatures follow declarations reachable from the current root entry. `?` marks optional input; generics/interfaces only constrain compile-time types. The shared types come first, and every hook's options and return types extend them; query types come from the [wow-client symbol index](../wow-client/symbols), and `Condition`, `SingleQuery`, `ListQuery`, `PagedQuery`, and the `…QueryRequest` unions from `@ahoo-wang/wow-client/legacy`. Runtime defaults and failure behavior are described above.

### QueryStatus {#api-QueryStatus}

```ts
export type QueryStatus = 'idle' | 'loading' | 'success' | 'error';
```

[typescript/wow-react/src/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/types.ts)

### QueryExecutor {#api-QueryExecutor}

```ts
export type QueryExecutor<Q, R> = (
  query: Q,
  attributes: Record<string, unknown> | undefined,
  abortController: AbortController,
) => Promise<R>;
```

[typescript/wow-react/src/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/types.ts)

### ListStreamExecutor {#api-ListStreamExecutor}

```ts
export type ListStreamExecutor<R, Q> = QueryExecutor<Q, ReadableStream<R>>;
```

[typescript/wow-react/src/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/types.ts)

### QueryHookOptions {#api-QueryHookOptions}

```ts
export interface QueryHookOptions<Q, R, E = Error> {
  /**
   * The query, controlled: the hook runs it again whenever it changes by
   * content, so a new object with the same content does not. Set to
   * `undefined` after a query (`id ? singleQuery(…) : undefined`), it aborts
   * the request in flight and goes `idle`, keeping the last result, as
   * `abort()` does; nothing runs until a query is given again.
   */
  query?: Q;
  /** The first query, uncontrolled: change it later with `setQuery()`. */
  initialQuery?: Q;
  /**
   * Whether the query runs on mount and whenever it changes; `true` by
   * default. With `false` it runs only through `execute()`.
   */
  autoExecute?: boolean;
  /** Handed to `execute` with every run; not part of what identifies a run. */
  attributes?: Record<string, unknown>;
  /** Runs one query. */
  execute: QueryExecutor<Q, R>;
  /** Called with the result of each run that succeeds. */
  onSuccess?: (result: R) => void | Promise<void>;
  /** Called with the error of each run that fails. */
  onError?: (error: E) => void | Promise<void>;
}
```

[typescript/wow-react/src/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/types.ts)

### QueryHookReturn {#api-QueryHookReturn}

```ts
export interface QueryHookReturn<Q, R, E = Error> {
  /** Where the hook stands; see {@link QueryStatus}. */
  status: QueryStatus;
  /** Whether a request is in flight: the same as `status === 'loading'`. */
  loading: boolean;
  /**
   * The result of the latest successful run, or `undefined`. A failed run,
   * `abort()` and a new run keep it until a new result arrives; only
   * `reset()` clears it.
   */
  result: R | undefined;
  /** Why the latest run failed, or `undefined`. */
  error: E | undefined;
  /** Runs the current query again, aborting the request in flight. */
  execute: () => Promise<void>;
  /**
   * Aborts the request in flight and returns to `idle`, keeping `result`;
   * a late answer to that request is dropped.
   */
  abort: () => void;
  /**
   * Aborts the request in flight, returns to `idle`, and clears `result` and
   * `error`; a late answer to that request is dropped.
   */
  reset: () => void;
  /** The current query. */
  getQuery: () => Q | undefined;
  /** Replaces the query; it runs when `autoExecute` is on. */
  setQuery: (query: Q) => void;
}
```

[typescript/wow-react/src/types.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/types.ts)

### useFetcherCountQuery {#api-useFetcherCountQuery}

```ts
export function useFetcherCountQuery<FIELDS extends string = string, E = Error>(
  options: UseFetcherCountQueryOptions<FIELDS, E, FilterExpression<FIELDS>>,
): UseFetcherCountQueryReturn<FIELDS, E, FilterExpression<FIELDS>>;
```

[typescript/wow-react/src/hooks/useFetcherCountQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherCountQuery.ts)

```ts
export function useFetcherCountQuery<FIELDS extends string = string, E = Error>(
  options: UseFetcherCountQueryOptions<FIELDS, E, Condition<FIELDS>>,
): UseFetcherCountQueryReturn<FIELDS, E, Condition<FIELDS>>;
```

[typescript/wow-react/src/hooks/useFetcherCountQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherCountQuery.ts)

```ts
export function useFetcherCountQuery<
  FIELDS extends string = string,
  E = Error,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> =
    FilterExpression<FIELDS>,
>(
  options: UseFetcherCountQueryOptions<FIELDS, E, Q>,
): UseFetcherCountQueryReturn<FIELDS, E, Q>;
```

[typescript/wow-react/src/hooks/useFetcherCountQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherCountQuery.ts)

### UseFetcherCountQueryOptions {#api-UseFetcherCountQueryOptions}

```ts
export interface UseFetcherCountQueryOptions<
  FIELDS extends string = string,
  E = Error,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> =
    FilterExpression<FIELDS>,
> extends Omit<QueryHookOptions<Q, number, E>, 'execute'> {
  /**
   * The query endpoint, resolved against the Fetcher's `baseURL`: for example
   * `order/snapshot/count` for the snapshots of an `order` aggregate.
   */
  url: string;
  /**
   * The Fetcher that sends the request, or the name of a registered one; the
   * default Fetcher when omitted.
   */
  fetcher?: string | Fetcher;
}
```

[typescript/wow-react/src/hooks/useFetcherCountQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherCountQuery.ts)

### UseFetcherCountQueryReturn {#api-UseFetcherCountQueryReturn}

```ts
export interface UseFetcherCountQueryReturn<
  FIELDS extends string = string,
  E = Error,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> =
    FilterExpression<FIELDS>,
> extends QueryHookReturn<Q, number, E> {}
```

[typescript/wow-react/src/hooks/useFetcherCountQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherCountQuery.ts)

### useFetcherListQuery {#api-useFetcherListQuery}

```ts
export function useFetcherListQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherListQueryOptions<R, FIELDS, E, FilterListQuery<FIELDS>>,
): UseFetcherListQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
```

[typescript/wow-react/src/hooks/useFetcherListQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherListQuery.ts)

```ts
export function useFetcherListQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherListQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseFetcherListQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
```

[typescript/wow-react/src/hooks/useFetcherListQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherListQuery.ts)

```ts
export function useFetcherListQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
>(
  options: UseFetcherListQueryOptions<R, FIELDS, E, Q>,
): UseFetcherListQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/hooks/useFetcherListQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherListQuery.ts)

### UseFetcherListQueryOptions {#api-UseFetcherListQueryOptions}

```ts
export interface UseFetcherListQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends Omit<QueryHookOptions<Q, R[], E>, 'execute'> {
  /**
   * The query endpoint, resolved against the Fetcher's `baseURL`: for example
   * `order/snapshot/list/state` for the states of an `order` aggregate.
   */
  url: string;
  /**
   * The Fetcher that sends the request, or the name of a registered one; the
   * default Fetcher when omitted.
   */
  fetcher?: string | Fetcher;
}
```

[typescript/wow-react/src/hooks/useFetcherListQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherListQuery.ts)

### UseFetcherListQueryReturn {#api-UseFetcherListQueryReturn}

```ts
export interface UseFetcherListQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends QueryHookReturn<Q, R[], E> {}
```

[typescript/wow-react/src/hooks/useFetcherListQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherListQuery.ts)

### useFetcherListStreamQuery {#api-useFetcherListStreamQuery}

```ts
export function useFetcherListStreamQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherListStreamQueryOptions<
    R,
    FIELDS,
    E,
    FilterListQuery<FIELDS>
  >,
): UseFetcherListStreamQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
```

[typescript/wow-react/src/hooks/useFetcherListStreamQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherListStreamQuery.ts)

```ts
export function useFetcherListStreamQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherListStreamQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseFetcherListStreamQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
```

[typescript/wow-react/src/hooks/useFetcherListStreamQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherListStreamQuery.ts)

```ts
export function useFetcherListStreamQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
>(
  options: UseFetcherListStreamQueryOptions<R, FIELDS, E, Q>,
): UseFetcherListStreamQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/hooks/useFetcherListStreamQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherListStreamQuery.ts)

### UseFetcherListStreamQueryOptions {#api-UseFetcherListStreamQueryOptions}

```ts
export interface UseFetcherListStreamQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends Omit<UseListStreamQueryOptions<R, FIELDS, E, Q>, 'execute'> {
  /**
   * The list endpoint, resolved against the Fetcher's `baseURL`: for example
   * `order/snapshot/list/state` for the states of an `order` aggregate.
   */
  url: string;
  /**
   * The Fetcher that sends the request, or the name of a registered one; the
   * default Fetcher when omitted.
   */
  fetcher?: string | Fetcher;
}
```

[typescript/wow-react/src/hooks/useFetcherListStreamQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherListStreamQuery.ts)

### UseFetcherListStreamQueryReturn {#api-UseFetcherListStreamQueryReturn}

```ts
export interface UseFetcherListStreamQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends UseListStreamQueryReturn<R, FIELDS, E, Q> {}
```

[typescript/wow-react/src/hooks/useFetcherListStreamQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherListStreamQuery.ts)

### useFetcherPagedQuery {#api-useFetcherPagedQuery}

```ts
export function useFetcherPagedQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherPagedQueryOptions<R, FIELDS, E, FilterPagedQuery<FIELDS>>,
): UseFetcherPagedQueryReturn<R, FIELDS, E, FilterPagedQuery<FIELDS>>;
```

[typescript/wow-react/src/hooks/useFetcherPagedQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherPagedQuery.ts)

```ts
export function useFetcherPagedQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherPagedQueryOptions<R, FIELDS, E, PagedQuery<FIELDS>>,
): UseFetcherPagedQueryReturn<R, FIELDS, E, PagedQuery<FIELDS>>;
```

[typescript/wow-react/src/hooks/useFetcherPagedQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherPagedQuery.ts)

```ts
export function useFetcherPagedQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
>(
  options: UseFetcherPagedQueryOptions<R, FIELDS, E, Q>,
): UseFetcherPagedQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/hooks/useFetcherPagedQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherPagedQuery.ts)

### UseFetcherPagedQueryOptions {#api-UseFetcherPagedQueryOptions}

```ts
export interface UseFetcherPagedQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
> extends Omit<QueryHookOptions<Q, PagedList<R>, E>, 'execute'> {
  /**
   * The query endpoint, resolved against the Fetcher's `baseURL`: for example
   * `order/snapshot/paged/state` for the states of an `order` aggregate.
   */
  url: string;
  /**
   * The Fetcher that sends the request, or the name of a registered one; the
   * default Fetcher when omitted.
   */
  fetcher?: string | Fetcher;
}
```

[typescript/wow-react/src/hooks/useFetcherPagedQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherPagedQuery.ts)

### UseFetcherPagedQueryReturn {#api-UseFetcherPagedQueryReturn}

```ts
export interface UseFetcherPagedQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
> extends QueryHookReturn<Q, PagedList<R>, E> {}
```

[typescript/wow-react/src/hooks/useFetcherPagedQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherPagedQuery.ts)

### useFetcherSingleQuery {#api-useFetcherSingleQuery}

```ts
export function useFetcherSingleQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherSingleQueryOptions<
    R,
    FIELDS,
    E,
    FilterSingleQuery<FIELDS>
  >,
): UseFetcherSingleQueryReturn<R, FIELDS, E, FilterSingleQuery<FIELDS>>;
```

[typescript/wow-react/src/hooks/useFetcherSingleQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherSingleQuery.ts)

```ts
export function useFetcherSingleQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherSingleQueryOptions<R, FIELDS, E, SingleQuery<FIELDS>>,
): UseFetcherSingleQueryReturn<R, FIELDS, E, SingleQuery<FIELDS>>;
```

[typescript/wow-react/src/hooks/useFetcherSingleQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherSingleQuery.ts)

```ts
export function useFetcherSingleQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
>(
  options: UseFetcherSingleQueryOptions<R, FIELDS, E, Q>,
): UseFetcherSingleQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/hooks/useFetcherSingleQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherSingleQuery.ts)

### UseFetcherSingleQueryOptions {#api-UseFetcherSingleQueryOptions}

```ts
export interface UseFetcherSingleQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
> extends Omit<QueryHookOptions<Q, R, E>, 'execute'> {
  /**
   * The query endpoint, resolved against the Fetcher's `baseURL`: for example
   * `order/snapshot/single/state` for the states of an `order` aggregate.
   */
  url: string;
  /**
   * The Fetcher that sends the request, or the name of a registered one; the
   * default Fetcher when omitted.
   */
  fetcher?: string | Fetcher;
}
```

[typescript/wow-react/src/hooks/useFetcherSingleQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherSingleQuery.ts)

### UseFetcherSingleQueryReturn {#api-UseFetcherSingleQueryReturn}

```ts
export interface UseFetcherSingleQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
> extends QueryHookReturn<Q, R, E> {}
```

[typescript/wow-react/src/hooks/useFetcherSingleQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useFetcherSingleQuery.ts)

### useCountQuery {#api-useCountQuery}

```ts
export function useCountQuery<FIELDS extends string = string, E = Error>(
  options: UseCountQueryOptions<FIELDS, E, FilterExpression<FIELDS>>,
): UseCountQueryReturn<FIELDS, E, FilterExpression<FIELDS>>;
```

[typescript/wow-react/src/hooks/useCountQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useCountQuery.ts)

```ts
export function useCountQuery<FIELDS extends string = string, E = Error>(
  options: UseCountQueryOptions<FIELDS, E, Condition<FIELDS>>,
): UseCountQueryReturn<FIELDS, E, Condition<FIELDS>>;
```

[typescript/wow-react/src/hooks/useCountQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useCountQuery.ts)

```ts
export function useCountQuery<
  FIELDS extends string = string,
  E = Error,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> =
    FilterExpression<FIELDS>,
>(
  options: UseCountQueryOptions<FIELDS, E, Q>,
): UseCountQueryReturn<FIELDS, E, Q>;
```

[typescript/wow-react/src/hooks/useCountQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useCountQuery.ts)

### UseCountQueryOptions {#api-UseCountQueryOptions}

```ts
export interface UseCountQueryOptions<
  FIELDS extends string = string,
  E = Error,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> =
    FilterExpression<FIELDS>,
> extends QueryHookOptions<Q, number, E> {}
```

[typescript/wow-react/src/hooks/useCountQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useCountQuery.ts)

### UseCountQueryReturn {#api-UseCountQueryReturn}

```ts
export interface UseCountQueryReturn<
  FIELDS extends string = string,
  E = Error,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> =
    FilterExpression<FIELDS>,
> extends QueryHookReturn<Q, number, E> {}
```

[typescript/wow-react/src/hooks/useCountQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useCountQuery.ts)

### useListQuery {#api-useListQuery}

```ts
export function useListQuery<R, FIELDS extends string = string, E = Error>(
  options: UseListQueryOptions<R, FIELDS, E, FilterListQuery<FIELDS>>,
): UseListQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
```

[typescript/wow-react/src/hooks/useListQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useListQuery.ts)

```ts
export function useListQuery<R, FIELDS extends string = string, E = Error>(
  options: UseListQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseListQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
```

[typescript/wow-react/src/hooks/useListQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useListQuery.ts)

```ts
export function useListQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
>(
  options: UseListQueryOptions<R, FIELDS, E, Q>,
): UseListQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/hooks/useListQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useListQuery.ts)

### UseListQueryOptions {#api-UseListQueryOptions}

```ts
export interface UseListQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends QueryHookOptions<Q, R[], E> {}
```

[typescript/wow-react/src/hooks/useListQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useListQuery.ts)

### UseListQueryReturn {#api-UseListQueryReturn}

```ts
export interface UseListQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends QueryHookReturn<Q, R[], E> {}
```

[typescript/wow-react/src/hooks/useListQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useListQuery.ts)

### useListStreamQuery {#api-useListStreamQuery}

```ts
export function useListStreamQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseListStreamQueryOptions<R, FIELDS, E, FilterListQuery<FIELDS>>,
): UseListStreamQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
```

[typescript/wow-react/src/hooks/useListStreamQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useListStreamQuery.ts)

```ts
export function useListStreamQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseListStreamQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseListStreamQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
```

[typescript/wow-react/src/hooks/useListStreamQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useListStreamQuery.ts)

```ts
export function useListStreamQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
>(
  options: UseListStreamQueryOptions<R, FIELDS, E, Q>,
): UseListStreamQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/hooks/useListStreamQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useListStreamQuery.ts)

### UseListStreamQueryOptions {#api-UseListStreamQueryOptions}

```ts
export interface UseListStreamQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends Omit<QueryHookOptions<Q, R[], E>, 'execute'> {
  /** Opens the stream for a query. */
  execute: ListStreamExecutor<R, Q>;
}
```

[typescript/wow-react/src/hooks/useListStreamQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useListStreamQuery.ts)

### UseListStreamQueryReturn {#api-UseListStreamQueryReturn}

```ts
export interface UseListStreamQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends Omit<QueryHookReturn<Q, R[], E>, 'result'> {
  /** The rows of the current query received so far. */
  items: R[];
  /** Whether the stream ended normally, so `items` holds every row. */
  done: boolean;
}
```

[typescript/wow-react/src/hooks/useListStreamQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useListStreamQuery.ts)

### usePagedQuery {#api-usePagedQuery}

```ts
export function usePagedQuery<R, FIELDS extends string = string, E = Error>(
  options: UsePagedQueryOptions<R, FIELDS, E, FilterPagedQuery<FIELDS>>,
): UsePagedQueryReturn<R, FIELDS, E, FilterPagedQuery<FIELDS>>;
```

[typescript/wow-react/src/hooks/usePagedQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/usePagedQuery.ts)

```ts
export function usePagedQuery<R, FIELDS extends string = string, E = Error>(
  options: UsePagedQueryOptions<R, FIELDS, E, PagedQuery<FIELDS>>,
): UsePagedQueryReturn<R, FIELDS, E, PagedQuery<FIELDS>>;
```

[typescript/wow-react/src/hooks/usePagedQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/usePagedQuery.ts)

```ts
export function usePagedQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
>(
  options: UsePagedQueryOptions<R, FIELDS, E, Q>,
): UsePagedQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/hooks/usePagedQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/usePagedQuery.ts)

### UsePagedQueryOptions {#api-UsePagedQueryOptions}

```ts
export interface UsePagedQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
> extends QueryHookOptions<Q, PagedList<R>, E> {}
```

[typescript/wow-react/src/hooks/usePagedQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/usePagedQuery.ts)

### UsePagedQueryReturn {#api-UsePagedQueryReturn}

```ts
export interface UsePagedQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
> extends QueryHookReturn<Q, PagedList<R>, E> {}
```

[typescript/wow-react/src/hooks/usePagedQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/usePagedQuery.ts)

### useSingleQuery {#api-useSingleQuery}

```ts
export function useSingleQuery<R, FIELDS extends string = string, E = Error>(
  options: UseSingleQueryOptions<R, FIELDS, E, FilterSingleQuery<FIELDS>>,
): UseSingleQueryReturn<R, FIELDS, E, FilterSingleQuery<FIELDS>>;
```

[typescript/wow-react/src/hooks/useSingleQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useSingleQuery.ts)

```ts
export function useSingleQuery<R, FIELDS extends string = string, E = Error>(
  options: UseSingleQueryOptions<R, FIELDS, E, SingleQuery<FIELDS>>,
): UseSingleQueryReturn<R, FIELDS, E, SingleQuery<FIELDS>>;
```

[typescript/wow-react/src/hooks/useSingleQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useSingleQuery.ts)

```ts
export function useSingleQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
>(
  options: UseSingleQueryOptions<R, FIELDS, E, Q>,
): UseSingleQueryReturn<R, FIELDS, E, Q>;
```

[typescript/wow-react/src/hooks/useSingleQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useSingleQuery.ts)

### UseSingleQueryOptions {#api-UseSingleQueryOptions}

```ts
export interface UseSingleQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
> extends QueryHookOptions<Q, R, E> {}
```

[typescript/wow-react/src/hooks/useSingleQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useSingleQuery.ts)

### UseSingleQueryReturn {#api-UseSingleQueryReturn}

```ts
export interface UseSingleQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
> extends QueryHookReturn<Q, R, E> {}
```

[typescript/wow-react/src/hooks/useSingleQuery.ts](https://github.com/Ahoo-Wang/Wow/blob/main/typescript/wow-react/src/hooks/useSingleQuery.ts)

## Related topics

[Snapshot queries](../wow-client/snapshot-queries) · [Filter expressions](../wow-client/filters) · [Projection, sorting and pagination](../wow-client/query-options) · [Storybook: Wow query hooks](/storybook/?path=/docs/react-hooks-wow-queries--docs)
