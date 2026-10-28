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

import type { CursorPage, CursorQuery } from '@ahoo-wang/wow-client/dsl';
import { useCallback, useState } from 'react';
import { useQueryRunner } from '../internal/useQueryRunner.js';
import type {
  QueryExecutor,
  QueryHookOptions,
  QueryHookReturn,
} from '../types.js';

/**
 * Options of {@link useCursorQuery}: a cursor query without its `cursor`,
 * which the hook owns, and an `execute` that reads one page, as a query
 * client's `cursor` does.
 *
 * `onSuccess` receives each page as it arrives.
 *
 * @template R - One row of a page
 * @template FIELDS - The field names the query may use
 * @template Q - The query type: `CursorQuery` without `cursor` by default
 */
export interface UseCursorQueryOptions<
  R,
  FIELDS extends string = string,
  Q extends Omit<CursorQuery<FIELDS>, 'cursor'> = Omit<
    CursorQuery<FIELDS>,
    'cursor'
  >,
> extends Omit<QueryHookOptions<Q, CursorPage<R>>, 'execute'> {
  /**
   * Reads one page: the query with the `cursor` of the page, `null` for
   * the first.
   */
  execute: QueryExecutor<Q & { cursor: string | null }, CursorPage<R>>;
}

/**
 * What {@link useCursorQuery} returns: the rows of every page read so far,
 * and how to read the next one.
 *
 * - `items` — the rows of the pages read so far, in order. A new query and
 *   `execute()` start again from the first page with no rows; `reset()`
 *   empties them; `abort()` and an error keep them.
 * - `hasMore` — whether a page follows the last one read.
 * - `fetchNext()` — reads the next page and appends its rows; it does
 *   nothing while a page is loading or when no page follows. A failed
 *   `fetchNext()` keeps the rows, so calling it again retries that page.
 */
export interface UseCursorQueryReturn<
  R,
  FIELDS extends string = string,
  Q extends Omit<CursorQuery<FIELDS>, 'cursor'> = Omit<
    CursorQuery<FIELDS>,
    'cursor'
  >,
> extends Omit<QueryHookReturn<Q, CursorPage<R>>, 'result'> {
  /** The rows of every page read so far, a new array at every page. */
  items: R[];
  /** Whether a page follows the last one read. */
  hasMore: boolean;
  /** Reads the next page and appends its rows. */
  fetchNext: () => Promise<void>;
}

/** The rows read so far, and the cursor of the page after them. */
interface Pages<R> {
  items: R[];
  /** `undefined` before the first page, `null` after the last. */
  nextCursor: string | null | undefined;
}

/**
 * Reads a forward-only cursor query page by page and keeps the rows as
 * state: typically a query client's `cursor`, for a list that grows as the
 * reader scrolls.
 *
 * The hook owns the cursor: every run of the query starts at the first page,
 * and `fetchNext()` hands `execute` the `nextCursor` of the page before.
 * `execute` receives the request options of the run, its `signal` included;
 * hand them on so that a newer query, `abort()` or an unmount cancels the
 * request. The first page is read on mount and whenever `query` or
 * `setQuery()` changes the query; set `autoExecute: false` to read it only
 * through `execute()`.
 *
 * @template R - One row of a page
 * @template FIELDS - The field names the query may use. With a client whose
 *   fields are narrower than `string`, as a generated client's are, pass
 *   them here: TypeScript does not infer them from `execute` once `R` is
 *   given
 *
 * @example
 * ```tsx
 * import type { SnapshotQueryClient, SnapshotRecord } from '@ahoo-wang/wow-client';
 * import { filter } from '@ahoo-wang/wow-client/dsl';
 * import { useCursorQuery } from '@ahoo-wang/wow-react';
 *
 * function PaidOrders({ client }: { client: SnapshotQueryClient<OrderState, OrderFields> }) {
 *   const { items, hasMore, loading, error, fetchNext } = useCursorQuery<SnapshotRecord<OrderState>, OrderFields>({
 *     query: { filter: filter.eq('state.status', 'PAID'), size: 50 },
 *     execute: (query, options) =>
 *       client.cursor(query, options),
 *   });
 *   if (error) return <p role="alert">{error.message}</p>;
 *   return (
 *     <>
 *       <ul>{items.map(order => <li key={order.aggregateId}>{order.state.status}</li>)}</ul>
 *       {hasMore && <button disabled={loading} onClick={() => void fetchNext()}>More</button>}
 *     </>
 *   );
 * }
 * ```
 */
export function useCursorQuery<R, FIELDS extends string = string>(
  options: UseCursorQueryOptions<R, FIELDS, Omit<CursorQuery<FIELDS>, 'cursor'>>,
): UseCursorQueryReturn<R, FIELDS, Omit<CursorQuery<FIELDS>, 'cursor'>>;
/**
 * {@link useCursorQuery} typed by a query `Q` of your own, a subtype of
 * `CursorQuery` without `cursor`, such as one whose literal projection
 * narrows the rows.
 */
export function useCursorQuery<
  R,
  FIELDS extends string = string,
  Q extends Omit<CursorQuery<FIELDS>, 'cursor'> = Omit<
    CursorQuery<FIELDS>,
    'cursor'
  >,
>(
  options: UseCursorQueryOptions<R, FIELDS, Q>,
): UseCursorQueryReturn<R, FIELDS, Q>;
export function useCursorQuery<
  R,
  FIELDS extends string,
  Q extends Omit<CursorQuery<FIELDS>, 'cursor'>,
>(
  options: UseCursorQueryOptions<R, FIELDS, Q>,
): UseCursorQueryReturn<R, FIELDS, Q> {
  const [pages, setPages] = useState<Pages<R>>(noPages);
  // The cursor the next run reads from, set by fetchNext() just before it
  // starts the run; any other run (a new query, execute(), setQuery()) finds
  // none and reads the first page.
  const [next] = useState<{ cursor: string | null }>(() => ({ cursor: null }));
  const readPage = options.execute;
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
  } = useQueryRunner<Q, CursorPage<R>>(
    {
      ...options,
      execute: async (query, options) => {
        const cursor = next.cursor;
        next.cursor = null;
        if (cursor === null) setPages(noPages());
        const page = await readPage({ ...query, cursor }, options);
        // A run that no longer counts was aborted by the runner: its page
        // belongs to a query the rows have moved on from.
        if (!options.signal.aborted) {
          setPages(pages => ({
            items:
              cursor === null
                ? [...page.list]
                : [...pages.items, ...page.list],
            nextCursor: page.nextCursor,
          }));
        }
        return page;
      },
    },
    false,
  );
  const hasMore = typeof pages.nextCursor === 'string';
  const fetchNext = useCallback(async () => {
    if (loading || typeof pages.nextCursor !== 'string') return;
    next.cursor = pages.nextCursor;
    // The runner hands the cursor to the executor before it first awaits;
    // clear it in case the run did not start (no query, unmounted).
    const run = execute();
    next.cursor = null;
    await run;
  }, [loading, pages.nextCursor, next, execute]);
  const resetPages = useCallback(() => {
    reset();
    setPages(noPages());
  }, [reset]);
  return {
    items: pages.items,
    hasMore,
    fetchNext,
    loading,
    error,
    problem,
    status,
    execute,
    abort,
    reset: resetPages,
    getQuery,
    setQuery,
  };
}

function noPages<R>(): Pages<R> {
  return { items: [], nextCursor: undefined };
}
