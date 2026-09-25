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

import type { FilterExpression } from '@ahoo-wang/wow-client';
// compat(wow<9): the hook also takes the Condition-based queries of `@ahoo-wang/wow-client/legacy`, which Wow < 8.11 needs; drop that overload in v10.
import type { Condition } from '@ahoo-wang/wow-client/legacy';
import type { Fetcher } from '@ahoo-wang/fetcher';
import { endpointIdentity, postQuery } from '../internal/endpoint.js';
import { useQueryRunner } from '../internal/useQueryRunner.js';
import type { QueryHookOptions, QueryHookReturn } from '../types.js';

/**
 * Options of {@link useFetcherCountQuery}: those of every query hook, with
 * the endpoint and the Fetcher in place of `execute`.
 *
 * @template FIELDS - The field names the filter may use
 * @template E - The error type, `Error` by default
 * @template Q - The filter type: `FilterExpression` by default
 */
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

/**
 * What {@link useFetcherCountQuery} returns: the count as `result`.
 *
 * @template FIELDS - The field names the filter may use
 * @template E - The error type, `Error` by default
 * @template Q - The filter type: `FilterExpression` by default
 */
export interface UseFetcherCountQueryReturn<
  FIELDS extends string = string,
  E = Error,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> =
    FilterExpression<FIELDS>,
> extends QueryHookReturn<Q, number, E> {}

/**
 * POSTs a filter to a Wow count endpoint through a Fetcher and keeps the
 * count as state.
 *
 * `url` is resolved against the Fetcher's `baseURL`; `fetcher` is a Fetcher
 * or the name of a registered one, the default Fetcher when omitted. The
 * count runs on mount and whenever `query` or `setQuery()` changes the
 * filter; set `autoExecute: false` to run it only through `execute()`. A
 * newer filter aborts the request in flight, so a late response never
 * overwrites a newer one; an unmount aborts it too.
 *
 * A change of `url`, or of `fetcher`, runs the query again. A Fetcher is
 * compared by its name, or by its `baseURL` when it has none, so one created
 * inline in render does not run it on every render.
 *
 * Returns `result` (the count, or `undefined` before the first success),
 * `loading`, `error`, `status`, `execute`, `abort`, `reset`, `getQuery` and
 * `setQuery`. A failed request sets `error` to a `FetcherError`;
 * `toWowError(error)` from `@ahoo-wang/wow-client` reads the server's
 * `errorCode` from it.
 *
 * @template FIELDS - The field names the filter may use
 * @template E - The error type, `Error` by default
 *
 * @example
 * ```tsx
 * import { filter } from '@ahoo-wang/wow-client';
 * import { useFetcherCountQuery } from '@ahoo-wang/wow-react';
 *
 * function PaidCount() {
 *   const { result, error } = useFetcherCountQuery({
 *     url: 'order/snapshot/count',
 *     initialQuery: filter.eq('state.status', 'PAID'),
 *   });
 *   if (error) return <p role="alert">{error.message}</p>;
 *   return <p>{result ?? '…'} paid orders</p>;
 * }
 * ```
 */
export function useFetcherCountQuery<FIELDS extends string = string, E = Error>(
  options: UseFetcherCountQueryOptions<FIELDS, E, FilterExpression<FIELDS>>,
): UseFetcherCountQueryReturn<FIELDS, E, FilterExpression<FIELDS>>;
export function useFetcherCountQuery<FIELDS extends string = string, E = Error>(
  options: UseFetcherCountQueryOptions<FIELDS, E, Condition<FIELDS>>,
): UseFetcherCountQueryReturn<FIELDS, E, Condition<FIELDS>>;
export function useFetcherCountQuery<
  FIELDS extends string = string,
  E = Error,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> =
    FilterExpression<FIELDS>,
>(
  options: UseFetcherCountQueryOptions<FIELDS, E, Q>,
): UseFetcherCountQueryReturn<FIELDS, E, Q>;
export function useFetcherCountQuery<
  FIELDS extends string,
  E,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS>,
>(
  options: UseFetcherCountQueryOptions<FIELDS, E, Q>,
): UseFetcherCountQueryReturn<FIELDS, E, Q> {
  const { url, fetcher, ...rest } = options;
  return useQueryRunner<Q, number, E>(
    { ...rest, execute: postQuery<Q, number>({ url, fetcher }) },
    endpointIdentity({ url, fetcher }),
  );
}
