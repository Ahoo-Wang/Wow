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

import { filter as filters, type FilterExpression } from './filter/index.js';

/** The filter a query builder sends: the one given, or `filter.matchAll()`. */
export function queryFilter<FIELDS extends string>(
  filter: FilterExpression<FIELDS> | undefined,
): FilterExpression<FIELDS> {
  if (filter === null) {
    throw new TypeError('filter cannot be null.');
  }
  return filter ?? filters.matchAll();
}
