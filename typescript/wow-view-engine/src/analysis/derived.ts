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
  AnalysisDateUnit,
  AnalysisViewConfig,
  DerivedKind,
  DerivedSeries,
} from '../model/index.js';
import type { CartesianData } from './cartesian.js';
import { isPercentStacked } from './chartOptions.js';
import { timeGroup } from './timeAxis.js';
import { isAdditiveMetric } from './validateChart.js';

/**
 * Why a derived series cannot be computed over this result (D33 Q53). Wow's
 * aggregation has no window functions, so a trend, a moving average and a
 * running total are computed here over the rows that came back — and over
 * rows that are not the whole time axis they are silently wrong. A wrong
 * running total is worse than none, so the kernel draws none and says why:
 *
 * - `split`: a split draws a series per value, and a computed line over one
 *   of them in the foreground's dashes could not say which it follows.
 * - `not-time`: the x is no time dimension; a category has no before.
 * - `shares`: the chart is stacked to 100%, its axis a scale of shares; a
 *   running total of shares is nothing.
 * - `narrowed`: 「只保留」 dropped groups by their numbers.
 * - `cut-short`: the rows are the first N groups of more.
 * - `holes`: the axis has a gap nobody can put a number on — a bucket the
 *   rows lack that is not known to be empty, or buckets the zone could not
 *   step (`CartesianData.timeline` absent).
 * - `not-additive`: a running total of an average is no total of anything.
 * - `too-few`: fewer measured points than the line needs (two for a trend,
 *   the window for a moving average).
 */
export type DerivedGap =
  | 'split'
  | 'not-time'
  | 'shares'
  | 'narrowed'
  | 'cut-short'
  | 'holes'
  | 'not-additive'
  | 'too-few';

/** One computed line, as the drawing takes it: a value per point or none. */
export interface DerivedLine {
  /** Unique among the chart's series keys and derived keys. */
  key: string;
  kind: DerivedKind;
  /** The metric alias of the series it is computed from. */
  metric: string;
  /** A moving average's window, as used. */
  window?: number;
  values: (number | null)[];
}

/**
 * A moving average's window when the analyst gave none: a week of days, a
 * month of weeks, a quarter of months — the period the unit's noise evens
 * out over — and three buckets of anything else.
 */
const DEFAULT_WINDOW: Partial<Record<AnalysisDateUnit, number>> = {
  DAY: 7,
  WEEK: 4,
  MONTH: 3,
  QUARTER: 4,
  HOUR: 24,
};

/** The window a moving average over this config's x runs over. */
export function movingWindow(
  config: AnalysisViewConfig,
  derived: Pick<DerivedSeries, 'window'>,
): number {
  if (derived.window !== undefined) return derived.window;
  const x = config.chart.cartesian?.x;
  const unit = timeGroup(config, x)?.unit;
  return (unit && DEFAULT_WINDOW[unit]) ?? 3;
}

/** A derived series' key: its kind, its metric and — averaged — its window. */
export function derivedKey(
  derived: Pick<DerivedSeries, 'kind' | 'metric'>,
  window?: number,
): string {
  return `\u0002${derived.kind}:${window ?? ''}:${derived.metric}`;
}

/**
 * Why `derived` cannot be drawn over `data`, or `undefined` when it can.
 * `cutShort` is whether the rows are the first groups of more
 * (`AnalysisView.truncated` or `atLimit`). The reasons are checked in the
 * order a reader fixes them: the chart's shape first, then the question,
 * then what came back.
 */
export function derivedGap(
  config: AnalysisViewConfig,
  data: CartesianData,
  derived: Pick<DerivedSeries, 'kind' | 'metric' | 'window'>,
  cutShort: boolean,
): DerivedGap | undefined {
  const spec = config.chart.cartesian;
  if (spec?.splitBy !== undefined) return 'split';
  if (!timeGroup(config, spec?.x)) return 'not-time';
  if (spec && isPercentStacked(spec, data.chart)) return 'shares';
  if (config.having !== undefined) return 'narrowed';
  if (cutShort) return 'cut-short';
  const values = data.points.map(point => point.values[derived.metric]);
  if (data.timeline !== true || values.some(value => typeof value !== 'number'))
    return 'holes';
  if (
    derived.kind === 'cumulative' &&
    !isAdditiveMetric(
      config.metrics.find(metric => metric.alias === derived.metric),
    )
  )
    return 'not-additive';
  const needs =
    derived.kind === 'moving-average' ? movingWindow(config, derived) : 2;
  return values.length < needs ? 'too-few' : undefined;
}

/**
 * The derived line's values over a whole series — `derivedGap` said there
 * is no hole, so every value is a number. A moving average has none before
 * its window fills: a mean of fewer points is another statistic, and
 * drawing it would bend the line's start toward whatever came first.
 */
export function derivedValues(
  values: readonly number[],
  kind: DerivedKind,
  window: number,
): (number | null)[] {
  switch (kind) {
    case 'cumulative': {
      let running = 0;
      return values.map(value => (running += value));
    }
    case 'moving-average': {
      let sum = 0;
      return values.map((value, index) => {
        sum += value;
        if (index >= window) sum -= values[index - window];
        return index + 1 >= window ? sum / window : null;
      });
    }
    case 'trend':
      return trendLine(values);
  }
}

/**
 * The least-squares line through the points, the buckets one step apart —
 * which they are, the axis being a timeline. It has no intercept of its
 * own to say, only a height at each bucket.
 */
function trendLine(values: readonly number[]): number[] {
  const n = values.length;
  const meanX = (n - 1) / 2;
  const meanY = values.reduce((sum, value) => sum + value, 0) / n;
  let across = 0;
  let spread = 0;
  values.forEach((value, index) => {
    across += (index - meanX) * (value - meanY);
    spread += (index - meanX) ** 2;
  });
  const slope = spread === 0 ? 0 : across / spread;
  return values.map((_value, index) => meanY + slope * (index - meanX));
}

/** Each asked-for line computed, or the reason it is not (`shapeCartesian`). */
export function deriveLines(
  config: AnalysisViewConfig,
  data: CartesianData,
  cutShort: boolean,
): { lines: DerivedLine[]; gaps: CartesianGap[] } {
  const lines: DerivedLine[] = [];
  const gaps: CartesianGap[] = [];
  for (const derived of config.chart.cartesian?.derived ?? []) {
    const gap = derivedGap(config, data, derived, cutShort);
    if (gap) {
      gaps.push({
        kind: derived.kind,
        metric: derived.metric,
        gap,
        ...(gap === 'cut-short' ? { limit: config.limit } : {}),
      });
      continue;
    }
    const window =
      derived.kind === 'moving-average'
        ? movingWindow(config, derived)
        : undefined;
    const values = derivedValues(
      data.points.map(point => point.values[derived.metric] as number),
      derived.kind,
      window ?? 0,
    );
    lines.push({
      key: derivedKey(derived, window),
      kind: derived.kind,
      metric: derived.metric,
      ...(window === undefined ? {} : { window }),
      values,
    });
  }
  return { lines, gaps };
}

/**
 * Something the spec asked the chart to draw that the kernel left out, and
 * why: a derived line (`DerivedGap`), or a statistic reference line over a
 * split or over no measured value.
 */
export type CartesianGap =
  | {
      kind: DerivedKind;
      metric: string;
      gap: DerivedGap;
      /** The groups the result was cut at, for `cut-short`. */
      limit?: number;
    }
  | {
      kind: 'average' | 'median';
      metric: string;
      gap: 'split' | 'shares' | 'none-measured';
    };
