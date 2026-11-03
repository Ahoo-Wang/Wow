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

import type { RecordKey } from '../model/index.js';

/**
 * The value at a dot path in a row, or `undefined` where the path leads
 * nowhere: through `null` or `undefined`, through a primitive, or into an
 * array by anything but a non-negative integer index.
 *
 * `'state.id'` reads `row.state.id`; `'items.0.sku'` reads the first item's
 * sku. An empty path reads the row itself. It never throws.
 *
 * It was `getPropertyValue` of `@ahoo-wang/wow-client`, which kept it only
 * for this package; a path read belongs with the kernel that reads rows.
 */
export function readPath(row: unknown, path: string): unknown {
  let current = row;
  for (const segment of path.split('.').filter(Boolean)) {
    if (current === null || current === undefined) return undefined;
    if (Array.isArray(current)) {
      if (!/^\d+$/.test(segment)) return undefined;
      current = current[Number(segment)];
    } else if (typeof current === 'object') {
      current = (current as Record<string, unknown>)[segment];
    } else {
      return undefined;
    }
  }
  return current ?? undefined;
}

/**
 * What a reader calls a row (`RecordCapability.rowName`): the value of
 * `field` where it is a string or a number with something in it, else the
 * row's key.
 */
export function rowNameOf(
  row: { key: RecordKey; data: unknown },
  field: string | undefined,
): string {
  if (field !== undefined) {
    const value = readPath(row.data, field);
    const text =
      typeof value === 'string'
        ? value.trim()
        : typeof value === 'number' && Number.isFinite(value)
          ? String(value)
          : '';
    if (text !== '') return text;
  }
  return String(row.key);
}
