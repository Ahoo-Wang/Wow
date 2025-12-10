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

import { ListQuery } from '@ahoo-wang/fetcher-wow';
import { FetcherError } from '@ahoo-wang/fetcher';
import { UseQueryReturn } from './useQuery';
import { useFetcherQuery, UseFetcherQueryOptions } from './useFetcherQuery';

/**
 * Options for the useFetcherListQuery hook.
 * Extends UseFetcherQueryOptions to provide configuration for list queries.
 *
 * @template R - The type of individual items in the result array.
 * @template FIELDS - The fields available for filtering, sorting, and pagination in the list query.
 * @template E - The error type, defaults to FetcherError.
 */
export interface UseFetcherListQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
> extends UseFetcherQueryOptions<ListQuery<FIELDS>, R[], E> {}

/**
 * Return type of the useFetcherListQuery hook.
 * Extends UseQueryReturn to provide state and methods for list query operations.
 *
 * @template R - The type of individual items in the result array.
 * @template FIELDS - The fields available for filtering, sorting, and pagination in the list query.
 * @template E - The error type, defaults to FetcherError.
 */
export interface UseFetcherListQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
> extends UseQueryReturn<ListQuery<FIELDS>, R[], E> {}

/**
 * A React hook for executing list queries using the fetcher library within the wow framework.
 *
 * This hook is designed for fetching lists of items with support for filtering, sorting, and pagination
 * through the ListQuery type. It returns an array of results and integrates seamlessly with the fetcher
 * library for HTTP requests.
 *
 * @template R - The type of individual items in the result array (e.g., User, Product).
 * @template FIELDS - The fields available for filtering, sorting, and pagination (e.g., 'name', 'createdAt').
 * @template E - The error type, defaults to FetcherError.
 * @param options - Configuration options including URL, initial list query parameters, and execution settings.
 * @returns An object containing loading state, result array, error state, and query management functions.
 *
 * @example
 * ```typescript
 * import { useFetcherListQuery } from '@ahoo-wang/fetcher-react';
 * import { listQuery, contains, desc } from '@ahoo-wang/fetcher-wow';
 *
 * interface User {
 *   id: string;
 *   name: string;
 *   email: string;
 *   createdAt: string;
 * }
 *
 * function UserListComponent() {
 *   const {
 *     loading,
 *     result: users,
 *     error,
 *     execute,
 *     setQuery,
 *     getQuery,
 *   } = useFetcherListQuery<User, keyof User>({
 *     url: '/api/users/list',
 *     initialQuery: listQuery({
 *       condition: contains('name', 'John'),
 *       sort: [desc('createdAt')],
 *       limit: 10,
 *     }),
 *     autoExecute: true,
 *   });
 *
 *   const loadMore = () => {
 *     const currentQuery = getQuery();
 *     setQuery({
 *       ...currentQuery,
 *       limit: (currentQuery.limit || 10) + 10,
 *     });
 *   };
 *
 *   if (loading) return <div>Loading users...</div>;
 *   if (error) return <div>Error: {error.message}</div>;
 *
 *   return (
 *     <div>
 *       <h2>Users ({users?.length || 0})</h2>
 *       <ul>
 *         {users?.map(user => (
 *           <li key={user.id}>
 *             {user.name} - {user.email}
 *           </li>
 *         ))}
 *       </ul>
 *       <button onClick={loadMore}>Load More</button>
 *       <button onClick={execute}>Refresh list</button>
 *     </div>
 *   );
 * }
 * ```
 *
 * @throws {FetcherError} When the HTTP request fails due to network issues, invalid responses, or server errors.
 * @throws {Error} When invalid options are provided, such as malformed URLs or unsupported query parameters.
 */
export function useFetcherListQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherListQueryOptions<R, FIELDS, E>,
): UseFetcherListQueryReturn<R, FIELDS, E> {
  return useFetcherQuery<ListQuery<FIELDS>, R[], E>(options);
}
