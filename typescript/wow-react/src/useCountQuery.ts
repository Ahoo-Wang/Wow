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

import { Condition } from '@ahoo-wang/fetcher-wow';
import { FetcherError } from '@ahoo-wang/fetcher';
import { useQuery, UseQueryOptions, UseQueryReturn } from '../core';

/**
 * Options for the useCountQuery hook.
 * Extends UseQueryOptions with Condition as query key and number as data type.
 * @template FIELDS - The fields type for the condition
 * @template E - The error type, defaults to FetcherError
 */
export interface UseCountQueryOptions<
  FIELDS extends string = string,
  E = FetcherError,
> extends UseQueryOptions<Condition<FIELDS>, number, E> {}

/**
 * Return type for the useCountQuery hook.
 * Extends UseQueryReturn with Condition as query key and number as data type.
 *
 * @template FIELDS - The fields type for the condition
 * @template E - The error type, defaults to FetcherError
 */
export interface UseCountQueryReturn<
  FIELDS extends string = string,
  E = FetcherError,
> extends UseQueryReturn<Condition<FIELDS>, number, E> {}

/**
 * Hook for querying count data with conditions.
 * Wraps useQuery to provide type-safe count queries.
 *
 * @template FIELDS - The fields type for the condition
 * @template E - The error type, defaults to FetcherError
 * @param options - The query options including condition and other settings
 * @returns The query result with count data
 *
 * @example
 * ```typescript
 * const { data, isLoading } = useCountQuery({
 *   queryKey: [{ field: 'status', operator: 'eq', value: 'active' }],
 *   queryFn: async (condition) => fetchCount(condition),
 * });
 * ```
 */
export function useCountQuery<FIELDS extends string = string, E = FetcherError>(
  options: UseCountQueryOptions<FIELDS, E>,
): UseCountQueryReturn<FIELDS, E> {
  return useQuery(options);
}
