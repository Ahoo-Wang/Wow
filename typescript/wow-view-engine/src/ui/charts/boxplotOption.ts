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
import type { BoxplotData } from '../../analysis/index.js';
import type { ChartSpec, RecordData } from '../../model/index.js';
import { categoryTick, sideTitle } from './axis.js';
import type { ColumnTitle, ValueLabel } from './family.js';
import { FADED_OPACITY } from './highlight.js';
import { color } from './palette.js';
import { emphasized, mixColor, type ChartTheme, chartText } from './theme.js';
import { tooltipFrame, tooltipHtml } from './tooltip.js';

/** What a boxplot reads besides its boxes. */
export interface BoxplotContext {
  spec?: ChartSpec;
  label: ValueLabel;
  column: ColumnTitle;
  animate: boolean;
  pickable: boolean;
  /** The group a press set the board's filter to (D22 I): the rest faint. */
  highlight?: (row: RecordData) => boolean;
}

/** The five slots in the order the library reads a box's value. */
const FIVE = ['low', 'q1', 'median', 'q3', 'high'] as const;

/**
 * How strong a box's fill is against the ground: pale enough that the
 * median's line reads across it, the edge in the full slot colour.
 */
const BOX_FILL = 0.25;

/** One drawn box, as the tooltip, the press and the reading take it. */
export interface DrawnBox {
  /** The group value it stands for, keyed by the dimension's alias. */
  row: RecordData;
  /** Its name, as its column reads it. */
  name: string;
}

/** Every box, in the order drawn; its place is how a press is read back. */
/**
 * The widest a box grows: a box is read by its ends and its median, and a
 * wide one on few groups was a slab. The box's geometry, not the theme's.
 */
const BOX_MAX_WIDTH = 48;

export function drawnBoxes(
  data: BoxplotData,
  { spec, label }: Pick<BoxplotContext, 'spec' | 'label'>,
): DrawnBox[] {
  const category = spec?.boxplot?.category;
  return data.boxes.map(box => ({
    row: category === undefined ? {} : { [category]: box.group },
    name: label(category, box.group),
  }));
}

/**
 * A boxplot as the library draws it: a box per group from the lower to the
 * upper quartile with the median across it, whiskers to the lowest and the
 * highest — the five numbers Wow computed, never recomputed from records
 * here. The value axis is the field's, read as its column reads it, and
 * does not start at 0: a spread is read against itself.
 */
export function boxplotOption(
  data: BoxplotData,
  context: BoxplotContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const { spec, label, column, animate, pickable, highlight } = context;
  const boxplot = spec?.boxplot;
  const boxes = drawnBoxes(data, context);
  const edge = theme.resolve(color(0));
  const fill = mixColor(theme.ground, edge, BOX_FILL);
  const anyLit = highlight ? boxes.some(box => highlight(box.row)) : false;
  const titleStyle = { color: theme.axis.color, fontWeight: 500 };
  const measured = boxplot?.median;
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: chartText(theme),
    grid: {
      left: 4,
      right: 16,
      top: 24,
      bottom: 4,
      outerBoundsMode: 'same',
      outerBoundsContain: 'all',
    },
    xAxis: {
      type: 'category',
      data: boxes.map(box => box.name),
      name: column(boxplot?.category),
      nameLocation: 'middle',
      nameGap: 28,
      nameMoveOverlap: true,
      nameTextStyle: titleStyle,
      axisTick: { show: false },
      axisLine: { lineStyle: { ...theme.grid } },
      axisLabel: {
        color: theme.axis.color,
        hideOverlap: true,
        formatter: (name: string) => categoryTick(name),
      },
    },
    yAxis: {
      type: 'value',
      scale: true,
      ...sideTitle(column(measured), 'left', 'end', titleStyle, 16),
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: {
        color: theme.axis.color,
        hideOverlap: true,
        formatter: (value: number) => label(measured, value, true),
      },
      splitLine: { lineStyle: { ...theme.grid } },
    },
    tooltip: {
      ...tooltipFrame(theme),
      trigger: 'item',
      formatter: ({ dataIndex }: { dataIndex: number }) => {
        const box = data.boxes[dataIndex];
        if (!box || !boxplot) return '';
        // Each number named by its column — 「实付的第 25 百分位」, never a
        // 「下四分位」 the metrics may not be — highest first, as the box
        // reads from the top.
        return tooltipHtml(
          boxes[dataIndex]?.name ?? '',
          [...FIVE].reverse().map(slot => ({
            color: slot === 'median' ? edge : fill,
            name: column(boxplot[slot]) ?? slot,
            value: label(boxplot[slot], box[slot]),
          })),
        );
      },
    },
    series: [
      {
        type: 'boxplot',
        cursor: pickable ? 'pointer' : 'default',
        boxWidth: ['20%', BOX_MAX_WIDTH],
        data: data.boxes.map((box, index) => ({
          value: FIVE.map(slot => box[slot]),
          ...(anyLit && !highlight?.(boxes[index].row)
            ? { itemStyle: { opacity: FADED_OPACITY } }
            : {}),
        })),
        // A box's outline is drawn as a derived line is, three quarters of
        // a line; under the pointer, a whole one.
        itemStyle: {
          color: fill,
          borderColor: edge,
          borderWidth: theme.line.width * 0.75,
        },
        emphasis: {
          itemStyle: {
            color: mixColor(theme.ground, edge, BOX_FILL * 2),
            borderColor: emphasized(theme, edge),
            borderWidth: theme.line.width,
          },
        },
      },
    ],
  };
}
