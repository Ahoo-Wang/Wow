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

import type {
  FilterListQuery,
  ListQuery,
  ListQueryRequest,
} from '@ahoo-wang/fetcher-wow';
import type { FetcherError } from '@ahoo-wang/fetcher';
import type { UseQueryOptions, UseQueryReturn } from '../core';
import { useQuery } from '../core';

/**
 * Options for the useListQuery hook.
 * Extends UseQueryOptions with ListQuery as query key and array of results as data type.
 *
 * @template R - The type of the result items in the list
 * @template FIELDS - The fields type for the list query
 * @template E - The error type, defaults to FetcherError
 */
export interface UseListQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = ListQuery<FIELDS>,
> extends UseQueryOptions<Q, R[], E> {}

/**
 * Return type for the useListQuery hook.
 * Extends UseQueryReturn with ListQuery as query key and array of results as data type.
 *
 * @template R - The type of the result items in the list
 * @template FIELDS - The fields type for the list query
 * @template E - The error type, defaults to FetcherError
 */
export interface UseListQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = ListQuery<FIELDS>,
> extends UseQueryReturn<Q, R[], E> {}

/**
 * Hook for querying list data with conditions, projection, and sorting.
 * Wraps useQuery to provide type-safe list queries.
 *
 * @template R - The type of the result items in the list
 * @template FIELDS - The fields type for the list query
 * @template E - The error type, defaults to FetcherError
 * @param options - The query options including list query configuration
 * @returns The query result with list data
 *
 * @example
 * ```typescript
 * const { data, isLoading } = useListQuery<{ id: number; name: string }, 'id' | 'name'>({
 *   initialQuery: {
 *     condition: all(),
 *     projection: { include: ['id', 'name'] },
 *     sort: [{ field: 'id', direction: SortDirection.ASC }],
 *   },
 *   execute: async (query) => fetchListData(query),
 * });
 * ```
 */
export function useListQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseListQueryOptions<R, FIELDS, E, ListQuery<FIELDS>>,
): UseListQueryReturn<R, FIELDS, E, ListQuery<FIELDS>>;
export function useListQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseListQueryOptions<R, FIELDS, E, FilterListQuery<FIELDS>>,
): UseListQueryReturn<R, FIELDS, E, FilterListQuery<FIELDS>>;
export function useListQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
  Q extends ListQueryRequest<FIELDS> = ListQuery<FIELDS>,
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
  return useQuery<Q, R[], E>(options);
}
