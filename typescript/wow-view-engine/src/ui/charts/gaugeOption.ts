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
import type { GaugeData } from '../../analysis/index.js';
import type { ChartSpec } from '../../model/index.js';
import { formatShare, formatValue } from './axis.js';
import type { ValueLabel } from './family.js';
import { color } from './palette.js';
import { mixColor, type ChartTheme, chartText } from './theme.js';

/** What a gauge reads besides its number. */
export interface GaugeContext {
  spec?: ChartSpec;
  label: ValueLabel;
  locale?: string;
  animate: boolean;
  /** 「达成目标的 {share}」, already worded, or nothing without a target. */
  reached?: string;
}

/** How strong the dial's unfilled track is against the ground. */
const TRACK = 0.18;

/**
 * The dial's band, as thick as it is drawn at every size: the dial's
 * geometry, not a line of the theme's.
 */
const DIAL = 14;

/** The number in the dial's middle, on the chart's type scale: 28 over 12. */
const FIGURE = 7 / 3;

/** Where the dial starts and ends, in degrees: an open arc, 240° round. */
const START = 210;
const END = -30;

/**
 * A number as the gauge writes it: in the spec's pinned format, else as
 * its column reads it — short on a tick, whole in the middle.
 */
export function gaugeText(
  value: number,
  { spec, label, locale }: Pick<GaugeContext, 'spec' | 'label' | 'locale'>,
  compact = false,
): string {
  const format = spec?.gauge?.format;
  return format && format !== 'auto'
    ? formatValue(value, format, locale)
    : label(spec?.gauge?.metric, value, compact);
}

/**
 * A gauge as the library draws it: an open dial from the scale's start to
 * its end, filled in the first slot up to the number, the number whole in
 * its middle and — with a target — how much of it is reached under it and
 * a tick in the ink where the target stands. No needle: the fill is the
 * reading, and a needle over it said the same thing twice. The ends are
 * the kernel's (`shapeGauge`); a number past them fills the whole dial and
 * the chart says it is off the scale.
 */
export function gaugeOption(
  data: GaugeData,
  context: GaugeContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const { animate, reached } = context;
  const fill = theme.resolve(color(0));
  const track = mixColor(theme.ground, theme.muted, TRACK);
  const value =
    data.value === null
      ? data.min
      : Math.min(data.max, Math.max(data.min, data.value));
  const dial = {
    type: 'gauge',
    min: data.min,
    max: data.max,
    startAngle: START,
    endAngle: END,
    radius: '88%',
    center: ['50%', '58%'],
    splitNumber: 4,
  };
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: chartText(theme),
    series: [
      {
        ...dial,
        id: 'value',
        progress: {
          show: data.value !== null,
          width: DIAL,
          itemStyle: { color: fill },
        },
        axisLine: { lineStyle: { width: DIAL, color: [[1, track]] } },
        pointer: { show: false },
        anchor: { show: false },
        axisTick: { show: false },
        splitLine: { show: false },
        axisLabel: {
          distance: 22,
          color: theme.axis.color,
          fontSize: theme.text.labelSize,
          formatter: (tick: number) =>
            tick === data.min || tick === data.max
              ? gaugeText(tick, context, true)
              : '',
        },
        title: {
          show: reached !== undefined,
          offsetCenter: [0, '30%'],
          color: theme.axis.color,
          fontSize: theme.text.size,
        },
        detail: {
          valueAnimation: animate,
          offsetCenter: [0, '0%'],
          color: theme.foreground,
          fontSize: theme.text.size * FIGURE,
          fontWeight: 600,
          formatter: () =>
            data.value === null ? '—' : gaugeText(data.value, context),
        },
        data: [{ value, name: reached ?? '' }],
      },
      ...(data.target === undefined
        ? []
        : [
            {
              ...dial,
              id: 'target',
              axisLine: { show: false },
              progress: { show: false },
              axisTick: { show: false },
              splitLine: { show: false },
              axisLabel: { show: false },
              title: { show: false },
              detail: { show: false },
              anchor: { show: false },
              // The target as a short bar across the dial's band, in the
              // ink, so it reads against the fill and the track alike.
              pointer: {
                show: true,
                icon: 'rect',
                // From just inside the band to just past it, whatever the
                // dial's radius: both as shares of it.
                length: '10%',
                width: 3,
                offsetCenter: [0, '-94%'],
                itemStyle: { color: theme.foreground },
              },
              data: [
                {
                  value: Math.min(data.max, Math.max(data.min, data.target)),
                },
              ],
            },
          ]),
    ],
  };
}

/** The share of the target reached, as the gauge's caption words it. */
export function reachedShare(
  data: GaugeData,
  locale: string | undefined,
): string | undefined {
  return data.reached === undefined
    ? undefined
    : formatShare(data.reached, locale);
}
