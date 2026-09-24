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
  AnalysisMetric,
  ChartSpec,
  Issue,
  IssuePath,
} from '../model/index.js';
import { issue } from '../filter/index.js';

/**
 * What every family's chart rules read (`validateChart`): the aliases the
 * result has, which of its metrics are moments, the chart and where it
 * sits — and the checks a slot is made of: a group the result has, a metric
 * it has, a quantity a mark can measure, every group consumed.
 */
export interface ChartContext {
  groups: Set<string>;
  metrics: Map<string, AnalysisMetric>;
  /** The metrics that are moments (`momentMetrics`): no mark measures one. */
  moments: ReadonlySet<string>;
  chart: ChartSpec;
  path: IssuePath;
}

export function group(
  context: ChartContext,
  alias: string,
  path: IssuePath,
): Issue[] {
  return context.groups.has(alias)
    ? []
    : [issue('chart.group.unknown', path, { alias })];
}

export function metric(
  context: ChartContext,
  alias: string,
  path: IssuePath,
): Issue[] {
  return context.metrics.has(alias)
    ? []
    : [issue('chart.metric.unknown', path, { alias })];
}

/**
 * A metric a mark measures: one the result has, and a quantity — a bar, a
 * slice, a shade or a point has no reading for the earliest of a date.
 */
export function measure(
  context: ChartContext,
  alias: string,
  path: IssuePath,
): Issue[] {
  const unknown = metric(context, alias, path);
  if (unknown.length > 0) return unknown;
  return context.moments.has(alias)
    ? [issue('chart.metric.moment', path, { alias })]
    : [];
}

/**
 * Every group alias must appear, or the chart cannot address its own rows.
 * One finding per dimension left out, each naming its own: a reader is told
 * which column it is by its header (`chart.*` findings name an alias, which
 * the surface says as the result names it), and a list joined here could
 * not be taken apart again to name each.
 */
export function consumesAll(
  context: ChartContext,
  consumed: readonly string[],
): Issue[] {
  const used = new Set(consumed);
  return [...context.groups]
    .filter(alias => !used.has(alias))
    .map(alias => issue('chart.group.unconsumed', context.path, { alias }));
}
