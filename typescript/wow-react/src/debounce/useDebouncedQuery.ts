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
 * Options for the useDebouncedQuery hook, extending UseQueryOptions with debouncing capabilities.
 * @template Q - The query type
 * @template R - The result type
 * @template E - The error type, defaults to FetcherError
 */
export interface UseDebouncedQueryOptions<Q, R, E = FetcherError>
  extends UseQueryOptions<Q, R, E>,
    DebounceCapable {}

/**
 * Return type for the useDebouncedQuery hook, including query state and debounced execution methods.
 * @template Q - The query type
 * @template R - The result type
 * @template E - The error type, defaults to FetcherError
 */
export interface UseDebouncedQueryReturn<Q, R, E = FetcherError>
  extends Omit<UseQueryReturn<Q, R, E>, 'execute'>,
    UseDebouncedCallbackReturn<UseQueryReturn<Q, R, E>['execute']> {}

/**
 * A React hook that provides debounced query execution, combining the useQuery hook with debouncing functionality
 * to prevent excessive API calls during rapid query changes.
 *
 * @template Q - The query type
 * @template R - The result type
 * @template E - The error type, defaults to FetcherError
 * @param options - Configuration options for the query and debouncing behavior, including debounce settings
 * @returns An object containing query state (loading, result, error, status) and debounced execution methods (run, cancel, isPending)
 *
 * @example
 * ```typescript
 * const { loading, result, error, run, cancel, isPending } = useDebouncedQuery({
 *   url: '/api/search',
 *   debounce: 300,
 * });
 *
 * // Trigger debounced query
 * run({ q: 'search term' });
 *
 * // Cancel pending debounced query
 * cancel();
 * ```
 *
 * @throws {FetcherError} When the underlying query execution fails
 */
export function useDebouncedQuery<Q, R, E = FetcherError>(
  options: UseDebouncedQueryOptions<Q, R, E>,
): UseDebouncedQueryReturn<Q, R, E> {
  const { loading, result, error, status, execute, reset,abort, getQuery, setQuery } =
    useQuery(options);
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
