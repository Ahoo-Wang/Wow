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

import { expressionFilters } from './expressionFilter.js';
import { fieldFilters } from './fieldFilters.js';
import { logicalFilters } from './logicalFilters.js';
import { metadataFilters } from './metadataFilters.js';
import { searchFilters } from './searchFilter.js';
import { timeFilters } from './timeFilters.js';

/**
 * Builders for {@link FilterExpression} values. Each builder validates its
 * arguments, throws `TypeError` on invalid input, and returns a plain JSON
 * object in the server wire shape.
 *
 * @example
 * ```typescript
 * const paid = filter.and([
 *   filter.eq('state.status', 'PAID'),
 *   filter.gte('state.total', 100),
 * ]);
 * ```
 */
export const filter: typeof metadataFilters &
  typeof logicalFilters &
  typeof fieldFilters &
  typeof searchFilters &
  typeof timeFilters &
  typeof expressionFilters = {
  ...metadataFilters,
  ...logicalFilters,
  ...fieldFilters,
  ...searchFilters,
  ...timeFilters,
  ...expressionFilters,
};
