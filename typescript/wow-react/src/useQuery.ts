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

import {
  useExecutePromise,
  useLatest,
  UseExecutePromiseReturn,
  UseExecutePromiseOptions,
  PromiseSupplier,
} from '../core';
import { useCallback, useMemo, useEffect, useRef } from 'react';
import { AttributesCapable, FetcherError } from '@ahoo-wang/fetcher';
import { AutoExecuteCapable } from './types';

/**
 * Configuration options for the useQuery hook
 * @template Q - The type of the query parameters
 * @template R - The type of the result value
 * @template E - The type of the error value
 */
export interface UseQueryOptions<Q, R, E = FetcherError>
  extends UseExecutePromiseOptions<R, E>,
    AttributesCapable,
    AutoExecuteCapable {
  /** The initial query parameters */
  initialQuery: Q;

  /** Function to execute the query with given parameters and optional attributes */
  execute: (
    query: Q,
    attributes?: Record<string, any>,
    abortController?: AbortController,
  ) => Promise<R>;
}

/**
 * Return type of the useQuery hook
 * @template Q - The type of the query parameters
 * @template R - The type of the result value
 * @template E - The type of the error value
 */
export interface UseQueryReturn<Q, R, E = FetcherError>
  extends UseExecutePromiseReturn<R, E> {
  /**
   * Get the current query parameters
   */
  getQuery: () => Q;
  /** Function to update the query parameters */
  setQuery: (query: Q) => void;
  /** Function to execute the query with current parameters */
  execute: () => Promise<void>;
}

/* eslint-disable react-hooks/preserve-manual-memoization */
/**
 * A React hook for managing query-based asynchronous operations
 * @template Q - The type of the query parameters
 * @template R - The type of the result value
 * @template E - The type of the error value
 * @param options - Configuration options for the query
 * @returns An object containing the query state and control functions
 *
 * @example
 * ```typescript
 * import { useQuery } from '@ahoo-wang/fetcher-react';
 *
 * interface UserQuery {
 *   id: string;
 * }
 *
 * interface User {
 *   id: string;
 *   name: string;
 * }
 *
 * function UserComponent() {
 *   const { loading, result, error, execute, setQuery } = useQuery<UserQuery, User>({
 *     initialQuery: { id: '1' },
 *     execute: async (query) => {
 *       const response = await fetch(`/api/users/${query.id}`);
 *       return response.json();
 *     },
 *     autoExecute: true,
 *   });
 *
 *   const handleUserChange = (userId: string) => {
 *     setQuery({ id: userId }); // Automatically executes if autoExecute is true
 *   };
 *
 *   if (loading) return <div>Loading...</div>;
 *   if (error) return <div>Error: {error.message}</div>;
 *   return (
 *     <div>
 *       <button onClick={() => handleUserChange('2')}>Load User 2</button>
 *       {result && <p>User: {result.name}</p>}
 *     </div>
 *   );
 * }
 * ```
 */
export function useQuery<Q, R, E = FetcherError>(
  options: UseQueryOptions<Q, R, E>,
): UseQueryReturn<Q, R, E> {
  const latestOptions = useLatest(options);
  const {
    loading,
    result,
    error,
    status,
    execute: promiseExecutor,
    reset,
    abort,
  } = useExecutePromise<R, E>(latestOptions.current);
  const queryRef = useRef(options.initialQuery);

  const queryExecutor: PromiseSupplier<R> = useCallback(
    async (abortController: AbortController): Promise<R> => {
      return latestOptions.current.execute(
        queryRef.current,
        latestOptions.current.attributes,
        abortController,
      );
    },
    [queryRef, latestOptions],
  );

  const execute = useCallback(() => {
    return promiseExecutor(queryExecutor);
  }, [promiseExecutor, queryExecutor]);
  const getQuery = useCallback(() => {
    return queryRef.current;
  }, [queryRef]);
  const setQuery = useCallback(
    (query: Q) => {
      queryRef.current = query;
      if (latestOptions.current.autoExecute) {
        execute();
      }
    },
    [queryRef, latestOptions, execute],
  );

  useEffect(() => {
    if (latestOptions.current.autoExecute) {
      execute();
    }
  }, [latestOptions, execute]);

  return useMemo(
    () => ({
      loading,
      result,
      error,
      status,
      execute,
      reset,
      abort,
      getQuery,
      setQuery,
    }),
    [loading, result, error, status, execute, reset, abort, getQuery, setQuery],
  );
}
