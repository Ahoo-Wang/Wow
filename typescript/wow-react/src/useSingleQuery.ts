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
import type { FetcherError } from '@ahoo-wang/fetcher';
import type {
  UseQueryOptions,
  UseQueryReturn,
} from '@ahoo-wang/fetcher-react/core';
import { useQuery } from '@ahoo-wang/fetcher-react/core';

/**
 * Options for the useSingleQuery hook.
 * Extends UseQueryOptions with SingleQuery as query key and custom result type.
 *
 * @template R - The result type of the query
 * @template FIELDS - The fields type for the single query
 * @template E - The error type, defaults to FetcherError
 */
export interface UseSingleQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
> extends UseQueryOptions<Q, R, E> {}

/**
 * Return type for the useSingleQuery hook.
 * Extends UseQueryReturn with SingleQuery as query key and custom result type.
 *
 * @template R - The result type of the query
 * @template FIELDS - The fields type for the single query
 * @template E - The error type, defaults to FetcherError
 */
export interface UseSingleQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
> extends UseQueryReturn<Q, R, E> {}

/**
 * Runs a single query through your own `execute` function and keeps the
 * result as state: typically a query client's `single` or `singleState`.
 *
 * `execute` receives the query, the `attributes` option and an
 * `AbortController`; hand the controller on so that a newer query, `abort()`
 * or an unmount cancels the request. The query runs on mount and whenever
 * `query` or `setQuery()` changes it; set `autoExecute: false` to run it only
 * through `execute()`.
 *
 * Returns `result` (the item, or `undefined` before the first success),
 * `loading`, `error`, `status`, `execute`, `abort`, `reset`, `getQuery` and
 * `setQuery`.
 *
 * @template R - The item the query returns
 * @template FIELDS - The field names the query may use
 * @template E - The error type, `FetcherError` by default
 *
 * @example
 * ```tsx
 * import { filter, singleQuery, type SnapshotQueryClient } from '@ahoo-wang/wow-client';
 * import { useSingleQuery } from '@ahoo-wang/wow-react';
 *
 * function OrderStatus({ client, id }: { client: SnapshotQueryClient<OrderState>; id: string }) {
 *   const { result, loading, error } = useSingleQuery<OrderState>({
 *     query: singleQuery({ filter: filter.id(id) }),
 *     execute: (query, attributes, abortController) =>
 *       client.singleState(query, attributes, abortController),
 *   });
 *   if (error) return <p role="alert">{error.message}</p>;
 *   if (loading || !result) return <p>Loading…</p>;
 *   return <p>{result.status}</p>;
 * }
 * ```
 */
export function useSingleQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseSingleQueryOptions<R, FIELDS, E, FilterSingleQuery<FIELDS>>,
): UseSingleQueryReturn<R, FIELDS, E, FilterSingleQuery<FIELDS>>;
export function useSingleQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseSingleQueryOptions<R, FIELDS, E, SingleQuery<FIELDS>>,
): UseSingleQueryReturn<R, FIELDS, E, SingleQuery<FIELDS>>;
export function useSingleQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends SingleQueryRequest<FIELDS> = FilterSingleQuery<FIELDS>,
>(
  options: UseSingleQueryOptions<R, FIELDS, E, Q>,
): UseSingleQueryReturn<R, FIELDS, E, Q>;
export function useSingleQuery<
  R,
  FIELDS extends string,
  E,
  Q extends SingleQueryRequest<FIELDS>,
>(
  options: UseSingleQueryOptions<R, FIELDS, E, Q>,
): UseSingleQueryReturn<R, FIELDS, E, Q> {
  return useQuery<Q, R, E>(options);
}
