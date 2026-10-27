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
  AGGREGATION_LIMITS,
  AggregationDateUnit,
  RunningFunction,
  SortDirection,
  aggregation,
  type AggregationMetric,
  type DerivedExpression,
  type DerivedExpressionDsl,
  type FieldSort,
} from '@ahoo-wang/wow-client/dsl';
import type {
  AnalysisViewConfig,
  DerivedSeries,
  RecordData,
} from '../model/index.js';
import { isAdditiveMetric } from './additive.js';
import { timeGroup } from './timeAxis.js';

/*
 * The derived lines Wow computes over every group (D87): a running total, a
 * running share or a moving average, asked for as a hidden derived metric
 * beside the config's own when the source lists the window it needs. Wow
 * computes a window before `having` and `limit`, so a line it computed is
 * right at every point the rows draw, cut short or narrowed.
 */

type Line = Pick<DerivedSeries, 'kind' | 'metric'>;

/** The hidden metric's alias, written the way a currency companion's is. */
export function serverLineAlias(line: Line, window?: number): string {
  switch (line.kind) {
    case 'cumulative':
      return `${line.metric}__cumulative`;
    case 'cumulative-share':
      return `${line.metric}__share`;
    case 'previous':
      return `${line.metric}__previous`;
    case 'previous-year':
      return `${line.metric}__previousYear`;
    default:
      return `${line.metric}__moving${window ?? ''}`;
  }
}

/** The windows each kind needs from the source, and how many it spends. */
const NEEDS = {
  cumulative: ['RUNNING'],
  'cumulative-share': ['RUNNING', 'TOTAL'],
  'moving-average': ['RUNNING'],
  previous: ['PREVIOUS'],
  'previous-year': ['PREVIOUS'],
} as const;

/**
 * The hidden metrics `compileAnalysis` adds for the chart's derived lines
 * the source computes. A line gets none — and is computed over the rows, as
 * before — when the source lacks the window, when the chart's shape greys
 * it out anyway (a split, a moving average along categories, a running
 * line along categories the result is not sorted by), when its metric is
 * not one a window reads, when its alias is taken, or past Wow's
 * `MAX_WINDOWS`. `offeredWindows` are the functions the source lists
 * (`AnalysisCapability.windows`); `windowOf` is the moving average's window
 * (`movingWindow`).
 */
export function serverLineMetrics(
  offeredWindows: readonly string[],
  config: AnalysisViewConfig,
  windowOf: (line: DerivedSeries) => number,
): AggregationMetric[] {
  const offered = new Set<string>(offeredWindows);
  const spec = config.chart.cartesian;
  if (
    offered.size === 0 ||
    !spec?.derived ||
    spec.splitBy !== undefined ||
    config.groups.length !== 1
  )
    return [];
  const time = timeGroup(config, spec.x) !== undefined;
  const taken = new Set([
    ...config.groups.map(group => group.alias),
    ...config.metrics.map(metric => metric.alias),
  ]);
  let windows = 0;
  const metrics: AggregationMetric[] = [];
  for (const line of spec.derived) {
    if (line.kind === 'trend') continue;
    const metric = config.metrics.find(one => one.alias === line.metric);
    const needs = NEEDS[line.kind];
    const window = line.kind === 'moving-average' ? windowOf(line) : undefined;
    const alias = serverLineAlias(line, window);
    const order = orderOf(config, line, time);
    const reads =
      line.kind === 'moving-average' || isPrevious(line.kind)
        ? time && WINDOWED.has(metric?.type ?? '')
        : isAdditiveMetric(metric);
    if (
      !reads ||
      order === undefined ||
      !needs.every(fn => offered.has(fn)) ||
      taken.has(alias) ||
      windows + needs.length > AGGREGATION_LIMITS.MAX_WINDOWS
    )
      continue;
    taken.add(alias);
    windows += needs.length;
    metrics.push(
      aggregation.derived(d => expressionOf(d, line, order, window), alias),
    );
  }
  return metrics;
}

/**
 * Why a metric card over a trend cannot be measured against the same period
 * a year earlier, or `undefined` when the source computes it: the
 * source lists no `PREVIOUS` (`unsupported`), the trend is cut in weeks — 52
 * weeks back is no week of the year (`week`) — or the headline is no number
 * of the group a window reads, a ratio or a moment (`not-measured`).
 */
export type YearAgoGap = 'unsupported' | 'week' | 'not-measured';

export function yearAgoGap(
  offeredWindows: readonly string[],
  config: AnalysisViewConfig,
  metric: string,
  moments: ReadonlySet<string> = new Set(),
): YearAgoGap | undefined {
  if (!offeredWindows.includes('PREVIOUS')) return 'unsupported';
  const x = config.chart.metric?.trend?.x;
  if (timeGroup(config, x)?.unit === 'WEEK') return 'week';
  const measured = config.metrics.find(one => one.alias === metric);
  return measured && WINDOWED.has(measured.type) && !moments.has(metric)
    ? undefined
    : 'not-measured';
}

