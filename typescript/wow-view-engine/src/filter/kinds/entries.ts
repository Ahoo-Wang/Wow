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

import { filter, type FilterExpression } from '@ahoo-wang/wow-client';
import type { FilterOperatorName } from '../../model/index.js';
import type { FilterSummaryRelation } from '../describe.js';

/**
 * A list's two questions of emptiness, and the only two a list offers
 * (second review R1-P1-7: five 「空」 — 没有条目、为空、不为空、存在、不存在 —
 * that nobody could tell apart). Stored under Wow's own operator names, so a
 * saved condition reads as what it asks:
 *
 * - `IS_EMPTY` — 「没有条目」: an empty list, and a list that is absent or
 *   null, which MongoDB's `$size: 0` alone would miss;
 * - `IS_NOT_NULL` — 「有条目」: the rest, at least one entry.
 */
export const ENTRY_OPERATORS: readonly FilterOperatorName[] = [
  'IS_EMPTY',
  'IS_NOT_NULL',
];

export function isEntryOperator(operator: FilterOperatorName): boolean {
  return ENTRY_OPERATORS.includes(operator);
}

/** How the bar and the pill say it. */
export function entryRelation(
  operator: FilterOperatorName,
): FilterSummaryRelation | null {
  if (operator === 'IS_EMPTY') return 'has-no-entries';
  if (operator === 'IS_NOT_NULL') return 'has-entries';
  return null;
}

/** What Wow is asked, or `null` for an operator that asks about entries. */
export function compileEntries(
  field: string,
  operator: FilterOperatorName,
): FilterExpression | null {
  const none = [filter.isEmpty(field), filter.isNull(field)];
  if (operator === 'IS_EMPTY') return filter.or(none);
  if (operator === 'IS_NOT_NULL') return filter.nor(none);
  return null;
}
