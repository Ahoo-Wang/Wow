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
  type ChartType,
  type RecordData,
} from '../model/index.js';
import {
  forwardInTime,
  hostTimeZone,
  timeGroup,
  withoutHoles,
} from './timeAxis.js';
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
  /**
   * Each stage is "reached at least this stage" — itself and every later
   * one added up — rather than the stage's own rows, because the spec asked
   * for it (`FunnelStages.cumulative`). Its values then differ from the
   * table's, and a drawing that does not say so reads as a wrong number.
   */
  cumulative?: true;
}

export interface MetricCardData {
  type: 'metric';
  /**
   * The headline. A number, except for a moment (`momentMetrics`) a source
   * answered as text — the latest of a day kept as `2026-09-18` — which is
   * written out as its column reads it rather than dropped for not being
   * one; nothing is measured, compared or aimed at over a moment.
   */
  value: number | string | null;
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
 * Whether a group the rows lack had no records — so an additive metric over
 * it is 0 — rather than records the query left out, which nobody can put a
 * number on. `at` is the missing group's values by alias, as far as known.
 *
 * A row is left out by two things. `having` (「只保留」) drops groups by
 * their numbers, so under it nothing absent is known to be empty. The limit
 * cuts the rows at the end of the view's sort: a result shorter than the
 * limit is every group there is, and one that fills it may have lost rows —
 * but only past its last row in the sort's leading dimension, so a group
 * whose leading value comes before that row's is still whole. Sorted by day,
 * newest first, thirty days of a longer history are thirty whole days, and
 * a day between two of them with no row is a day with no records.
 */
function absenceReader(
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
): (at: Readonly<Record<string, unknown>>) => boolean {
  if (config.having) return () => false;
  const limit = config.limit;
  if (Number.isInteger(limit) && rows.length < limit) return () => true;
  const lead = config.sort[0]?.alias;
  const last = rows[rows.length - 1];
  if (lead === undefined || last === undefined) return () => false;
  const edge = seriesKey(last[lead]);
  return at => owns(at, lead) && seriesKey(at[lead]) !== edge;
}

/**
 * Whether `record` holds `key` itself: a split value may be any string,
 * `toString` included, and `in` would find that one on every object.
 */
function owns(record: object, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(record, key);
}

/** Whether the metric `alias` names adds up, so a group of nothing is 0. */
function adds(config: AnalysisViewConfig, alias: string): boolean {
  return isAdditiveMetric(
    config.metrics.find(metric => metric.alias === alias),
  );
}

/** What shaping reads besides the config and the rows. */
export interface ShapeContext {
  /**
   * The engine's zone: the one a histogram that names none was cut in, and
   * so the one its missing buckets are stepped in. The host's when left out.
   */
  timeZone?: string;
}

/**
 * `totals` is the one row of the ungrouped totals query, when it ran. Only
 * the metric card reads it: over a trend it is the headline for any metric.
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
        cartesian(chart.type, chart.cartesian, config, rows, timeZone)
      );
    case 'pie':
      return chart.pie && pie(chart.pie, config, rows);
    case 'heatmap':
      return chart.heatmap && heatmap(chart.heatmap, config, rows, timeZone);
    case 'scatter':
      return chart.scatter && scatter(chart.scatter, rows);
    case 'funnel':
      return chart.funnel && funnel(chart.funnel, rows);
    case 'metric':
      return (
        chart.metric && metricCard(chart.metric, config, rows, totals, timeZone)
      );
  }
}

/**
 * A pivot fills every combination its rows lack: 0 for a metric that adds
 * when the combination is known to have had no records (`absenceReader`) —
 * 「华东 has no 已取消」 is a count of zero, and left out it drew as no data,
 * a stacked area of lone dots floating at the stack's height — and nothing
 * otherwise: an average of no records is no number, and a combination the
 * limit or 「只保留」 cut is not known to be empty. A time x runs without
 * holes (`withoutHoles`), each hole filled by the same rule.
 */
function cartesian(
  type: ChartType,
  spec: NonNullable<AnalysisViewConfig['chart']['cartesian']>,
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
  timeZone: string,
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

  const series = [...seriesKeys].map(([key, entry]) => ({ key, ...entry }));
  const absent = absenceReader(config, rows);
  const additive = new Set(
    series.filter(entry => adds(config, entry.metric)).map(entry => entry.key),
  );
  const missing = (
    entry: (typeof series)[number],
    x: unknown,
  ): number | null =>
    additive.has(entry.key) &&
    absent({
      [spec.x]: x,
      ...(spec.splitBy === undefined ? {} : { [spec.splitBy]: entry.value }),
    })
      ? 0
      : null;

  let points = [...byX].map(([x, values]) => ({
    x,
    values: Object.fromEntries(
      series.map(entry => [
        entry.key,
        owns(values, entry.key) ? values[entry.key] : missing(entry, x),
      ]),
    ),
  }));
  const axis = timeGroup(config, spec.x);
  if (axis)
    points = withoutHoles(
      forwardInTime(points, point => point.x),
      point => point.x,
      axis,
      timeZone,
      x => ({
        x,
        values: Object.fromEntries(
          series.map(entry => [entry.key, missing(entry, x)]),
        ),
      }),
    );
  // When time is the split rather than the axis, it is the legend that reads
  // as a sequence — and the palette hands its slots out in that order, so
  // the first day is always the first colour. The axis is then a category
  // and keeps the rows' order, as any category does.
  return {
    type: 'cartesian',
    chart: type,
    points,
    series: timeGroup(config, spec.splitBy)
      ? forwardInTime(series, entry => entry.value)
      : series,
  };
}

/**
 * A pie folds its tail into "other" at `maxSlices`, and at the palette's size
 * when nothing says otherwise — and never past it. The palette holds
 * `CHART_COLOR_SLOTS` colours and a ninth slice would wear the first one
 * again: two wedges one colour, and a legend that cannot say which is which.
 * Folding is only a sum, so a metric that does not add up is left unfolded
 * (validation refuses `maxSlices` on one), and its slices past the palette
 * repeat colours.
 *
 * A pie has no axis, so its slices keep the rows' order even over time;
 * once folded they go largest first, since "the rest" means the smallest.
 */
function pie(
  spec: NonNullable<AnalysisViewConfig['chart']['pie']>,
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
): PieData {
  const slices: PieSlice[] = rows.map(row => ({
    category: row[spec.category],
    value: num(row, spec.value) ?? 0,
  }));
  const folds =
    spec.maxSlices !== undefined ||
    isAdditiveMetric(
      config.metrics.find(metric => metric.alias === spec.value),
    );
  const cap = Math.min(spec.maxSlices ?? CHART_COLOR_SLOTS, CHART_COLOR_SLOTS);
  if (!folds || slices.length <= cap) return { type: 'pie', slices };

  const sorted = [...slices].sort((a, b) => b.value - a.value);
  const kept = sorted.slice(0, cap - 1);
  // Only additive metrics reach this branch, which validation enforces.
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
  return {
    type: 'funnel',
    stages,
    ...(spec.stages.from === 'group' && spec.stages.cumulative === true
      ? { cumulative: true as const }
      : {}),
  };
}

/**
 * The stages a group's values make, in the business order, each its own
 * rows' number — the same number the table and the bar chart show beside
 * that value, which is what Metabase draws too.
 *
 * Accumulating was the default once, on the reading that each object sits
 * in exactly one stage and "reached at least here" is this stage and every
 * later one. That holds for a status an order moves through and for nothing
 * else a category can be: an event's type, a warehouse. The compensation
 * service's 「事件类型分布」 drew 「首次失败 1,831,229」 — the seven types
 * added up — beside a table that said 65.9万. So a funnel accumulates only
 * when asked, and then says so (`FunnelData.cumulative`).
 */
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
  if (stages.cumulative !== true) return ordered;

