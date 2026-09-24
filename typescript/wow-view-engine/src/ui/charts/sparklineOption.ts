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
import type { MetricCardData } from '../../analysis/index.js';
import type { FilledNote, ValueLabel } from './family.js';
import { color } from './palette.js';
import type { ChartTheme } from './theme.js';
import { tooltipFrame, tooltipHtml } from './tooltip.js';

/** What a metric card's trend reads besides its points. */
export interface SparklineContext {
  label: ValueLabel;
  /** The date dimension the trend runs along. */
  x?: string;
  /** The metric the card headlines. */
  metric?: string;
  /** What a point is called in the tooltip: 「趋势」. */
  name: string;
  animate: boolean;
  /** A filled-in bucket as the tooltip says it (`useFilledNote`). */
  filled?: FilledNote;
}

/**
 * A metric card's trend: a line and a faint fill under it, with no axes,
 * no grid and no dots — the headline is the number, and the line only says
 * which way it has been going. The tooltip still reads each point, its day
 * and its value as the column reads it.
 */
export function sparklineOption(
  trend: NonNullable<MetricCardData['trend']>,
  { label, x, metric, name, animate, filled }: SparklineContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const stroke = theme.resolve(color(0));
  const days = trend.map(point => label(x, point.x));
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: { fontFamily: theme.fontFamily, fontSize: 12 },
    grid: { left: 2, right: 2, top: 4, bottom: 4 },
    xAxis: { type: 'category', show: false, boundaryGap: false, data: days },
    yAxis: { type: 'value', show: false, scale: true },
    tooltip: {
      ...tooltipFrame(theme),
      trigger: 'axis',
      axisPointer: {
        type: 'line',
        lineStyle: { color: theme.muted, width: 1 },
      },
      formatter: (params: { dataIndex: number }[]) => {
        const index = params[0]?.dataIndex ?? -1;
        const point = trend[index];
        if (!point || point.value === null) return '';
        const value = label(metric, point.value);
        return tooltipHtml(days[index] ?? '', [
          {
            color: stroke,
            name,
            // A bucket filled in with 0 says so (D23, Q14).
            value: point.filled && filled ? filled(x, value) : value,
          },
        ]);
      },
    },
    series: [
      {
        type: 'line',
        data: trend.map(point => point.value),
        showSymbol: false,
        symbol: 'circle',
        symbolSize: 6,
        connectNulls: false,
        lineStyle: { color: stroke, width: 2 },
        itemStyle: { color: stroke },
        areaStyle: { color: stroke, opacity: 0.12 },
      },
    ],
  };
}
