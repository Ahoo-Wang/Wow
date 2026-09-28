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
  CHART_FAMILY,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type RecordData,
  type RuntimeLimits,
} from '../model/index.js';
import type { CartesianData } from './cartesian.js';
import { num, OTHER_SERIES_KEY, seriesKey } from './chartRows.js';
import { limitBounds } from './defaults.js';
import { isAdditiveMetric } from './validateChart.js';

/**
 * A split into more series than the palette has colours (D33 Q56, which
 * settles Q9). Past `CHART_COLOR_SLOTS` two series would wear one colour and
 * a legend could not tell them apart, so a split whose metric adds up draws
 * its seven largest series and one grey 「其他」 for the rest — the pie's
 * 「其他」, the same colour and the same meaning. The rest is not the sum of
 * the series the rows hold past the seventh: the limit may have cut some
 * away. It is the whole of each category, from one more query grouped by
 * the axis alone (`splitWholeConfig`), less the seven kept. A metric that
 * does not add up has no rest to subtract, and is drawn whole, its colours
 * repeating; the display page says so (`CartesianData.crowded`).
 *
 * Internal to the package: the runtime asks `foldsSplit` and runs the query,
 * `shapeCartesian` folds, and `/ui` reads what they left on the data.
 */

/**
 * Whether a config's chart folds its split over these rows: a cartesian
 * chart split by a dimension, its one metric adding up, more split values
 * than colours, and no 「只保留」 — a having drops groups by their numbers,
 * so the axis's whole would count groups the split never had.
 */
export function foldsSplit(
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
): boolean {
  const spec = config.chart.cartesian;
  if (CHART_FAMILY[config.chart.type] !== 'cartesian' || !spec) return false;
  const split = spec.splitBy;
  const metric = spec.series[0]?.metric;
  if (split === undefined || metric === undefined || spec.series.length !== 1)
    return false;
  if (config.having !== undefined) return false;
  if (!isAdditiveMetric(config.metrics.find(one => one.alias === metric)))
    return false;
  const values = new Set(rows.map(row => seriesKey(row[split])));
  return values.size > CHART_COLOR_SLOTS;
}

/**
 * The question the rest is measured by: the same range and metrics, grouped
 * by the chart's axis alone, every category of it asked for — up to the
 * ceiling a query may ask (`limitBounds`: the definition's `maxLimit`, the
 * runtime's `maxAnalysisRows`, Wow's own; D42), since a category the rows
 * draw and this one cut would have no rest — and in no particular order,
 * since it is read by key.
 */
export function splitWholeConfig(
  definition: DataViewDefinition,
  config: AnalysisViewConfig,
  limits?: Pick<RuntimeLimits, 'maxAnalysisRows'>,
): AnalysisViewConfig {
  const x = config.chart.cartesian?.x;
  const ceiling = limitBounds(definition.analysis, limits).max;
  return {
    ...config,
    groups: config.groups.filter(group => group.alias === x),
    sort: config.sort.filter(entry => entry.alias === x),
    // On the ceiling, so no probe row is asked past it (`analysisProbeLimit`).
    limit: ceiling,
    layout: 'table',
    table: { ...config.table, totals: false },
  };
}

/**
 * How big each of `data`'s series is (`data.series`' order), the one measure
 * a split's series are ranked by — for the order a legend reads and a stack
 * builds in (`bySize`), and for which seven a fold keeps (`foldOther`):
 *
 * - the series' own metric: a split draws exactly one
 *   (`chart.splitBy.needs-one-series`), so every series of it measures the
 *   same thing and the sizes compare;
 * - the numbers it measured — a value the kernel filled in (`filled`) is a
 *   known 0 or nothing, never a size — each by its absolute value: a bar
 *   under the axis is as big as one over it, and a channel that lost ¥1M is
 *   not smaller than one that broke even (a profit, a net change);
 * - added up where the metric adds up (`additive`): a total is how much of
 *   the whole the series holds; otherwise their mean, since a total of
 *   averages grows with how many categories a series turns up in, not with
 *   how big its numbers are.
 *
 * Computed from the drawn points, so the order is the engine's own and does
 * not rest on the order a source answers groups in.
 */
