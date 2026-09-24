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

import type { EChartsCoreOption } from 'echarts/core';
import { categoryTick, measuredTitle, titleAtHead } from './axis.js';
import type { CartesianPlan, DrawnSeries } from './cartesianPlan.js';

/**
 * How far below the axis line the title under the plot sits: a line of
 * ticks (some 20px) and a clear line of air under it.
 */
export const TITLE_GAP_UNDER = 36;

/** How far a value label stands off its mark's end (the library's default). */
export const LABEL_DISTANCE = 5;

/** The air between the lowest tick and the title under it. */
const TITLE_AIR = 14;

/** How far a tick stands off its axis line (the library's default). */
const LABEL_MARGIN = 8;

/** A line of tick text, and so the narrowest band a slanted name fits. */
const LINE_HEIGHT = 16;

/** The longest a slanted name is drawn before it is cut. */
const SLANT_MAX = 120;

/** A value label's size, a step under the page's 12. */
const LABEL_SIZE = 11;

/** The height of a line of value-label text, and so of a label on its end. */
const LABEL_LINE = 13;

/**
 * How the category names on a bar chart's axis fit the width they have.
 *
 * Side by side while every name fits its band; at a slant once one does
 * not, each still under its own bar, cut at a length a slant can carry; and
 * only when a band is narrower than a line of text does the axis name every
 * few bars instead — the first and the last always among them. Metabase
 * turns its labels the same way; the library itself only thins them, which
 * showed every other name of 16 event types with room to spare.
 */
export function categoryFit(
  names: readonly string[],
  width: number,
  measure: (text: string) => number,
  horizontal: boolean,
  /**
   * The names are the days or months of a time axis, in order. Those are
   * never slanted: a reader carries a date across the gap between two
   * ticks, so the axis writes every few flat, the first always among them,
   * as Metabase's time axis does.
   */
  dated = false,
): EChartsCoreOption {
  if (horizontal)
    return {
      yAxis: {
        axisLabel: {
          width: Math.max(64, Math.round(width * 0.3)),
          overflow: 'truncate',
        },
      },
    };
  // What the value axis and the padding leave the categories.
  const band = Math.max(0, width - 72) / Math.max(1, names.length);
  const widest = Math.max(0, ...names.map(name => measure(categoryTick(name))));
  if (widest + 8 <= band)
    return { xAxis: { axisLabel: { rotate: 0, interval: 0 } } };
  if (dated)
    return {
      xAxis: {
        axisLabel: { rotate: 0, interval: 'auto', showMinLabel: true },
      },
    };
  // Slanted, the names reach further down than a line of text, and the
  // title moved clear of them (`nameMoveOverlap`) sat 4px under their ends:
  // the gap is set past the slant's own depth instead, a line of air below.
  const slant = (Math.min(widest, SLANT_MAX) + LINE_HEIGHT) * Math.SQRT1_2;
  return {
    xAxis: {
      nameGap: Math.ceil(LABEL_MARGIN + slant + TITLE_AIR),
      axisLabel: {
        rotate: 45,
        interval: band >= LINE_HEIGHT ? 0 : 'auto',
        width: SLANT_MAX,
        overflow: 'truncate',
        showMinLabel: true,
        showMaxLabel: true,
      },
    },
  };
}

/**
 * How the value labels past the marks' ends are written: flat, turned to
 * run up from the bar's end, or not at all.
 */
export type LabelFit = 'flat' | 'upright' | 'none';

/**
 * What the plot's size changes about a cartesian chart: how its category
 * names stand (`categoryFit`), and which value labels it writes.
 *
 * The library's `hideOverlap` dropped whichever label it met second, so a
 * crowded chart kept every other number, or two in three, in no order a
 * reader could see — and a bar with no number over it reads as a bar with
 * no value (2026-09-23 audit). The rule here is one a reader can predict:
 *
 * - **over the bars' ends**, every label is written flat where the widest
 *   of them fits the room a bar has; turned to run up from the bar's end
 *   where it does not but a line of text still does, as Metabase turns
 *   them; and none at all where not even that fits. All or none, per chart.
 * - **inside a stacked segment**, a part is written where the segment is
 *   tall enough for a line and wide enough for the number — judged against
 *   the scale the chart owns (`sharedScales`), so it is the segment's real
 *   size — and a segment too small keeps its number to the tooltip, as a
 *   pie's sliver does.
 * - **on a line or an area**, every point's number where the widest fits
 *   the step between two points, and none where it does not; two lines'
 *   numbers at one height are moved apart rather than dropped.
 *
 * It also cuts an axis title to the room its side has, rather than letting
 * it run off the plot.
 */
