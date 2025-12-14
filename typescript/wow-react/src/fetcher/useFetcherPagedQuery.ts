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
import { UseQueryReturn } from '../../core';
import { useFetcherQuery, UseFetcherQueryOptions } from '../../fetcher';

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
> extends UseFetcherQueryOptions<PagedQuery<FIELDS>, PagedList<R>, E> {}

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
> extends UseQueryReturn<PagedQuery<FIELDS>, PagedList<R>, E> {}

/**
 * A React hook for performing paged queries using the Fetcher library.
 *
 * This hook is designed for scenarios where you need to retrieve paginated data that matches a query condition.
 * It returns a PagedList containing the items for the current page along with pagination metadata such as total count and page information.
 *
 * @template R - The type of the resource or entity contained in each item of the paged list.
 * @template FIELDS - A string union type representing the fields that can be used in the paged query.
 * @template E - The type of error that may be thrown, defaults to FetcherError.
 *
 * @param options - Configuration options for the paged query, including the paged query parameters, fetcher instance, and other query settings.
 * @returns An object containing the query result (a PagedList with items and pagination info), loading state, error state, and utility functions.
 *
 * @throws {E} Throws an error of type E if the query fails, which could be due to network issues, invalid queries, or server errors.
 *
 * @example
 * ```typescript
 * import { useFetcherPagedQuery } from '@ahoo-wang/fetcher-react';
 * import { pagedQuery, contains, pagination, desc } from '@ahoo-wang/fetcher-wow';
 *
 * interface User {
 *   id: number;
 *   name: string;
 *   email: string;
 * }
 *
 * function UserListComponent() {
 *   const {
 *     data: pagedList,
 *     loading,
 *     error,
 *     execute,
 *     setQuery,
 *     getQuery
 *   } = useFetcherPagedQuery<User, keyof User>({
 *     url: '/api/users/paged',
 *     initialQuery: pagedQuery({
 *       condition: contains('name', 'John'),
 *       sort: [desc('createdAt')],
 *       pagination: pagination({ index: 1, size: 10 })
 *     }),
 *     autoExecute: true,
 *   });
 *
 *   const goToPage = (page: number) => {
 *     const currentQuery = getQuery();
 *     setQuery({
 *       ...currentQuery,
 *       pagination: { ...currentQuery.pagination, index: page }
 *     });
 *   };
 *
 *   if (loading) return <div>Loading...</div>;
 *   if (error) return <div>Error: {error.message}</div>;
 *
 *   return (
 *     <div>
 *       <h2>Users</h2>
 *       <ul>
 *         {pagedList.list.map(user => (
 *           <li key={user.id}>{user.name} - {user.email}</li>
 *         ))}
 *       </ul>
 *       <div>
 *         <span>Total: {pagedList.total} users</span>
 *         <button onClick={() => goToPage(1)} disabled={pagedList.pagination.index === 1}>
 *           First
 *         </button>
 *         <button onClick={() => goToPage(pagedList.pagination.index - 1)} disabled={pagedList.pagination.index === 1}>
 *           Previous
 *         </button>
 *         <span>Page {pagedList.pagination.index}</span>
 *         <button onClick={() => goToPage(pagedList.pagination.index + 1)}>
 *           Next
 *         </button>
 *       </div>
 *     </div>
 *   );
 * }
 * ```
 */
export function useFetcherPagedQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseFetcherPagedQueryOptions<R, FIELDS, E>,
): UseFetcherPagedQueryReturn<R, FIELDS, E> {
  return useFetcherQuery<PagedQuery<FIELDS>, PagedList<R>, E>(options);
}