export function seriesSizes(
  data: Pick<CartesianData, 'points' | 'series'>,
  additive: boolean,
): number[] {
  return data.series.map(entry => {
    let total = 0;
    let count = 0;
    for (const point of data.points) {
      const value = point.values[entry.key];
      if (typeof value !== 'number' || point.filled?.includes(entry.key))
        continue;
      total += Math.abs(value);
      count += 1;
    }
    return additive || count === 0 ? total : total / count;
  });
}

/**
 * A split's series, the largest first (`seriesSizes`): what a reader looks
 * for first is the biggest, and a stack reads from the bottom up, so the
 * biggest sits at its base and heads the legend — the palette's first
 * colour goes to it. Series of one size keep the order they came in.
 */
export function bySize(
  data: Pick<CartesianData, 'points' | 'series'>,
  additive: boolean,
): CartesianData['series'] {
  const sizes = seriesSizes(data, additive);
  return data.series
    .map((entry, index) => ({ entry, index }))
    .sort((a, b) => sizes[b.index] - sizes[a.index] || a.index - b.index)
    .map(({ entry }) => entry);
}

/**
 * `data` with its series past the palette folded: the seven largest
 * (`seriesSizes`), in the order they were drawn, then 「其他」 —
 * at each category the axis's whole (`whole`, rows of `splitWholeConfig`)
 * less the seven. Where that cannot be known — a kept value not measured,
 * a category the whole did not answer — the rest is drawn as nothing,
 * never as a guess; a category with nothing measured at all is 0, filled
 * in as the kernel fills any known-empty group.
 */
export function foldOther(
  data: CartesianData,
  config: AnalysisViewConfig,
  whole: readonly RecordData[],
): CartesianData {
  const x = config.chart.cartesian?.x;
  const metric = data.series[0]?.metric;
  if (
    x === undefined ||
    metric === undefined ||
    data.series.length <= CHART_COLOR_SLOTS
  )
    return data;
  const sizes = seriesSizes(data, true);
  const kept = new Set(
    data.series
      .map((_, index) => index)
      .sort((a, b) => sizes[b] - sizes[a] || a - b)
      .slice(0, CHART_COLOR_SLOTS - 1),
  );
  const series = data.series.filter((_, index) => kept.has(index));
  const keys = new Set(series.map(entry => entry.key));
  const totals = new Map(
    whole.map(row => [seriesKey(row[x]), num(row, metric)] as const),
  );
  const points = data.points.map(point => {
    const values: Record<string, number | null> = {};
    for (const entry of series) values[entry.key] = point.values[entry.key];
    const measured = data.series.some(
      entry =>
        typeof point.values[entry.key] === 'number' &&
        point.filled?.includes(entry.key) !== true,
    );
    const total = totals.get(seriesKey(point.x));
    const parts = series.map(entry => point.values[entry.key]);
    values[OTHER_SERIES_KEY] = !measured
      ? 0
      : typeof total === 'number' &&
          parts.every(part => typeof part === 'number')
        ? exact(parts.reduce<number>((rest, part) => rest - part, total))
        : null;
    const filled = [
      ...(point.filled ?? []).filter(key => keys.has(key)),
      ...(measured ? [] : [OTHER_SERIES_KEY]),
    ];
    return filled.length > 0
      ? { x: point.x, values, filled }
      : { x: point.x, values };
  });
  return {
    ...data,
    points,
    series: [
      ...series,
      { key: OTHER_SERIES_KEY, label: '', metric, other: true },
    ],
  };
}

/** A difference as the decimal it stands for, without a float's residue. */
function exact(value: number): number {
  return Number(value.toPrecision(12));
}