export function cartesianFit(
  plan: CartesianPlan,
  width: number,
  height: number,
  measure: (text: string) => number,
): EChartsCoreOption {
  const { horizontal, series, data, sides } = plan;
  const dated = plan.context.ticks !== undefined;
  const names = plan.names.map(
    (name, index) => plan.context.ticks?.[index] ?? name,
  );
  const category = categoryFit(names, width, measure, horizontal, dated);
  const points = Math.max(1, data.points.length);
  const labelWidth = (text: string) => (measure(text) * LABEL_SIZE) / 12;
  const widestOuter = Math.max(0, ...plan.outerTexts.map(labelWidth));

  // The plot, as near as the axes' text lets it be said before drawing: the
  // value ticks and titles beside it, the category names and title under.
  const valueRoom = sides.length * 56;
  const underRoom = horizontal
    ? TITLE_GAP_UNDER + LINE_HEIGHT
    : ((category.xAxis as { nameGap?: number } | undefined)?.nameGap ??
        TITLE_GAP_UNDER) + LINE_HEIGHT;
  const headRoom = 24;
  const across = Math.max(
    0,
    width - 20 - (horizontal ? Math.round(width * 0.3) + 8 : valueRoom),
  );
  const along = Math.max(0, height - underRoom - headRoom);
  const plotLength = horizontal ? across : along;
  const band = (horizontal ? along : across) / points;

  // A bar's slot in its band: side by side, each series or stack takes one.
  const bars = series.filter(entry => entry.kind === 'bar');
  const slots = new Set(
    bars.map(entry => plan.stackOf(entry) ?? `own:${entry.key}`),
  ).size;
  const thickness = Math.min(
    48,
    (band * 0.8) / Math.max(1, slots + 0.3 * (slots - 1)),
  );
  const room = slots > 0 ? (band * 0.8) / slots : band;

  const outer: LabelFit = horizontal
    ? thickness >= LABEL_LINE
      ? 'flat'
      : 'none'
    : widestOuter + 2 <= room
      ? 'flat'
      : room >= LABEL_LINE
        ? 'upright'
        : 'none';
  const outerPatch =
    outer === 'none'
      ? { show: false }
      : outer === 'upright'
        ? { show: true, rotate: 90, align: 'left', verticalAlign: 'middle' }
        : // Spelled out: a resize merges this over an upright one.
          {
            show: true,
            rotate: 0,
            align: horizontal ? 'left' : 'center',
            verticalAlign: horizontal ? 'middle' : 'bottom',
          };
  const lineFit = (entry: DrawnSeries) => {
    const widest = Math.max(
      0,
      ...data.points.map((_point, index) => {
        const value = plan.drawnAt(entry, index);
        return value === null ? 0 : labelWidth(plan.drawnText(entry, value));
      }),
    );
    return widest + 2 <= band;
  };
  const insideFits = (entry: DrawnSeries, index: number) => {
    const text = plan.insideText(entry, index);
    const value = plan.drawnAt(entry, index);
    if (text === '' || value === null) return false;
    const { min, max } = plan.span(entry.side);
    const size = (Math.abs(value) / Math.max(max - min, 1e-9)) * plotLength;
    const words = labelWidth(text);
    return horizontal
      ? size >= words + 4 && thickness >= LABEL_LINE
      : size >= LABEL_LINE + 2 && thickness >= words + 2;
  };

  const patches = [
    ...series.map(entry => {
      if (!plan.labelled(entry)) return {};
      if (entry.kind !== 'bar') return { label: { show: lineFit(entry) } };
      if (!plan.stacked(entry)) return { label: outerPatch };
      const fits = data.points.map((_point, index) => insideFits(entry, index));
      return {
        label: {
          formatter: ({ dataIndex }: { dataIndex: number }) =>
            fits[dataIndex] ? plan.insideText(entry, dataIndex) : '',
        },
      };
    }),
    ...plan.totals.map(() => ({ label: outerPatch })),
  ];
  const wrote = outer !== 'none' && plan.outerTexts.some(text => text !== '');

  // An axis title stays within its side of the plot: along the axis when
  // it is turned, over half the plot when it is set flat at the head.
  const titled = (name: string | undefined, runsUp: boolean) =>
    runsUp && !titleAtHead(name)
      ? along
      : runsUp
        ? across / sides.length
        : across;
  const valueNames = sides.map(side => ({
    nameTruncate: {
      maxWidth: Math.max(48, titled(nameOf(plan, side), !horizontal) - 8),
    },
  }));

  return {
    ...category,
    ...(horizontal
      ? {
          xAxis: valueNames,
          grid: {
            right: wrote
              ? Math.max(
                  16,
                  Math.ceil(Math.max(0, ...plan.outerTexts.map(measure))) +
                    LABEL_DISTANCE +
                    4,
                )
              : 16,
          },
        }
      : {
          yAxis: valueNames,
          grid: {
            top: !wrote
              ? 16
              : outer === 'upright'
                ? Math.ceil(widestOuter) + LABEL_DISTANCE + 8
                : 24,
          },
        }),
    series: patches,
  };
}

/** The title an axis carries (`measuredTitle`, unless one was typed). */
function nameOf(plan: CartesianPlan, side: 'left' | 'right') {
  return (
    plan.context.spec?.cartesian?.yAxis?.[side]?.label ??
    measuredTitle(
      plan.series
        .filter(entry => entry.side === side)
        .map(entry => entry.metric),
      plan.sides.length > 1,
      plan.context.column,
      plan.context.join ?? ', ',
    )
  );
}
