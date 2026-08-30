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

import type { FilterExpression } from './filter';
import { defaultProjection, type Projection } from './projection';
import type { FieldSort } from './sort';

export const DEFAULT_CURSOR_SIZE = 10;
export const MAX_CURSOR_SIZE = 2_147_483_646;
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
  projection = defaultProjection<FIELDS>(),
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
  return { filter, projection, sort, size, cursor };
}
