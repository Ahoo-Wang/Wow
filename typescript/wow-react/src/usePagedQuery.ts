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
  PagedList,
  PagedQuery,
  QueryApi,
  EMPTY_PAGED_LIST,
} from '@ahoo-wang/fetcher-wow';
import { PromiseState, usePromiseState, UsePromiseStateOptions } from '../core';
import { useCallback, useEffect, useMemo } from 'react';

export interface UsePagedQueryOptions<R, E = unknown>
  extends UsePromiseStateOptions<PagedList<R>, E> {
  /** The QueryApi instance to use for executing queries */
  queryApi: QueryApi<R>;
  /** Optional attributes to pass to the query API */
  attributes?: Record<string, any>;
}

export interface UsePagedQueryReturn<R>
  extends PagedList<R>,
    PromiseState<PagedList<R>> {
}

/**
 * A React hook for executing paged queries with automatic state management.
 *
 * @param query - The paged query to execute
 * @param options - Configuration options including the QueryApi instance
 * @returns An object containing the query result and promise state
 *
 * @example
 * ```typescript
 * import { usePagedQuery } from '@ahoo-wang/fetcher-react';
 * import { pagedQuery } from '@ahoo-wang/fetcher-wow';
 *
 * function MyComponent() {
 *   const query = pagedQuery({ condition: all() });
 *   const { list, total, loading, error } = usePagedQuery(query, {
 *     queryApi: myQueryClient,
 *   });
 *
 *   if (loading) return <div>Loading...</div>;
 *   if (error) return <div>Error: {error.message}</div>;
 *
 *   return (
 *     <div>
 *       <p>Total: {total}</p>
 *       <ul>
 *         {list.map(item => <li key={item.id}>{item.name}</li>)}
 *       </ul>
 *     </div>
 *   );
 * }
 * ```
 */
export function usePagedQuery<R, FIELDS extends string = string, E = unknown>(
  query: PagedQuery<FIELDS>,
  options: UsePagedQueryOptions<R, E>,
): UsePagedQueryReturn<R> {
  const { queryApi, attributes, ...promiseStateOptions } = options;
  const state = usePromiseState<PagedList<R>, E>(promiseStateOptions);

  const executeQuery = useCallback(async () => {
    state.setLoading();
    try {
      const result = await queryApi.paged(query, attributes);
      await state.setSuccess(result);
    } catch (error) {
      await state.setError(error as E);
    }
  }, [queryApi, query, attributes, state]);

  useEffect(() => {
    executeQuery();
  }, [executeQuery]);

  return useMemo(
    () => ({
      ...(state.result ?? EMPTY_PAGED_LIST),
      ...state,
    }),
    [state],
  );
}
