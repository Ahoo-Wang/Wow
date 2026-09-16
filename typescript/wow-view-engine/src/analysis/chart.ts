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
  /** Series key per drawn line or bar; a pivot names them by group value. */
  series: { key: string; metric: string }[];
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

/** A split value names its series; anything unprintable becomes an empty key. */
function seriesKey(value: unknown): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean')
    return value.toString();
  return JSON.stringify(value) ?? '';
}

/** Composite key of a heatmap cell; the separator cannot occur in a key. */
function cellKey(y: unknown, x: unknown): string {
  return `${seriesKey(y)}\u0000${seriesKey(x)}`;
}

function num(row: RecordData, alias: string): number | null {
  const value = row[alias];
  return typeof value === 'number' ? value : null;
}

export function shapeChart(
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
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
      return chart.metric && metricCard(chart.metric, rows);
  }
}

function cartesian(
  type: ChartType,
  spec: NonNullable<AnalysisViewConfig['chart']['cartesian']>,
  rows: readonly RecordData[],
): CartesianData {
  const byX = new Map<unknown, Record<string, number | null>>();
  const seriesKeys = new Map<string, string>();

  for (const row of rows) {
    const x = row[spec.x];
    const values = byX.get(x) ?? {};
    for (const series of spec.series) {
      // A pivot names each series by the split value; otherwise by the metric.
      const key =
        spec.splitBy === undefined
          ? series.metric
          : seriesKey(row[spec.splitBy]);
      seriesKeys.set(key, series.metric);
      values[key] = num(row, series.metric);
    }
    byX.set(x, values);
  }

  return {
    type: 'cartesian',
    chart: type,
    points: [...byX].map(([x, values]) => ({ x, values })),
    series: [...seriesKeys].map(([key, metric]) => ({ key, metric })),
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

  const ordered = stages.order.map(key => ({
    label: key,
    value: byCategory.get(key) ?? 0,
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

function metricCard(
  spec: NonNullable<AnalysisViewConfig['chart']['metric']>,
  rows: readonly RecordData[],
): MetricCardData {
  if (spec.trend) {
    const trend = rows.map(row => ({
      x: row[spec.trend?.x ?? ''],
      value: num(row, spec.metric),
    }));
    const total = trend.reduce(
      (sum, point) => (point.value === null ? sum : sum + point.value),
      0,
    );
    return { type: 'metric', value: total, trend };
  }

  const row = rows[0] ?? {};
  const value = num(row, spec.metric);
  const compare = spec.compare ? num(row, spec.compare.metric) : undefined;
  return {
    type: 'metric',
    value,
    ...(spec.compare
      ? {
          compare: {
            value: compare ?? null,
            delta: deltaOf(value, compare ?? null, spec.compare.mode),
          },
        }
      : {}),
    ...(spec.target === undefined ? {} : { target: spec.target }),
  };
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
