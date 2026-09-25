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
  CartesianSpec,
  ReferenceLine,
  ReferenceStatistic,
} from '../model/index.js';
import type { CartesianData } from './cartesian.js';
import { isPercentStacked } from './chartOptions.js';
import type { CartesianGap } from './derived.js';

/**
 * A reference line where the kernel placed it: a constant as written, a
 * statistic at the number it came to.
 */
export interface PlacedLine extends ReferenceLine {
  value: number;
}

/**
 * Each series' highest and lowest measured value, as point indexes. Only
 * where the series has two measured values that differ: a flat series has
 * no peak to point at.
 */
export type SeriesExtremes = Record<string, { high: number; low: number }>;

/**
 * The values one series measured, by point — a 0 the kernel filled in and
 * a point with no number left out. A statistic of the marks is a statistic
 * of what the rows said: the library's own average line would count every
 * filled-in day as a day of 0 and pull the line down (analysis-echarts.md
 * 5 「内核与库的边界」).
 */
function measured(
  data: CartesianData,
  key: string,
): { index: number; value: number }[] {
  const found: { index: number; value: number }[] = [];
  data.points.forEach((point, index) => {
    const value = point.values[key];
    if (typeof value === 'number' && point.filled?.includes(key) !== true)
      found.push({ index, value });
  });
  return found;
}

/** The mean or the middle of some numbers; there is at least one. */
export function statistic(
  values: readonly number[],
  of: ReferenceStatistic,
): number {
  if (of === 'average')
    return values.reduce((sum, value) => sum + value, 0) / values.length;
  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 1
    ? sorted[middle]
    : (sorted[middle - 1] + sorted[middle]) / 2;
}

/**
 * The spec's reference lines placed over `data`: a constant stays where it
 * is; a statistic is taken of its metric's measured values and stands
 * there. A statistic over a split is left out — the split made one series
 * per value, and one line could not say which it measures — one over a
 * chart of shares, and one over a metric with nothing measured; each says
 * why in `gaps`. A line with neither a value nor a statistic is not drawn
 * (validation refuses it).
 */
export function placeLines(
  spec: CartesianSpec,
  data: CartesianData,
): { lines: PlacedLine[]; gaps: CartesianGap[] } {
  const lines: PlacedLine[] = [];
  const gaps: CartesianGap[] = [];
  for (const line of spec.referenceLines ?? []) {
    if (line.statistic === undefined) {
      if (typeof line.value === 'number')
        lines.push({ ...line, value: line.value });
      continue;
    }
    const metric = line.metric ?? '';
    if (spec.splitBy !== undefined) {
      gaps.push({ kind: line.statistic, metric, gap: 'split' });
      continue;
    }
    // Stacked to 100% the axis is shares, and the metric's own average
    // would stand on it at a height it does not mean.
    if (isPercentStacked(spec, data.chart)) {
      gaps.push({ kind: line.statistic, metric, gap: 'shares' });
      continue;
    }
    const values = measured(data, metric).map(point => point.value);
    if (values.length === 0) {
      gaps.push({ kind: line.statistic, metric, gap: 'none-measured' });
      continue;
    }
    lines.push({ ...line, value: statistic(values, line.statistic) });
  }
  return { lines, gaps };
}

/**
 * The highest and the lowest measured point of every series, the first of
 * equals — `CartesianSpec.extremes`. A filled-in 0 is never the lowest: it
 * is a day with no records, not a day that measured nothing.
 */
export function seriesExtremes(data: CartesianData): SeriesExtremes {
  const extremes: SeriesExtremes = {};
  for (const series of data.series) {
    const points = measured(data, series.key);
    if (points.length < 2) continue;
    let high = points[0];
    let low = points[0];
    for (const point of points) {
      if (point.value > high.value) high = point;
      if (point.value < low.value) low = point;
    }
    if (high.value !== low.value)
      extremes[series.key] = { high: high.index, low: low.index };
  }
  return extremes;
}
