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

import { fieldAliasSegment, type SummaryFunction } from '../model/index.js';

/** Alias of one summary cell; the projection reads the result back by it. */
export function summaryAlias(field: string, fn: SummaryFunction): string {
  // Aliases are single-segment in Wow, so a field path becomes one token.
  return `${fieldAliasSegment(field)}_${fn.toLowerCase()}`;
}
