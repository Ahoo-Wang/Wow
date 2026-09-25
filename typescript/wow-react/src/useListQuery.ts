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
import { useDelegatedQuery } from './internal/fetcherReact.js';
import type { QueryHookOptions, QueryHookReturn } from './types.js';

/**
 * Options of {@link useListQuery}: a list query and an `execute` that resolves
 * to its rows.
 *
 * @template R - One row of the list
 * @template FIELDS - The field names the query may use
 * @template E - The error type, `Error` by default
 * @template Q - The query type: `FilterListQuery` by default
 */
export interface UseListQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends QueryHookOptions<Q, R[], E> {}

/**
 * What {@link useListQuery} returns: the rows as `result`.
 *
 * @template R - One row of the list
 * @template FIELDS - The field names the query may use
 * @template E - The error type, `Error` by default
 * @template Q - The query type: `FilterListQuery` by default
 */
export interface UseListQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
> extends QueryHookReturn<Q, R[], E> {}

/**
 * Runs a list query through your own `execute` function and keeps the rows
 * as state: typically a query client's `list` or `listState`.
 *
 * `execute` receives the query, the `attributes` option and an
 * `AbortController`; hand the controller on so that a newer query, `abort()`
 * or an unmount cancels the request. The query runs on mount and whenever
 * `query` or `setQuery()` changes it; set `autoExecute: false` to run it only
 * through `execute()`.
 *
 * Returns `result` (the rows, or `undefined` before the first success),
 * `loading`, `error`, `status`, `execute`, `abort`, `reset`, `getQuery` and
 * `setQuery`.
 *
 * @template R - One row of the list
 * @template FIELDS - The field names the query may use
 * @template E - The error type, `Error` by default
 *
 * @example
 * ```tsx
 * import { desc, filter, listQuery, type SnapshotQueryClient } from '@ahoo-wang/wow-client';
 * import { useListQuery } from '@ahoo-wang/wow-react';
 *
 * function LatestOrders({ client }: { client: SnapshotQueryClient<OrderState> }) {
 *   const { result, loading, error } = useListQuery<OrderState>({
 *     initialQuery: listQuery({
 *       filter: filter.eq('state.status', 'PAID'),
 *       sort: [desc('createTime')],
 *       limit: 20,
 *     }),
 *     execute: (query, attributes, abortController) =>
 *       client.listState(query, attributes, abortController),
 *   });
 *   if (error) return <p role="alert">{error.message}</p>;
 *   if (loading || !result) return <p>Loading…</p>;
 *   return <ul>{result.map(order => <li key={order.id}>{order.id}</li>)}</ul>;
 * }
 * ```
 */
export function useListQuery<R, FIELDS extends string = string, E = Error>(
  options: UseListQueryOptions<R, FIELDS, E, FilterListQuery<FIELDS>>,
): UseListQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
export function useListQuery<R, FIELDS extends string = string, E = Error>(
  options: UseListQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseListQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
export function useListQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends ListQueryRequest<FIELDS> = FilterListQuery<FIELDS>,
>(
  options: UseListQueryOptions<R, FIELDS, E, Q>,
): UseListQueryReturn<R, FIELDS, E, Q>;
export function useListQuery<
  R,
  FIELDS extends string,
  E,
  Q extends ListQueryRequest<FIELDS>,
>(
  options: UseListQueryOptions<R, FIELDS, E, Q>,
): UseListQueryReturn<R, FIELDS, E, Q> {
  return useDelegatedQuery<Q, R[], E>(options);
}
