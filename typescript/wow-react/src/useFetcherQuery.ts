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
import { useQueryState } from './useQueryState';

export interface useFetcherQuery<Q, R, E = FetcherError>
  extends UseFetcherOptions<R, E>, AutoExecuteCapable {
  url: string;
  initialQuery: Q;
}

export interface UseFetcherQueryReturn<
  Q,
  R,
  E = FetcherError,
> extends UseFetcherReturn<R, E> {
  /**
   * Get the current query parameters
   */
  getQuery: () => Q;
  /** Function to update the query parameters */
  setQuery: (query: Q) => void;
  /** Function to execute the query with current parameters */
  execute: () => Promise<void>;
}

export function useFetcherQuery<Q, R, E = FetcherError>(
  options: useFetcherQuery<Q, R, E>,
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
