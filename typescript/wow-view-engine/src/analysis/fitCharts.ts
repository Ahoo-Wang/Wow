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
  CHART_TYPES,
  type AnalysisGroup,
  type AnalysisMetric,
  type ChartType,
} from '../model/index.js';
import { isAdditiveMetric } from './validateChart.js';

/**
 * Why a chart type cannot draw the shape on hand, as a catalogue key. Each
 * names what the shape lacks, in the analyst's words, because that is what
 * the greyed tile says under itself (D20 屏 I).
 */
export type ChartUnfit =
  | 'chart.fit.needs-dimension'
  | 'chart.fit.needs-one-dimension'
  | 'chart.fit.needs-two-dimensions'
  | 'chart.fit.needs-two-metrics'
  | 'chart.fit.needs-no-dimension';

export interface ChartFit {
  available: boolean;
  /** Why not, when not. */
  reason?: ChartUnfit;
  /** The one the shape reads best as; at most one type carries it. */
  recommended?: true;
}

/** The shape a chart is fitted to: what there is to draw. */
export interface ChartShape {
  groups: readonly AnalysisGroup[];
  metrics: readonly AnalysisMetric[];
}

/**
 * Which chart types can draw a result of this shape, and which one it reads
 * best as (K3). The capability decides which types exist at all (D4); this
 * decides which of them are greyed right now, and why — the answer to Q6.
 *
 * The rules are the ones `validateChart` enforces after the fact, read
 * forward: a cartesian chart needs a dimension for its axis; a pie and a
 * funnel-by-group need exactly one; a heatmap needs two; a scatter plots
 * two metrics against each other; a metric card is one number, so it draws
 * nothing that has dimensions unless the one dimension is a date it can
 * sparkline. A table draws anything.
 */
export function fitCharts(shape: ChartShape): Record<ChartType, ChartFit> {
  const groups = shape.groups.length;
  const metrics = shape.metrics.length;
  const dated = groups === 1 && shape.groups[0]?.type === 'DATE_HISTOGRAM';
  const additive = shape.metrics.some(isAdditiveMetric);
  const fit = (ok: boolean, reason: ChartUnfit): ChartFit =>
    ok ? { available: true } : { available: false, reason };

  const fits: Record<ChartType, ChartFit> = {
    bar: fit(groups >= 1, 'chart.fit.needs-dimension'),
    line: fit(groups >= 1, 'chart.fit.needs-dimension'),
    area: fit(groups >= 1, 'chart.fit.needs-dimension'),
    combo: fit(groups >= 1, 'chart.fit.needs-dimension'),
    pie: fit(groups === 1, 'chart.fit.needs-one-dimension'),
    heatmap: fit(groups === 2, 'chart.fit.needs-two-dimensions'),
    scatter: fit(
      groups >= 1 && metrics >= 2,
      groups >= 1 ? 'chart.fit.needs-two-metrics' : 'chart.fit.needs-dimension',
    ),
    funnel: fit(
      groups === 1 || (groups === 0 && metrics >= 2),
      groups === 0
        ? 'chart.fit.needs-two-metrics'
        : 'chart.fit.needs-one-dimension',
    ),
    metric: fit(
      groups === 0 || (dated && additive),
      'chart.fit.needs-no-dimension',
    ),
  };
  const best = recommend(groups, dated);
  if (fits[best].available) fits[best] = { ...fits[best], recommended: true };
  return fits;
}

/**
 * What the shape reads best as: a number is a card, a series over time is a
 * line, one dimension is bars, two are bars split by the second. Nothing is
 * recommended for three or more dimensions — that is a table's job.
 */
function recommend(groups: number, dated: boolean): ChartType {
  if (groups === 0) return 'metric';
  if (dated) return 'line';
  return 'bar';
}

/** Every type, in the order the picker lays them out. */
export const CHART_PICKER_ORDER: readonly ChartType[] = CHART_TYPES;
