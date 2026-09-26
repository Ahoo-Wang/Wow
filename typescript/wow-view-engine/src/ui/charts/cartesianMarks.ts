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

import type { DerivedKind, ReferenceBand } from '../../model/index.js';
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

type PlacedLine = CartesianPlan['lines'][number];

/** The reference lines and target bands one value axis carries. */
interface Carrier {
  side: Side;
  drawn: PlacedLine[];
  shaded: ReferenceBand[];
}

/** The axes that carry a reference line or a band, each with its own. */
function carriersOf(plan: CartesianPlan): Carrier[] {
  const bands = plan.context.spec?.cartesian?.referenceBands ?? [];
  return plan.sides
    .map(side => ({
      side,
      drawn: plan.lines.filter(line => axisId(line.axis) === side),
      shaded: bands.filter(band => axisId(band.axis) === side),
    }))
    .filter(({ drawn, shaded }) => drawn.length > 0 || shaded.length > 0);
}

/** Where a mark stands on the value axis, for the plan's direction. */
function standing(plan: CartesianPlan, value: number): object {
  return plan.horizontal ? { xAxis: value } : { yAxis: value };
}

/**
 * What a reference line says: a statistic line with no caption of its own
 * says what it is and where it stands, 「平均 ¥1,234」; a constant one says
 * its caption or nothing.
 */
function captionOf(plan: CartesianPlan, line: PlacedLine): string {
  if (line.label !== undefined || line.statistic === undefined)
    return line.label ?? '';
  const value = plan.context.label(line.metric, line.value, true);
  return plan.context.words?.statistic(line.statistic, value) ?? value;
}

/**
 * The reference lines and target bands of each axis, on one carrier: a
 * series with no points of its own, so it takes no slot beside the bars in
 * a category's band. Here each is named inside the plot — a line over its
 * right end, a band in its top left corner — which is where a chart with no
 * room for a margin keeps them; `referenceCaptions` moves the names out
 * beside the plot where there is room.
 */
export function referenceSeries(
  plan: CartesianPlan,
  theme: ChartTheme,
  axisIndex: AxisIndex,
): object[] {
  return carriersOf(plan).map(({ side, drawn, shaded }) => ({
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
            lineStyle: {
              color: theme.muted,
              type: [4, 4],
              width: theme.grid.width,
            },
            // A halo of the ground keeps the caption legible where a mark
            // runs through it.
            label: {
              position: 'insideEndTop',
              color: theme.muted,
              textBorderColor: theme.ground,
              textBorderWidth: 2,
              formatter: (params: { dataIndex: number }) => {
                const line = drawn[params.dataIndex];
                return line ? captionOf(plan, line) : '';
              },
            },
            data: drawn.map(line => standing(plan, line.value)),
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
              { ...standing(plan, band.from), name: band.label ?? '' },
              standing(plan, band.to),
            ]),
          },
        }
      : {}),
  }));
}

/** The most of the chart's width the names beside the plot may take. */
const CAPTION_SHARE = 0.25;

/** How far a name beside the plot stands off the plot's edge. */
export const CAPTION_DISTANCE = 6;

/** One name beside the plot: whose it is, what it says, and its height. */
interface Caption {
  carrier: number;
  band: boolean;
  index: number;
  text: string;
  y: number;
}

/**
 * Where the reference lines' and target bands' names stand on a chart of
 * this size: out of the plot, in a margin on its right (`right`, the room
 * the grid keeps there), or inside it, as `referenceSeries` put them.
 *
 * Inside, a line's name sat over its right end and a band's in its top
 * left corner, on whatever the marks put there: the last bars and their
 * numbers, the lowest point's word, the first date under the axis (the
 * 2026-09-26 review P1-5 — 「48 小时」 through the numbers of the last
 * warehouse, 「目标 ≤ 3%」 over 「最低 0%」 and the first week, 「日均」
 * on 「中位数」). Beside the plot, level with its line or its band's
 * middle, nothing of the data is under a name; two names nearer than a line
 * of text are set a line apart, each as near its own height as that allows,
 * and none below the plot's floor.
 *
 * Only an upright chart with the one value axis has that margin: a right
 * axis's ticks stand there, and lying on its side a chart's lines stand up
 * and are named at their top. Nor where the widest name would take more
 * than a quarter of the width: a narrow panel keeps its plot, and the
 * names stay inside.
 */
