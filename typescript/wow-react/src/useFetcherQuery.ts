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

import { useFetcher, UseFetcherOptions, UseFetcherReturn } from '../fetcher';
import { FetcherError, FetchRequest } from '@ahoo-wang/fetcher';
import { useLatest } from '../core';
import { useCallback, useMemo } from 'react';
import { AutoExecuteCapable } from './types';
import { useQueryState, UseQueryStateReturn } from './useQueryState';

/**
 * Configuration options for the useFetcherQuery hook
 * @template Q - The type of the query parameters
 * @template R - The type of the result value
 * @template E - The type of the error value
 */
export interface UseFetcherQueryOptions<Q, R, E = FetcherError>
  extends UseFetcherOptions<R, E>, AutoExecuteCapable {
  /** The URL endpoint to send the POST request to */
  url: string;
  /** The initial query parameters to be sent as the request body */
  initialQuery: Q;
}

/**
 * Return type of the useFetcherQuery hook
 * @template Q - The type of the query parameters
 * @template R - The type of the result value
 * @template E - The type of the error value
 */
export interface UseFetcherQueryReturn<Q, R, E = FetcherError>
  extends UseFetcherReturn<R, E>, UseQueryStateReturn<Q> {
  /** Function to execute the query with current parameters */
  execute: () => Promise<void>;
}

/**
 * A React hook for managing query-based HTTP requests with automatic execution
 *
 * This hook combines the fetcher functionality with query state management to provide
 * a convenient way to make POST requests where query parameters are sent as the request body.
 * It supports automatic execution on mount and when query parameters change.
 *
 * @template Q - The type of the query parameters
 * @template R - The type of the result value
 * @template E - The type of the error value (defaults to FetcherError)
 * @param options - Configuration options for the hook
 * @returns An object containing fetcher state, query management functions, and execution controls
 *
 * @example
 * ```typescript
 * import { useFetcherQuery } from '@ahoo-wang/fetcher-react';
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
 *     execute,
 *     setQuery,
 *     getQuery,
 *   } = useFetcherQuery<SearchQuery, SearchResult>({
 *     url: '/api/search',
 *     initialQuery: { keyword: '', limit: 10 },
 *     autoExecute: false, // Don't execute on mount
 *   });
 *
 *   const handleSearch = (keyword: string) => {
 *     setQuery({ keyword, limit: 10 }); // This will auto-execute if autoExecute was true
 *   };
 *
 *   const handleManualSearch = () => {
 *     execute(); // Manual execution with current query
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
 *       <button onClick={handleManualSearch}>Search</button>
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
 * handled by the caller. The execute function may throw FetcherError or other network-related errors.
 * Invalid URL or malformed request options may also cause exceptions.
 */
export function useFetcherQuery<Q, R, E = FetcherError>(
  options: UseFetcherQueryOptions<Q, R, E>,
): UseFetcherQueryReturn<Q, R, E> {
  const latestOptions = useLatest(options);
  const {
    loading,
    result,
    error,
    status,
    execute: fetcherExecute,
    reset,
    abort,
  } = useFetcher<R, E>(latestOptions.current);
  const execute = useCallback(
    (query: Q) => {
      const fetcherRequest: FetchRequest = {
        url: options.url,
        method: 'POST',
        body: query as Record<string, any>,
      };
      return fetcherExecute(fetcherRequest);
    },
    [fetcherExecute, options.url],
  );

  const { getQuery, setQuery } = useQueryState({
    initialQuery: options.initialQuery,
    autoExecute: latestOptions.current.autoExecute,
    execute,
  });

  const executeWrapper = useCallback(() => {
    return execute(getQuery());
  }, [execute, getQuery]);

  return useMemo(
    () => ({
      loading,
      result,
      error,
      status,
      execute: executeWrapper,
      reset,
      abort,
      getQuery,
      setQuery,
    }),
    [
      loading,
      result,
      error,
      status,
      executeWrapper,
      reset,
      abort,
      getQuery,
      setQuery,
    ],
  );
}
