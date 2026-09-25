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

import type { FilterSingleQuery } from '@ahoo-wang/wow-client';
// compat(wow<9): the hook also takes the Condition-based queries of `@ahoo-wang/wow-client/legacy`, which Wow < 8.11 needs; drop that overload in v10.
import type {
  SingleQuery,
  SingleQueryRequest,
} from '@ahoo-wang/wow-client/legacy';
import type { Fetcher } from '@ahoo-wang/fetcher';
import { useDelegatedEndpointQuery } from '../internal/fetcherReact.js';
import type { QueryHookOptions, QueryHookReturn } from '../types.js';

/**
 * Options of {@link useFetcherSingleQuery}: those of every query hook, with
 * the endpoint and the Fetcher in place of `execute`.
 *
 * @template R - The item the query returns
 * @template FIELDS - The field names the query may use
 * @template E - The error type, `Error` by default
 * @template Q - The query type: `FilterSingleQuery` by default
 */
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

/**
 * What {@link useFetcherSingleQuery} returns: the item as `result`.
 *
 * @template R - The item the query returns
 * @template FIELDS - The field names the query may use
 * @template E - The error type, `Error` by default
 * @template Q - The query type: `FilterSingleQuery` by default
 */
export interface UseFetcherSingleQueryReturn<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
> extends QueryHookReturn<Q, R, E> {}

/**
 * POSTs a single query to a Wow endpoint through a Fetcher and keeps the
 * result as state.
 *
 * `url` is resolved against the Fetcher's `baseURL`; `fetcher` is a Fetcher
 * or the name of a registered one, the default Fetcher when omitted. The
 * query runs on mount and whenever `query` or `setQuery()` changes it; set
 * `autoExecute: false` to run it only through `execute()`. A newer query
 * aborts the request in flight, so a late response never overwrites a newer
 * one; an unmount aborts it too.
 *
 * Returns `result` (the item, or `undefined` before the first success),
 * `loading`, `error`, `status`, `execute`, `abort`, `reset`, `getQuery` and
 * `setQuery`. A failed request sets `error` to a `FetcherError`;
 * `toWowError(error)` from `@ahoo-wang/wow-client` reads the server's
 * `errorCode` from it.
 *
 * @template R - The item the query returns
 * @template FIELDS - The field names the query may use
 * @template E - The error type, `Error` by default
 *
 * @example
 * ```tsx
 * import { filter, singleQuery } from '@ahoo-wang/wow-client';
 * import { useFetcherSingleQuery } from '@ahoo-wang/wow-react';
 *
 * function OrderStatus({ id }: { id: string }) {
 *   const { result, loading, error } = useFetcherSingleQuery<OrderState>({
 *     url: 'order/snapshot/single/state',
 *     query: singleQuery({ filter: filter.id(id) }),
 *   });
 *   if (error) return <p role="alert">{error.message}</p>;
 *   if (loading || !result) return <p>Loading…</p>;
 *   return <p>{result.status}</p>;
 * }
 * ```
 */
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
export function useFetcherSingleQuery<
  R,
  FIELDS extends string = string,
  E = Error,
>(
  options: UseFetcherSingleQueryOptions<R, FIELDS, E, SingleQuery<FIELDS>>,
): UseFetcherSingleQueryReturn<R, FIELDS, E, SingleQuery<FIELDS>>;
export function useFetcherSingleQuery<
  R,
  FIELDS extends string = string,
  E = Error,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
>(
  options: UseFetcherSingleQueryOptions<R, FIELDS, E, Q>,
): UseFetcherSingleQueryReturn<R, FIELDS, E, Q>;
export function useFetcherSingleQuery<
  R,
  FIELDS extends string,
  E,
  Q extends SingleQueryRequest<FIELDS>,
>(
  options: UseFetcherSingleQueryOptions<R, FIELDS, E, Q>,
): UseFetcherSingleQueryReturn<R, FIELDS, E, Q> {
  return useDelegatedEndpointQuery<Q, R, E>(options);
}
