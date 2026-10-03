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
  seriesMark,
  type CartesianData,
  type DerivedLine,
} from '../../analysis/index.js';
import { isRunning } from '../../analysis/derived.js';
import type { CartesianSeries, ChartSpec } from '../../model/index.js';
import { axisId } from './axis.js';
import type { CartesianContext, CartesianPlan } from './cartesianPlan.js';
import type { ValueLabel } from './family.js';
import type { MarkWords } from './markWords.js';
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

/** A side of the plot, and so of a value axis. */
type Side = 'left' | 'right';

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

/** The target bands a cartesian spec draws. */
export type CartesianBands = NonNullable<
  NonNullable<ChartSpec['cartesian']>['referenceBands']
>;

/**
 * The highest and the lowest a mark or a reference line reaches on one
 * axis: a stack reaches the sum of its parts on either side of zero,
 * anything else its value. The axis starts at zero, so neither is ever
 * past it the wrong way. A line past the marks is a threshold the reader
 * set, and the axis stretches to it rather than letting it fall off.
 */
export function reachOf(
  side: Side,
  withLines: boolean,
  {
    data,
    series,
    drawnAt,
    stackOf,
    lines,
    bands,
    derived,
  }: Pick<
    CartesianPlan,
    'data' | 'series' | 'drawnAt' | 'stackOf' | 'lines' | 'derived'
  > & { bands: CartesianBands },
): { high: number; low: number } {
  let high = 0;
  let low = 0;
  for (const [index] of data.points.entries()) {
    const sums = new Map<string | undefined, { up: number; down: number }>();
    for (const entry of series.filter(one => one.side === side)) {
      const value = drawnAt(entry, index);
      if (typeof value !== 'number') continue;
      const stack = stackOf(entry);
      if (stack === undefined) {
        high = Math.max(high, value);
        low = Math.min(low, value);
        continue;
      }
      const sum = sums.get(stack) ?? { up: 0, down: 0 };
      if (value > 0) sum.up += value;
      else sum.down += value;
      sums.set(stack, sum);
    }
    for (const { up, down } of sums.values()) {
      high = Math.max(high, up);
      low = Math.min(low, down);
    }
  }
  if (!withLines) return { high, low };
  const over = [
    ...lines.filter(one => axisId(one.axis) === side).map(one => one.value),
    ...bands
      .filter(band => axisId(band.axis) === side)
      .flatMap(band => [band.from, band.to]),
    ...derived
      .filter(line => line.side === side)
      .flatMap(line => line.values)
      .filter((value): value is number => value !== null),
  ].filter(Number.isFinite);
  for (const value of over) {
    high = Math.max(high, value);
    low = Math.min(low, value);
  }
  return { high, low };
}

/**
 * The total over each stack of bars that writes one, every total or, where
 * only the peaks are written, the highest and the lowest with their words.
 */
export function stackTotals(
  stacks: DrawnSeries[][],
  totalsOn: (members: readonly DrawnSeries[]) => boolean,
  totalPeaksOnly: boolean,
  data: CartesianData,
  label: ValueLabel,
  words: MarkWords | undefined,
): CartesianPlan['totals'] {
  return stacks
    .map(members => members.filter(member => member.kind === 'bar'))
    .filter(members => totalsOn(members))
    .map(members => {
      const sums = stackSums(members, data.points);
      const every = sums.map(sum =>
        sum === null ? '' : label(members[0].metric, sum, true),
      );
      // Totals all equal, or one stack among window-filled ones, have no
      // highest and lowest to tell apart: each is written, as bars are.
      const peaks = totalPeaksOnly ? peaksOf(sums) : undefined;
      if (peaks === undefined)
        return { members, texts: every, every, peaksOnly: false };
      const said = (index: number, word: string | undefined) =>
        word === undefined ? every[index] : `${word} ${every[index]}`;
      const texts = every.map((_text, index) =>
        index === peaks.high
          ? said(index, words?.high)
          : index === peaks.low
            ? said(index, words?.low)
            : '',
      );
      return { members, texts, every, peaksOnly: true };
    });
}

/** A derived line as drawn: the kernel's, its name and its axis. */
export interface DrawnDerived extends DerivedLine {
  name: string;
  side: Side;
}

/**
 * The derived lines as drawn, each named and on its axis: every one, as the
 * legend lists them, and the ones drawn — not switched off, and of a series
 * on screen.
 */
export function derivedLines(
  data: CartesianData,
  legend: DrawnSeries[],
  series: DrawnSeries[],
  hidden: ReadonlySet<string> | undefined,
  words: MarkWords | undefined,
): { derivedLegend: DrawnDerived[]; derived: DrawnDerived[] } {
  const sideOf = (metric: string) =>
    legend.find(entry => entry.metric === metric)?.side ?? 'left';
  // A running total outgrows the numbers it adds up — ninety days of sales
  // flattened every day's bar to the floor under it — so it takes the other
  // axis, on a scale of its own, where no series stands there; a running
  // share is no quantity of the series at all, and takes it the same way,
  // as a scale of shares (the Pareto line, D38). A trend and a moving
  // average keep their series' scale: they are read against it.
  const other = (side: Side): Side => (side === 'left' ? 'right' : 'left');
  const derivedSide = (line: DerivedLine): Side => {
    const own = sideOf(line.metric);
    return isRunning(line.kind) &&
      !legend.some(entry => entry.side === other(own))
      ? other(own)
      : own;
  };
  const derivedLegend: DrawnDerived[] = (data.derived ?? []).map(line => ({
    ...line,
    side: derivedSide(line),
    name:
      words?.derived(
        line,
        series.length + (hidden?.size ?? 0) > 1
          ? legend.find(entry => entry.key === line.metric)?.name
          : undefined,
      ) ?? line.metric,
  }));
  const derived = derivedLegend.filter(
    line =>
      !hidden?.has(line.key) && series.some(entry => entry.key === line.metric),
  );
  return { derivedLegend, derived };
}
