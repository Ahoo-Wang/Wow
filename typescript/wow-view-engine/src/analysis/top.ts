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
  aggregation,
  SortDirection,
  type AggregationMetric,
  type HavingExpression,
} from '@ahoo-wang/wow-client';
import type { AnalysisTop, AnalysisViewConfig } from '../model/index.js';

/*
 * 「每个〈维度〉只留前 N 个」 (D89): the per-group top N, compiled into a
 * hidden derived metric — the metric's dense rank among the groups sharing
 * the `within` value, which Wow computes over every group before the having
 * — and a having condition that keeps the ranks up to N, beside 「只保留」.
 */

/**
 * The hidden rank's alias: `<metric>__rank`, written the way a computed
 * line's is (D87), with a number after it in the unlikely case a group or a
 * metric of the config already holds the name.
 */
export function topRankAlias(
  config: Pick<AnalysisViewConfig, 'groups' | 'metrics'>,
  top: AnalysisTop,
): string {
  const taken = new Set([
    ...config.groups.map(group => group.alias),
    ...config.metrics.map(metric => metric.alias),
  ]);
  const base = `${top.metric}__rank`;
  let alias = base;
  for (let next = 2; taken.has(alias); next += 1) alias = `${base}${next}`;
  return alias;
}

/** The hidden metric that ranks each group within its `within` value. */
export function topRankMetric(
  config: Pick<AnalysisViewConfig, 'groups' | 'metrics'>,
  top: AnalysisTop,
): AggregationMetric {
  return aggregation.derived(
    d =>
      d.rank(top.metric, {
        within: [top.within],
        direction:
          top.direction === 'ASC' ? SortDirection.ASC : SortDirection.DESC,
      }),
    topRankAlias(config, top),
  );
}

/**
 * The query's having with the top N in it: the ranks up to `count`, and
 * 「只保留」's own conditions besides when there are any.
 */
export function withTopHaving(
  config: Pick<AnalysisViewConfig, 'groups' | 'metrics'>,
  top: AnalysisTop,
  having: HavingExpression | undefined,
): HavingExpression {
  const rank = aggregation.having.lte(topRankAlias(config, top), top.count);
  return having ? aggregation.having.and([having, rank]) : rank;
}

/**
 * Whether the result may hold fewer groups than the range has: 「只保留」
 * drops groups by their numbers, and the top N by their ranks. Either way a
 * group missing from the rows is not known to have had no records.
 */
export function narrowsGroups(
  config: Pick<AnalysisViewConfig, 'having' | 'top'>,
): boolean {
  return config.having !== undefined || config.top !== undefined;
}
