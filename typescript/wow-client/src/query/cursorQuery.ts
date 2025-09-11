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
import { Queryable } from './queryable';
import { Condition, gt, lt } from './condition';
import { PartialBy } from '@ahoo-wang/fetcher';

/**
 * Options for cursor-based queries.
 *
 * Cursor-based pagination allows for efficient pagination through large datasets
 * by using a cursor (typically an ID or timestamp) to track the current position.
 */
export interface CursorQueryOptions {
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
                                }: CursorQueryOptions): Condition {
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
export function cursorSort({ field, direction }: CursorQueryOptions): FieldSort {
  return { field, direction };
}

/**
 * Creates a cursor-based query object.
 *
 * This function generates a query that can be used for efficient pagination
 * by using a cursor field and direction to determine both the query condition
 * and sort order.
 *
 * @param options - The cursor query options including field, cursor value, and sort direction
 * @returns A Queryable object with appropriate condition and sort settings for cursor-based pagination
 */
export function cursorQuery(options: CursorQueryOptions): Queryable {
  return {
    condition: cursorCondition(options),
    sort: [
      cursorSort(options),
    ],
  };
}