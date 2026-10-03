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

import type { Issue } from '../../model/index.js';

/**
 * Where a record view's config is fixed, by the part of it an error is
 * about:
 *
 * - `page-size` — a stored page size the limits refuse: one press sets the
 *   admitted size nearest it (the pagination that would offer sizes is not
 *   drawn while the view will not run);
 * - `layout` — a layout the definition no longer offers: one press switches
 *   to one it does;
 * - `sort` — the sort settings;
 * - `card` — the card settings;
 * - `columns` — the column settings, which hold the columns and their
 *   summaries.
 */
type ConfigRemedy = 'page-size' | 'layout' | 'sort' | 'card' | 'columns';

const BY_PART: Readonly<Record<string, ConfigRemedy>> = {
  pageSize: 'page-size',
  layout: 'layout',
  sort: 'sort',
  card: 'card',
  table: 'columns',
  summaries: 'columns',
};

/**
 * The remedy for the first error a remedy exists for, or `null`.
 *
 * The error strip offers one way out, and it used to be the column settings
 * whatever the error was: a page size over the limit sent the reader to a
 * panel that holds no page size, and the view could not be fixed from the
 * screen at all (second review R1-P1-3). One button for the first fixable
 * error is the strip's shape — it lists the rest — and once that one is
 * fixed, the next error brings its own.
 */
export function configRemedy(issues: readonly Issue[]): ConfigRemedy | null {
  for (const found of issues) {
    if (found.severity !== 'error') continue;
    const part = found.path[0];
    const remedy = typeof part === 'string' ? BY_PART[part] : undefined;
    if (remedy) return remedy;
  }
  return null;
}
