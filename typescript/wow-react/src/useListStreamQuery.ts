/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import type { FilterListQuery } from '@ahoo-wang/wow-client';
// compat(wow<9): the hook also takes the Condition-based queries of `@ahoo-wang/wow-client/legacy`, which Wow < 8.11 needs; drop that overload in v10.
import type { ListQuery, ListQueryRequest } from '@ahoo-wang/wow-client/legacy';
import { useState } from 'react';
import { useDelegatedQuery } from './internal/fetcherReact.js';
import { readStreamRows } from './readStreamRows.js';
import type {
  ListStreamExecutor,
  QueryHookOptions,
  QueryHookReturn,
} from './types.js';

/**
 * Options of {@link useListStreamQuery}: those of every query hook, with an
 * `execute` that opens the stream.
 *
 * `onSuccess` receives every row once the stream has ended.
 *
 * @template R - One row of the stream: the `data` of each event
 * @template FIELDS - The field names the query may use
 * @template E - The error type, `Error` by default: a failed request
 *   rejects with a `FetcherError`, an error event in the stream with a
 *   `WowError`
 * @template Q - The query type: `FilterListQuery` by default
 */
export interface UseListStreamQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends Omit<QueryHookOptions<Q, R[], E>, 'execute'> {
  /** Opens the stream for a query. */
  execute: ListStreamExecutor<R, Q>;
}

/**
 * What {@link useListStreamQuery} and `useFetcherListStreamQuery` return: the
 * rows as they arrive, and whether the stream has ended.
 *
 * - `items` — the rows of the current query received so far, in order. A new
 *   query starts from an empty list; `reset()` empties it; `abort()` and an
 *   error keep the rows received before them.
 * - `done` — the stream ended normally and `items` holds every row.
 * - `loading` — from the request until the stream ends, fails or is aborted.
 * - `error` — the request failed (`FetcherError`), or the server sent an error
 *   event in the stream (`WowError`, with its `errorCode`).
 * - `status` — `idle`, `loading`, `success` (the same as `done`) or `error`.
 * - `execute()` runs the current query again and aborts the stream in flight;
 *   `abort()` stops the stream; `reset()` stops it and empties `items`.
 */
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

/**
 * Streams the rows of a list query as server-sent events and keeps them as
 * state.
 *
 * The hook owns the stream: it reads it, collects the rows into `items`, and
 * cancels it when a newer query starts, on `abort()` or `reset()`, and on
 * unmount. Components render `items` and never hold a reader, so the hook is
 * safe under StrictMode. `autoExecute` defaults to `true`.
 *
 * Pass a query client method as `execute` — it sends
 * `Accept: text/event-stream` and turns error events into a `WowError` — or
 * use `useFetcherListStreamQuery` with a URL.
 *
 * @template R - One row of the stream: the `data` of each event
 * @template FIELDS - The field names the query may use
 * @template E - The error type
 *
 * @example
 * ```tsx
 * import { filter, listQuery, type SnapshotQueryClient } from '@ahoo-wang/wow-client';
 * import { useListStreamQuery } from '@ahoo-wang/wow-react';
 *
 * function PaidOrders({ client }: { client: SnapshotQueryClient<OrderState> }) {
 *   const { items, done, loading, error, abort } = useListStreamQuery<OrderState>({
 *     initialQuery: listQuery({ filter: filter.eq('state.status', 'PAID') }),
 *     execute: (query, attributes, abortController) =>
 *       client.listStateStream(query, attributes, abortController),
 *   });
 *   if (error) return <p role="alert">{error.message}</p>;
 *   return (
 *     <>
 *       <ul>{items.map(order => <li key={order.id}>{order.id}</li>)}</ul>
 *       {loading && <button onClick={abort}>Stop</button>}
 *       {done && <p>{items.length} orders</p>}
 *     </>
 *   );
 * }
 * ```
 */
export function useListStreamQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseListStreamQueryOptions<R, FIELDS, E, FilterListQuery<FIELDS>>,
): UseListStreamQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
export function useListStreamQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseListStreamQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseListStreamQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
export function useListStreamQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
>(
  options: UseListStreamQueryOptions<R, FIELDS, E, Q>,
): UseListStreamQueryReturn<R, FIELDS, E, Q>;
export function useListStreamQuery<
  R,
  FIELDS extends string,
  E,
  Q extends ListQueryRequest<FIELDS>,
>(
  options: UseListStreamQueryOptions<R, FIELDS, E, Q>,
): UseListStreamQueryReturn<R, FIELDS, E, Q> {
  const [items, setItems] = useState<R[]>(() => []);
  const openStream = options.execute;
  const { loading, error, status, execute, abort, getQuery, setQuery } =
    useDelegatedQuery<Q, R[], E>({
      ...options,
      // useExecutePromise calls this only for the latest query while the
      // component is mounted, and aborts `abortController` once a newer query
      // starts or the component unmounts; readStreamRows stops publishing
      // from then on, so a stale stream never overwrites newer rows.
      execute: async (query, attributes, abortController) => {
        const controller = abortController ?? new AbortController();
        setItems([]);
        const stream = await openStream(query, attributes, controller);
        return readStreamRows(stream, controller.signal, setItems);
      },
    });
  const reset = () => {
    abort();
    setItems([]);
  };
  return {
    items,
    done: status === 'success',
    loading,
    error,
    status,
    execute,
    abort,
    reset,
    getQuery,
    setQuery,
  };
}
