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
import { valueLabelsOn, type HeatmapData } from '../../analysis/index.js';
import type { ChartSpec } from '../../model/index.js';
import { categoryTick, sideTitle } from './axis.js';
import type { ColumnTitle, ValueLabel } from './family.js';
import { color } from './palette.js';
import { inkOn, mixColor, type ChartTheme } from './theme.js';
import { tooltipFrame, tooltipHtml } from './tooltip.js';

/**
 * How strong the palest cell is against the ground: the low end is still a
 * shade of the slot, not the ground itself — a cell with the least in it
 * is a cell, and an empty one is none.
 */
const PALEST = 0.2;

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
  const palest = mixColor(theme.ground, fill, PALEST);
  const bottom = high === low ? shade(low) - 1 : shade(low);
  const top = shade(high);
  /** The colour the scale gives a value: the cell the label is written on. */
  const cellColor = (value: number) =>
    mixColor(
      palest,
      fill,
      top === bottom ? 1 : (shade(value) - bottom) / (top - bottom),
    );
  const titleStyle = { color: theme.muted, fontWeight: 500 };
  const category = (
    names: string[],
    title: string | undefined,
    upright: boolean,
  ) => ({
    type: 'category',
    data: names,
    // The rows' title set flat at the head where it is Chinese.
    ...(upright
      ? sideTitle(title, 'left', 'start', titleStyle, 24)
      : {
          name: title,
          nameLocation: 'middle',
          nameGap: 24,
          nameMoveOverlap: true,
          nameTextStyle: titleStyle,
        }),
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
    xAxis: category(xs, column(heatmap?.x), false),
    yAxis: { ...category(ys, column(heatmap?.y), true), inverse: true },
    visualMap: {
      type: 'continuous',
      // Every cell alike reads as the scale's deep end, not a wash of its
      // palest: the one value there is is the most there is.
      min: bottom,
      max: top,
      dimension: 2,
      calculable: false,
      orient: 'horizontal',
      left: 'center',
      bottom: 0,
      itemWidth: 10,
      itemHeight: 160,
      // Both ends as concrete colours on the actual ground, so the cells
      // and the bar that reads them are the same colours in either mode.
      inRange: { color: [palest, fill] },
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
            // the label and the tooltip. The label is in the ink that
            // stands off this cell's own shade: one ink for all of them
            // wrote dark digits on the deep end (2026-09-23 audit).
            cell === null
              ? []
              : [
                  {
                    value: [x, y, shade(cell), cell],
                    label: { color: inkOn(theme, cellColor(cell)) },
                    emphasis: {
                      label: {
                        color: inkOn(theme, cellColor(cell)),
                      },
                    },
                  },
                ],
          ),
        ),
        itemStyle: {
          borderColor: theme.ground,
          borderWidth: 2,
          borderRadius: 2,
        },
        label: {
          show: valueLabelsOn(spec),
          fontSize: 11,
          formatter: ({ value }: { value: Cell }) =>
            label(heatmap?.value, value[3], true),
        },
        // The cell under the pointer is ringed in the ink; its own shade
        // is its value, so it keeps it.
        emphasis: { itemStyle: { borderColor: theme.foreground } },
      },
    ],
  };
}

/**
 * Whether the plot's cells are big enough for their numbers: every one of
 * them, or — where the widest does not fit a cell, or a cell is shorter than
 * a line — none. The library's `hideOverlap` dropped whichever it met
 * second, and a cell with no number among cells with one reads as a cell
 * with none (2026-09-23 audit). Estimated before drawing from the plot's
 * size less the axes' text; the tooltip always has the number.
 */
export function heatmapLabelsFit(
  data: HeatmapData,
  { spec, label }: Pick<HeatmapContext, 'spec' | 'label'>,
  width: number,
  height: number,
  measure: (text: string) => number,
): boolean {
  const heatmap = spec?.heatmap;
  const rows = Math.max(
    0,
    ...data.ys.map(y => measure(categoryTick(label(heatmap?.y, y)))),
  );
  const values = data.cells
    .flat()
    .filter((cell): cell is number => cell !== null);
  const widest = Math.max(
    0,
    ...values.map(
      value => (measure(label(heatmap?.value, value, true)) * 11) / 12,
    ),
  );
  // The row names and their title beside the cells; under them the column
  // names, their title and the colour scale.
  const across = (width - 20 - rows - 8) / Math.max(1, data.xs.length);
  const along = (height - 8 - 44 - 40) / Math.max(1, data.ys.length);
  return widest + 6 <= across && along >= 16;
}
