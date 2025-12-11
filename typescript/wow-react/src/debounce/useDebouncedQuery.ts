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
import { useQuery, UseQueryOptions, UseQueryReturn } from '../useQuery';
import {
  DebounceCapable,
  useDebouncedCallback,
  UseDebouncedCallbackReturn,
} from '../../core';
import { useMemo } from 'react';

/**
 * Configuration options for the useDebouncedQuery hook
 * @template Q - The type of the query parameters
 * @template R - The type of the result value
 * @template E - The type of the error value (defaults to FetcherError)
 */
export interface UseDebouncedQueryOptions<Q, R, E = FetcherError>
  extends UseQueryOptions<Q, R, E>, DebounceCapable {}

/**
 * Return type of the useDebouncedQuery hook
 * @template Q - The type of the query parameters
 * @template R - The type of the result value
 * @template E - The type of the error value (defaults to FetcherError)
 */
export interface UseDebouncedQueryReturn<Q, R, E = FetcherError>
  extends
    Omit<UseQueryReturn<Q, R, E>, 'execute'>,
    UseDebouncedCallbackReturn<UseQueryReturn<Q, R, E>['execute']> {}

/**
 * A React hook for managing debounced query execution with automatic state management
 *
 * This hook combines the query functionality with debouncing to provide
 * a convenient way to execute queries with built-in debouncing to prevent excessive
 * operations during rapid query changes, such as in search inputs or dynamic filtering.
 *
 * The hook supports automatic execution on mount and when query parameters change,
 * but wraps the execution in a debounced callback to optimize performance.
 *
 * @template Q - The type of the query parameters
 * @template R - The type of the result value
 * @template E - The type of the error value (defaults to FetcherError)
 * @param options - Configuration options for the hook
 * @returns An object containing query state, query management functions, and debounced execution controls
 *
 * @example
 * ```typescript
 * import { useDebouncedQuery } from '@ahoo-wang/fetcher-react';
 *
 * interface SearchQuery {
 *   keyword: string;
 *   limit: number;
 *   filters?: { category?: string };
 * }
 *
 * interface SearchResult {
 *   items: Array<{ id: string; title: string }>;
 *   total: number;
 * }
 *
 * function SearchComponent() {
 *   const {
 *     loading,
 *     result,
 *     error,
 *     run,
 *     cancel,
 *     isPending,
 *     setQuery,
 *     getQuery,
 *   } = useDebouncedQuery<SearchQuery, SearchResult>({
 *     initialQuery: { keyword: '', limit: 10 },
 *     execute: async (query) => {
 *       const response = await fetch('/api/search', {
 *         method: 'POST',
 *         body: JSON.stringify(query),
 *         headers: { 'Content-Type': 'application/json' },
 *       });
 *       return response.json();
 *     },
 *     debounce: { delay: 300 }, // Debounce for 300ms
 *     autoExecute: false, // Don't execute on mount
 *   });
 *
 *   const handleSearch = (keyword: string) => {
 *     setQuery({ keyword, limit: 10 }); // This will trigger debounced execution if autoExecute was true
 *   };
 *
 *   const handleManualSearch = () => {
 *     run(); // Manual debounced execution with current query
 *   };
 *
 *   const handleCancel = () => {
 *     cancel(); // Cancel any pending debounced execution
 *   };
 *
 *   if (loading) return <div>Searching...</div>;
 *   if (error) return <div>Error: {error.message}</div>;
 *
 *   return (
 *     <div>
 *       <input
 *         type="text"
 *         onChange={(e) => handleSearch(e.target.value)}
 *         placeholder="Search..."
 *       />
 *       <button onClick={handleManualSearch} disabled={isPending()}>
 *         {isPending() ? 'Searching...' : 'Search'}
 *       </button>
 *       <button onClick={handleCancel}>Cancel</button>
 *       {result && (
 *         <div>
 *           Found {result.total} items:
 *           {result.items.map(item => (
 *             <div key={item.id}>{item.title}</div>
 *           ))}
 *         </div>
 *       )}
 *     </div>
 *   );
 * }
 * ```
 *
 * @throws This hook may throw exceptions related to query execution, which should be
 * handled by the caller. The execute function may throw FetcherError or other errors.
 * Invalid query parameters or execution function errors may also cause exceptions.
 */
export function useDebouncedQuery<Q, R, E = FetcherError>(
  options: UseDebouncedQueryOptions<Q, R, E>,
): UseDebouncedQueryReturn<Q, R, E> {
  const {
    loading,
    result,
    error,
    status,
    execute,
    reset,
    abort,
    getQuery,
    setQuery,
  } = useQuery(options);
  const { run, cancel, isPending } = useDebouncedCallback(
    execute,
    options.debounce,
  );
  return useMemo(
    () => ({
      loading,
      result,
      error,
      status,
      reset,
      abort,
      getQuery,
      setQuery,
      run,
      cancel,
      isPending,
    }),
    [
      loading,
      result,
      error,
      status,
      reset,
      abort,
      getQuery,
      setQuery,
      run,
      cancel,
      isPending,
    ],
  );
}
