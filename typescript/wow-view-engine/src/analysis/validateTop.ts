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
  type AnalysisMetric,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type Issue,
} from '../model/index.js';
import { issue } from '../filter/index.js';
import { aliasesOf } from './validateAliases.js';

/** The metric types a rank reads: a number of the group, not a derived one. */
const RANKED = new Set(['COUNT', 'NUMERIC', 'DISTINCT_COUNT', 'PERCENTILE']);

/**
 * Whether a metric can rank the groups: a number of the group — never a
 * derived metric, a sample value or a moment (`momentMetrics`), which Wow's
 * `RANK` refuses as no number to rank by.
 */
export function rankable(
  metric: AnalysisMetric,
  moments: ReadonlySet<string>,
): boolean {
  return (
    RANKED.has(metric.type) &&
    !isValueMetric(metric) &&
    !moments.has(metric.alias)
  );
}

/**
 * Admits the per-group top N the way Wow admits the `RANK` window and
 * the having it compiles into: the source lists `RANK` and offers a having;
 * two dimensions or more, `within` one of them — so another is left to
 * rank inside it; `metric` a number of the group, never a derived metric,
 * a sample value or a moment; `count` a whole number no larger than the
 * rows the result keeps.
 */
export function validateTop(
  config: AnalysisViewConfig,
  capability: NonNullable<DataViewDefinition['analysis']>,
  moments: ReadonlySet<string> = new Set(),
): Issue[] {
  const top = config.top;
  if (top === undefined) return [];
  if (
    capability.having !== true ||
    !(capability.windows ?? []).includes('RANK')
  )
    return [issue('analysis.top.unsupported', ['top'])];
  if (config.groups.length < 2)
    return [issue('analysis.top.requires-groups', ['top'])];
  const issues: Issue[] = [];
  const { groups } = aliasesOf(config);
  if (typeof top.within !== 'string' || !groups.has(top.within))
    issues.push(
      issue('analysis.top.unknown-within', ['top', 'within'], {
        within: String(top.within),
      }),
    );
  const metric = config.metrics.find(entry => entry.alias === top.metric);
  if (metric === undefined || !rankable(metric, moments))
    issues.push(
      issue('analysis.top.unknown-metric', ['top', 'metric'], {
        metric: String(top.metric),
      }),
    );
  if (!Number.isInteger(top.count) || top.count < 1 || top.count > config.limit)
    issues.push(
      issue('analysis.top.count-out-of-range', ['top', 'count'], {
        max: config.limit,
      }),
    );
  if (
    top.direction !== undefined &&
    top.direction !== 'ASC' &&
    top.direction !== 'DESC'
  )
    issues.push(issue('analysis.top.malformed', ['top', 'direction']));
  return issues;
}
