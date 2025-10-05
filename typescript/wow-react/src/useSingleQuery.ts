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
  SingleQuery,
  Condition,
  type Projection,
  singleQuery,
  FieldSort,
} from '@ahoo-wang/fetcher-wow';
import {
  useExecutePromise,
  UsePromiseStateOptions,
  useLatest,
  UseExecutePromiseReturn,
} from '../core';
import { useCallback, useMemo, useState, useEffect } from 'react';
import { AttributesCapable, FetcherError } from '@ahoo-wang/fetcher';
import { AutoExecuteCapable } from './types';

/**
 * Options for the useSingleQuery hook.
 * @template R - The type of the result.
 * @template FIELDS - The type of the fields used in conditions and projections.
 * @template E - The type of the error.
 */
export interface UseSingleQueryOptions<
  R,
  FIELDS extends string = string,
  E = FetcherError,
> extends UsePromiseStateOptions<R, E>, AttributesCapable, AutoExecuteCapable {
  /**
   * The initial single query configuration.
   */
  initialQuery: SingleQuery<FIELDS>;
  /**
   * The function to execute the single query.
   * @param singleQuery - The single query object.
   * @param attributes - Optional additional attributes.
   * @returns A promise that resolves to the result.
   */
  query: (
    singleQuery: SingleQuery<FIELDS>,
    attributes?: Record<string, any>,
  ) => Promise<R>;
}

/**
 * Return type for the useSingleQuery hook.
 * @template R - The type of the result.
 * @template FIELDS - The type of the fields used in conditions and projections.
 * @template E - The type of the error.
 */
export interface UseSingleQueryReturn<
  R,
  FIELDS extends string = string,
  E = FetcherError,
> extends UseExecutePromiseReturn<R, E> {
  /**
   * Executes the single query.
   * @returns A promise that resolves to the result or an error.
   */
  execute: () => Promise<E | R>;
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
}

/**
 * A React hook for managing single queries with state management for conditions, projections, and sorting.
 * @template R - The type of the result.
 * @template FIELDS - The type of the fields used in conditions and projections.
 * @template E - The type of the error.
 * @param options - The options for the hook.
 * @returns An object containing the query state and methods to update it.
 * @example
 * ```typescript
 * const { data, loading, error, execute, setCondition, setProjection } = useSingleQuery({
 *   initialQuery: { condition: {}, projection: {}, sort: [] },
 *   query: async (singleQuery) => fetchSingleData(singleQuery),
 * });
 * ```
 */
export function useSingleQuery<
  R,
  FIELDS extends string = string,
  E = FetcherError,
>(
  options: UseSingleQueryOptions<R, FIELDS, E>,
): UseSingleQueryReturn<R, FIELDS, E> {
  const { initialQuery } = options;
  const promiseState = useExecutePromise<R, E>(options);
  const [condition, setCondition] = useState(initialQuery.condition);
  const [projection, setProjection] = useState(initialQuery.projection);
  const [sort, setSort] = useState(initialQuery.sort);
  const latestOptions = useLatest(options);
  const queryExecutor = useCallback(async (): Promise<R> => {
    const queryRequest = singleQuery({
      condition,
      projection,
      sort,
    });
    return latestOptions.current.query(
      queryRequest,
      latestOptions.current.attributes,
    );
  }, [condition, projection, sort, latestOptions]);

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
      setSort,
    }),
    [promiseState, execute, setCondition, setProjection, setSort],
  );
}
