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
  Condition,
  type Pagination,
  pagedQuery,
  Projection,
  FieldSort,
} from '@ahoo-wang/fetcher-wow';
import {
  useExecutePromise,
  useLatest,
  UseExecutePromiseReturn, UseExecutePromiseOptions,
} from '../core';
import { useCallback, useMemo, useState, useEffect } from 'react';
import { AttributesCapable, FetcherError } from '@ahoo-wang/fetcher';
import { AutoExecuteCapable } from './types';

/**
 * Options for the usePagedQuery hook.
 * @template R - The type of the result items in the paged list.
 * @template FIELDS - The type of the fields used in conditions and projections.
 * @template E - The type of the error.
 */
export interface UsePagedQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
> extends UseExecutePromiseOptions<PagedList<R>, E>, AttributesCapable, AutoExecuteCapable {
  /**
   * The initial paged query configuration.
   */
  initialQuery: PagedQuery<FIELDS>;
  /**
   * The function to execute the paged query.
   * @param pagedQuery - The paged query object.
   * @param attributes - Optional additional attributes.
   * @returns A promise that resolves to a paged list of results.
   */
  query: (
    pagedQuery: PagedQuery<FIELDS>,
    attributes?: Record<string, any>,
  ) => Promise<PagedList<R>>;
}

/**
 * Return type for the usePagedQuery hook.
 * @template R - The type of the result items in the paged list.
 * @template FIELDS - The type of the fields used in conditions and projections.
 * @template E - The type of the error.
 */
export interface UsePagedQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
> extends UseExecutePromiseReturn<PagedList<R>, E> {
  /**
   * Executes the paged query.
   * @returns A promise that resolves to the paged list or an error.
   */
  execute: () => Promise<E | PagedList<R>>;
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
   * Sets the pagination for the query.
   * @param pagination - The new pagination.
   */
  setPagination: (pagination: Pagination) => void;
  /**
   * Sets the sort order for the query.
   * @param sort - The new sort array.
   */
  setSort: (sort: FieldSort<FIELDS>[]) => void;
}

/**
 * A React hook for managing paged queries with state management for conditions, projections, pagination, and sorting.
 * @template R - The type of the result items in the paged list.
 * @template FIELDS - The type of the fields used in conditions and projections.
 * @template E - The type of the error.
 * @param options - The options for the hook.
 * @returns An object containing the query state and methods to update it.
 * @example
 * ```typescript
 * const { data, loading, error, execute, setCondition, setPagination } = usePagedQuery({
 *   initialQuery: { condition: {}, pagination: { index: 1, size: 10 }, projection: {}, sort: [] },
 *   query: async (pagedQuery) => fetchPagedData(pagedQuery),
 * });
 * ```
 */
export function usePagedQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UsePagedQueryOptions<R, FIELDS, E>,
): UsePagedQueryReturn<R, FIELDS, E> {
  const { initialQuery } = options;
  const promiseState = useExecutePromise<PagedList<R>, E>(options);
  const [condition, setCondition] = useState(initialQuery.condition);
  const [pagination, setPagination] = useState(initialQuery.pagination);
  const [projection, setProjection] = useState(initialQuery.projection);
  const [sort, setSort] = useState(initialQuery.sort);
  const latestOptions = useLatest(options);
  const queryExecutor = useCallback(async (): Promise<PagedList<R>> => {
    const queryRequest = pagedQuery({
      condition,
      pagination,
      projection,
      sort,
    });
    return latestOptions.current.query(
      queryRequest,
      latestOptions.current.attributes,
    );
  }, [condition, projection, pagination, sort, latestOptions]);

  const execute = useCallback(() => {
    return promiseState.execute(queryExecutor);
  }, [promiseState, queryExecutor]);

  const { autoExecute } = options;

  useEffect(() => {
    if (autoExecute) {
      execute();
    }
  }, [autoExecute, execute]);

  return useMemo(
    () => ({
      ...promiseState,
      execute,
      setCondition,
      setProjection,
      setPagination,
      setSort,
    }),
    [
      promiseState,
      execute,
      setCondition,
      setProjection,
      setPagination,
      setSort,
    ],
  );
}
