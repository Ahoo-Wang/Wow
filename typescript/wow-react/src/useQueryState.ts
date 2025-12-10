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

import { useCallback, useEffect, useRef } from 'react';
import { AutoExecuteCapable } from './types';

/**
 * Configuration options for the useQueryState hook
 * @template Q - The type of the query parameters
 */
export interface UseQueryStateOptions<Q> extends AutoExecuteCapable {
  /** The initial query parameters to be stored and managed */
  initialQuery: Q;
  /** Function to execute with the current query parameters. Called when autoExecute is true */
  execute: (query: Q) => Promise<void>;
}

/**
 * Return type of the useQueryState hook
 * @template Q - The type of the query parameters
 */
export interface UseQueryStateReturn<Q> {
  /** Function to retrieve the current query parameters */
  getQuery: () => Q;
  /** Function to update the query parameters. Triggers execution if autoExecute is true */
  setQuery: (query: Q) => void;
}

/**
 * A React hook for managing query state with automatic execution capabilities
 *
 * This hook provides a centralized way to manage query parameters, including
 * getting and setting the current query, and optionally automatically executing
 * queries when the query changes or on component mount.
 *
 * @template Q - The type of the query parameters
 * @param options - Configuration options for the hook
 * @returns An object containing getQuery and setQuery functions
 *
 * @example
 * ```typescript
 * interface UserQuery {
 *   id: string;
 *   name?: string;
 * }
 *
 * function UserComponent() {
 *   const executeQuery = async (query: UserQuery) => {
 *     // Perform query execution logic here
 *     console.log('Executing query:', query);
 *   };
 *
 *   const { getQuery, setQuery } = useQueryState<UserQuery>({
 *     initialQuery: { id: '1' },
 *     autoExecute: true,
 *     execute: executeQuery,
 *   });
 *
 *   const handleQueryChange = (newQuery: UserQuery) => {
 *     setQuery(newQuery); // Will automatically execute if autoExecute is true
 *   };
 *
 *   const currentQuery = getQuery(); // Get current query parameters
 *
 *   return (
 *     <div>
 *       <button onClick={() => handleQueryChange({ id: '2', name: 'John' })}>
 *         Update Query
 *       </button>
 *     </div>
 *   );
 * }
 * ```
 *
 * @throws This hook does not throw exceptions directly, but the execute function
 * may throw exceptions that should be handled by the caller.
 */
export function useQueryState<Q>(
  options: UseQueryStateOptions<Q>,
): UseQueryStateReturn<Q> {
  const { initialQuery, autoExecute, execute } = options;
  const autoExecuteValue = autoExecute ?? false;
  const queryRef = useRef(initialQuery);

  const getQuery = useCallback(() => {
    return queryRef.current;
  }, []);

  const setQuery = useCallback(
    (query: Q) => {
      queryRef.current = query;
      if (autoExecuteValue) {
        execute(query);
      }
    },
    [autoExecuteValue, execute],
  );

  useEffect(() => {
    if (autoExecuteValue) {
      execute(queryRef.current);
    }
  }, [autoExecuteValue, execute]);

  return { getQuery, setQuery };
}
