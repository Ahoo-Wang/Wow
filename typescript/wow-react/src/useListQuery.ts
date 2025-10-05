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
  ListQuery,
  Condition,
  type Projection,
  FieldSort,
} from '@ahoo-wang/fetcher-wow';
import {
  useExecutePromise,
  UsePromiseStateOptions,
  useLatest,
  UseExecutePromiseReturn,
} from '../core';
import { useCallback, useMemo } from 'react';
import { useListQueryState } from './useListQueryState';
import { FetcherError } from '@ahoo-wang/fetcher';

/**
 * Options for the useListQuery hook.
 * @template R - The type of the result items in the list.
 * @template FIELDS - The type of the fields used in conditions and projections.
 * @template E - The type of the error.
 */
export interface UseListQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
> extends UsePromiseStateOptions<R[], E> {
  /**
   * The initial list query configuration.
   */
  initialQuery: ListQuery<FIELDS>;
  /**
   * The function to execute the list query.
   * @param listQuery - The list query object.
   * @param attributes - Optional additional attributes.
   * @returns A promise that resolves to an array of results.
   */
  list: (
    listQuery: ListQuery<FIELDS>,
    attributes?: Record<string, any>,
  ) => Promise<R[]>;
  /**
   * Optional additional attributes to pass to the list function.
   */
  attributes?: Record<string, any>;
}

/**
 * Return type for the useListQuery hook.
 * @template R - The type of the result items in the list.
 * @template FIELDS - The type of the fields used in conditions and projections.
 * @template E - The type of the error.
 */
export interface UseListQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
> extends UseExecutePromiseReturn<R[], E> {
  /**
   * Executes the list query.
   * @returns A promise that resolves to the result array or an error.
   */
  execute: () => Promise<E | R[]>;
  /**
   * Sets the condition for the query.
   * @param condition - The new condition.
   */
  setCondition: (condition: Condition<FIELDS>) => void;
  /**
   * Sets the projection for the query.
   * @param projection - The new projection.
   */
  setProjection: (projection: Projection<FIELDS>) => void;
  /**
   * Sets the sort order for the query.
   * @param sort - The new sort array.
   */
  setSort: (sort: FieldSort<FIELDS>[]) => void;
  /**
   * Sets the limit for the query.
   * @param limit - The new limit.
   */
  setLimit: (limit: number) => void;
}

/**
 * A React hook for managing list queries with state management for conditions, projections, sorting, and limits.
 * @template R - The type of the result items in the list.
 * @template FIELDS - The type of the fields used in conditions and projections.
 * @template E - The type of the error.
 * @param options - The options for the hook.
 * @returns An object containing the query state and methods to update it.
 * @example
 * ```typescript
 * const { data, loading, error, execute, setCondition, setLimit } = useListQuery({
 *   initialQuery: { condition: {}, projection: {}, sort: [], limit: 10 },
 *   list: async (listQuery) => fetchList(listQuery),
 * });
 * ```
 */
export function useListQuery<R, FIELDS extends string = string, E = FetcherError>(
  options: UseListQueryOptions<R, FIELDS, E>,
): UseListQueryReturn<R, FIELDS, E> {
  const promiseState = useExecutePromise<R[], E>(options);
  const queryState = useListQueryState({ initialQuery: options.initialQuery });
  const latestOptions = useLatest(options);
  const listExecutor = useCallback(async (): Promise<R[]> => {
    const queryRequest = queryState.buildQuery();
    return latestOptions.current.list(
      queryRequest,
      latestOptions.current.attributes,
    );
  }, [queryState, latestOptions]);

  const execute = useCallback(() => {
    return promiseState.execute(listExecutor);
  }, [promiseState, listExecutor]);

  return useMemo(
    () => ({
      ...promiseState,
      execute,
      setCondition: queryState.setCondition,
      setProjection: queryState.setProjection,
      setSort: queryState.setSort,
      setLimit: queryState.setLimit,
    }),
    [
      promiseState,
      execute,
      queryState.setCondition,
      queryState.setProjection,
      queryState.setSort,
      queryState.setLimit,
    ],
  );
}
