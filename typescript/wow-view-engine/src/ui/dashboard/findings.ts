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

import type { DashboardField, Issue } from '../../model/index.js';

/**
 * The kernel's findings about a board's filters that name one in their
 * `field`: the kernel says the filter by its key, which is what the config
 * holds — and a filter added on the bar is keyed `filter-1`, a word nobody
 * reading the board ever typed (X-03). Not the two about the key itself —
 * one that cannot be a key, or one two filters share — where the key is
 * what is wrong and the name would point past it.
 */
const NAMES_A_FILTER: ReadonlySet<string> = new Set([
  'dashboard.binding.global-duplicate',
  'dashboard.binding.global-unknown',
  'dashboard.binding.missing',
  'dashboard.field.not-multiple',
  'dashboard.field.options-empty',
  'dashboard.field.required-no-default',
  'dashboard.filter.held',
  'dashboard.filter.unknown',
]);

/**
 * A finding about one of the board's filters, saying it the way the bar
 * does — by its name, 「仓库」 — rather than by its key. A key the board
 * has no filter for (an address naming one since removed) is all there is
 * to say, and stays.
 */
export function filterNamer(
  fields: readonly DashboardField[],
): (found: Issue) => Issue {
  return found => {
    const key = found.params?.field;
    if (!NAMES_A_FILTER.has(found.code) || typeof key !== 'string')
      return found;
    const label = fields.find(field => field.name === key)?.label;
    return label && label !== key
      ? { ...found, params: { ...found.params, field: label } }
      : found;
  };
}
