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
import type { HeatmapData } from '../../analysis/index.js';
import type { ChartSpec } from '../../model/index.js';
import { categoryTick } from './axis.js';
import type { ColumnTitle, ValueLabel } from './family.js';
import { color } from './palette.js';
import type { ChartTheme } from './theme.js';
import { tooltipFrame, tooltipHtml } from './tooltip.js';

/** A cell as the series holds it: its column, its row, its shade, its value. */
type Cell = [number, number, number, number];

/** What a heatmap reads besides its cells. */
export interface HeatmapContext {
  spec?: ChartSpec;
  label: ValueLabel;
  column: ColumnTitle;
  animate: boolean;
  pickable: boolean;
}

/**
 * A heatmap as the library draws it: a cell per pair of groups, as deep as
 * its value, and the scale beside it — the audit's heatmap was a corner of
 * grey squares with no way to read a shade back into a number (another
 * session's walk of the real backend, 2026-09-23). The cells fill the plot,
 * the first row on top as a table reads; the colour runs from the ground to
 * the palette's first slot, and a `log` scale spreads out the low end of a
 * matrix one cell dwarfs — the shade follows the log, the numbers stay the
 * numbers. A cell nothing fell in is left empty: it is no group.
 */
export function heatmapOption(
  data: HeatmapData,
  { spec, label, column, animate, pickable }: HeatmapContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const heatmap = spec?.heatmap;
  const values = data.cells
    .flat()
    .filter((cell): cell is number => cell !== null);
  const low = values.length > 0 ? Math.min(...values) : 0;
  const high = values.length > 0 ? Math.max(...values) : 0;
  const logged = heatmap?.scale === 'log';
  /** Where a value sits on the colour scale: itself, or its log above the low. */
  const shade = (value: number) => (logged ? Math.log1p(value - low) : value);
  const unshade = (at: number) => (logged ? Math.expm1(at) + low : at);
  const xs = data.xs.map(x => label(heatmap?.x, x));
  const ys = data.ys.map(y => label(heatmap?.y, y));
  const fill = theme.resolve(color(0));
  const category = (names: string[], title: string | undefined) => ({
    type: 'category',
    data: names,
    name: title,
    nameLocation: 'middle',
    nameGap: 24,
    nameMoveOverlap: true,
    nameTextStyle: { color: theme.muted, fontWeight: 500 },
    axisTick: { show: false },
    axisLine: { show: false },
    axisLabel: {
      color: theme.muted,
      hideOverlap: true,
      formatter: (name: string) => categoryTick(name),
    },
    splitArea: { show: false },
  });
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: { fontFamily: theme.fontFamily, fontSize: 12 },
    grid: {
      left: 4,
      right: 16,
      top: 8,
      bottom: 44,
      outerBoundsMode: 'same',
      outerBoundsContain: 'all',
    },
    xAxis: category(xs, column(heatmap?.x)),
    yAxis: { ...category(ys, column(heatmap?.y)), inverse: true },
    visualMap: {
      type: 'continuous',
      // Every cell alike reads as the scale's deep end, not a wash of its
      // palest: the one value there is is the most there is.
      min: high === low ? shade(low) - 1 : shade(low),
      max: shade(high),
      dimension: 2,
      calculable: false,
      orient: 'horizontal',
      left: 'center',
      bottom: 0,
      itemWidth: 10,
      itemHeight: 160,
      inRange: { color: [fill, fill], colorAlpha: [0.2, 1] },
      text: [
        label(heatmap?.value, high, true),
        label(heatmap?.value, low, true),
      ],
      textGap: 6,
      textStyle: { color: theme.muted, fontSize: 11 },
      formatter: (at: number) => label(heatmap?.value, unshade(at), true),
    },
    tooltip: {
      ...tooltipFrame(theme),
      trigger: 'item',
      formatter: ({ value }: { value: Cell }) =>
        tooltipHtml(`${ys[value[1]] ?? ''} · ${xs[value[0]] ?? ''}`, [
          {
            color: fill,
            name: column(heatmap?.value) ?? '',
            value: label(heatmap?.value, value[3]),
          },
        ]),
    },
    series: [
      {
        type: 'heatmap',
        cursor: pickable ? 'pointer' : 'default',
        data: data.cells.flatMap((row, y) =>
          row.flatMap((cell, x) =>
            // The shade is what the scale reads; the value rides along for
            // the label and the tooltip.
            cell === null ? [] : [[x, y, shade(cell), cell]],
          ),
        ),
        itemStyle: {
          borderColor: theme.ground,
          borderWidth: 2,
          borderRadius: 2,
        },
        label: {
          show: spec?.labels === true,
          color: theme.foreground,
          fontSize: 11,
          textBorderColor: theme.ground,
          textBorderWidth: 2,
          formatter: ({ value }: { value: Cell }) =>
            label(heatmap?.value, value[3], true),
        },
        labelLayout: { hideOverlap: true },
        emphasis: { itemStyle: { borderColor: theme.foreground } },
      },
    ],
  };
}
