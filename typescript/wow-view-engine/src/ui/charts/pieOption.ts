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
import { groupKeyText, type PieData } from '../../analysis/index.js';
import type { ChartSpec } from '../../model/index.js';
import { formatValue } from './axis.js';
import type { ValueLabel } from './family.js';
import { OTHER_COLOR, colorOf } from './palette.js';
import type { ChartTheme } from './theme.js';
import { tooltipFrame, tooltipHtml } from './tooltip.js';

/** What a pie reads besides its slices. */
export interface PieContext {
  spec?: ChartSpec;
  label: ValueLabel;
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
  /** A CSS colour: the category's slot, the one pinned, or the grey of 「其他」. */
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
 */
export function drawnSlices(
  data: PieData,
  { spec, label, other }: Pick<PieContext, 'spec' | 'label' | 'other'>,
): DrawnSlice[] {
  const whole = wholeOf(data);
  return data.slices.map((slice, index) => ({
    key: `p${index}`,
    name:
      slice.other === true ? other : label(spec?.pie?.category, slice.category),
    value: slice.value,
    ...(whole > 0 && slice.value >= 0 ? { share: slice.value / whole } : {}),
    color:
      slice.other === true
        ? OTHER_COLOR
        : colorOf(spec, index, groupKeyText(slice.category)),
  }));
}

/** The whole the shares are of: every slice that has a share to give. */
export function wholeOf(data: PieData): number {
  return data.slices.reduce(
    (sum, slice) => (slice.value > 0 ? sum + slice.value : sum),
    0,
  );
}

/**
 * A pie or a donut as the library draws it.
 *
 * Each slice of 3% or more says its share outside itself, on a leader line,
 * and a label that would land on another is left out rather than drawn
 * over it; with `labels: true` it says its value too. A donut writes its
 * whole in the hole — when the measure adds up, since the total of some
 * averages is not a number anyone asked for. The tooltip is the slice's
 * value, whole, and its share.
 */
export function pieOption(
  data: PieData,
  context: PieContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const { spec, label, locale, total, adds, animate, pickable } = context;
  const slices = drawnSlices(data, context);
  const measure = spec?.pie?.value;
  const percent = (share: number) => formatValue(share, 'percent', locale);
  const caption = (slice: DrawnSlice) => {
    if (slice.share === undefined || slice.share < LABELLED_SHARE) return '';
    return spec?.labels === true
      ? `${label(measure, slice.value, true)} · ${percent(slice.share)}`
      : percent(slice.share);
  };
  const donut = spec?.pie?.donut === true;
  const whole = wholeOf(data);
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: { fontFamily: theme.fontFamily, fontSize: 12 },
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
                : `${value} · ${percent(slice.share)}`,
          },
        ]);
      },
    },
    series: [
      {
        type: 'pie',
        radius: donut ? ['50%', '72%'] : [0, '72%'],
        center: ['50%', '50%'],
        cursor: pickable ? 'pointer' : 'default',
        // Clockwise from the top, in the order the kernel shaped them.
        startAngle: 90,
        avoidLabelOverlap: true,
        labelLayout: { hideOverlap: true },
        emphasis: { scale: true, scaleSize: 4 },
        data: slices.map(slice => {
          const text = caption(slice);
          return {
            name: slice.name,
            // The library draws no negative wedge; the tooltip still says it.
            value: Math.max(0, slice.value),
            itemStyle: {
              color: theme.resolve(slice.color),
              // A thin seam of the ground between two slices.
              borderColor: theme.ground,
              borderWidth: 1,
            },
            label: {
              show: text !== '',
              formatter: text,
              color: theme.foreground,
              fontSize: 11,
              textBorderColor: theme.ground,
              textBorderWidth: 2,
            },
            labelLine: {
              show: text !== '',
              length: 8,
              length2: 8,
              lineStyle: { color: theme.border },
            },
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
                    fontSize: 20,
                    fontWeight: 600,
                    fontFamily: theme.fontFamily,
                    lineHeight: 26,
                  },
                  word: {
                    fill: theme.muted,
                    fontSize: 12,
                    fontFamily: theme.fontFamily,
                    lineHeight: 16,
                  },
                },
              },
            },
          ],
        }
      : {}),
  };
}
