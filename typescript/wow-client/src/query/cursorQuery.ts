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

import { FieldSort, SortDirection } from './sort';
import { Queryable, SingleQuery } from './queryable';
import { and, Condition, gt, lt } from './condition';
import { PartialBy } from '@ahoo-wang/fetcher';

/**
 * Options for cursor-based queries.
 *
 * Cursor-based pagination allows for efficient pagination through large datasets
 * by using a cursor (typically an ID or timestamp) to track the current position.
 */
export interface CursorQueryOptions<Q extends Queryable = Queryable> {
  /**
   * The field to use for cursor comparison and sorting.
   */
  field: string;
  /**
   * The cursor value to compare against.
   * For ascending order, this is the maximum value to start from.
   * For descending order, this is the minimum value to start from.
   */
  cursorId?: string;
  /**
   * The sort direction (ASC or DESC).
   */
  direction: SortDirection;

  /**
   * The base query to which cursor conditions and sorting will be applied.
   */
  query: Q;
}

export const CURSOR_ID_START = '~';

/**
 * Creates a cursor-based condition for querying.
 *
 * This function generates a condition based on the sort direction.
 * For ascending order, it creates a "greater than" condition.
 * For descending order, it creates a "less than" condition.
 * If no cursorId is provided, it defaults to CURSOR_ID_START ('~').
 *
 * @param options - The cursor query options including field, cursor value, and sort direction
 * @returns A Condition object representing the cursor-based query condition
 */
export function cursorCondition({
                                  field,
                                  cursorId = CURSOR_ID_START,
                                  direction,
                                }: Omit<CursorQueryOptions, 'query'>): Condition {
  if (direction === SortDirection.ASC) {
    return gt(field, cursorId);
  } else {
    return lt(field, cursorId);
  }
}

/**
 * Creates a cursor-based sort configuration.
 *
 * This function generates sort criteria for cursor-based queries.
 *
 * @param options - The cursor query options including field, cursorId, and sort direction
 * @returns A FieldSort object representing the sort configuration
 */
export function cursorSort({ field, direction }: Omit<CursorQueryOptions, 'query'>): FieldSort {
  return { field, direction };
}

/**
 * Creates a cursor-based query by applying cursor conditions and sorting to a base query.
 *
 * This function combines cursor-based conditions with an existing query's conditions
 * using a logical AND operation, and applies cursor-based sorting as the primary sort criteria.
 *
 * @param options - The cursor query options including field, cursorId, direction, and base query
 * @returns The modified query object with cursor-based conditions and sorting applied
 */
export function cursorQuery<Q extends Queryable>(options: CursorQueryOptions<Q>): Q {
  const query = options.query;
  query.condition = and(cursorCondition(options), query.condition);
  query.sort = [
    cursorSort(options),
  ];
  return query;
}