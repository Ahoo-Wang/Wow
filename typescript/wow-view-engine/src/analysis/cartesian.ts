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
  absenceReader,
  groupKeyText,
  num,
  owns,
  seriesKey,
} from './chartRows.js';
import {
  consecutive,
  forwardInTime,
  timeGroup,
  withoutHoles,
} from './timeAxis.js';
import { deriveLines, type CartesianGap, type DerivedLine } from './derived.js';
import {
  placeLines,
  seriesExtremes,
  type PlacedLine,
  type SeriesExtremes,
} from './references.js';
import { foldOther } from './splitOther.js';
import { isAdditiveMetric } from './validateChart.js';

export interface CartesianData {
  type: 'cartesian';
  chart: ChartType;
  /**
   * One entry per x value, already pivoted when `splitBy` is configured.
   * `filled` names the series whose value here the kernel filled in rather
   * than measured — a day the rows lack, a split combination they lack —
   * so a drawing can tell a known 0 from a counted one (decisions.md D21,
   * Q14): it writes no label on it, and says why in the tooltip.
   */
  points: {
    x: unknown;
    values: Record<string, number | null>;
    filled?: string[];
  }[];
  /**
   * One entry per drawn line or bar. `key` is the record key of the points'
   * values and is injective over group values, so it may carry a type tag;
   * `label` is what a legend shows, the value as it prints. A pivoted series
   * also keeps the raw split `value`, for a UI to show as its field does.
   */
  series: {
    key: string;
    label: string;
    metric: string;
    value?: unknown;
    /**
     * The split's folded rest (D33 Q56): every split value past the seven
     * drawn, at each category the axis's whole less them (`foldOther`). No
     * group of the rows, so it names no value and no press follows it up.
     */
    other?: true;
  }[];
  /**
   * The x is a time axis whose points are its buckets one after another —
   * earliest first, no bucket skipped (`consecutive`), the missing value's
   * last — so two points side by side are a bucket and the one before it
   * (`bucketChange`). Absent where the axis is not time, or where a hole
   * could not be placed and the points may skip one.
   */
  timeline?: true;
  /**
   * The spec's reference lines where the kernel placed them — a statistic
   * at the number it came to over the measured values (`placeLines`).
   * Absent when the spec has none.
   */
  references?: PlacedLine[];
  /**
   * The lines computed from a drawn metric over the time axis (D33 batch B):
   * a trend, a moving average, a running total. Absent when none is drawn.
   */
  derived?: DerivedLine[];
  /** Each series' highest and lowest measured point, when the spec asks. */
  extremes?: SeriesExtremes;
  /**
   * What the spec asked for that the kernel did not draw, each with why —
   * a running total over rows cut short, an average over a split (Q53).
   */
  gaps?: CartesianGap[];
  /**
   * More series than the palette has colours, drawn all the same, so some
   * wear one colour twice: a split whose metric does not add up has no rest
   * to fold into 「其他」 (D33 Q56), and the display page suggests another
   * reading. Absent where every series has a colour of its own.
   */
  crowded?: true;
}

/** Whether the metric `alias` names adds up, so a group of nothing is 0. */
function adds(config: AnalysisViewConfig, alias: string): boolean {
  return isAdditiveMetric(
    config.metrics.find(metric => metric.alias === alias),
  );
}

/**
 * A pivot fills every combination its rows lack: 0 for a metric that adds
 * when the combination is known to have had no records (`absenceReader`) —
 * 「华东 has no 已取消」 is a count of zero, and left out it drew as no data,
 * a stacked area of lone dots floating at the stack's height — and nothing
 * otherwise: an average of no records is no number, and a combination the
 * limit or 「只保留」 cut is not known to be empty. A time x runs without
 * holes (`withoutHoles`), each hole filled by the same rule. The spec's
 * `missing: 'gap'` fills every one of them with nothing instead; the holes
 * still stand on the axis, where their time is.
 */
