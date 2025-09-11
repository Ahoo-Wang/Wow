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
import { ListQuery } from './queryable';
import { and, Condition, gt, lt } from './condition';

/**
 * Represents a cursor-based pagination query configuration.
 * This interface defines the structure for implementing cursor-based pagination,
 * which is an efficient way to paginate through large datasets.
 */
export interface CursorQuery {
  /** Field name used for cursor-based sorting and filtering */
  field: string;
  /**
   * Cursor ID marking the starting point (exclusive)
   * Uses CURSOR_ID_START constant for initial query
   */
  cursorId?: string;
  /** Sort direction for pagination traversal (ascending or descending) */
  direction?: SortDirection;
  /** Base query object to be enhanced with cursor-based parameters */
  query: ListQuery;
}

/** Special cursor ID value representing the starting point of a dataset */
export const CURSOR_ID_START = '~';

/**
 * Generates a cursor condition for filtering records relative to the cursor position
 * @param params - Cursor parameters excluding the base query
 * @param params.field - The field to apply the cursor condition on
 * @param params.cursorId - The cursor ID to compare against (defaults to CURSOR_ID_START)
 * @param params.direction - Sort direction which determines the comparison operator (defaults to SortDirection.DESC)
 * @returns Condition object for filtering records based on cursor position
 */
export function cursorCondition({
                                  field,
                                  cursorId = CURSOR_ID_START,
                                  direction = SortDirection.DESC,
                                }: Omit<CursorQuery, 'query'>): Condition {
  // When sorting in ascending order, we want records greater than the cursor
  if (direction === SortDirection.ASC) {
    return gt(field, cursorId);
  } else {
    // When sorting in descending order, we want records less than the cursor
    return lt(field, cursorId);
  }
}

/**
 * Creates a sort configuration based on cursor parameters
 * @param params - Cursor parameters excluding the base query
 * @param params.field - The field to sort by
 * @param params.direction - Sort direction (defaults to SortDirection.DESC)
 * @returns FieldSort configuration for cursor-based pagination
 */
export function cursorSort({ field, direction = SortDirection.DESC }: Omit<CursorQuery, 'query'>): FieldSort {
  return { field, direction };
}

/**
 * Enhances a base query with cursor-based pagination parameters
 * This function combines the cursor condition with the existing query condition
 * and sets the sorting according to the cursor parameters.
 * @param options - Complete cursor query configuration
 * @param options.field - The field used for cursor-based sorting and filtering
 * @param options.cursorId - The cursor ID marking the starting point (exclusive)
 * @param options.direction - Sort direction for pagination traversal
 * @param options.query - Base query object to be enhanced with cursor-based parameters
 * @returns Enhanced query with cursor-based filtering and sorting
 */
export function cursorQuery(options: CursorQuery): ListQuery {
  const query = options.query;
  // Combine the cursor condition with the existing query condition
  query.condition = and(cursorCondition(options), query.condition);
  // Apply cursor-based sorting
  query.sort = [
    cursorSort(options),
  ];
  return query;
}