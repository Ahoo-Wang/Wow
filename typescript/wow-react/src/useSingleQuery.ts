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

import { SingleQuery } from '@ahoo-wang/fetcher-wow';
import { FetcherError } from '@ahoo-wang/fetcher';
import { useQuery, UseQueryOptions, UseQueryReturn } from '../core';

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
> extends UseQueryOptions<SingleQuery<FIELDS>, R, E> {}

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
> extends UseQueryReturn<SingleQuery<FIELDS>, R, E> {}

/**
 * Hook for querying a single item with conditions, projection, and sorting.
 * Wraps useQuery to provide type-safe single item queries.
 *
 * @template R - The result type of the query
 * @template FIELDS - The fields type for the single query
 * @template E - The error type, defaults to FetcherError
 * @param options - The query options including single query configuration
 * @returns The query result with single item data
 *
 * @example
 * ```typescript
 * const { data, isLoading } = useSingleQuery<{ id: number; name: string }, 'id' | 'name'>({
 *   initialQuery: {
 *     condition: all(),
 *     projection: { include: ['id', 'name'] },
 *     sort: [{ field: 'id', direction: SortDirection.ASC }],
 *   },
 *   execute: async (query) => fetchSingleItem(query),
 * });
 * ```
 */
export function useSingleQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseSingleQueryOptions<R, FIELDS, E>,
): UseSingleQueryReturn<R, FIELDS, E> {
  return useQuery<SingleQuery<FIELDS>, R, E>(options);
}
