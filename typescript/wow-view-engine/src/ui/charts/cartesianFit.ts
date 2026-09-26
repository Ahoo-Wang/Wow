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
import { PEAKS_ONLY_FROM } from '../../analysis/index.js';
import { categoryTick, measuredTitle, titleAtHead } from './axis.js';
import type { CartesianPlan, DrawnSeries } from './cartesianPlan.js';
import { referenceCaptions } from './cartesianMarks.js';
import { CHART_FALLBACK, type ChartTheme } from './theme.js';
import {
  SLIDER_ROOM,
  visibleCount,
  zooms,
  type ZoomWindow,
} from './cartesianZoom.js';

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

/** How many of a time axis's ticks are measured for the widest (`datedFit`). */
const TICK_SAMPLE = 200;

/** The longest a slanted name is drawn before it is cut. */
const SLANT_MAX = 120;

/**
 * The air a line of value-label text takes over its size: 13px of line for
 * an 11px label.
 */
const LABEL_LEADING = 2;

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
 * How a time axis names its buckets: every one where each fits its band,
 * else every `step`-th, flat — and, where a tick set is at hand (`tickFor`),
 * each named one written short against the one named before it, `step`
 * back: the year where it changed since, and on the first one on screen.
 * Thinned by the library alone, a year of days read 9月24日, 11月25日,
 * 1月26日 with nothing to say the third was a year later, and ten thousand
 * days read twenty-seven years as months (the 2026-09-24 walk).
 *
 * Which buckets the library names is its own to say — counted from the
 * zoom's first category, which it rounds its own way — so the formatter
 * answers for any bucket rather than for a list worked out here. It goes
 * with every fit, so a fit that names them all again takes the thinned
 * one's back.
 */
function datedFit(
  plan: CartesianPlan,
  first: number,
  shown: number,
  width: number,
  measure: (text: string) => number,
): EChartsCoreOption | undefined {
  const tickFor = plan.context.tickFor;
  if (!tickFor) return undefined;
  const band = Math.max(0, width - 72) / Math.max(1, shown);
  // The ticks of a time axis are one pattern of one length, give or take a
  // digit: a sample measures them as well as all ten thousand would, in a
  // fraction of the time a redraw can spare.
  const sampled = Math.max(1, Math.floor(shown / TICK_SAMPLE));
  let widest = 0;
  for (let index = first; index < first + shown; index += sampled)
    widest = Math.max(widest, measure(plan.tickOf(plan.names[index])));
  if (widest + 8 <= band)
    return {
      xAxis: {
        axisLabel: { rotate: 0, interval: 0, formatter: plan.tickOf },
      },
    };
  const step = Math.max(2, Math.ceil((widest + 16) / Math.max(band, 1e-6)));
  const values = plan.data.points.map(point => point.x);
  const indexOf = new Map<string, number>();
  plan.names.forEach((name, index) => {
    if (!indexOf.has(name)) indexOf.set(name, index);
  });
  const formatter = (name: string) => {
    const index = indexOf.get(name);
    if (index === undefined) return plan.tickOf(name);
    const before = index - step;
    const texts =
      before < first
        ? tickFor([values[index]])
        : tickFor([values[before], values[index]]);
    return texts?.[texts.length - 1] ?? plan.tickOf(name);
  };
  return {
    xAxis: {
      axisLabel: {
        rotate: 0,
        interval: step - 1,
        showMinLabel: true,
        showMaxLabel: false,
        formatter,
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
 *
 * Zoomed (`window`), it fits the categories on screen rather than all of
 * them: a year of days narrowed to a week names every day and has room for
 * each day's number again.
 */
export function cartesianFit(
  plan: CartesianPlan,
  width: number,
  height: number,
  /** How wide a line of text is, at the chart's text size. */
  measure: (text: string) => number,
  window?: ZoomWindow,
  /** The chart's type: a value label is a step under its text. */
  text: Pick<ChartTheme['text'], 'size' | 'labelSize'> = CHART_FALLBACK.text,
): EChartsCoreOption {
  const { horizontal, series, data, sides } = plan;
  const dated = plan.context.ticks !== undefined;
  const shown = visibleCount(data.points.length, window);
  const first = window
    ? Math.min(
        data.points.length - shown,
        Math.floor((data.points.length * window.start) / 100),
      )
    : 0;
  const names = plan.names
    .map((name, index) => plan.context.ticks?.[index] ?? name)
    .slice(first, first + shown);
  const category =
    (dated && !horizontal
      ? datedFit(plan, first, shown, width, measure)
      : undefined) ?? categoryFit(names, width, measure, horizontal, dated);
  const points = Math.max(1, shown);
  const labelWidth = (label: string) =>
    (measure(label) * text.labelSize) / text.size;
  /** The height of a line of value-label text, and so of a label on its end. */
  const LABEL_LINE = text.labelSize + LABEL_LEADING;
  // A long row of bars writes its peak and trough as marks; zoomed to fewer
  // bars than that, it writes every number on screen, as a short row does.
  const everyBar = shown < PEAKS_ONLY_FROM;
  const zoomedTexts = everyBar
    ? series.filter(plan.peaksOnly).flatMap(entry => {
        const texts: string[] = [];
        for (let index = first; index < first + shown; index += 1) {
          const value = plan.drawnAt(entry, index);
          if (value !== null && !plan.filledAt(entry, index))
            texts.push(plan.drawnText(entry, value));
        }
        return texts;
      })
    : [];
  const widestOuter = Math.max(
    0,
    ...[...plan.outerTexts, ...zoomedTexts].map(labelWidth),
  );

  // The plot, as near as the axes' text lets it be said before drawing: the
  // value ticks and titles beside it, the category names and title under.
  const valueRoom = sides.length * 56;
  const underRoom =
    (horizontal
      ? TITLE_GAP_UNDER + LINE_HEIGHT
      : ((category.xAxis as { nameGap?: number } | undefined)?.nameGap ??
          TITLE_GAP_UNDER) + LINE_HEIGHT) + (zooms(plan) ? SLIDER_ROOM : 0);
  const headRoom = 24;
  const along = Math.max(0, height - underRoom - headRoom);
  // The reference lines' and bands' names, beside the plot where it has
  // room for them (`referenceCaptions`): the margin comes off the plot.
  const captions = referenceCaptions(plan, width, along, measure, LINE_HEIGHT);
  const across = Math.max(
    0,
    width -
      20 -
      (horizontal ? Math.round(width * 0.3) + 8 : valueRoom) -
      ((captions.right ?? 16) - 16),
  );
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
  // The numbers on screen, not the ones zoomed out of it.
  const lineFit = (entry: DrawnSeries) => {
    let widest = 0;
    for (let index = first; index < first + shown; index += 1) {
      const value = plan.drawnAt(entry, index);
      if (value !== null)
        widest = Math.max(widest, labelWidth(plan.drawnText(entry, value)));
    }
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
      if (plan.peaksOnly(entry))
        return { label: everyBar ? outerPatch : { show: false } };
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
    ...captions.series,
  ];
  const wrote =
    outer !== 'none' &&
    [...plan.outerTexts, ...zoomedTexts].some(text => text !== '');

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
            // A line of text over the tallest mark for a value label or the
            // highest point's word, as the option keeps before the fit.
            top:
              wrote && outer === 'upright'
                ? Math.ceil(widestOuter) + LABEL_DISTANCE + 8
                : wrote ||
                    series.some(entry => plan.extremesOf(entry) !== undefined)
                  ? 24
                  : 16,
            right: captions.right,
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
