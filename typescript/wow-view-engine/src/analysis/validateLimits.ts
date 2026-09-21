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

import { AGGREGATION_LIMITS } from '@ahoo-wang/fetcher-wow';
import type {
  AnalysisViewConfig,
  DataViewDefinition,
  Issue,
  IssuePath,
  RuntimeLimits,
} from '../model/index.js';
import { issue } from '../filter/index.js';

export function validateLimits(
  config: AnalysisViewConfig,
  capability: NonNullable<DataViewDefinition['analysis']>,
  limits: RuntimeLimits,
): Issue[] {
  const issues: Issue[] = [];
  const declared = capability.limits ?? {};

  if (!Number.isInteger(config.limit) || config.limit < 1)
    issues.push(issue('analysis.limit.not-positive', ['limit']));
  else {
    // The runtime ceiling always applies; a capability may only lower it.
    const max = Math.min(
      declared.maxLimit ?? Number.POSITIVE_INFINITY,
      limits.maxAnalysisRows,
      AGGREGATION_LIMITS.MAX_LIMIT,
    );
    if (config.limit > max)
      issues.push(issue('analysis.limit.too-large', ['limit'], { max }));
  }

  // Wow's own sizes always apply: a capability that declares no limits does
  // not lift them, it only means it lowers none of them. Without this the
  // ceiling was the capability's alone, so an undeclared one let a config
  // through to be refused by `aggregation.query()` instead.
  const count = (
    value: number,
    declaredMax: number | undefined,
    ceiling: number,
    code: string,
    path: IssuePath,
  ) => {
    const max = Math.min(declaredMax ?? Number.POSITIVE_INFINITY, ceiling);
    if (value > max) issues.push(issue(code, path, { max }));
  };
  count(
    config.groups.length,
    declared.maxGroups,
    AGGREGATION_LIMITS.MAX_GROUPS,
    'analysis.groups.too-many',
    ['groups'],
  );
  count(
    config.metrics.length,
    declared.maxMetrics,
    AGGREGATION_LIMITS.MAX_METRICS,
    'analysis.metrics.too-many',
    ['metrics'],
  );
  count(
    (config.elements ?? []).length,
    declared.maxElements,
    AGGREGATION_LIMITS.MAX_ELEMENTS,
    'analysis.elements.too-many',
    ['elements'],
  );
  count(
    config.sort.length,
    undefined,
    AGGREGATION_LIMITS.MAX_SORT_FIELDS,
    'analysis.sort.too-many',
    ['sort'],
  );

  return issues;
}