  // Asked to, each object is read as sitting in exactly one stage, so
  // "reached at least here" is the sum of this stage and every later one.
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
  timeZone: string,
): MetricCardData {
  const trend = spec.trend;
  const headline: RecordData = trend
    ? (totals ?? summed(config, rows))
    : (rows[0] ?? {});

  const value = num(headline, spec.metric);
  const compare = spec.compare ? num(headline, spec.compare.metric) : null;
  const written = headline[spec.metric];
  return {
    type: 'metric',
    value: value ?? (typeof written === 'string' ? written : null),
    ...(spec.compare
      ? {
          compare: {
            value: compare,
            delta: deltaOf(value, compare, spec.compare.mode),
          },
        }
      : {}),
    ...(spec.target === undefined ? {} : { target: spec.target }),
    // The sparkline is a time axis too, and validation holds `trend.x` to a
    // date bucket; the headline and the comparison add up in any order.
    ...(trend
      ? { trend: sparkline(spec, trend.x, config, rows, timeZone) }
      : {}),
  };
}

/**
 * The card's trend, earliest first and without holes as every time axis
 * runs: a quiet day is a dip to 0, not a line drawn straight past it.
 */
function sparkline(
  spec: NonNullable<AnalysisViewConfig['chart']['metric']>,
  x: string,
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
  timeZone: string,
): { x: unknown; value: number | null }[] {
  const points = forwardInTime(
    rows.map(row => ({ x: row[x], value: num(row, spec.metric) })),
    point => point.x,
  );
  const axis = timeGroup(config, x);
  if (!axis) return points;
  const additive = adds(config, spec.metric);
  const absent = absenceReader(config, rows);
  return withoutHoles(
    points,
    point => point.x,
    axis,
    timeZone,
    key => ({
      x: key,
      value: additive && absent({ [x]: key }) ? 0 : null,
    }),
  );
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