export function shapeCartesian(
  type: ChartType,
  spec: NonNullable<AnalysisViewConfig['chart']['cartesian']>,
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
  timeZone: string,
  cutShort = false,
  splitWhole?: readonly RecordData[],
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
  // Asked to leave them empty (`missing: 'gap'`), nothing is filled at all:
  // the analyst knows the rows better than the rule, and a line broken where
  // they end is a drawing choice — the rows and the query are the same.
  const fills = spec.missing !== 'gap';
  const missing = (
    entry: (typeof series)[number],
    x: unknown,
  ): number | null =>
    fills &&
    additive.has(entry.key) &&
    absent({
      [spec.x]: x,
      ...(spec.splitBy === undefined ? {} : { [spec.splitBy]: entry.value }),
    })
      ? 0
      : null;

  // A histogram the source filled itself (`dense`) answers an empty bucket
  // as Wow's `EmptyAggregationValues` does: counts 0, value metrics null.
  // Every bucket it answers is one it cut, and a sum over records that are
  // there is never null — so a null there is a bucket known to be empty,
  // and a metric that adds is 0 over it, filled and named so (Q14), as a
  // hole the kernel places itself is. What does not add stays no number.
  const sourceFilled = fills && timeGroup(config, spec.x)?.dense === true;

  /** A point at `x`: the values measured, the rest filled and named so. */
  const pointAt = (
    x: unknown,
    values: Record<string, number | null>,
  ): CartesianData['points'][number] => {
    const filled: string[] = [];
    const drawn = Object.fromEntries(
      series.map(entry => {
        if (owns(values, entry.key)) {
          const value = values[entry.key];
          if (value !== null || !sourceFilled || !additive.has(entry.key))
            return [entry.key, value];
          filled.push(entry.key);
          return [entry.key, 0];
        }
        const value = missing(entry, x);
        if (value !== null) filled.push(entry.key);
        return [entry.key, value];
      }),
    );
    return filled.length > 0
      ? { x, values: drawn, filled }
      : { x, values: drawn };
  };
  let points = [...byX].map(([x, values]) => pointAt(x, values));
  const axis = timeGroup(config, spec.x);
  if (axis)
    points = withoutHoles(
      forwardInTime(points, point => point.x),
      point => point.x,
      axis,
      timeZone,
      x => pointAt(x, {}),
    );
  // When time is the split rather than the axis, it is the legend that reads
  // as a sequence — and the palette hands its slots out in that order, so
  // the first day is always the first colour. The axis is then a category
  // and keeps the rows' order, as any category does.
  const shaped: CartesianData = {
    type: 'cartesian',
    chart: type,
    points,
    ...(axis && consecutive(points, point => point.x, axis, timeZone)
      ? { timeline: true as const }
      : {}),
    series: timeGroup(config, spec.splitBy)
      ? forwardInTime(series, entry => entry.value)
      : series,
  };
  // Past the palette, a split that adds up folds its rest into 「其他」
  // where the axis's whole is known (`foldOther`); anything else is drawn
  // whole, and says its colours repeat.
  const data = splitWhole ? foldOther(shaped, config, splitWhole) : shaped;
  const crowded = data.series.length > CHART_COLOR_SLOTS;
  return {
    ...data,
    ...drawnOver(config, data, cutShort),
    ...(crowded ? { crowded: true as const } : {}),
  };
}

/**
 * What a chart draws over its marks, from the marks' own numbers: the
 * reference lines placed, the derived lines computed, the extremes found,
 * and what could not be. Each member only where the spec asked for it.
 */
function drawnOver(
  config: AnalysisViewConfig,
  data: CartesianData,
  cutShort: boolean,
): Partial<CartesianData> {
  const spec = config.chart.cartesian;
  if (!spec) return {};
  const placed = placeLines(spec, data);
  const derived = deriveLines(config, data, cutShort);
  const extremes = spec.extremes === true ? seriesExtremes(data) : {};
  const gaps = [...placed.gaps, ...derived.gaps];
  return {
    ...(placed.lines.length > 0 ? { references: placed.lines } : {}),
    ...(derived.lines.length > 0 ? { derived: derived.lines } : {}),
    ...(Object.keys(extremes).length > 0 ? { extremes } : {}),
    ...(gaps.length > 0 ? { gaps } : {}),
  };
}
