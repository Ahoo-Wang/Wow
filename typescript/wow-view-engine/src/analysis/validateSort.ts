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

import type {
  AnalysisCapability,
  AnalysisViewConfig,
  Issue,
  IssuePath,
} from '../model/index.js';
import { issue } from '../filter/index.js';
import { aliasesOf } from './validateAliases.js';

export function validateSortAndColumns(
  config: AnalysisViewConfig,
  analysis?: Pick<AnalysisCapability, 'metricSort'>,
): Issue[] {
  const { groups, metrics } = aliasesOf(config);
  const known = new Set([...groups, ...metrics]);
  const issues: Issue[] = [];

  // One row cannot be ordered: Wow refuses a sort without a groupBy, so a
  // config that carries one is caught here rather than by the server.
  if (config.sort.length > 0 && groups.size === 0)
    issues.push(issue('analysis.sort.requires-group', ['sort']));
  const sorted = new Set<string>();
  config.sort.forEach((sort, index) => {
    const path: IssuePath = ['sort', index, 'alias'];
    if (!known.has(sort.alias))
      issues.push(
        issue('analysis.sort.unknown-alias', path, { alias: sort.alias }),
      );
    // A source that orders groups by their keys alone refuses a metric.
    else if (analysis?.metricSort === false && metrics.has(sort.alias))
      issues.push(
        issue('analysis.sort.metric-unsupported', path, { alias: sort.alias }),
      );
    // One column cannot be ordered twice; Wow refuses `sort fields must be
    // unique.` and the second entry never had any effect anyway.
    if (sorted.has(sort.alias))
      issues.push(
        issue('analysis.sort.duplicate', path, { alias: sort.alias }),
      );
    sorted.add(sort.alias);
  });

  const seen = new Set<string>();
  config.table.columns.forEach((column, index) => {
    const path: IssuePath = ['table', 'columns', index, 'alias'];
    if (!known.has(column.alias))
      issues.push(
        issue('analysis.column.unknown-alias', path, { alias: column.alias }),
      );
    else if (seen.has(column.alias))
      issues.push(
        issue('analysis.column.duplicate', path, { alias: column.alias }),
      );
    seen.add(column.alias);
  });

  return issues;
}
