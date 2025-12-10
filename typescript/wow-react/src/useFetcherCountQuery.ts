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

import { FetcherError } from '@ahoo-wang/fetcher';
import { UseQueryReturn } from './useQuery';
import { useFetcherQuery, UseFetcherQueryOptions } from './useFetcherQuery';
import { Condition } from '@ahoo-wang/fetcher-wow';

/**
 * Options for configuring the useFetcherCountQuery hook.
 *
 * This interface extends UseFetcherQueryOptions and is specifically tailored for count queries
 * that use a Condition object to filter results and return a numeric count.
 *
 * @template FIELDS - A string union type representing the fields that can be used in the condition.
 * @template E - The type of error that may be thrown, defaults to FetcherError.
 */
export interface UseFetcherCountQueryOptions<
  FIELDS extends string = string,
  E = FetcherError,
> extends UseFetcherQueryOptions<Condition<FIELDS>, number, E> {}

/**
 * Return type for the useFetcherCountQuery hook.
 *
 * This interface extends UseQueryReturn and provides the structure for the hook's return value,
 * including data (the count as a number), loading state, error state, and other query-related properties.
 *
 * @template FIELDS - A string union type representing the fields that can be used in the condition.
 * @template E - The type of error that may be thrown, defaults to FetcherError.
 */
export interface UseFetcherCountQueryReturn<
  FIELDS extends string = string,
  E = FetcherError,
> extends UseQueryReturn<Condition<FIELDS>, number, E> {}

/**
 * A React hook for performing count queries using the Fetcher library.
 *
 * This hook is designed for scenarios where you need to retrieve the count of records
 * that match a specific condition. It wraps the useFetcherQuery hook and specializes
 * it for count operations, returning a number representing the count.
 *
 * @template FIELDS - A string union type representing the fields that can be used in the condition.
 * @template E - The type of error that may be thrown, defaults to FetcherError.
 *
 * @param options - Configuration options for the count query, including the condition, fetcher instance, and other query settings.
 * @returns An object containing the query result (count as a number), loading state, error state, and utility functions.
 *
 * @throws {E} Throws an error of type E if the query fails, which could be due to network issues, invalid conditions, or server errors.
 *
 * @example
 * ```typescript
 * import { useFetcherCountQuery } from '@ahoo-wang/fetcher-react';
 * import { all } from '@ahoo-wang/fetcher-wow';
 *
 * function UserCountComponent() {
 *   const { data: count, loading, error, execute } = useFetcherCountQuery({
 *     url: '/api/users/count',
 *     initialQuery: all(),
 *     autoExecute: true,
 *   });
 *
 *   if (loading) return <div>Loading...</div>;
 *   if (error) return <div>Error: {error.message}</div>;
 *
 *   return (
 *     <div>
 *       <div>Total active users: {count}</div>
 *       <button onClick={execute}>Refresh Count</button>
 *     </div>
 *   );
 * }
 * ```
 */
export function useFetcherCountQuery<
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherCountQueryOptions<FIELDS, E>,
): UseFetcherCountQueryReturn<FIELDS, E> {
  return useFetcherQuery<Condition<FIELDS>, number, E>(options);
}
