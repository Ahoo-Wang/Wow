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

import type { FilterExpression } from './filter/index.js';
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

/**
 * The body of a forward-only cursor query, which `QueryApi.cursor` and
 * `SnapshotQueryApi.cursorState` send; build it with {@link cursorQuery}.
 * Unlike a paged query it returns no total, and each page starts where the
 * one before ended. Wow 9.0 and later; Wow 8.11 has no cursor endpoints.
 */
export interface CursorQuery<FIELDS extends string = string> {
  /** What to match. */
  filter: FilterExpression<FIELDS>;
  /** Which fields to return; all of them when absent. */
  projection?: Projection<FIELDS>;
  /**
   * The order of the rows. The server appends a unique field, so each field
   * may appear once.
   */
  sort?: FieldSort<FIELDS>[];
  /** The rows of one page; the server uses 10 when absent. */
  size?: number;
  /**
   * Where the page starts: `null` or absent for the first page, then the
   * `nextCursor` of the page before, with the same filter and sort.
   */
  cursor?: string | null;
}

/** One page of a cursor query. */
export interface CursorPage<T> {
  /** The rows of this page. */
  list: T[];
  /** The `cursor` of the next page, or `null` after the last page. */
  nextCursor: string | null;
}

/**
 * Builds the body of a cursor query, with the defaults filled in: all fields,
 * no sort, a page of {@link DEFAULT_CURSOR_SIZE}, from the start.
 *
 * @throws TypeError when `size` is not an integer from 1 to
 *   {@link MAX_CURSOR_SIZE}, or `sort` names more than
 *   {@link MAX_CURSOR_SORT_FIELDS} fields or a field twice.
 *
 * @example
 * ```typescript
 * const first = await snapshotClient.cursorState(
 *   cursorQuery({ filter: filter.eq('status', 'ACTIVE'), size: 50 }),
 * );
 * if (first.nextCursor !== null) {
 *   await snapshotClient.cursorState(
 *     cursorQuery({
 *       filter: filter.eq('status', 'ACTIVE'),
 *       size: 50,
 *       cursor: first.nextCursor,
 *     }),
 *   );
 * }
 * ```
 */
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