/** The hidden metric a card's same period a year earlier is read off. */
export function yearAgoAlias(metric: string): string {
  return serverLineAlias({ kind: 'previous-year', metric });
}

/**
 * The hidden metric a metric card asks for to say its last period against
 * the same period a year earlier: `PREVIOUS(metric, along the trend,
 * a year)`, which Wow reads over the range shifted back a year — so a month
 * to date is compared with the same days of that month a year before. None
 * where the card reads the whole, measures against the period before, or
 * cannot be measured so (`yearAgoGap`), or where the alias is taken.
 */
export function cardLineMetrics(
  offeredWindows: readonly string[],
  config: AnalysisViewConfig,
  moments: ReadonlySet<string> = new Set(),
): AggregationMetric[] {
  const spec = config.chart.type === 'metric' ? config.chart.metric : undefined;
  const trend = spec?.trend;
  if (
    !spec ||
    !trend ||
    trend.against !== 'previous-year' ||
    trend.headline === 'whole' ||
    timeGroup(config, trend.x) === undefined ||
    yearAgoGap(offeredWindows, config, spec.metric, moments) !== undefined
  )
    return [];
  const alias = yearAgoAlias(spec.metric);
  if (
    config.groups.some(group => group.alias === alias) ||
    config.metrics.some(metric => metric.alias === alias)
  )
    return [];
  return [
    aggregation.derived(
      d =>
        d.previous(spec.metric, trend.x, { period: AggregationDateUnit.YEAR }),
      alias,
    ),
  ];
}

/**
 * Every hidden metric the chart asks the source for: the computed lines of
 * a cartesian chart and a metric card's same period a year
 * earlier. A chart that asks for one the rows lack runs again
 * (`useAnalysisResult`), since a chart edit alone never asks the source.
 */
export function hiddenMetrics(
  offeredWindows: readonly string[],
  config: AnalysisViewConfig,
  windowOf: (line: DerivedSeries) => number,
  moments: ReadonlySet<string> = new Set(),
): AggregationMetric[] {
  return [
    ...serverLineMetrics(offeredWindows, config, windowOf),
    ...cardLineMetrics(offeredWindows, config, moments),
  ];
}

/** Whether a derived kind is an earlier period's value of the metric. */
export function isPrevious(kind: DerivedSeries['kind']): boolean {
  return kind === 'previous' || kind === 'previous-year';
}

/** The metric types a window reads: a number of the group, not a derived one. */
const WINDOWED = new Set(['COUNT', 'NUMERIC', 'DISTINCT_COUNT', 'PERCENTILE']);

/**
 * The order a line runs in: along time, forward; along categories, the
 * result's own order, which must be the line's metric (D38's Pareto).
 */
function orderOf(
  config: AnalysisViewConfig,
  line: Line,
  time: boolean,
): FieldSort | undefined {
  const x = config.chart.cartesian?.x;
  // A week has no bucket a year earlier: 52 weeks back is no week of the year.
  if (line.kind === 'previous-year' && timeGroup(config, x)?.unit === 'WEEK')
    return undefined;
  if (time && x !== undefined)
    return { field: x, direction: SortDirection.ASC };
  const first = config.sort[0];
  if (line.kind === 'moving-average' || first?.alias !== line.metric)
    return undefined;
  return {
    field: line.metric,
    direction:
      first.direction === 'ASC' ? SortDirection.ASC : SortDirection.DESC,
  };
}

function expressionOf(
  d: DerivedExpressionDsl,
  line: Line,
  order: FieldSort,
  window: number | undefined,
): DerivedExpression {
  switch (line.kind) {
    case 'cumulative':
      return d.running(line.metric, order);
    case 'cumulative-share':
      return d.divide(d.running(line.metric, order), d.total(line.metric));
    case 'previous':
      return d.previous(line.metric, order.field);
    case 'previous-year':
      return d.previous(line.metric, order.field, {
        period: AggregationDateUnit.YEAR,
      });
    default:
      return d.running(line.metric, order, {
        frame: window,
        function: RunningFunction.AVG,
      });
  }
}

/**
 * A server-computed line's value at each x, read off the rows — or
 * `undefined` when the rows do not carry it (the source computed none, or
 * the query was compiled without it).
 */
export function serverLineValues(
  rows: readonly RecordData[],
  x: string,
  alias: string,
): Map<unknown, number | null> | undefined {
  if (rows.length === 0 || !rows.every(row => alias in row)) return undefined;
  return new Map(
    rows.map(row => {
      const value = row[alias];
      return [
        row[x],
        typeof value === 'number' && Number.isFinite(value) ? value : null,
      ];
    }),
  );
}
