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

import {
  isValueMetric,
  type AnalysisViewConfig,
  type Issue,
  type IssuePath,
} from '../model/index.js';
import { issue } from '../filter/index.js';

/** Wow reserves this prefix and accepts single-segment aliases only. */
const RESERVED_ALIAS_PREFIX = '__wow';

/**
 * One segment of Wow's query-field syntax, which is all an alias may be.
 *
 * `aggregationAlias` runs the alias through the same admission a field path
 * gets and then refuses a dot, so a name like `orders total` or `2024` never
 * reaches the server as an alias — it reaches `aggregation.sum` as a
 * `TypeError`, which is a crash rather than something the editor can point at.
 */
const ALIAS_PATTERN = /^@?[A-Za-z_][A-Za-z0-9_-]*$/;

/** The alias sets the having and sort rules address their config by. */
export function aliasesOf(config: AnalysisViewConfig) {
  return {
    groups: new Set(config.groups.map(group => group.alias)),
    metrics: new Set(config.metrics.map(metric => metric.alias)),
    nonAnyMetrics: new Set(
      config.metrics
        .filter(metric => !isValueMetric(metric))
        .map(metric => metric.alias),
    ),
  };
}

export function validateAliases(config: AnalysisViewConfig): Issue[] {
  const issues: Issue[] = [];
  const seen = new Set<string>();
  const check = (alias: string, path: IssuePath) => {
    if (alias.includes('.'))
      issues.push(issue('analysis.alias.not-a-segment', path, { alias }));
    else if (!ALIAS_PATTERN.test(alias))
      issues.push(issue('analysis.alias.invalid', path, { alias }));
    if (alias.startsWith(RESERVED_ALIAS_PREFIX))
      issues.push(issue('analysis.alias.reserved', path, { alias }));
    if (seen.has(alias))
      issues.push(issue('analysis.alias.duplicate', path, { alias }));
    seen.add(alias);
  };

  config.groups.forEach((group, index) =>
    check(group.alias, ['groups', index, 'alias']),
  );
  config.metrics.forEach((metric, index) =>
    check(metric.alias, ['metrics', index, 'alias']),
  );
  return issues;
}

/**
 * A display name is optional, and given it is a word: a blank one would
 * head a column with nothing, which reads as a header that failed to load.
 */
export function displayNameIssues(
  entry: { label?: unknown },
  path: IssuePath,
): Issue[] {
  if (entry.label === undefined) return [];
  if (typeof entry.label !== 'string')
    return [issue('analysis.config.malformed', [...path, 'label'])];
  if (entry.label.trim() === '')
    return [issue('analysis.label.blank', [...path, 'label'])];
  return [];
}
