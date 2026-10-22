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

import type { PagedQuery, PagedResult } from '@ahoo-wang/wow-client';
import { useQueryRunner } from '../internal/useQueryRunner.js';
import type { QueryHookOptions, QueryHookReturn } from '../types.js';

/**
 * Options of {@link usePagedQuery}: a paged query and an `execute` that
 * resolves to one page.
 *
 * @template R - One row of the page
 * @template FIELDS - The field names the query may use
 * @template E - The error type, `Error` by default
 * @template Q - The query type: `FilterPagedQuery` by default
 */
export interface UsePagedQueryOptions<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends PagedQuery<FIELDS> = PagedQuery<FIELDS>,
> extends QueryHookOptions<Q, PagedResult<R>, E> {}

/**
 * What {@link usePagedQuery} returns: the page (`total` and `list`) as
 * `result`.
 *
 * @template R - One row of the page
 * @template FIELDS - The field names the query may use
 * @template E - The error type, `Error` by default
 * @template Q - The query type: `FilterPagedQuery` by default
 */
export interface UsePagedQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends PagedQuery<FIELDS> = PagedQuery<FIELDS>,
> extends QueryHookReturn<Q, PagedResult<R>, E> {}

/**
 * Runs a paged query through your own `execute` function and keeps the page
 * as state: typically a query client's `paged` or `pagedState`.
 *
 * `execute` receives the query and the request options of the run, its
 * `signal` included; hand them on so that a newer query, `abort()`
 * or an unmount cancels the request. The query runs on mount and whenever
 * `query` or `setQuery()` changes it — turn pages with `setQuery()`; set
 * `autoExecute: false` to run it only through `execute()`.
 *
 * Returns `result` (`{ total, list }`, or `undefined` before the first
 * success), `loading`, `error`, `status`, `execute`, `abort`, `reset`,
 * `getQuery` and `setQuery`.
 *
 * @template R - One row of the page
 * @template FIELDS - The field names the query may use. With a client whose
 *   fields are narrower than `string`, as a generated client's are, pass
 *   them here: TypeScript does not infer them from `execute` once `R` is given
 * @template E - The error type, `Error` by default
 *
 * @example
 * ```tsx
 * import type { SnapshotQueryClient, SnapshotRecord } from '@ahoo-wang/wow-client';
 * import { filter, pagedQuery } from '@ahoo-wang/wow-client/dsl';
 * import { usePagedQuery } from '@ahoo-wang/wow-react';
 *
 * function PaidOrders({ client, page }: { client: SnapshotQueryClient<OrderState, OrderFields>; page: number }) {
 *   const { result, loading, error } = usePagedQuery<SnapshotRecord<OrderState>, OrderFields>({
 *     query: pagedQuery({
 *       filter: filter.eq('state.status', 'PAID'),
 *       pagination: { index: page, size: 20 },
 *     }),
 *     execute: (query, options) =>
 *       client.paged(query, options),
 *   });
 *   if (error) return <p role="alert">{error.message}</p>;
 *   if (loading || !result) return <p>Loading…</p>;
 *   return <p>{result.list.length} of {result.total}</p>;
 * }
 * ```
 */
export function usePagedQuery<R, FIELDS extends string = string, E = Error>(
  options: UsePagedQueryOptions<R, FIELDS, E, PagedQuery<FIELDS>>,
): UsePagedQueryReturn<R, FIELDS, E, PagedQuery<FIELDS>>;
/**
 * {@link usePagedQuery} typed by a query `Q` of your own, a subtype of
 * `PagedQuery`, such as one whose literal projection narrows the result.
 */
export function usePagedQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends PagedQuery<FIELDS> = PagedQuery<FIELDS>,
>(
  options: UsePagedQueryOptions<R, FIELDS, E, Q>,
): UsePagedQueryReturn<R, FIELDS, E, Q>;
export function usePagedQuery<
  R,
  FIELDS extends string,
  E,
  Q extends PagedQuery<FIELDS>,
>(
  options: UsePagedQueryOptions<R, FIELDS, E, Q>,
): UsePagedQueryReturn<R, FIELDS, E, Q> {
  return useQueryRunner<Q, PagedResult<R>, E>(options);
}
