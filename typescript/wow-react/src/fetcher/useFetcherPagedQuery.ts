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

import type { FilterPagedQuery, PagedList } from '@ahoo-wang/wow-client';
// compat(wow<9): the hook also takes the Condition-based queries of `@ahoo-wang/wow-client/legacy`, which Wow < 8.11 needs; drop that overload in v10.
import type {
  PagedQuery,
  PagedQueryRequest,
} from '@ahoo-wang/wow-client/legacy';
import type { FetcherError } from '@ahoo-wang/fetcher';
import type { UseQueryReturn } from '@ahoo-wang/fetcher-react/core';
import type { UseFetcherQueryOptions } from '@ahoo-wang/fetcher-react/fetcher';
import { useFetcherQuery } from '@ahoo-wang/fetcher-react/fetcher';

/**
 * Options for configuring the useFetcherPagedQuery hook.
 *
 * This interface extends UseFetcherQueryOptions and is specifically tailored for paged queries
 * that use a PagedQuery to filter and paginate results, returning a PagedList of items.
 *
 * @template R - The type of the resource or entity contained in each item of the paged list.
 * @template FIELDS - A string union type representing the fields that can be used in the paged query.
 * @template E - The type of error that may be thrown, defaults to FetcherError.
 */
export interface UseFetcherPagedQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
> extends UseFetcherQueryOptions<Q, PagedList<R>, E> {}

/**
 * Return type for the useFetcherPagedQuery hook.
 *
 * This interface extends UseQueryReturn and provides the structure for the hook's return value,
 * including data (a PagedList containing items and pagination metadata), loading state, error state, and other query-related properties.
 *
 * @template R - The type of the resource or entity contained in each item of the paged list.
 * @template FIELDS - A string union type representing the fields that can be used in the paged query.
 * @template E - The type of error that may be thrown, defaults to FetcherError.
 */
export interface UseFetcherPagedQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
> extends UseQueryReturn<Q, PagedList<R>, E> {}

/**
 * POSTs a paged query to a Wow endpoint through a Fetcher and keeps the page
 * as state.
 *
 * `url` is resolved against the Fetcher's `baseURL`; `fetcher` is a Fetcher
 * or the name of a registered one, the default Fetcher when omitted. The
 * query runs on mount and whenever `query` or `setQuery()` changes it — turn
 * pages with `setQuery()`; set `autoExecute: false` to run it only through
 * `execute()`. A newer query aborts the request in flight, so a late response
 * never overwrites a newer one; an unmount aborts it too.
 *
 * Returns `result` (`{ total, list }`, or `undefined` before the first
 * success), `loading`, `error`, `status`, `execute`, `abort`, `reset`,
 * `getQuery` and `setQuery`. A failed request sets `error` to a
 * `FetcherError`; `toWowError(error)` from `@ahoo-wang/wow-client` reads the
 * server's `errorCode` from it.
 *
 * @template R - One row of the page
 * @template FIELDS - The field names the query may use
 * @template E - The error type, `FetcherError` by default
 *
 * @example
 * ```tsx
 * import { filter, pagedQuery } from '@ahoo-wang/wow-client';
 * import { useFetcherPagedQuery } from '@ahoo-wang/wow-react';
 *
 * function PaidOrders({ page }: { page: number }) {
 *   const { result, loading, error } = useFetcherPagedQuery<OrderState>({
 *     url: 'order/snapshot/paged/state',
 *     query: pagedQuery({
 *       filter: filter.eq('state.status', 'PAID'),
 *       pagination: { index: page, size: 20 },
 *     }),
 *   });
 *   if (error) return <p role="alert">{error.message}</p>;
 *   if (loading || !result) return <p>Loading…</p>;
 *   return <p>{result.list.length} of {result.total}</p>;
 * }
 * ```
 */
export function useFetcherPagedQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherPagedQueryOptions<R, FIELDS, E, FilterPagedQuery<FIELDS>>,
): UseFetcherPagedQueryReturn<R, FIELDS, E, FilterPagedQuery<FIELDS>>;
export function useFetcherPagedQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherPagedQueryOptions<R, FIELDS, E, PagedQuery<FIELDS>>,
): UseFetcherPagedQueryReturn<R, FIELDS, E, PagedQuery<FIELDS>>;
export function useFetcherPagedQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends PagedQueryRequest<FIELDS> = FilterPagedQuery<FIELDS>,
>(
  options: UseFetcherPagedQueryOptions<R, FIELDS, E, Q>,
): UseFetcherPagedQueryReturn<R, FIELDS, E, Q>;
export function useFetcherPagedQuery<
  R,
  FIELDS extends string,
  E,
  Q extends PagedQueryRequest<FIELDS>,
>(
  options: UseFetcherPagedQueryOptions<R, FIELDS, E, Q>,
): UseFetcherPagedQueryReturn<R, FIELDS, E, Q> {
  return useFetcherQuery<Q, PagedList<R>, E>(options);
}
