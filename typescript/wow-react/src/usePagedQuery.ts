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

import { PagedList, PagedQuery } from '@ahoo-wang/fetcher-wow';
import { FetcherError } from '@ahoo-wang/fetcher';
import { useQuery, UseQueryOptions, UseQueryReturn } from './useQuery';

/**
 * Options for the usePagedQuery hook.
 * Extends UseQueryOptions with PagedQuery as query key and PagedList as data type.
 *
 * @template R - The type of the result items in the paged list
 * @template FIELDS - The fields type for the paged query
 * @template E - The error type, defaults to FetcherError
 */
export interface UsePagedQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
> extends UseQueryOptions<PagedQuery<FIELDS>, PagedList<R>, E> {}

/**
 * Return type for the usePagedQuery hook.
 * Extends UseQueryReturn with PagedQuery as query key and PagedList as data type.
 *
 * @template R - The type of the result items in the paged list
 * @template FIELDS - The fields type for the paged query
 * @template E - The error type, defaults to FetcherError
 */
export interface UsePagedQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
> extends UseQueryReturn<PagedQuery<FIELDS>, PagedList<R>, E> {}

/**
 * Hook for querying paged data with conditions, projection, pagination, and sorting.
 * Wraps useQuery to provide type-safe paged queries.
 *
 * @template R - The type of the result items in the paged list
 * @template FIELDS - The fields type for the paged query
 * @template E - The error type, defaults to FetcherError
 * @param options - The query options including paged query configuration
 * @returns The query result with paged list data
 *
 * @example
 * ```typescript
 * const { data, isLoading } = usePagedQuery<{ id: number; name: string }, 'id' | 'name'>({
 *   initialQuery: {
 *     condition: all(),
 *     pagination: { index: 1, size: 10 },
 *     projection: { include: ['id', 'name'] },
 *     sort: [{ field: 'id', direction: SortDirection.ASC }],
 *   },
 *   execute: async (query) => fetchPagedData(query),
 * });
 * ```
 */
export function usePagedQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UsePagedQueryOptions<R, FIELDS, E>,
): UsePagedQueryReturn<R, FIELDS, E> {
  return useQuery<PagedQuery<FIELDS>, PagedList<R>, E>(options);
}
