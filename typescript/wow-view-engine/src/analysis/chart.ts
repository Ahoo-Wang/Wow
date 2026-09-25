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
  CHART_COLOR_SLOTS,
  type AnalysisViewConfig,
  type RecordData,
} from '../model/index.js';
import { shapeBoxplot, type BoxplotData } from './boxplot.js';
import { shapeCartesian, type CartesianData } from './cartesian.js';
import { num, seriesKey } from './chartRows.js';
import { shapeFunnel, type FunnelData } from './funnel.js';
import { shapeGauge, type GaugeData } from './gauge.js';
import { metricCard, type MetricCardData } from './metricCard.js';
import {
  shapeParallel,
  shapeRadar,
  type ParallelData,
  type RadarData,
} from './profiles.js';
import {
  forwardInTime,
  hostTimeZone,
  timeGroup,
  withoutHoles,
} from './timeAxis.js';
import { shapeTreemap, type TreemapData } from './treemap.js';
import { shapeWaterfall, type WaterfallData } from './waterfall.js';

/**
 * What a renderer receives. The shaping a chart needs happens here rather than
 * in a component, so the same numbers reach any UI and the rules stay testable
 * without a DOM.
 */
export type ChartData =
  | CartesianData
  | PieData
  | HeatmapData
  | ScatterData
  | FunnelData
  | MetricCardData
  | WaterfallData
  | TreemapData
  | BoxplotData
  | GaugeData
  | RadarData
  | ParallelData;

export type { MetricCardData, MetricPeriod } from './metricCard.js';
export { periodRollover } from './metricCard.js';
export type { WaterfallData, WaterfallStep } from './waterfall.js';
export type { TreemapData, TreemapTile } from './treemap.js';
export type { BoxplotBox, BoxplotData } from './boxplot.js';
export type { GaugeData } from './gauge.js';
export type { ParallelData, ChartProfile, RadarData } from './profiles.js';
export type { CartesianData } from './cartesian.js';
// Named because `CartesianData` names them; the helpers that compute them
// stay inside the package (`/ui` imports `derived.ts` itself).
export type { CartesianGap, DerivedGap, DerivedLine } from './derived.js';
export type { PlacedLine, SeriesExtremes } from './references.js';
export type { FunnelData, FunnelStage } from './funnel.js';
export { groupKeyText } from './chartRows.js';

export interface PieSlice {
  category: unknown;
  value: number;
  /** The merged remainder rather than a queried category. */
  other?: boolean;
}

export interface PieData {
  type: 'pie';
  slices: PieSlice[];
}

export interface HeatmapData {
  type: 'heatmap';
  xs: unknown[];
  ys: unknown[];
  /** `cells[y][x]`, null where the query returned no row. */
  cells: (number | null)[][];
}

export interface ScatterData {
  type: 'scatter';
  points: { category: unknown; x: number; y: number; size?: number }[];
}

/**
 * Composite key of a heatmap cell. The row key is length-prefixed rather than
 * separated by a character, because no character is barred from a group value
 * and a separator one of them held would split the pair somewhere else.
 */
function cellKey(y: unknown, x: unknown): string {
  const row = seriesKey(y);
  return `${row.length}:${row}${seriesKey(x)}`;
}

/** What shaping reads besides the config and the rows. */
export interface ShapeContext {
  /**
   * The engine's zone: the one a histogram that names none was cut in, and
   * so the one its missing buckets are stepped in. The host's when left out.
   */
  timeZone?: string;
  /**
   * When the question was asked — the moment its relative conditions
   * resolved against. A trend card's headline is the last period that had
   * ended by then; left out, every bucket counts as ended.
   */
  now?: Date;
  /**
   * The rows are the first groups of more (`AnalysisView.truncated` or
   * `atLimit`): a line computed across them — a running total, a moving
   * average — would be wrong, and is not drawn (`derivedGap`).
   */
  cutShort?: boolean;
  /**
   * The rows of the query grouped by a split chart's axis alone
   * (`AnalysisView.splitWhole`): what a split past the palette folds its
   * rest into 「其他」 against (D33 Q56). Left out, nothing is folded.
   */
  splitWhole?: readonly RecordData[];
}

/**
 * `totals` is the one row of the ungrouped totals query, when it ran. Only
 * the metric card reads it: over a trend read as the whole, it is the
 * headline for any metric.
 *
 * Every time axis — a cartesian chart's x, a heatmap's rows or columns, the
 * card's sparkline — and a series split by time run earliest first
 * (`forwardInTime`); everything else keeps the order the rows came in, which
 * is the view's sort, because a category has no order of its own to restore.
 * A time axis also runs without holes (`withoutHoles`), and what fills a
 * missing bucket or a missing split — 0 or nothing — is `absenceReader`'s
 * and the metric's to say.
 */
