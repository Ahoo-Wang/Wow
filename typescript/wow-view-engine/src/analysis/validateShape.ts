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

import type { AnalysisViewConfig, Issue, IssuePath } from '../model/index.js';
import { issue } from '../filter/index.js';

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

/**
 * Whether the config has the skeleton every other rule reads through.
 *
 * A config arrives from a store, so any container may be missing or of the
 * wrong kind — `groups` a string, `chart` absent, an alias a number. The rules
 * index into them without looking (`groups.map`, `alias.includes`,
 * `CHART_FAMILY[chart.type]`), so each wrong piece is named here with its
 * path, and the caller stops before anything walks it. This is the analysis
 * counterpart of the filter budget: the kernel's contract is an Issue out,
 * never a TypeError.
 */
export function validateShape(config: AnalysisViewConfig): Issue[] {
  const issues: Issue[] = [];
  const malformed = (path: IssuePath) =>
    issues.push(issue('analysis.config.malformed', path));

  // Every entry of these lists is addressed by alias before it is validated,
  // so the alias must at least be a string.
  const list = (value: unknown, path: IssuePath, aliased: boolean) => {
    if (!Array.isArray(value)) return malformed(path);
    value.forEach((entry, index) => {
      if (!isObject(entry)) malformed([...path, index]);
      else if (aliased && typeof entry.alias !== 'string')
        malformed([...path, index, 'alias']);
    });
  };

  list(config.groups, ['groups'], true);
  list(config.metrics, ['metrics'], true);
  list(config.sort, ['sort'], true);
  if (config.elements !== undefined) list(config.elements, ['elements'], false);
  if (!isObject(config.table)) malformed(['table']);
  else list(config.table.columns, ['table', 'columns'], true);
  if (!isObject(config.chart)) malformed(['chart']);
  return issues;
}
