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
  AnalysisViewConfig,
  ChartType,
  RecordData,
} from '../model/index.js';
import { isAdditiveMetric } from './validateChart.js';

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
  | MetricCardData;

export interface CartesianData {
  type: 'cartesian';
  chart: ChartType;
  /** One entry per x value, already pivoted when `splitBy` is configured. */
  points: { x: unknown; values: Record<string, number | null> }[];
  /**
   * One entry per drawn line or bar. `key` is the record key of the points'
   * values and is injective over group values, so it may carry a type tag;
   * `label` is what a legend shows, the value as it prints. A pivoted series
   * also keeps the raw split `value`, for a UI to show as its field does.
   */
  series: { key: string; label: string; metric: string; value?: unknown }[];
}

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

export interface FunnelStage {
  label: string;
  value: number;
  /** Share of the previous stage or of the first, per the configuration. */
  conversion?: number;
}

export interface FunnelData {
  type: 'funnel';
  stages: FunnelStage[];
}

export interface MetricCardData {
  type: 'metric';
  value: number | null;
  compare?: { value: number | null; delta: number | null };
  target?: number;
  trend?: { x: unknown; value: number | null }[];
}

/**
 * A split value's identity as a string key.
 *
 * Two values a query kept apart must stay apart here: `null` and `''` are two
 * series, `1` and `'1'` are two heatmap cells, and a key that merged them let
 * whichever row came second overwrite the first — wrong numbers, no warning.
 * So only a string is its own key, and everything else carries a type tag. A
 * string that holds the tag character doubles it, which is what keeps the two
 * alphabets from meeting: a tagged key's second character is a type letter,
 * an escaped string's is the tag again.
 *
 * A string is left alone rather than tagged too, because this key is also the
 * legend label of a cartesian pivot series, and a group value is a string in
 * every case that reaches a chart legend.
 */
const TYPE_TAG = '\u0001';

/**
 * A group value as text: the one spelling of a category or a split value
 * wherever a string has to name it — the key of `ChartSpec.colors` (see its
 * contract in `model/chart.ts`), a pivot series' legend label, a React key.
 * Nothing and null print as empty, a number or a boolean as `String` has it,
 * anything else as JSON. The kernel labels a split series with it and every
 * chart family looks a pinned colour up by it, so a key written once colours
 * the same category in a pie and in a split.
 */
export function groupKeyText(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean')
    return String(value);
  return JSON.stringify(value) ?? '';
}

