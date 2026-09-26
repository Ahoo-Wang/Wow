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
  AnalysisViewConfig,
  RecordData,
} from '../model/index.js';
import { filterFields } from '../filter/index.js';
import { appliedWindow } from './timeAxis.js';

/**
 * How a metric's own dates sit in the question's: `out` where the two do
 * not meet at all — 「上月同期」 on a board narrowed to one day this month
 * counts nothing, and a 0 there reads as a month with no sales — and
 * `clipped` where the question's dates cut the metric's short, so it
 * counts only part of the span its name says.
 */
export type MetricReach = 'out' | 'clipped';

/**
 * Each metric whose own conditions pin a date the question's conditions
 * narrow (`appliedWindow` on both, at the moment the question was asked),
 * by alias: `out` where the two windows do not meet, `clipped` where the
 * question's cut the metric's short. A derived metric reads what its
 * operands read: out when one is out, clipped when one is clipped. Nothing
 * without that moment.
 */
export function metricReach(
  config: AnalysisViewConfig,
  context: { now?: Date; timeZone: string },
): Map<string, MetricReach> {
  const reach = new Map<string, MetricReach>();
  const { now, timeZone } = context;
  if (!now || !Array.isArray(config.metrics)) return reach;
  for (const metric of config.metrics) {
    if (metric.type === 'DERIVED') {
      const read = [...operands(metric.expression)].map(alias =>
        reach.get(alias),
      );
      if (read.includes('out')) reach.set(metric.alias, 'out');
      else if (read.includes('clipped')) reach.set(metric.alias, 'clipped');
      continue;
    }
    const own = metricFilter(metric);
    if (!own) continue;
    for (const field of filterFields(own)) {
      const mine = appliedWindow(own, field, now, timeZone);
      if (mine.from === null && mine.to === null) continue;
      const asked = appliedWindow(config.filter, field, now, timeZone);
      const from = maxOf(mine.from, asked.from);
      const to = minOf(mine.to, asked.to);
      if (from !== null && to !== null && to <= from) {
        reach.set(metric.alias, 'out');
        break;
      }
      if (from !== mine.from || to !== mine.to)
        reach.set(metric.alias, 'clipped');
    }
  }
  return reach;
}

/**
 * `rows` with every metric out of the question's dates (`metricReach`)
 * read as null: it was not measured over any of them, and its 0 is no
 * number of the period it names.
 */
export function withoutOutOfReach(
  rows: readonly RecordData[],
  reach: ReadonlyMap<string, MetricReach>,
): RecordData[] {
  const out = [...reach].filter(([, how]) => how === 'out');
  if (out.length === 0) return [...rows];
  return rows.map(row => {
    const kept: RecordData = { ...row };
    for (const [alias] of out) if (alias in kept) kept[alias] = null;
    return kept;
  });
}

function metricFilter(
  metric: AnalysisMetric,
): AnalysisViewConfig['filter'] | undefined {
  return 'filter' in metric ? metric.filter : undefined;
}

function* operands(expression: AnalysisDerivedExpression): Generator<string> {
  switch (expression.type) {
    case 'METRIC_REF':
      yield expression.metric;
      return;
    case 'BINARY':
      yield* operands(expression.left);
      yield* operands(expression.right);
      return;
    default:
      return;
  }
}

function maxOf(one: number | null, other: number | null): number | null {
  if (one === null) return other;
  return other === null ? one : Math.max(one, other);
}

function minOf(one: number | null, other: number | null): number | null {
  if (one === null) return other;
  return other === null ? one : Math.min(one, other);
}
