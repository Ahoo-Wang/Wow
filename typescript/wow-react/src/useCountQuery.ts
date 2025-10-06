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

import { Condition } from '@ahoo-wang/fetcher-wow';
import {
  useExecutePromise,
  useLatest,
  UseExecutePromiseReturn, UseExecutePromiseOptions,
} from '../core';
import { useCallback, useState, useMemo, useEffect } from 'react';
import { AttributesCapable, FetcherError } from '@ahoo-wang/fetcher';
import { AutoExecuteCapable } from './types';

/**
 * Options for the useCountQuery hook.
 * @template FIELDS - The type of the fields used in conditions.
 * @template E - The type of the error.
 */
export interface UseCountQueryOptions<
  FIELDS extends string = string,
  E = FetcherError,
> extends UseExecutePromiseOptions<number, E>, AttributesCapable, AutoExecuteCapable {
  /**
   * The initial condition for the count query.
   */
  initialCondition: Condition<FIELDS>;
  /**
   * The function to execute the count query.
   * @param condition - The condition to filter resources.
   * @param attributes - Optional additional attributes.
   * @returns A promise that resolves to the count of matching resources.
   */
  count: (
    condition: Condition<FIELDS>,
    attributes?: Record<string, any>,
  ) => Promise<number>;
}

/**
 * Return type for the useCountQuery hook.
 * @template FIELDS - The type of the fields used in conditions.
 * @template E - The type of the error.
 */
export interface UseCountQueryReturn<
  FIELDS extends string = string,
  E = FetcherError,
> extends UseExecutePromiseReturn<number, E> {
  /**
   * Executes the count query.
   * @returns A promise that resolves to the count or an error.
   */
  execute: () => Promise<E | number>;
  /**
   * Sets the condition for the query.
   * @param condition - The new condition.
   */
  setCondition: (condition: Condition<FIELDS>) => void;
}

/**
 * A React hook for managing count queries with state management for conditions.
 * @template FIELDS - The type of the fields used in conditions.
 * @template E - The type of the error.
 * @param options - The options for the hook.
 * @returns An object containing the query state and methods to update it.
 * @example
 * ```typescript
 * const { data, loading, error, execute, setCondition } = useCountQuery({
 *   initialCondition: {},
 *   count: async (condition) => fetchCount(condition),
 * });
 * ```
 */
export function useCountQuery<FIELDS extends string = string, E = FetcherError>(
  options: UseCountQueryOptions<FIELDS, E>,
): UseCountQueryReturn<FIELDS, E> {
  const { initialCondition } = options;
  const promiseState = useExecutePromise<number, E>(options);
  const [condition, setCondition] = useState(initialCondition);
  const latestOptions = useLatest(options);
  const countExecutor = useCallback(async (): Promise<number> => {
    return latestOptions.current.count(
      condition,
      latestOptions.current.attributes,
    );
  }, [condition, latestOptions]);

  const execute = useCallback(() => {
    return promiseState.execute(countExecutor);
  }, [promiseState, countExecutor]);

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
    }),
    [promiseState, execute, setCondition],
  );
}
