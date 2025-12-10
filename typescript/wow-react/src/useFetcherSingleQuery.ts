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
import { UseQueryReturn } from './useQuery';
import { useFetcherQuery, UseFetcherQueryOptions } from './useFetcherQuery';

/**
 * Configuration options for the useFetcherSingleQuery hook.
 *
 * Extends UseFetcherQueryOptions to provide configuration for single item queries.
 *
 * @template R - The type of the result item.
 * @template FIELDS - The fields available for filtering and sorting in the single query.
 * @template E - The error type, defaults to FetcherError.
 */
export interface UseFetcherSingleQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
> extends UseFetcherQueryOptions<SingleQuery<FIELDS>, R, E> {}

/**
 * Return type of the useFetcherSingleQuery hook.
 *
 * Extends UseQueryReturn to provide state and methods for single item query operations.
 *
 * @template R - The type of the result item.
 * @template FIELDS - The fields available for filtering and sorting in the single query.
 * @template E - The error type, defaults to FetcherError.
 */
export interface UseFetcherSingleQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
> extends UseQueryReturn<SingleQuery<FIELDS>, R, E> {}

/**
 * A React hook for executing single item queries using the fetcher library within the wow framework.
 *
 * This hook is designed for fetching a single item with support for filtering and sorting
 * through the SingleQuery type. It returns a single result item and integrates seamlessly
 * with the fetcher library for HTTP requests.
 *
 * @template R - The type of the result item (e.g., User, Product).
 * @template FIELDS - The fields available for filtering and sorting (e.g., 'id', 'name', 'createdAt').
 * @template E - The error type, defaults to FetcherError.
 * @param options - Configuration options including URL, initial single query parameters, and execution settings.
 * @returns An object containing loading state, result item, error state, and query management functions.
 *
 * @example
 * ```typescript
 * import { useFetcherSingleQuery } from '@ahoo-wang/fetcher-react';
 * import { singleQuery, eq } from '@ahoo-wang/fetcher-wow';
 *
 * interface User {
 *   id: string;
 *   name: string;
 *   email: string;
 *   createdAt: string;
 * }
 *
 * function UserProfileComponent({ userId }: { userId: string }) {
 *   const {
 *     loading,
 *     result: user,
 *     error,
 *     execute,
 *   } = useFetcherSingleQuery<User, keyof User>({
 *     url: `/api/users/${userId}`,
 *     initialQuery: singleQuery({
 *       condition: eq('id', userId),
 *     }),
 *     autoExecute: true,
 *   });
 *
 *   if (loading) return <div>Loading user...</div>;
 *   if (error) return <div>Error: {error.message}</div>;
 *   if (!user) return <div>User not found</div>;
 *
 *   return (
 *     <div>
 *       <h2>{user.name}</h2>
 *       <p>Email: {user.email}</p>
 *       <p>Created: {user.createdAt}</p>
 *       <button onClick={execute}>Refresh</button>
 *     </div>
 *   );
 * }
 * ```
 *
 * @throws {FetcherError} When the HTTP request fails due to network issues, invalid responses, or server errors.
 * @throws {Error} When invalid options are provided, such as malformed URLs or unsupported query parameters.
 */
export function useFetcherSingleQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherSingleQueryOptions<R, FIELDS, E>,
): UseFetcherSingleQueryReturn<R, FIELDS, E> {
  return useFetcherQuery<SingleQuery<FIELDS>, R, E>(options);
}
