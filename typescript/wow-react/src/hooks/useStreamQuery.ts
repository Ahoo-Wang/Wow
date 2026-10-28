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

import type { StreamOptions } from '@ahoo-wang/wow-client';
import type { FilterListQuery } from '@ahoo-wang/wow-client/dsl';
import { useCallback, useState } from 'react';
import { readStreamRows } from '../internal/readStreamRows.js';
import { useQueryRunner } from '../internal/useQueryRunner.js';
import type {
  QueryHookOptions,
  QueryHookReturn,
  StreamExecutor,
} from '../types.js';

/**
 * Options of {@link useStreamQuery}: those of every query hook, with an
 * `execute` that opens the stream and `requestOptions` that take the
 * stream's `resume`.
 *
 * `onSuccess` receives every record once the stream has ended.
 *
 * @template R - One record of the stream
 * @template FIELDS - The field names the query may use
 * @template Q - The query type: `FilterListQuery` by default
 */
export interface UseStreamQueryOptions<
  R,
  FIELDS extends string = string,
  Q extends FilterListQuery<FIELDS> = FilterListQuery<FIELDS>,
> extends Omit<QueryHookOptions<Q, R[], StreamOptions>, 'execute'> {
  /**
   * Opens the stream for a query: a query client's `streamRows`, which
   * answers each record with its position.
   */
  execute: StreamExecutor<R, Q>;
}

/**
 * What {@link useStreamQuery} returns: the records as they arrive, whether
 * the stream has ended, and where it stopped.
 *
 * - `items` — the records of the current query received so far, in order,
 *   updated at most about once a frame (every 16 ms) while they arrive. A new
 *   query starts from an empty list; `reset()` empties it; `abort()` and an
 *   error keep the records received before them.
 * - `position` — the position of the last record in `items` that had one.
 * - `done` — the stream ended normally and `items` holds every record.
 * - `loading` — from the request until the stream ends, fails or is aborted.
 * - `error` — the request failed, or the server sent an error event in the
 *   stream; `problem` is then the `WowProblemError`, with the problem's
 *   `code`.
 * - `status` — `idle`, `loading`, `success` (the same as `done`) or `error`.
 * - `execute()` runs the current query again and aborts the stream in flight;
 *   `abort()` stops the stream; `reset()` stops it and empties `items`.
 */
export interface UseStreamQueryReturn<
  R,
  FIELDS extends string = string,
  Q extends FilterListQuery<FIELDS> = FilterListQuery<FIELDS>,
> extends Omit<QueryHookReturn<Q, R[]>, 'result'> {
  /**
   * The records of the current query received so far, a new array at every
   * update.
   */
  items: R[];
  /**
   * The position of the last record received that had one, or `undefined`
   * before the first such record: hand it back as
   * `requestOptions.resume.lastEventId` to go on after it, on a later mount.
   * A new query and `reset()` clear it with `items`.
   */
  position: string | undefined;
  /** Whether the stream ended normally, so `items` holds every record. */
  done: boolean;
}

/** The records read so far, and where they stopped. */
interface Rows<R> {
  items: R[];
  position: string | undefined;
}

/**
 * Streams the records of a list query as server-sent events and keeps them
 * as state.
 *
 * The hook owns the stream: it reads it, collects the records into `items`,
 * and cancels it when a newer query starts, on `abort()` or `reset()`, and on
 * unmount. Components render `items` and never hold a reader, so the hook is
 * safe under StrictMode. `autoExecute` defaults to `true`.
 *
 * Pass a query client's `streamRows` as `execute`: it sends
 * `Accept: text/event-stream`, resumes a stream the server ends to be
 * resumed or that is cut off, turns error events into a `WowProblemError`,
 * and answers each record with its position, which the hook keeps as
 * `position`.
 *
 * To go on where a stream stopped after the component remounts (a page
 * change, a reload), keep `position` somewhere that outlives it and hand it
 * back as `requestOptions: { resume: { lastEventId: saved } }`; the first
 * request then asks for the records after it.
 *
 * @template R - One record of the stream
 * @template FIELDS - The field names the query may use. With a client whose
 *   fields are narrower than `string`, as a generated client's are, pass
 *   them here: TypeScript does not infer them from `execute` once `R` is given
 *
 * @example
 * ```tsx
 * import type { SnapshotQueryClient, SnapshotRecord } from '@ahoo-wang/wow-client';
 * import { filter, listQuery } from '@ahoo-wang/wow-client/dsl';
 * import { useStreamQuery } from '@ahoo-wang/wow-react';
 *
 * function PaidOrders({ client }: { client: SnapshotQueryClient<OrderState, OrderFields> }) {
 *   const { items, done, loading, error, abort } = useStreamQuery<SnapshotRecord<OrderState>, OrderFields>({
 *     initialQuery: listQuery({ filter: filter.eq('state.status', 'PAID') }),
 *     execute: (query, options) =>
 *       client.streamRows(query, options),
 *   });
 *   if (error) return <p role="alert">{error.message}</p>;
 *   return (
 *     <>
 *       <ul>{items.map(order => <li key={order.aggregateId}>{order.state.status}</li>)}</ul>
 *       {loading && <button onClick={abort}>Stop</button>}
 *       {done && <p>{items.length} orders</p>}
 *     </>
 *   );
 * }
 * ```
 */
export function useStreamQuery<R, FIELDS extends string = string>(
  options: UseStreamQueryOptions<R, FIELDS, FilterListQuery<FIELDS>>,
): UseStreamQueryReturn<R, FIELDS, FilterListQuery<FIELDS>>;
/**
 * {@link useStreamQuery} typed by a query `Q` of your own, a subtype of
 * `FilterListQuery`, such as one whose literal projection narrows the result.
 */
export function useStreamQuery<
  R,
  FIELDS extends string = string,
  Q extends FilterListQuery<FIELDS> = FilterListQuery<FIELDS>,
>(
  options: UseStreamQueryOptions<R, FIELDS, Q>,
): UseStreamQueryReturn<R, FIELDS, Q>;
export function useStreamQuery<
  R,
  FIELDS extends string,
  Q extends FilterListQuery<FIELDS>,
>(
  options: UseStreamQueryOptions<R, FIELDS, Q>,
): UseStreamQueryReturn<R, FIELDS, Q> {
  // Each run starts from no records; `abort()` and an error keep the records
  // received; `reset()` empties them. The runner aborts the run's signal
  // whenever the run stops counting, and readStreamRows stops publishing
  // then, so records of a stale stream never reach `items`.
  const [rows, setRows] = useState<Rows<R>>(noRows);
  const openStream = options.execute;
  const {
    status,
    loading,
    error,
    problem,
    execute,
    abort,
    reset,
    getQuery,
    setQuery,
  } = useQueryRunner<Q, R[], StreamOptions>(
    {
      ...options,
      execute: async (query, options) => {
        setRows(noRows());
        const stream = await openStream(query, options);
        return readStreamRows(stream, options.signal, (items, position) =>
          setRows({ items, position }),
        );
      },
    },
    false,
  );
  const resetRows = useCallback(() => {
    reset();
    setRows(noRows());
  }, [reset]);
  return {
    items: rows.items,
    position: rows.position,
    done: status === 'success',
    loading,
    error,
    problem,
    status,
    execute,
    abort,
    reset: resetRows,
    getQuery,
    setQuery,
  };
}

function noRows<R>(): Rows<R> {
  return { items: [], position: undefined };
}
