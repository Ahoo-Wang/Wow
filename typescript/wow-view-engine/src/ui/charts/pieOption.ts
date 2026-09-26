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
import {
  groupKeyText,
  valueLabelsOn,
  type PieData,
} from '../../analysis/index.js';
import type { ChartSpec } from '../../model/index.js';
import { formatShare } from './axis.js';
import type { SeriesName, ToneOf, ValueLabel } from './family.js';
import { OTHER_COLOR, color, pinnedColor, toneColor } from './palette.js';
import {
  CHART_FALLBACK,
  chartText,
  emphasized,
  type ChartTheme,
} from './theme.js';
import { tooltipFrame, tooltipHtml } from './tooltip.js';

/** What a pie reads besides its slices. */
export interface PieContext {
  spec?: ChartSpec;
  label: ValueLabel;
  /**
   * What a slice is called (`useSeriesName`): 「新客：是」 for a pie by a
   * yes/no field; left out, its category as its column reads it.
   */
  seriesName?: SeriesName;
  /** The tone a category's option gives it (`useToneOf`). */
  toneOf?: ToneOf;
  locale?: string;
  /** The merged remainder's name, 「其他」. */
  other: string;
  /** The word under a donut's total, 「合计」. */
  total: string;
  /** Whether the measured column adds up, so a donut may write its whole. */
  adds: boolean;
  animate: boolean;
  pickable: boolean;
}

/** One slice as every part of the pie names it. */
export interface DrawnSlice {
  key: string;
  name: string;
  value: number;
  /** Its part of the whole, or nothing: a negative value has no share. */
  share?: number;
  /**
   * A CSS colour: the one pinned, the category's tone, its slot, or the
   * grey of 「其他」.
   */
  color: string;
}

/**
 * The smallest share written on its slice. A sliver's label would crowd its
 * neighbours; its share is in the tooltip, the legend and the reading table.
 */
export const LABELLED_SHARE = 0.03;

/**
 * The slices with their names, shares and colours.
 *
 * A pie is shares of a whole (audit P0-10), and the whole is the slices
 * drawn: a metric that went negative has no share to give, so it gives
 * none and takes none. A slice is coloured by its category through
 * `groupKeyText` — the spelling a split series is keyed by, so one key
 * colours a category in either chart; the merged remainder is no category
 * anyone could colour, so it is the neutral whatever the spec says.
 *
 * A category whose option names a tone wears the tone's role colour
 * (`toneColor`) — 「不可恢复」 red, 「已成功」 green, as their badges are —
 * unless the analyst pinned another. Two categories sharing a tone would
 * then be one colour, and a pie is read by telling its slices apart, so a
 * tone paints only the one category that holds it; the others take their
 * slots, as a category without a tone does.
 */
export function drawnSlices(
  data: PieData,
  {
    spec,
    label,
    other,
    seriesName = label,
    toneOf,
  }: Pick<PieContext, 'spec' | 'label' | 'other' | 'seriesName' | 'toneOf'>,
): DrawnSlice[] {
  const whole = wholeOf(data);
  const category = spec?.pie?.category;
  const tones = data.slices.map(slice =>
    slice.other === true
      ? undefined
      : toneColor(toneOf?.(category, slice.category)),
  );
  const held = (tone: string | undefined) =>
    tone !== undefined && tones.filter(each => each === tone).length === 1;
  return data.slices.map((slice, index) => ({
    key: `p${index}`,
    name:
      slice.other === true
        ? other
        : seriesName(spec?.pie?.category, slice.category),
    value: slice.value,
    ...(whole > 0 && slice.value >= 0 ? { share: slice.value / whole } : {}),
    color:
      slice.other === true
        ? OTHER_COLOR
        : (pinnedColor(spec, groupKeyText(slice.category)) ??
          (held(tones[index]) ? tones[index] : undefined) ??
          color(index)),
  }));
}

/** The whole the shares are of: every slice that has a share to give. */
function wholeOf(data: PieData): number {
  return data.slices.reduce(
    (sum, slice) => (slice.value > 0 ? sum + slice.value : sum),
    0,
  );
}

/**
 * What each slice writes outside itself, in the slices' order; `''` for a
 * slice that writes nothing. A slice of 3% or more says its share, and with
 * `labels: true` its value, short, before it.
 */
export function pieCaptions(
  data: PieData,
  context: Pick<
    PieContext,
    'spec' | 'label' | 'locale' | 'other' | 'seriesName'
  >,
): string[] {
  const { spec, label, locale } = context;
  const measure = spec?.pie?.value;
  return drawnSlices(data, context).map(slice => {
    if (slice.share === undefined || slice.share < LABELLED_SHARE) return '';
    const share = formatShare(slice.share, locale);
    return valueLabelsOn(spec)
      ? `${label(measure, slice.value, true)} · ${share}`
      : share;
  });
}

/** The pie's outer radius, as a part of half the plot's shorter side. */
const OUTER = 0.72;
/** A donut's hole, as a part of the same. */
const INNER = 0.5;
/** A donut's whole in its hole, on the chart's type scale: 20 over 12. */
const TOTAL = 5 / 3;
/** The leader line's two legs, and the gap between it and its label. */
const LEADER = { first: 8, second: 8, gap: 5 };
/** What the library keeps clear between a label and the plot's edge. */
const BLEED = 8;
/**
 * How far a pie gives way to make room for its labels: to seven tenths of
 * its full size. Smaller, the picture is spent on words the legend already
 * holds.
 */
export const LABEL_ROOM_SHRINK = 0.7;

