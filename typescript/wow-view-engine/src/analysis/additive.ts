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
  AnalysisDerivedExpression,
  AnalysisMetric,
} from '../model/index.js';

/**
 * Metrics the projection may add up across rows: a pie's "other" slice and a
 * metric card's trend headline are both sums of queried buckets, which only
 * means something for a metric that adds. AVG, MIN, MAX, DISTINCT_COUNT and a
 * percentile cannot be re-aggregated from their parts. A funnel's stages are
 * counts of what entered and remained, so they are these too.
 */
export function isAdditiveMetric(metric: AnalysisMetric | undefined): boolean {
  if (!metric) return false;
  if (metric.type === 'COUNT') return true;
  return metric.type === 'NUMERIC' && metric.function === 'SUM';
}

/**
 * Whether a metric's number over any span is read off sums over that span
 * (D38): one that adds, or a derived metric computed from such metrics and
 * numbers alone — 客单价 = GMV ÷ 订单数. The source computes a derived metric
 * from each row's own operands, so a bucket's is its own sums divided and
 * the whole's the whole's sums divided — which is what a metric card's
 * trend and its whole need, though the ratios themselves never add.
 * `metricOf` finds an operand by alias.
 */
export function readsOffSums(
  metric: AnalysisMetric | undefined,
  metricOf: (alias: string) => AnalysisMetric | undefined,
  through: ReadonlySet<string> = new Set(),
): boolean {
  if (isAdditiveMetric(metric)) return true;
  if (metric?.type !== 'DERIVED' || through.has(metric.alias)) return false;
  const inside = new Set(through).add(metric.alias);
  return derivedOperands(metric.expression).every(alias =>
    readsOffSums(metricOf(alias), metricOf, inside),
  );
}

/** Every metric a derived expression names, left to right. */
function derivedOperands(expression: AnalysisDerivedExpression): string[] {
  switch (expression.type) {
    case 'METRIC_REF':
      return [expression.metric];
    case 'BINARY':
      return [
        ...derivedOperands(expression.left),
        ...derivedOperands(expression.right),
      ];
    default:
      return [];
  }
}