export function referenceCaptions(
  plan: CartesianPlan,
  width: number,
  /** The plot's height, as near as the fit can say it before drawing. */
  plotHeight: number,
  /** How wide a line of text is, at the chart's text size. */
  measure: (text: string) => number,
  /** The height a name takes: two are set this far apart at least. */
  lineHeight: number,
): { right?: number; series: object[] } {
  const carriers = carriersOf(plan);
  const captions: Caption[] = carriers.flatMap((carrier, index) => [
    ...carrier.drawn.map((line, at) => ({
      carrier: index,
      band: false,
      index: at,
      text: captionOf(plan, line),
      y: heightOf(plan, plotHeight, line.value),
    })),
    ...carrier.shaded.map((band, at) => ({
      carrier: index,
      band: true,
      index: at,
      text: band.label ?? '',
      y:
        (heightOf(plan, plotHeight, band.from) +
          heightOf(plan, plotHeight, band.to)) /
        2,
    })),
  ]);
  const named = captions.filter(caption => caption.text !== '');
  const widest = Math.max(0, ...named.map(caption => measure(caption.text)));
  const room = Math.ceil(widest) + CAPTION_DISTANCE + 8;
  const beside =
    !plan.horizontal &&
    plan.sides.length === 1 &&
    named.length > 0 &&
    room <= width * CAPTION_SHARE;

  const shift = beside
    ? setApart(named, plotHeight, lineHeight)
    : new Map<Caption, number>();
  const offset = (carrier: number, band: boolean, index: number) => {
    const caption = named.find(
      one =>
        one.carrier === carrier && one.band === band && one.index === index,
    );
    return [0, caption ? (shift.get(caption) ?? 0) : 0];
  };

  // Every placement spelled out, inside ones too: a resize merges this over
  // the last one, and a panel narrowed past the margin takes its names back
  // in.
  return {
    ...(plan.horizontal ? {} : { right: beside ? room : 16 }),
    series: carriers.map(({ drawn, shaded }, carrier) => ({
      ...(drawn.length > 0
        ? {
            markLine: {
              label: beside
                ? { position: 'end', distance: CAPTION_DISTANCE }
                : { position: 'insideEndTop', distance: 5 },
              // Whole items: a resize hands the library this list, and it
              // replaces a list rather than merging it.
              data: drawn.map((line, index) => ({
                ...standing(plan, line.value),
                label: { offset: offset(carrier, false, index) },
              })),
            },
          }
        : {}),
      ...(shaded.length > 0
        ? {
            markArea: {
              label: beside
                ? { position: 'right', distance: CAPTION_DISTANCE }
                : { position: 'insideTopLeft', distance: 5 },
              data: shaded.map((band, index) => [
                {
                  ...standing(plan, band.from),
                  name: band.label ?? '',
                  label: { offset: offset(carrier, true, index) },
                },
                standing(plan, band.to),
              ]),
            },
          }
        : {}),
    })),
  };
}

/**
 * How far down the plot a value stands on the left axis, in pixels from
 * its top: by the span the axis runs (`span`), in powers of ten on a log
 * scale.
 */
function heightOf(
  plan: CartesianPlan,
  plotHeight: number,
  value: number,
): number {
  const { min, max } = plan.span('left');
  const log = plan.logOn('left');
  const scaled = (at: number) => (log ? Math.log10(Math.max(at, 1e-12)) : at);
  const low = scaled(min);
  const high = scaled(max);
  if (!(high > low)) return plotHeight / 2;
  const at = Math.min(high, Math.max(low, scaled(value)));
  return plotHeight * (1 - (at - low) / (high - low));
}

/**
 * How far each name moves so that no two are nearer than a line: top to
 * bottom each at least a line under the one above, then bottom to top
 * none past the plot's floor — unless its own line is already there.
 */
function setApart(
  captions: Caption[],
  plotHeight: number,
  lineHeight: number,
): Map<Caption, number> {
  const order = [...captions].sort((one, other) => one.y - other.y);
  const placed = order.map(caption => caption.y);
  for (let index = 1; index < placed.length; index += 1)
    placed[index] = Math.max(placed[index], placed[index - 1] + lineHeight);
  const last = placed.length - 1;
  placed[last] = Math.min(placed[last], Math.max(plotHeight, order[last].y));
  for (let index = last - 1; index >= 0; index -= 1)
    placed[index] = Math.min(placed[index], placed[index + 1] - lineHeight);
  return new Map(
    order.map((caption, index) => [
      caption,
      Math.round(placed[index] - caption.y),
    ]),
  );
}

/**
 * The highest and the lowest point of one series, as the library pins a
 * mark on it: a dot in the series' colour ringed by the ground, and beside
 * it 「最高 1.2万」 and 「最低 980」. The series' own value label is not
 * written at those two points (`extremeIndexes`): the mark says the number
 * already.
 *
 * On a bar both words stand past the bar's end, where its own number would:
 * under the lowest bar's end the word ran down its body and over the bars
 * beside it (2026-09-26 review P1-5, 「最低 ¥1,940」). On a line the highest
 * point's word stands over it and the lowest's under it — over it too near
 * the plot's floor, where under it the word fell on the first category's
 * name (「最低 0%」 on 「2024年8月26日」).
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
  const bar = entry.kind === 'bar';
  const { min, max } = plan.span(entry.side);
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
  /** Whether a word stands past the far end of its mark, or before it. */
  const beyond = (value: number, high: boolean) =>
    bar
      ? value >= 0
      : high || (max > min && (value - min) / (max - min) < FLOOR_SHARE);
  const point = (index: number, word: string | undefined, high: boolean) => {
    const value = plan.drawnAt(entry, index) ?? 0;
    const text = context.label(entry.metric, value, true);
    const far = beyond(value, high);
    return {
      coord: horizontal ? [value, index] : [index, value],
      value,
      label: {
        position: horizontal
          ? far
            ? 'right'
            : 'left'
          : far
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
        fontSize: theme.text.labelSize,
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

/**
 * How near the floor of the plot, as a share of its height, a line's
 * lowest point writes its word over itself rather than under it: a line of
 * text and its distance on a plot of some 250px.
 */
const FLOOR_SHARE = 0.1;

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
  'cumulative-share': 'dashed',
};

const DASH = { dashed: [6, 4], dotted: [2, 3] } as const;

/** A derived line is drawn lighter than the series it is computed from. */
const DERIVED_WIDTH = 0.75;

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
      width: theme.line.width * DERIVED_WIDTH,
    },
    itemStyle: { color: theme.foreground },
    emphasis: { disabled: true },
  }));
}
