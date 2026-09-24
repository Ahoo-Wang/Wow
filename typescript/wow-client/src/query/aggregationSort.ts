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

// Internal: not re-exported by query/index.ts.
import type { AggregationQuery } from './aggregation.js';
import { asc, type FieldSort } from './sort.js';

/**
 * The sort Wow actually applies: what the query asked for, then each remaining
 * group ascending, so a page of grouped rows has one stable order.
 */
export function effectiveSort<FIELDS extends string = string>(
  query: Pick<AggregationQuery<string, FIELDS>, 'groupBy' | 'sort'>,
): FieldSort[] {
  const sort = [...(query.sort ?? [])];
  const sorted = new Set(sort.map(entry => entry.field));
  return [
    ...sort,
    ...(query.groupBy ?? [])
      .map(group => group.alias)
      .filter(alias => !sorted.has(alias))
      .map(asc),
  ];
}
