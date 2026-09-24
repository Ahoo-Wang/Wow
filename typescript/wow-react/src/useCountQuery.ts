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
import type { FetcherError } from '@ahoo-wang/fetcher';
import type {
  UseQueryOptions,
  UseQueryReturn,
} from '@ahoo-wang/fetcher-react/core';
import { useQuery } from '@ahoo-wang/fetcher-react/core';

/**
 * Options for the useCountQuery hook.
 * Extends UseQueryOptions with a FilterExpression as the query and number as data type.
 * @template FIELDS - The fields type for the filter
 * @template E - The error type, defaults to FetcherError
 */
export interface UseCountQueryOptions<
  FIELDS extends string = string,
  E = FetcherError,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> =
    FilterExpression<FIELDS>,
> extends UseQueryOptions<Q, number, E> {}

/**
 * Return type for the useCountQuery hook.
 * Extends UseQueryReturn with a FilterExpression as the query and number as data type.
 *
 * @template FIELDS - The fields type for the filter
 * @template E - The error type, defaults to FetcherError
 */
export interface UseCountQueryReturn<
  FIELDS extends string = string,
  E = FetcherError,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> =
    FilterExpression<FIELDS>,
> extends UseQueryReturn<Q, number, E> {}

/**
 * Hook for querying count data with a filter.
 * Wraps useQuery to provide type-safe count queries.
 *
 * @template FIELDS - The fields type for the filter
 * @template E - The error type, defaults to FetcherError
 * @param options - The query options including the filter and other settings
 * @returns The query result with count data
 *
 * @example
 * ```typescript
 * const { data, isLoading } = useCountQuery({
 *   initialQuery: filter.eq('status', 'ACTIVE'),
 *   execute: async (filter) => fetchCount(filter),
 * });
 * ```
 */
export function useCountQuery<FIELDS extends string = string, E = FetcherError>(
  options: UseCountQueryOptions<FIELDS, E, FilterExpression<FIELDS>>,
): UseCountQueryReturn<FIELDS, E, FilterExpression<FIELDS>>;
export function useCountQuery<FIELDS extends string = string, E = FetcherError>(
  options: UseCountQueryOptions<FIELDS, E, Condition<FIELDS>>,
): UseCountQueryReturn<FIELDS, E, Condition<FIELDS>>;
export function useCountQuery<
  FIELDS extends string = string,
  E = FetcherError,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS> =
    FilterExpression<FIELDS>,
>(
  options: UseCountQueryOptions<FIELDS, E, Q>,
): UseCountQueryReturn<FIELDS, E, Q>;
export function useCountQuery<
  FIELDS extends string,
  E,
  Q extends Condition<FIELDS> | FilterExpression<FIELDS>,
>(
  options: UseCountQueryOptions<FIELDS, E, Q>,
): UseCountQueryReturn<FIELDS, E, Q> {
  return useQuery<Q, number, E>(options);
}
