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
import { useState, useCallback, useMemo } from 'react';

/**
 * Options for the useListQueryState hook.
 * @template FIELDS - The type of the fields used in conditions and projections.
 */
export interface UseListQueryStateOptions<FIELDS extends string = string> {
  /**
   * The initial list query configuration.
   */
  initialQuery: ListQuery<FIELDS>;
}

/**
 * Return type for the useListQueryState hook.
 * @template FIELDS - The type of the fields used in conditions and projections.
 */
export interface UseListQueryStateReturn<FIELDS extends string = string> {
  /**
   * The current condition.
   */
  condition: Condition<FIELDS>;
  /**
   * The current projection.
   */
  projection?: Projection<FIELDS>;
  /**
   * The current sort order.
   */
  sort?: FieldSort<FIELDS>[];
  /**
   * The current limit.
   */
  limit?: number;
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
  /**
   * Builds the list query object from current state.
   * @returns The list query object.
   */
  buildQuery: () => ListQuery<FIELDS>;
}

/**
 * A React hook for managing query state (condition, projection, sort, limit).
 * @template FIELDS - The type of the fields used in conditions and projections.
 * @param options - The options for the hook.
 * @returns An object containing the query state and methods to update it.
 */
export function useListQueryState<FIELDS extends string = string>(
  options: UseListQueryStateOptions<FIELDS>,
): UseListQueryStateReturn<FIELDS> {
  const { initialQuery } = options;
  const [condition, setCondition] = useState(initialQuery.condition);
  const [projection, setProjection] = useState(initialQuery.projection);
  const [sort, setSort] = useState(initialQuery.sort);
  const [limit, setLimit] = useState(initialQuery.limit);

  const buildQuery = useCallback(() => {
    return listQuery({
      condition,
      projection,
      sort,
      limit,
    });
  }, [condition, projection, sort, limit]);

  return useMemo(
    () => ({
      condition,
      projection,
      sort,
      limit,
      setCondition,
      setProjection,
      setSort,
      setLimit,
      buildQuery,
    }),
    [
      condition,
      projection,
      sort,
      limit,
      setCondition,
      setProjection,
      setSort,
      setLimit,
      buildQuery,
    ],
  );
}
