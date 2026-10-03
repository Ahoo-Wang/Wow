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

import type { AnalysisViewConfig, RecordData } from '../model/index.js';
import type { BoxplotData } from './boxplot.js';
import type { CandlestickData } from './candlestick.js';
import type { CartesianData } from './cartesian.js';
import { familyRules } from './familyRules.js';
import type { FunnelData } from './funnel.js';
import type { GaugeData } from './gauge.js';
import type { MapData } from './map.js';
import type { HierarchyData, SankeyData } from './hierarchy.js';
import type { MetricCardData } from './metricCard.js';
import type { ParallelData, RadarData } from './profiles.js';
import { hostTimeZone } from './timeAxis.js';
import type { CalendarData, ThemeRiverData } from './timeCharts.js';
import type { TreemapData } from './treemap.js';
import type { WaterfallData } from './waterfall.js';

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
  | CandlestickData
  | GaugeData
  | RadarData
  | ParallelData
  | HierarchyData
  | SankeyData
  | CalendarData
  | ThemeRiverData
  | MapData;

export type { MetricCardData, MetricPeriod } from './metricCard.js';
export { periodRollover } from './metricCard.js';
export type { WaterfallData, WaterfallStep } from './waterfall.js';
export type { TreemapData, TreemapTile } from './treemap.js';
export type { BoxplotBox, BoxplotData } from './boxplot.js';
export type { Candle, CandlestickData } from './candlestick.js';
export type { GaugeData } from './gauge.js';
export type { MapData, MapRegion } from './map.js';
export type {
  CalendarData,
  CalendarDay,
  RiverStream,
  ThemeRiverData,
} from './timeCharts.js';
export type {
  HierarchyData,
  HierarchyNode,
  SankeyData,
  SankeyLink,
  SankeyNode,
} from './hierarchy.js';
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

/** What shaping reads besides the config and the rows. */
export interface ShapeContext {
  /**
   * The metric types the source estimates (`AnalysisProjection.approximate`): a
   * boxplot says its quartiles are approximate when percentiles are among
   * them. Percentiles when left out (`DEFAULT_APPROXIMATE_METRICS`).
   */
  approximate?: readonly string[];
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
   * The rows are the first groups of more (`AnalysisProjection.truncated` or
   * `atLimit`): a line computed across them — a running total, a moving
   * average — would be wrong, and is not drawn (`derivedGap`).
   */
  cutShort?: boolean;
  /**
   * The rows of the query grouped by a split chart's axis alone
   * (`AnalysisProjection.splitWhole`): what a split past the palette folds its
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
  return familyRules(chart.type)?.shape(chart, {
    config,
    rows,
    totals,
    context,
    timeZone,
  });
}
