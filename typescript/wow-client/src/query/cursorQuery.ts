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

import type { FilterExpression } from '../dsl/filter/index.js';
import type { Projection } from './projection.js';
import type { FieldSort } from './sort.js';

/** The page size `cursorQuery()` uses when none is given. */
export const DEFAULT_CURSOR_SIZE = 10;
/**
 * The largest page size the cursor model admits (`CursorQuery` in Wow).
 * Over HTTP the server enforces a smaller, configurable page limit — 100 by
 * default — and answers a larger size with a 400.
 */
export const MAX_CURSOR_SIZE = 2_147_483_646;
/** The most sort fields a cursor query may name. */
export const MAX_CURSOR_SORT_FIELDS = 32;

/** Wow V9 forward-only cursor query request. */
export interface CursorQuery<FIELDS extends string = string> {
  filter: FilterExpression<FIELDS>;
  projection?: Projection<FIELDS>;
  sort?: FieldSort<FIELDS>[];
  size?: number;
  cursor?: string | null;
}

/** Wow V9 cursor page response. */
export interface CursorPage<T> {
  list: T[];
  nextCursor: string | null;
}

export function cursorQuery<FIELDS extends string = string>({
  filter,
  projection = {},
  sort = [],
  size = DEFAULT_CURSOR_SIZE,
  cursor = null,
}: CursorQuery<FIELDS>): CursorQuery<FIELDS> {
  if (!Number.isInteger(size) || size < 1 || size > MAX_CURSOR_SIZE) {
    throw new TypeError(`size must be between 1 and ${MAX_CURSOR_SIZE}.`);
  }
  if (sort.length > MAX_CURSOR_SORT_FIELDS) {
    throw new TypeError(
      `sort must contain at most ${MAX_CURSOR_SORT_FIELDS} fields.`,
    );
  }
  // A cursor is a position in one total order, so the gateway appends a unique
  // field and then refuses a repeated one: two directions for the same field
  // would leave the position ambiguous and a page could repeat or skip rows.
  const fields = sort.map(entry => entry.field);
  if (new Set(fields).size !== fields.length) {
    throw new TypeError('Cursor sort fields must be unique.');
  }
  return { filter, projection, sort, size, cursor };
}