/**
 * What the plot's size changes about a pie: whether its labels are written,
 * and how big it is to make room for them (audit P0-6).
 *
 * A label outside a slice needs the room from the pie's edge to the plot's:
 * the leader line, then the words. Where there is not enough the library
 * cut the number — 47.7% came out 「4」, 28.3% 「28....」, and a 「4」 is a
 * number, worse than none. So a label is written whole or not at all: the
 * pie gives way first, down to `LABEL_ROOM_SHRINK` of its size, so the
 * widest label fits even beside the pie's widest point; past that no slice
 * writes its label, and the legend, which carries every share, and the
 * tooltip say them — as Metabase leaves out an outer label it cannot fit.
 * Measured against the widest point, so a label the library slides along
 * the rim to clear its neighbour still fits.
 */
export function pieFit(
  captions: readonly string[],
  width: number,
  height: number,
  /** How wide a line of text is, at the chart's text size. */
  measure: (text: string) => number,
  donut: boolean,
  /** The chart's type: a label is measured a step under its text. */
  text: Pick<ChartTheme['text'], 'size' | 'labelSize'> = CHART_FALLBACK.text,
): EChartsCoreOption {
  const full = (OUTER * Math.min(width, height)) / 2;
  const widest = Math.max(
    0,
    ...captions.map(caption =>
      caption ? (measure(caption) * text.labelSize) / text.size : 0,
    ),
  );
  const fits =
    width / 2 - LEADER.first - LEADER.second - LEADER.gap - BLEED - widest;
  const labelled = widest === 0 || fits >= full * LABEL_ROOM_SHRINK;
  const outer = labelled ? Math.min(full, fits) : full;
  return {
    series: [
      {
        radius: [donut ? (outer * INNER) / OUTER : 0, outer],
        label: { show: labelled },
        labelLine: { show: labelled },
      },
    ],
  };
}

/**
 * A pie or a donut as the library draws it.
 *
 * Each slice of 3% or more says its share outside itself, on a leader line
 * (`pieCaptions`), and a label that would land on another is left out
 * rather than drawn over it. A label is never cut short: whether the plot
 * has room for them is `pieFit`'s to say. A donut writes its whole in the
 * hole — when the measure adds up, since the total of some averages is not
 * a number anyone asked for. The tooltip is the slice's value, whole, and
 * its share.
 */
export function pieOption(
  data: PieData,
  context: PieContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const { spec, label, locale, total, adds, animate, pickable } = context;
  const slices = drawnSlices(data, context);
  const captions = pieCaptions(data, context);
  const measure = spec?.pie?.value;
  const donut = spec?.pie?.donut === true;
  const whole = wholeOf(data);
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: chartText(theme),
    tooltip: {
      ...tooltipFrame(theme),
      trigger: 'item',
      formatter: ({ dataIndex }: { dataIndex: number }) => {
        const slice = slices[dataIndex];
        if (!slice) return '';
        const value = label(measure, slice.value);
        return tooltipHtml('', [
          {
            color: theme.resolve(slice.color),
            name: slice.name,
            value:
              slice.share === undefined
                ? value
                : `${value} · ${formatShare(slice.share, locale)}`,
          },
        ]);
      },
    },
    series: [
      {
        type: 'pie',
        radius: donut
          ? [`${INNER * 100}%`, `${OUTER * 100}%`]
          : [0, `${OUTER * 100}%`],
        center: ['50%', '50%'],
        cursor: pickable ? 'pointer' : 'default',
        // Clockwise from the top, in the order the kernel shaped them.
        startAngle: 90,
        avoidLabelOverlap: true,
        labelLayout: { hideOverlap: true },
        emphasis: { scale: true, scaleSize: 4 },
        // Whether the labels are written at all is the series' (`pieFit`
        // turns them off together); a slice with nothing to say is off
        // for itself.
        label: {
          show: true,
          color: theme.foreground,
          fontSize: theme.text.labelSize,
          textBorderColor: theme.ground,
          textBorderWidth: 2,
          // Whole or not at all: the library's default cuts the number.
          overflow: 'none',
          bleedMargin: BLEED,
          distanceToLabelLine: LEADER.gap,
        },
        labelLine: {
          show: true,
          length: LEADER.first,
          length2: LEADER.second,
          lineStyle: { color: theme.grid.color },
        },
        data: slices.map((slice, index) => {
          const text = captions[index] ?? '';
          const fill = theme.resolve(slice.color);
          return {
            name: slice.name,
            // The library draws no negative wedge; the tooltip still says it.
            value: Math.max(0, slice.value),
            itemStyle: {
              color: fill,
              // A thin seam of the ground between two slices.
              borderColor: theme.ground,
              borderWidth: theme.slice.border,
            },
            // The slice under the pointer steps toward the ink rather than
            // paling, as every mark does (`emphasized`).
            emphasis: { itemStyle: { color: emphasized(theme, fill) } },
            ...(text === ''
              ? { label: { show: false }, labelLine: { show: false } }
              : { label: { formatter: text } }),
          };
        }),
      },
    ],
    ...(donut && adds && whole > 0
      ? {
          graphic: [
            {
              type: 'text',
              left: 'center',
              top: 'middle',
              silent: true,
              style: {
                text: `{value|${label(measure, whole, true)}}\n{word|${total}}`,
                align: 'center',
                rich: {
                  value: {
                    fill: theme.foreground,
                    fontSize: theme.text.size * TOTAL,
                    fontWeight: 600,
                    fontFamily: theme.text.family,
                    lineHeight: Math.round(theme.text.size * TOTAL * 1.3),
                  },
                  word: {
                    fill: theme.axis.color,
                    fontSize: theme.text.size,
                    fontFamily: theme.text.family,
                    lineHeight: theme.text.size + 4,
                  },
                },
              },
            },
          ],
        }
      : {}),
  };
}
