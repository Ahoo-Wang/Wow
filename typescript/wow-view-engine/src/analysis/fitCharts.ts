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
  type AnalysisViewConfig,
  type ChartSpec,
  type ChartType,
  type RecordData,
} from '../model/index.js';
import { familyOf, type ChartUnfit, type ShapeFacts } from './chartFamilies.js';
import { stageValues } from './chartOptions.js';
import { isAdditiveMetric } from './validateChart.js';

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
  /**
   * The metrics among them that are moments (`momentMetrics`), by alias:
   * written out on a card and in the table, never measured by a mark.
   */
  moments?: ReadonlySet<string>;
  /**
   * The rows the shape answered with, when there are any. A funnel over one
   * dimension takes its stages from them the moment it is picked
   * (`withStagesFrom`), so they decide whether it has the two stages a
   * funnel needs; left out, only the dimension's type is judged.
   */
  rows?: readonly RecordData[];
  /**
   * The chart as it stands, read while no rows are known: a view that has
   * not run has nothing to fill a funnel's stages from, so a funnel over the
   * one dimension has the stages its spec already names (`order`, which
   * `fitChartSlots` keeps) and no others. Left out, as are the rows, the
   * stages are not judged.
   */
  chart?: ChartSpec;
}

/**
 * Which chart types can draw a result of this shape, and which one it reads
 * best as (K3). The capability decides which types exist at all (D4); this
 * decides which of them are greyed right now, and why — the answer to Q6.
 *
 * Each type answers with its family's fit (`CHART_FAMILIES` in
 * `chartFamilies.ts`), which is `validateChart`'s rules read forward; a
 * table draws anything and is not among them.
 */
export function fitCharts(shape: ChartShape): Record<ChartType, ChartFit> {
  const facts = shapeFacts(shape);
  const fits = Object.fromEntries(
    CHART_TYPES.map(type => {
      const reason = familyOf(type).unfit(facts);
      return [
        type,
        reason ? { available: false, reason } : { available: true },
      ];
    }),
  ) as Record<ChartType, ChartFit>;
  const best = recommend(facts);
  if (best) fits[best] = { ...fits[best], recommended: true };
  return fits;
}

/**
 * Why the config's chart cannot draw its own shape, or null when it can —
 * or when its type is none this package has, which `validateChart` names.
 *
 * Judged by the shape alone, as the picker judges it before any row: the
 * dimensions and the metrics, the moments among them. A chart whose type
 * the shape leaves no room for — a bar over three dimensions, bars over
 * nothing but dates — is not a config gone wrong but a result the chart
 * cannot draw (D20): the result is drawn as its table, which draws any
 * shape, and the chart waits for a shape it can draw. Nothing is shaped
 * for it (`projectAnalysis`), and its rules are not asked (`validateAnalysis`).
 */
export function chartUnfit(
  config: Pick<AnalysisViewConfig, 'groups' | 'metrics' | 'chart'>,
  moments: ReadonlySet<string>,
): ChartUnfit | null {
  const type = config.chart.type;
  if (!CHART_TYPES.includes(type)) return null;
  return familyOf(type).unfit(
    shapeFacts({ groups: config.groups, metrics: config.metrics, moments }),
  );
}

function shapeFacts(shape: ChartShape): ShapeFacts {
  const groups = shape.groups.length;
  const only = groups === 1 ? shape.groups[0] : undefined;
  const rows = shape.rows ?? [];
  const quantities = shape.metrics.filter(
    metric => !shape.moments?.has(metric.alias),
  );
  // No rows is no answer yet, not an answer of no stages: an empty result
  // draws no chart of any type, and says so in a sentence of its own.
  const stages =
    only && rows.length > 0
      ? stageValues(rows, only.alias).length
      : only && shape.chart
        ? namedStages(shape.chart)
        : undefined;
  return {
    groups,
    metrics: shape.metrics.length,
    quantities: quantities.length,
    dated: groups === 1 && shape.groups[0]?.type === 'DATE_HISTOGRAM',
    additive: quantities.filter(isAdditiveMetric).length,
    categorical: only?.type === 'TERMS',
    textless: rows.length > 0 && stages === 0,
    ...(stages === undefined ? {} : { stages }),
  };
}

/**
 * The stages a funnel over one dimension has before any row arrives: the
 * values its spec already orders, once each — kept by `fitChartSlots`
 * whichever type the chart is now, since a family's settings survive a
 * switch away and back — and none when it orders nothing.
 */
function namedStages(chart: ChartSpec): number {
  const stages = chart.funnel?.stages;
  return stages?.from === 'group' ? new Set(stages.order).size : 0;
}

/**
 * What the shape reads best as: a number is a card, a series over time is a
 * line, one dimension is bars, two are bars split by the second. Three or
 * more are a table's job, and so is a shape whose every metric is a moment
 * (`momentMetrics`); nothing is recommended — every chart that
 * could take them is greyed, and a recommendation nobody can act on is
 * worse than none.
 *
 * The recommendation is never a greyed tile: the return type is narrowed to
 * the three whose fit the answer implies (a card with no dimension, the
 * cartesian family with one or two), and a test holds it to that over every
 * shape (test/fitCharts.test.ts「fitCharts」).
 */
function recommend({
  groups,
  dated,
  quantities,
}: ShapeFacts): Extract<ChartType, 'metric' | 'line' | 'bar'> | null {
  if (groups === 0) return 'metric';
  // Nothing to measure: the earliest and the latest are the table's to read.
  if (groups > 2 || quantities === 0) return null;
  if (dated) return 'line';
  return 'bar';
}

/** Every type, in the order the picker lays them out. */
export const CHART_PICKER_ORDER: readonly ChartType[] = CHART_TYPES;

/** The picker's two groups of tiles (D33 Q54). */
export interface ChartPickerGroups {
  /** 「适合这个结果」: every type that can draw it, then the table. */
  suits: (ChartType | 'table')[];
  /** 「其他图型」: the types it leaves greyed, each with its reason. */
  others: ChartType[];
}

/**
 * The picker's tiles in its two groups: what can draw this result first —
 * the table last among them, since it draws anything — and every other type
 * under 「其他图型」, still a tile, still saying what it lacks (D33 Q54). The
 * split is the fit and nothing else: a definition declares no chart types,
 * because the shape already says which draw, and saying it twice would be
 * two answers that could part. Each group keeps `CHART_PICKER_ORDER`, so a
 * type moving between them lands where a reader expects it.
 */
export function chartPickerGroups(
  fits: Record<ChartType, ChartFit>,
): ChartPickerGroups {
  const suits: (ChartType | 'table')[] = [];
  const others: ChartType[] = [];
  for (const type of CHART_PICKER_ORDER)
    (fits[type]?.available === false ? others : suits).push(type);
  suits.push('table');
  return { suits, others };
}
