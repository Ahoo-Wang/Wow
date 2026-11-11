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

/**
 * The cartesian family of charts — bar, line, area, combo: its series and
 * axes, the lines derived from a series (`DerivedSeries`), how a missing
 * point reads (`CartesianMissing`), and the reference lines and bands
 * drawn behind the marks, each with its option list.
 */

import type { ValueFormat } from './chart.js';

export interface CartesianSeries {
  /** Metric alias. */
  metric: string;
  /** Required per series when the chart type is `combo`. */
  type?: 'bar' | 'line' | 'area';
  axis?: 'left' | 'right';
  /**
   * Series sharing a stack name are stacked — bars and areas; a line draws
   * its own values whatever it says (`stacks`).
   */
  stack?: string;
  /**
   * How a line or an area joins its points; unwritten, straight from one
   * to the next. `smooth` curves through them; `step` holds each value
   * until the next point and moves there, which is how a balance or a
   * price reads: it stays what it was until something changes it. A bar
   * draws no line and reads neither.
   */
  line?: 'smooth' | 'step';
}

export interface CartesianSpec {
  /** Group alias. */
  x: string;
  /** Second group alias, pivoting one metric into a series per value. */
  splitBy?: string;
  series: CartesianSeries[];
  orientation?: 'vertical' | 'horizontal';
  yAxis?: { left?: AxisSpec; right?: AxisSpec };
  referenceLines?: ReferenceLine[];
  /**
   * What a point the rows lack draws as — a bucket missing from a time axis,
   * a combination missing from a split. Left out or `zero`, the kernel's
   * rule: 0 where the group is known to have had no records and the metric
   * adds up, nothing otherwise (`absenceReader`). `gap`: nothing anywhere, a
   * line broken where the rows are. Drawing only; the query is the same.
   */
  missing?: CartesianMissing;
  /**
   * Stacked bars or areas drawn as each category's shares of its stack, so
   * every stack reaches 100%. Only a stack of metrics that add up has a
   * whole to share; a line or a chart that does not stack ignores it.
   */
  percentStack?: boolean;
  /**
   * Target bands: a stretch of one value axis shaded behind the marks, from
   * one number to another — 「目标区间」, where a value should land.
   */
  referenceBands?: ReferenceBand[];
  /**
   * Marks each drawn series' highest and lowest measured value — never a 0
   * the kernel filled in. A series drawn inside a stack is not marked: its
   * mark stands at the stack's height, not at its own value.
   */
  extremes?: boolean;
  /**
   * Lines computed from one drawn metric over the time axis — a trend, a
   * moving average, a running total — drawn dashed over the marks, never
   * stacked and in no colour slot (D33 batch B). The kernel draws one only
   * over rows that are whole (`derivedGap`, Q53).
   */
  derived?: DerivedSeries[];
}

/**
 * What a derived series computes: the least-squares line through the
 * measured points (`trend`), the mean of each point and the ones before it
 * (`moving-average`, over `window` points), the running total
 * (`cumulative`, a metric that adds up only), and the running total as a
 * share of the whole (`cumulative-share`, 累计占比). The two running kinds
 * also run along a categorical axis the result is sorted by the metric on —
 * the Pareto chart (D38). The metric one bucket earlier (`previous`, 上一期)
 * and a year earlier (`previous-year`, 去年同期) are the source's to compute
 * (D88): only a source whose descriptor lists `PREVIOUS` draws them.
 */
export type DerivedKind =
  | 'trend'
  | 'moving-average'
  | 'cumulative'
  | 'cumulative-share'
  | 'previous'
  | 'previous-year';

/** Every derived kind, in the order the options page offers them. */
export const DERIVED_KINDS = [
  'trend',
  'moving-average',
  'cumulative',
  'cumulative-share',
  'previous',
  'previous-year',
] as const satisfies readonly DerivedKind[];

/** The widest window a moving average takes: a year of days. */
export const MAX_MOVING_WINDOW = 366;

export interface DerivedSeries {
  kind: DerivedKind;
  /** The metric alias of the drawn series it is computed from. */
  metric: string;
  /** A moving average's points, the current one included; 2 or more. */
  window?: number;
}

/** How a missing point reads: as 0, or as a gap in the line. */
export type CartesianMissing = 'zero' | 'gap';

/** The two readings of a missing point, the default first. */
export const CARTESIAN_MISSING = [
  'zero',
  'gap',
] as const satisfies readonly CartesianMissing[];

export interface AxisSpec {
  label?: string;
  min?: number;
  max?: number;
  format?: ValueFormat;
  /**
   * How the axis is stepped: evenly (`linear`, the default) or by powers of
   * ten (`log`), for numbers that span several orders of magnitude. A log
   * axis has no place for 0 or a negative number, so over values that hold
   * one it is drawn linear and says why (D33 batch E).
   */
  scale?: 'linear' | 'log';
}

/**
 * A horizontal rule across the plot: at a constant `value`, or at a
 * `statistic` of one drawn metric's measured values — its average or its
 * median over the points drawn, a filled-in 0 left out (batch B). One of
 * the two places it; a statistic names its `metric`.
 */
export interface ReferenceLine {
  axis: 'left' | 'right';
  value?: number;
  statistic?: ReferenceStatistic;
  /** The metric alias a statistic is taken of. */
  metric?: string;
  label?: string;
}

/** Where a statistic reference line stands: the mean, or the middle value. */
export type ReferenceStatistic = 'average' | 'median';

/** The statistics a reference line may stand at, in the order offered. */
export const REFERENCE_STATISTICS = [
  'average',
  'median',
] as const satisfies readonly ReferenceStatistic[];

/** A stretch of one value axis, `from` below `to`, shaded behind the marks. */
export interface ReferenceBand {
  axis: 'left' | 'right';
  from: number;
  to: number;
  label?: string;
}