function seriesKey(value: unknown): string {
  if (typeof value === 'string')
    return value.includes(TYPE_TAG)
      ? value.split(TYPE_TAG).join(`${TYPE_TAG}${TYPE_TAG}`)
      : value;
  if (value === null) return `${TYPE_TAG}n`;
  if (value === undefined) return `${TYPE_TAG}u`;
  if (typeof value === 'number') return `${TYPE_TAG}d${value}`;
  if (typeof value === 'boolean') return `${TYPE_TAG}b${value}`;
  return `${TYPE_TAG}j${JSON.stringify(value) ?? ''}`;
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

function num(row: RecordData, alias: string): number | null {
  const value = row[alias];
  return typeof value === 'number' ? value : null;
}

/**
 * `totals` is the one row of the ungrouped totals query, when it ran. Only
 * the metric card reads it: over a trend it is the headline for any metric.
 */
export function shapeChart(
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
  totals?: RecordData,
): ChartData | undefined {
  const chart = config.chart;
  switch (chart.type) {
    case 'bar':
    case 'line':
    case 'area':
    case 'combo':
      return chart.cartesian && cartesian(chart.type, chart.cartesian, rows);
    case 'pie':
      return chart.pie && pie(chart.pie, rows);
    case 'heatmap':
      return chart.heatmap && heatmap(chart.heatmap, rows);
    case 'scatter':
      return chart.scatter && scatter(chart.scatter, rows);
    case 'funnel':
      return chart.funnel && funnel(chart.funnel, rows);
    case 'metric':
      return chart.metric && metricCard(chart.metric, config, rows, totals);
  }
}

function cartesian(
  type: ChartType,
  spec: NonNullable<AnalysisViewConfig['chart']['cartesian']>,
  rows: readonly RecordData[],
): CartesianData {
  const byX = new Map<unknown, Record<string, number | null>>();
  const seriesKeys = new Map<
    string,
    { label: string; metric: string; value?: unknown }
  >();

  for (const row of rows) {
    const x = row[spec.x];
    const values = byX.get(x) ?? {};
    for (const series of spec.series) {
      // A pivot names each series by the split value; otherwise by the metric.
      const split = spec.splitBy === undefined ? undefined : row[spec.splitBy];
      const key = spec.splitBy === undefined ? series.metric : seriesKey(split);
      seriesKeys.set(key, {
        label: spec.splitBy === undefined ? series.metric : groupKeyText(split),
        metric: series.metric,
        ...(spec.splitBy === undefined ? {} : { value: split }),
      });
      values[key] = num(row, series.metric);
    }
    byX.set(x, values);
  }

  return {
    type: 'cartesian',
    chart: type,
    points: [...byX].map(([x, values]) => ({ x, values })),
    series: [...seriesKeys].map(([key, entry]) => ({ key, ...entry })),
  };
}

function pie(
  spec: NonNullable<AnalysisViewConfig['chart']['pie']>,
  rows: readonly RecordData[],
): PieData {
  const slices: PieSlice[] = rows.map(row => ({
    category: row[spec.category],
    value: num(row, spec.value) ?? 0,
  }));
  if (spec.maxSlices === undefined || slices.length <= spec.maxSlices)
    return { type: 'pie', slices };

  const sorted = [...slices].sort((a, b) => b.value - a.value);
  const kept = sorted.slice(0, spec.maxSlices - 1);
  // Only additive metrics reach this branch, which validation enforces.
  const other = sorted
    .slice(spec.maxSlices - 1)
    .reduce((total, slice) => total + slice.value, 0);
  return {
    type: 'pie',
    slices: [...kept, { category: null, value: other, other: true }],
  };
}

function heatmap(
  spec: NonNullable<AnalysisViewConfig['chart']['heatmap']>,
  rows: readonly RecordData[],
): HeatmapData {
  const xs: unknown[] = [];
  const ys: unknown[] = [];
  const cells = new Map<string, number | null>();

  for (const row of rows) {
    const x = row[spec.x];
    const y = row[spec.y];
    if (!xs.includes(x)) xs.push(x);
    if (!ys.includes(y)) ys.push(y);
    cells.set(cellKey(y, x), num(row, spec.value));
  }

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

function funnel(
  spec: NonNullable<AnalysisViewConfig['chart']['funnel']>,
  rows: readonly RecordData[],
): FunnelData {
  const raw: FunnelStage[] =
    spec.stages.from === 'metrics'
      ? spec.stages.items.map(item => ({
          label: item.label ?? item.metric,
          value: num(rows[0] ?? {}, item.metric) ?? 0,
        }))
      : stagesFromGroup(spec.stages, rows);

  const stages = withConversion(raw, spec.conversion ?? 'previous');
  return { type: 'funnel', stages };
}

function stagesFromGroup(
  stages: Extract<
    NonNullable<AnalysisViewConfig['chart']['funnel']>['stages'],
    { from: 'group' }
  >,
  rows: readonly RecordData[],
): FunnelStage[] {
  const byCategory = new Map<string, number>();
  for (const row of rows)
    byCategory.set(
      seriesKey(row[stages.category]),
      num(row, stages.value) ?? 0,
    );

  // The configured order names group values, so it is read through the same
  // key: a stage written as `1` is the string `1`, never the number.
  const ordered = stages.order.map(key => ({
    label: key,
    value: byCategory.get(seriesKey(key)) ?? 0,
  }));
  if (stages.cumulative === false) return ordered;

  // Each object sits in exactly one stage, so "reached at least here" is the
  // sum of this stage and every later one.
  let running = 0;
  return [...ordered]
    .reverse()
    .map(stage => {
      running += stage.value;
      return { label: stage.label, value: running };
    })
    .reverse();
}

function withConversion(
  stages: FunnelStage[],
  mode: 'previous' | 'first' | 'none',
): FunnelStage[] {
  if (mode === 'none' || stages.length === 0) return stages;
  const first = stages[0].value;
  return stages.map((stage, index) => {
    if (index === 0) return { ...stage, conversion: 1 };
    const base = mode === 'first' ? first : stages[index - 1].value;
    return {
      ...stage,
      conversion: base === 0 ? undefined : stage.value / base,
    };
  });
}

/**
 * Without a trend the query is ungrouped, so its one row is the headline.
 * With one, the rows are the buckets of the sparkline and the headline is the
 * totals row when its query ran — the ungrouped aggregation, right for any
 * metric — and otherwise the buckets added up, which validation admits only
 * for a metric that adds. Compare and target read the same headline either
 * way, so a trend never drops them.
 */
function metricCard(
  spec: NonNullable<AnalysisViewConfig['chart']['metric']>,
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
  totals: RecordData | undefined,
): MetricCardData {
  const trend = spec.trend;
  const headline: RecordData = trend
    ? (totals ?? summed(config, rows))
    : (rows[0] ?? {});

  const value = num(headline, spec.metric);
  const compare = spec.compare ? num(headline, spec.compare.metric) : null;
  return {
    type: 'metric',
    value,
    ...(spec.compare
      ? {
          compare: {
            value: compare,
            delta: deltaOf(value, compare, spec.compare.mode),
          },
        }
      : {}),
    ...(spec.target === undefined ? {} : { target: spec.target }),
    ...(trend
      ? {
          trend: rows.map(row => ({
            x: row[trend.x],
            value: num(row, spec.metric),
          })),
        }
      : {}),
  };
}

/**
 * The buckets added up, per additive metric. A metric that does not add is
 * left out, so it reads as null rather than as a number that means nothing.
 */
function summed(
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
): RecordData {
  const row: RecordData = {};
  for (const metric of config.metrics) {
    if (!isAdditiveMetric(metric)) continue;
    row[metric.alias] = rows.reduce((sum, bucket) => {
      const value = num(bucket, metric.alias);
      return value === null ? sum : sum + value;
    }, 0);
  }
  return row;
}

function deltaOf(
  value: number | null,
  compare: number | null,
  mode: 'delta' | 'percent',
): number | null {
  if (value === null || compare === null) return null;
  if (mode === 'delta') return value - compare;
  return compare === 0 ? null : (value - compare) / compare;
}
