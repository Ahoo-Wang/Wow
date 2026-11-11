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
 * The stacks of a cartesian chart: which stack a series stands in and the
 * shares a stack to 100% is drawn as (`stackPlan`), whether its totals have a
 * highest and a lowest to tell apart (`stackPeaksFound`), and each stack's
 * series (`stacksOf`). `cartesianPlan` draws from them.
 */

import {
  isPercentStacked,
  seriesMark,
  type CartesianData,
} from '../../analysis/index.js';
import type { ChartSpec } from '../../model/index.js';
import { axisId } from './axis.js';
import { peaksOf, stackSums, type DrawnSeries } from './cartesianSeries.js';

/** What decides the stack a series stands in. */
export type Stackable = Pick<DrawnSeries, 'side' | 'configured' | 'kind'>;

/**
 * The stacks of a cartesian chart and the shares they are drawn as.
 *
 * A stack as the library draws it is the spec's name on one axis. Series
 * measured against two axes are two scales, and stacking a count on top
 * of an amount drew the count at the amount's height; each axis stacks
 * its own. A line never stacks: its point would stand at the running sum
 * while its label says its own value (「¥640」 at ¥1920), with no band
 * under it to show the part it adds — it draws its own values.
 *
 * Stacked to 100% (`percentStack`): a bar or an area chart only — a
 * combo's line would stand on a scale of shares — whose stacks are then
 * drawn as each part's share of its stack at that category, so every
 * stack reaches the top. The reading table says the same shares.
 */
export function stackPlan(
  data: CartesianData,
  spec: ChartSpec | undefined,
): {
  stackOf(series: Stackable): string | undefined;
  /** Whether the stacks are drawn as shares. */
  percent: boolean;
  /** A series' share of its stack at one point, when it is drawn as one. */
  shareAt(key: string, index: number): number | undefined;
} {
  const cartesian = spec?.cartesian;
  const bySeries = new Map(
    (cartesian?.series ?? []).map(series => [series.metric, series]),
  );
  const stackOf = (series: Stackable) =>
    series.configured?.stack === undefined || series.kind === 'line'
      ? undefined
      : `${series.side}:${series.configured.stack}`;
  const percent =
    cartesian !== undefined &&
    (data.chart === 'bar' || data.chart === 'area') &&
    isPercentStacked(cartesian, data.chart);
  const stackByKey = new Map(
    data.series.map(series => {
      const configured = bySeries.get(series.metric);
      return [
        series.key,
        stackOf({
          side: axisId(configured?.axis),
          configured,
          kind: seriesMark(data.chart, configured),
        }),
      ];
    }),
  );
  const wholes = percent
    ? data.points.map(point => {
        const sums = new Map<string, number>();
        for (const series of data.series) {
          const stack = stackByKey.get(series.key);
          const value = point.values[series.key];
          if (stack !== undefined && typeof value === 'number')
            sums.set(stack, (sums.get(stack) ?? 0) + Math.abs(value));
        }
        return sums;
      })
    : undefined;
  return {
    stackOf,
    percent,
    shareAt(key, index) {
      const stack = stackByKey.get(key);
      const value = data.points[index]?.values[key];
      const whole =
        stack === undefined ? undefined : wholes?.[index]?.get(stack);
      return typeof value === 'number' && whole !== undefined && whole > 0
        ? value / whole
        : undefined;
    },
  };
}

/**
 * Whether 「自动」 writes only the highest and the lowest total over some
 * stack of these bars: one of more than one bar whose totals differ. Where
 * none does, every total is written (`CartesianPlan.totals`).
 */
export function stackPeaksFound(
  data: CartesianData,
  spec: ChartSpec | undefined,
): boolean {
  const { stackOf } = stackPlan(data, spec);
  const bySeries = new Map(
    (spec?.cartesian?.series ?? []).map(series => [series.metric, series]),
  );
  const stacks = new Map<string, { key: string }[]>();
  for (const series of data.series) {
    const configured = bySeries.get(series.metric);
    const kind = seriesMark(data.chart, configured);
    const stack = stackOf({ side: axisId(configured?.axis), configured, kind });
    if (stack !== undefined && kind === 'bar')
      stacks.set(stack, [...(stacks.get(stack) ?? []), series]);
  }
  return [...stacks.values()].some(
    members =>
      members.length > 1 &&
      peaksOf(stackSums(members, data.points)) !== undefined,
  );
}

/** The series of each stack, by the stack's name, in the series' order. */
export function stacksOf(
  series: readonly DrawnSeries[],
  stackOf: (entry: DrawnSeries) => string | undefined,
): Map<string, DrawnSeries[]> {
  const stacks = new Map<string, DrawnSeries[]>();
  for (const entry of series) {
    const stack = stackOf(entry);
    if (stack !== undefined)
      stacks.set(stack, [...(stacks.get(stack) ?? []), entry]);
  }
  return stacks;
}
