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

import type { DerivedKind } from '../../model/index.js';
import { axisId } from './axis.js';
import type { CartesianPlan, DrawnSeries } from './cartesianPlan.js';
import type { ChartTheme } from './theme.js';

type Side = 'left' | 'right';

/** How a mark names the value axis it stands on, for the plan's direction. */
export type AxisIndex = (side: Side) => object;

/**
 * What a cartesian chart draws over its marks (D33 batch B), every number
 * the kernel's: reference lines — a constant, or a statistic at the number
 * it came to — and target bands, one carrier per axis; the highest and
 * lowest point of a series; the derived lines. None of them takes a colour
 * slot: the rules and bands are the muted ink, a derived line the
 * foreground in dashes.
 */

/**
 * The reference lines and target bands of each axis, on one carrier: a
 * series with no points of its own, so it takes no slot beside the bars in
 * a category's band. A statistic line with no caption of its own says what
 * it is and where it stands, 「平均 ¥1,234」; a constant one says its
 * caption or nothing, as before.
 */
export function referenceSeries(
  plan: CartesianPlan,
  theme: ChartTheme,
  axisIndex: AxisIndex,
): object[] {
  const { context, horizontal, sides } = plan;
  const lines = plan.lines;
  const bands = context.spec?.cartesian?.referenceBands ?? [];
  const at = (value: number) =>
    horizontal ? { xAxis: value } : { yAxis: value };
  const captionOf = (line: (typeof lines)[number]) => {
    if (line.label !== undefined || line.statistic === undefined)
      return line.label ?? '';
    const value = context.label(line.metric, line.value, true);
    return context.words?.statistic(line.statistic, value) ?? value;
  };
  return sides
    .map(side => ({
      side,
      drawn: lines.filter(line => axisId(line.axis) === side),
      shaded: bands.filter(band => axisId(band.axis) === side),
    }))
    .filter(({ drawn, shaded }) => drawn.length > 0 || shaded.length > 0)
    .map(({ side, drawn, shaded }) => ({
      type: 'line',
      ...axisIndex(side),
      data: [],
      silent: true,
      tooltip: { show: false },
      ...(drawn.length > 0
        ? {
            markLine: {
              symbol: 'none',
              silent: true,
              animation: false,
              lineStyle: { color: theme.muted, type: [4, 4], width: 1 },
              // A halo of the ground keeps the caption legible where a mark
              // runs through it.
              label: {
                position: 'insideEndTop',
                color: theme.muted,
                textBorderColor: theme.ground,
                textBorderWidth: 2,
                formatter: (params: { dataIndex: number }) => {
                  const line = drawn[params.dataIndex];
                  return line ? captionOf(line) : '';
                },
              },
              data: drawn.map(line => at(line.value)),
            },
          }
        : {}),
      // A target band: the stretch where a value should land, shaded
      // faintly behind the marks and named at its top left corner.
      ...(shaded.length > 0
        ? {
            markArea: {
              silent: true,
              animation: false,
              itemStyle: { color: theme.muted, opacity: 0.12 },
              label: {
                position: 'insideTopLeft',
                color: theme.muted,
                textBorderColor: theme.ground,
                textBorderWidth: 2,
              },
              data: shaded.map(band => [
                { ...at(band.from), name: band.label ?? '' },
                at(band.to),
              ]),
            },
          }
        : {}),
    }));
}

/**
 * The highest and the lowest point of one series, as the library pins a
 * mark on it: a dot in the series' colour ringed by the ground, and beside
 * it 「最高 1.2万」 above the highest, 「最低 980」 under the lowest. The
 * series' own value label is not written at those two points
 * (`extremeIndexes`): the mark says the number already.
 */
export function extremeMarks(
  plan: CartesianPlan,
  entry: DrawnSeries,
  fill: string,
  theme: ChartTheme,
): object {
  const found = plan.extremesOf(entry);
  if (!found) return {};
  const { horizontal, context, data } = plan;
  const words = context.words;
  const count = data.points.length;
  // Upright, a word over a point near either end would hang past the plot:
  // it is set off to the side the plot has room on.
  const align = (index: number) =>
    horizontal || count < 3
      ? undefined
      : index >= (count * 2) / 3
        ? 'right'
        : index < count / 3
          ? 'left'
          : undefined;
  const point = (index: number, word: string | undefined, high: boolean) => {
    const value = plan.drawnAt(entry, index) ?? 0;
    const text = context.label(entry.metric, value, true);
    return {
      coord: horizontal ? [value, index] : [index, value],
      value,
      label: {
        position: horizontal
          ? high
            ? 'right'
            : 'left'
          : high
            ? 'top'
            : 'bottom',
        formatter: word === undefined ? text : `${word} ${text}`,
        ...(align(index) ? { align: align(index) } : {}),
      },
    };
  };
  return {
    markPoint: {
      symbol: 'circle',
      symbolSize: 8,
      silent: true,
      animation: false,
      itemStyle: { color: fill, borderColor: theme.ground, borderWidth: 2 },
      label: {
        show: true,
        color: theme.foreground,
        fontSize: 11,
        distance: 6,
        textBorderColor: theme.ground,
        textBorderWidth: 2,
      },
      data: [
        point(found.high, words?.high, true),
        point(found.low, words?.low, false),
      ],
    },
  };
}

/** The points of a series whose value its extreme marks already write. */
export function extremeIndexes(
  plan: CartesianPlan,
  entry: DrawnSeries,
): ReadonlySet<number> {
  const found = plan.extremesOf(entry);
  return new Set(found ? [found.high, found.low] : []);
}

/**
 * How each derived line is dashed: two of them on one chart — a trend and a
 * moving average — are both the foreground, so the stroke is what tells
 * them apart, here and in the legend (`DERIVED_STROKE`).
 */
export const DERIVED_STROKE: Record<DerivedKind, 'dashed' | 'dotted'> = {
  trend: 'dashed',
  'moving-average': 'dotted',
  cumulative: 'dashed',
};

const DASH = { dashed: [6, 4], dotted: [2, 3] } as const;

/**
 * The derived lines: the foreground in dashes over the marks, on the axis
 * of the series each is computed from, never stacked and without dots — a
 * computed line is read along, not point by point. The tooltip names each
 * as computed (`cartesianTooltip`); a press on one opens nothing, since it
 * stands for no group.
 */
export function derivedSeries(
  plan: CartesianPlan,
  theme: ChartTheme,
  axisIndex: AxisIndex,
): object[] {
  return plan.derived.map((line, index) => ({
    id: `d${index}`,
    name: line.name,
    type: 'line',
    ...axisIndex(line.side),
    data: line.values,
    silent: true,
    symbol: 'none',
    connectNulls: false,
    z: 3,
    lineStyle: {
      color: theme.foreground,
      type: DASH[DERIVED_STROKE[line.kind]],
      width: 1.5,
    },
    itemStyle: { color: theme.foreground },
    emphasis: { disabled: true },
  }));
}
