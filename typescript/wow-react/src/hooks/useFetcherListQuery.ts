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
import type { Fetcher } from '@ahoo-wang/fetcher';
import { postQuery } from '../internal/endpoint.js';
import type { QueryHookOptions, QueryHookReturn } from '../types.js';
import { useListQuery } from './useListQuery.js';

/**
 * Options of {@link useFetcherListQuery}: those of every query hook, with the
 * endpoint and the Fetcher in place of `execute`.
 *
 * @template R - One row of the list
 * @template FIELDS - The field names the query may use
 * @template E - The error type, `Error` by default
 * @template Q - The query type: `FilterListQuery` by default
 */
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

/**
 * What {@link useFetcherListQuery} returns: the rows as `result`.
 *
 * @template R - One row of the list
 * @template FIELDS - The field names the query may use
 * @template E - The error type, `Error` by default
 * @template Q - The query type: `FilterListQuery` by default
 */
export interface UseFetcherListQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends QueryHookReturn<Q, R[], E> {}

/**
 * POSTs a list query to a Wow endpoint through a Fetcher and keeps the rows
 * as state.
 *
 * `url` is resolved against the Fetcher's `baseURL`; `fetcher` is a Fetcher
 * or the name of a registered one, the default Fetcher when omitted. The
 * query runs on mount and whenever `query` or `setQuery()` changes it; set
 * `autoExecute: false` to run it only through `execute()`. A newer query
 * aborts the request in flight, so a late response never overwrites a newer
 * one; an unmount aborts it too.
 *
 * Returns `result` (the rows, or `undefined` before the first success),
 * `loading`, `error`, `status`, `execute`, `abort`, `reset`, `getQuery` and
 * `setQuery`. A failed request sets `error` to a `FetcherError`;
 * `toWowError(error)` from `@ahoo-wang/wow-client` reads the server's
 * `errorCode` from it.
 *
 * @template R - One row of the list
 * @template FIELDS - The field names the query may use
 * @template E - The error type, `Error` by default
 *
 * @example
 * ```tsx
 * import { desc, filter, listQuery } from '@ahoo-wang/wow-client';
 * import { useFetcherListQuery } from '@ahoo-wang/wow-react';
 *
 * function LatestOrders() {
 *   const { result, loading, error, execute } = useFetcherListQuery<OrderState>({
 *     url: 'order/snapshot/list/state',
 *     initialQuery: listQuery({
 *       filter: filter.eq('state.status', 'PAID'),
 *       sort: [desc('createTime')],
 *       limit: 20,
 *     }),
 *   });
 *   if (error) return <p role="alert">{error.message}</p>;
 *   if (loading || !result) return <p>Loading…</p>;
 *   return (
 *     <>
 *       <ul>{result.map(order => <li key={order.id}>{order.id}</li>)}</ul>
 *       <button onClick={execute}>Refresh</button>
 *     </>
 *   );
 * }
 * ```
 */
export function useFetcherListQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherListQueryOptions<R, FIELDS, E, FilterListQuery<FIELDS>>,
): UseFetcherListQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
export function useFetcherListQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherListQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseFetcherListQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
export function useFetcherListQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
>(
  options: UseFetcherListQueryOptions<R, FIELDS, E, Q>,
): UseFetcherListQueryReturn<R, FIELDS, E, Q>;
export function useFetcherListQuery<
  R,
  FIELDS extends string,
  E,
  Q extends ListQueryRequest<FIELDS>,
>(
  options: UseFetcherListQueryOptions<R, FIELDS, E, Q>,
): UseFetcherListQueryReturn<R, FIELDS, E, Q> {
  const { url, fetcher, ...rest } = options;
  return useListQuery<R, FIELDS, E, Q>({
    ...rest,
    execute: postQuery<Q, R[]>({ url, fetcher }),
  });
}
