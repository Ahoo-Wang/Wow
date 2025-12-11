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
import {
  DebounceCapable,
  useDebouncedCallback,
  UseDebouncedCallbackReturn,
} from '../../core';
import { useCallback, useEffect, useMemo } from 'react';
import {
  useFetcherQuery,
  UseFetcherQueryOptions,
  UseFetcherQueryReturn,
} from '../useFetcherQuery';

/**
 * Configuration options for the useDebouncedFetcherQuery hook.
 *
 * Extends UseFetcherQueryOptions with DebounceCapable to provide debouncing functionality.
 * The hook will automatically debounce fetcher query executions to prevent excessive API calls.
 * Note that autoExecute is overridden internally to false to ensure proper debouncing behavior.
 *
 * @template Q - The type of the query parameters
 * @template R - The type of the result value
 * @template E - The type of the error value (defaults to FetcherError)
 */
export interface UseDebouncedFetcherQueryOptions<Q, R, E = FetcherError>
  extends UseFetcherQueryOptions<Q, R, E>, DebounceCapable {}

/**
 * Return type of the useDebouncedFetcherQuery hook.
 *
 * Omits the original 'execute' method from UseFetcherQueryReturn and replaces it with
 * debounced execution methods from UseDebouncedCallbackReturn. Uses a custom setQuery function
 * that respects the original autoExecute setting.
 *
 * @template Q - The type of the query parameters
 * @template R - The type of the result value
 * @template E - The type of the error value (defaults to FetcherError)
 */
export interface UseDebouncedFetcherQueryReturn<Q, R, E = FetcherError>
  extends
    Omit<UseFetcherQueryReturn<Q, R, E>, 'execute'>,
    UseDebouncedCallbackReturn<UseFetcherQueryReturn<Q, R, E>['execute']> {}

/**
 * A React hook for managing debounced query-based HTTP requests with automatic execution.
 *
 * This hook combines fetcher query functionality with debouncing to provide a convenient way to
 * make POST requests where query parameters are sent as the request body, while preventing
 * excessive API calls during rapid user interactions.
 *
 * The hook supports automatic execution on mount and when query parameters change, but wraps
 * the execution in a debounced callback to optimize performance. Internally, it overrides
 * autoExecute to false and implements custom logic to respect the original autoExecute setting.
 * When autoExecute is enabled, queries triggered by setQuery will also be debounced.
 *
 * @template Q - The type of the query parameters
 * @template R - The type of the result value
 * @template E - The type of the error value (defaults to FetcherError)
 * @param options - Configuration options for the hook, including url, initialQuery, and debounce settings
 * @returns An object containing fetcher state, query management functions, and debounced execution controls
 *
 * @example
 * ```typescript
 * import { useDebouncedFetcherQuery } from '@ahoo-wang/fetcher-react';
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
 *   } = useDebouncedFetcherQuery<SearchQuery, SearchResult>({
 *     url: '/api/search',
 *     initialQuery: { keyword: '', limit: 10 },
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
 * @throws This hook may throw exceptions related to network requests, which should be
 * handled by the caller. The underlying fetcher may throw FetcherError or other network-related errors.
 * Invalid URL or malformed request options may also cause exceptions.
 */
export function useDebouncedFetcherQuery<Q, R, E = FetcherError>(
  options: UseDebouncedFetcherQueryOptions<Q, R, E>,
): UseDebouncedFetcherQueryReturn<Q, R, E> {
  const originalAutoExecute = options.autoExecute;
  const debouncedExecuteOptions = {
    ...options,
    autoExecute: false,
  };
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
  } = useFetcherQuery(debouncedExecuteOptions);
  const { run, cancel, isPending } = useDebouncedCallback(
    execute,
    options.debounce,
  );
  const setQueryFn = useCallback(
    (query: Q) => {
      setQuery(query);
      if (originalAutoExecute) {
        run();
      }
    },
    [setQuery, run, originalAutoExecute],
  );
  useEffect(() => {
    if (originalAutoExecute) {
      run();
    }
  }, [run, originalAutoExecute]);
  return useMemo(
    () => ({
      loading,
      result,
      error,
      status,
      reset,
      abort,
      getQuery,
      setQuery: setQueryFn,
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
      setQueryFn,
      run,
      cancel,
      isPending,
    ],
  );
}
