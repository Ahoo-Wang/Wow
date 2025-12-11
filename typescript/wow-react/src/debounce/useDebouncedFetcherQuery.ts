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
import { useMemo } from 'react';
import { useFetcherQuery, UseFetcherQueryOptions, UseFetcherQueryReturn } from '../useFetcherQuery';

export interface UseDebouncedFetcherQueryOptions<Q, R, E = FetcherError>
  extends UseFetcherQueryOptions<Q, R, E>,
    DebounceCapable {}

export interface UseDebouncedFetcherQueryReturn<Q, R, E = FetcherError>
  extends Omit<UseFetcherQueryReturn<Q, R, E>, 'execute'>,
    UseDebouncedCallbackReturn<UseFetcherQueryReturn<Q, R, E>['execute']> {}

export function useDebouncedFetcherQuery<Q, R, E = FetcherError>(
  options: UseDebouncedFetcherQueryOptions<Q, R, E>,
): UseDebouncedFetcherQueryReturn<Q, R, E> {
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
  } = useFetcherQuery(options);
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
