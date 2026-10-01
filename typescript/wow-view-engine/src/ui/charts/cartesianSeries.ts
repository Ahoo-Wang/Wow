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

import { seriesMark, type CartesianData } from '../../analysis/index.js';
import type { CartesianSeries } from '../../model/index.js';
import { axisId } from './axis.js';
import type { CartesianContext } from './cartesianPlan.js';
import { color, heldTones, OTHER_COLOR, pinnedColor } from './palette.js';

/**
 * `data` without the series `hidden` names — what the marks, the scales and
 * the reading table say once the legend switched them off. The same object
 * when nothing is hidden.
 */
export function withoutHidden(
  data: CartesianData,
  hidden: ReadonlySet<string> | undefined,
): CartesianData {
  if (!hidden || hidden.size === 0) return data;
  const series = data.series.filter(series => !hidden.has(series.key));
  // A derived line goes with the series it is computed from, and on its own.
  const derived = data.derived?.filter(
    line =>
      !hidden.has(line.key) && series.some(entry => entry.key === line.metric),
  );
  return { ...data, series, ...(data.derived ? { derived } : {}) };
}

/** One series as drawn: the kernel's, and what the spec says about it. */
export interface DrawnSeries {
  key: string;
  metric: string;
  /** Its name in the legend and the tooltip. */
  name: string;
  /** A CSS colour: a theme slot or the one the spec pinned. */
  color: string;
  side: 'left' | 'right';
  configured?: CartesianSeries;
  /** The mark: the chart's own, or — in a combo — the one the spec names. */
  kind: 'bar' | 'line' | 'area';
}

/** The series as the legend, the tooltip and the marks all name them. */
export function drawnSeries(
  data: CartesianData,
  {
    spec,
    label,
    column,
    seriesName = label,
    words,
    toneOf,
  }: Pick<
    CartesianContext,
    'spec' | 'label' | 'column' | 'seriesName' | 'words' | 'toneOf'
  >,
): DrawnSeries[] {
  const bySeries = new Map(
    (spec?.cartesian?.series ?? []).map(series => [series.metric, series]),
  );
  // A pivoted series by a toned value wears the tone, as its badge does;
  // the folded rest and an unpivoted series have no value to have one.
  const split = spec?.cartesian?.splitBy;
  const tones = heldTones(
    data.series.map(series =>
      series.other === true || series.value === undefined
        ? undefined
        : toneOf?.(split, series.value),
    ),
  );
  return data.series.map((series, index) => {
    const configured = bySeries.get(series.metric);
    return {
      key: series.key,
      metric: series.metric,
      // A pivoted series shows its split value as that field shows it —
      // with the field, where the value alone says nothing of what (a yes
      // or a no); an unpivoted one is its column's title — 「金额的总和」,
      // never the alias, which names the query.
      name:
        series.other === true
          ? (words?.other ?? series.label)
          : series.value === undefined
            ? (column(series.metric) ?? series.label)
            : seriesName(spec?.cartesian?.splitBy, series.value),
      // The spec names a pivoted series by its split value as the kernel
      // labels it, and an unpivoted one by its metric alias. The folded
      // rest is the pie's grey: no category, so nothing pins its colour.
      color:
        series.other === true
          ? OTHER_COLOR
          : (pinnedColor(spec, series.label, series.metric) ??
            tones[index] ??
            color(index)),
      side: axisId(configured?.axis),
      configured,
      kind: seriesMark(data.chart, configured),
    };
  });
}

/**
 * A stack's total at each point: the sum of its parts that measured
 * something. A stack made only of filled parts measured nothing: `null`.
 */
export function stackSums(
  members: readonly { key: string }[],
  points: CartesianData['points'],
): (number | null)[] {
  return points.map(point => {
    const parts = members
      .filter(member => !point.filled?.includes(member.key))
      .map(member => point.values[member.key])
      .filter((value): value is number => typeof value === 'number');
    return parts.length > 0
      ? parts.reduce((sum, value) => sum + value, 0)
      : null;
  });
}

/**
 * The places of the highest and the lowest of some numbers, the first of
 * each where several tie; nothing where there are fewer than two numbers,
 * or all are equal, which have no peak and trough to tell apart.
 */
export function peaksOf(
  values: readonly (number | null)[],
): { high: number; low: number } | undefined {
  let high = -1;
  let low = -1;
  values.forEach((value, index) => {
    if (value === null) return;
    if (high === -1 || value > (values[high] ?? 0)) high = index;
    if (low === -1 || value < (values[low] ?? 0)) low = index;
  });
  return high === -1 || high === low ? undefined : { high, low };
}
