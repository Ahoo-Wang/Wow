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
  listQuery,
  FieldSort,
} from '@ahoo-wang/fetcher-wow';
import type { JsonServerSentEvent } from '@ahoo-wang/fetcher-eventstream';
import {
  useExecutePromise,
  UsePromiseStateOptions,
  useLatest, UseExecutePromiseReturn,
} from '../core';
import { useCallback, useState } from 'react';

/**
 * Options for the useListStreamQuery hook.
 * @template R - The type of the result items in the stream events.
 * @template FIELDS - The type of the fields used in conditions and projections.
 * @template E - The type of the error.
 */
export interface UseListStreamQueryOptions<
  R,
  FIELDS extends string = string,
  E = unknown,
> extends UsePromiseStateOptions<ReadableStream<JsonServerSentEvent<R>>, E> {
  /**
   * The initial list query configuration.
   */
  initialQuery: ListQuery<FIELDS>;
  /**
   * The function to execute the list stream query.
   * @param listQuery - The list query object.
   * @param attributes - Optional additional attributes.
   * @returns A promise that resolves to a readable stream of JSON server-sent events.
   */
  listStream: (
    listQuery: ListQuery<FIELDS>,
    attributes?: Record<string, any>,
  ) => Promise<ReadableStream<JsonServerSentEvent<R>>>;
  /**
   * Optional additional attributes to pass to the listStream function.
   */
  attributes?: Record<string, any>;
}

/**
 * Return type for the useListStreamQuery hook.
 * @template R - The type of the result items in the stream events.
 * @template FIELDS - The type of the fields used in conditions and projections.
 * @template E - The type of the error.
 */
export interface UseListStreamQueryReturn<
  R,
  FIELDS extends string = string,
  E = unknown,
> extends UseExecutePromiseReturn<ReadableStream<JsonServerSentEvent<R>>, E> {
  /**
   * Executes the list stream query.
   * @returns A promise that resolves to a readable stream of JSON server-sent events or an error.
   */
  execute: () => Promise<E | ReadableStream<JsonServerSentEvent<R>>>;
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
 * A React hook for managing list stream queries with state management for conditions, projections, sorting, and limits.
 * Returns a readable stream of JSON server-sent events.
 * @template R - The type of the result items in the stream events.
 * @template FIELDS - The type of the fields used in conditions and projections.
 * @template E - The type of the error.
 * @param options - The options for the hook.
 * @returns An object containing the query state and methods to update it.
 * @example
 * ```typescript
 * const { data, loading, error, execute, setCondition, setLimit } = useListStreamQuery({
 *   initialQuery: { condition: {}, projection: {}, sort: [], limit: 10 },
 *   listStream: async (listQuery) => fetchListStream(listQuery),
 * });
 *
 * // Use the stream
 * if (data) {
 *   const reader = data.getReader();
 *   // Process stream events
 * }
 * ```
 */
export function useListStreamQuery<
  R,
  FIELDS extends string = string,
  E = unknown,
>(
  options: UseListStreamQueryOptions<R, FIELDS, E>,
): UseListStreamQueryReturn<R, FIELDS> {
  const { initialQuery } = options;
  const promiseState = useExecutePromise<
    ReadableStream<JsonServerSentEvent<R>>,
    E
  >(options);
  const [condition, setCondition] = useState(initialQuery.condition);
  const [projection, setProjection] = useState(initialQuery.projection);
  const [sort, setSort] = useState(initialQuery.sort);
  const [limit, setLimit] = useState(initialQuery.limit);
  const latestOptions = useLatest(options);
  const streamExecutor = useCallback(async (): Promise<
    ReadableStream<JsonServerSentEvent<R>>
  > => {
    const queryRequest = listQuery({
      condition,
      projection,
      sort,
      limit,
    });
    return latestOptions.current.listStream(
      queryRequest,
      latestOptions.current.attributes,
    );
  }, [condition, projection, sort, limit, latestOptions]);

  const execute = useCallback(() => {
    return promiseState.execute(streamExecutor);
  }, [promiseState, streamExecutor]);

  return {
    ...promiseState,
    execute,
    setCondition,
    setProjection,
    setSort,
    setLimit,
  };
}