export function shapeChart(
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
  totals?: RecordData,
  context: ShapeContext = {},
): ChartData | undefined {
  const chart = config.chart;
  const timeZone = context.timeZone ?? hostTimeZone();
  switch (chart.type) {
    case 'bar':
    case 'line':
    case 'area':
    case 'combo':
      return (
        chart.cartesian &&
        shapeCartesian(
          chart.type,
          chart.cartesian,
          config,
          rows,
          timeZone,
          context.cutShort,
          context.splitWhole,
        )
      );
    case 'pie':
      return chart.pie && pie(chart.pie, rows);
    case 'heatmap':
      return chart.heatmap && heatmap(chart.heatmap, config, rows, timeZone);
    case 'scatter':
      return chart.scatter && scatter(chart.scatter, rows);
    case 'funnel':
      return chart.funnel && shapeFunnel(chart.funnel, rows);
    case 'metric':
      return (
        chart.metric &&
        metricCard(chart.metric, config, rows, totals, {
          timeZone,
          now: context.now,
        })
      );
    case 'waterfall':
      return chart.waterfall && shapeWaterfall(chart.waterfall, config, rows);
    case 'treemap':
      return chart.treemap && shapeTreemap(chart.treemap, rows);
    case 'boxplot':
      return chart.boxplot && shapeBoxplot(chart.boxplot, config, rows);
    case 'gauge':
      return chart.gauge && shapeGauge(chart.gauge, rows);
    case 'radar':
      return chart.radar && shapeRadar(chart.radar, config, rows);
    case 'parallel':
      return chart.parallel && shapeParallel(chart.parallel, config, rows);
  }
}

/**
 * A pie folds its tail into "other" at `maxSlices`, and at the palette's size
 * when nothing says otherwise — and never past it. The palette holds
 * `CHART_COLOR_SLOTS` colours and a ninth slice would wear the first one
 * again: two wedges one colour, and a legend that cannot say which is which.
 * Folding is only a sum, which every pie can take: its metric adds up, or it
 * is no pie (`chart.pie.not-additive`, D33 Q56).
 *
 * A pie has no axis, so its slices keep the rows' order even over time;
 * once folded they go largest first, since "the rest" means the smallest.
 */
function pie(
  spec: NonNullable<AnalysisViewConfig['chart']['pie']>,
  rows: readonly RecordData[],
): PieData {
  const slices: PieSlice[] = rows.map(row => ({
    category: row[spec.category],
    value: num(row, spec.value) ?? 0,
  }));
  const cap = Math.min(spec.maxSlices ?? CHART_COLOR_SLOTS, CHART_COLOR_SLOTS);
  if (slices.length <= cap) return { type: 'pie', slices };

  const sorted = [...slices].sort((a, b) => b.value - a.value);
  const kept = sorted.slice(0, cap - 1);
  const other = sorted
    .slice(cap - 1)
    .reduce((total, slice) => total + slice.value, 0);
  return {
    type: 'pie',
    slices: [...kept, { category: null, value: other, other: true }],
  };
}

/**
 * A time row or column runs without holes too, as any time axis does; the
 * cells of a bucket that had no rows are empty, as every cell the query
 * returned no row for is — a heatmap draws "no group" as no cell.
 */
function heatmap(
  spec: NonNullable<AnalysisViewConfig['chart']['heatmap']>,
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
  timeZone: string,
): HeatmapData {
  let xs: unknown[] = [];
  let ys: unknown[] = [];
  const cells = new Map<string, number | null>();

  for (const row of rows) {
    const x = row[spec.x];
    const y = row[spec.y];
    if (!xs.includes(x)) xs.push(x);
    if (!ys.includes(y)) ys.push(y);
    cells.set(cellKey(y, x), num(row, spec.value));
  }
  // Columns run left to right and rows top to bottom, so either one over
  // time reads forward; the cells follow, being looked up by key.
  const across = timeGroup(config, spec.x);
  const down = timeGroup(config, spec.y);
  const same = (key: unknown) => key;
  if (across)
    xs = withoutHoles(forwardInTime(xs, same), same, across, timeZone, same);
  if (down)
    ys = withoutHoles(forwardInTime(ys, same), same, down, timeZone, same);

  return {
    type: 'heatmap',
    xs,
    ys,
    cells: ys.map(y => xs.map(x => cells.get(cellKey(y, x)) ?? null)),
  };
}

function scatter(
  spec: NonNullable<AnalysisViewConfig['chart']['scatter']>,
  rows: readonly RecordData[],
): ScatterData {
  return {
    type: 'scatter',
    points: rows.map(row => ({
      category: row[spec.category],
      x: num(row, spec.x) ?? 0,
      y: num(row, spec.y) ?? 0,
      ...(spec.size === undefined ? {} : { size: num(row, spec.size) ?? 0 }),
    })),
  };
}
