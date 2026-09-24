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
import type { FunnelData } from '../../analysis/index.js';
import type { ChartSpec } from '../../model/index.js';
import { formatValue } from './axis.js';
import { stageName, type ColumnTitle, type ValueLabel } from './family.js';
import { color } from './palette.js';
import { emphasized, type ChartTheme } from './theme.js';
import { tooltipFrame, tooltipHtml } from './tooltip.js';

/** What a funnel reads besides its stages. */
export interface FunnelContext {
  spec?: ChartSpec;
  label: ValueLabel;
  column: ColumnTitle;
  locale?: string;
  /** The heading over the percentages: what they are relative to. */
  conversion: string;
  animate: boolean;
}

/** One stage as the drawing, the tooltip and the reading table name it. */
export interface DrawnStage {
  name: string;
  value: number;
  /** Its value as its column reads it. */
  text: string;
  /** Its conversion, a percentage, or nothing where the spec asks none. */
  conversion?: string;
}

/**
 * The stages with their names, values and conversions. A stage taken from
 * a group is named by that group's value, a metric stage by the name it was
 * given or its column's title (`stageName`); each value reads as the metric
 * that measures it.
 */
export function drawnStages(
  data: FunnelData,
  {
    spec,
    label,
    column,
    locale,
  }: Omit<FunnelContext, 'conversion' | 'animate'>,
): DrawnStage[] {
  const stages = spec?.funnel?.stages;
  const measured = (index: number) =>
    stages === undefined
      ? undefined
      : stages.from === 'group'
        ? stages.value
        : stages.items[index]?.metric;
  return data.stages.map((stage, index) => ({
    name: stageName(stages, index, stage.label, label, column),
    value: stage.value,
    text: label(measured(index), stage.value),
    ...(stage.conversion === undefined
      ? {}
      : { conversion: formatValue(stage.conversion, 'percent', locale) }),
  }));
}

/**
 * A funnel as the library draws it: one bar a stage, centred on one line,
 * as long as its value against the longest, in the order the stages were
 * given — never re-sorted, since a funnel's order is the business's.
 *
 * Bars and not the library's funnel of trapezoids: a trapezoid's top edge
 * is its stage and its bottom edge the next one, so its area — what the eye
 * reads — was two numbers at once, and a stage bigger than the one before
 * drew an hourglass (2026-09-23 audit). A bar is one length for one number.
 * It is centred by an unseen bar before it; another after it carries the
 * stage's words, so they stand in one column beside the widest stage rather
 * than along the ragged ends. Each says its name, its value and its
 * conversion; the heading over the percentages says what they are relative
 * to (`conversionHeading`), drawn above the bars. It wears the palette's
 * first slot, as one series does.
 */
export function funnelOption(
  data: FunnelData,
  context: FunnelContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const { spec, animate } = context;
  const stages = drawnStages(data, context);
  const horizontal = spec?.funnel?.orientation === 'horizontal';
  const fill = theme.resolve(color(0));
  const values = stages.map(stage => Math.max(0, stage.value));
  const longest = Math.max(0, ...values);
  const room = values.map(value => (longest - value) / 2);
  const words = ({ dataIndex }: { dataIndex: number }) => {
    const stage = stages[dataIndex];
    if (!stage) return '';
    // Under a funnel lying down each stage has a column's width: the name
    // over its numbers.
    return `{name|${stage.name}}${horizontal ? '\n' : '  '}{value|${stage.text}}${
      stage.conversion === undefined ? '' : `  {rate|${stage.conversion}}`
    }`;
  };
  const unseen = {
    type: 'bar',
    stack: 'funnel',
    silent: true,
    tooltip: { show: false },
    itemStyle: { color: 'transparent' },
    emphasis: { disabled: true },
    data: room,
  };
  // The unseen bar on the far side carries the words: past the longest
  // stage's end, so every stage's words stand in one column (one row, on a
  // funnel lying down, under the bars).
  const labelled = {
    ...unseen,
    label: {
      show: true,
      position: horizontal ? 'bottom' : 'right',
      color: theme.foreground,
      fontSize: 12,
      formatter: words,
      rich: {
        name: { color: theme.muted },
        value: { color: theme.foreground, fontWeight: 500 },
        rate: { color: theme.muted },
      },
    },
  };
  const stageAxis = {
    type: 'category',
    data: stages.map(stage => stage.name),
    // The first stage on top, or on the left, as the business runs.
    inverse: !horizontal,
    show: false,
  };
  const valueAxis = {
    type: 'value',
    min: 0,
    max: longest > 0 ? longest : 1,
    show: false,
  };
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: { fontFamily: theme.fontFamily, fontSize: 12 },
    grid: horizontal
      ? { left: '4%', right: '4%', top: 8, bottom: 56 }
      : { left: '4%', right: '38%', top: 8, bottom: 8 },
    xAxis: horizontal ? stageAxis : valueAxis,
    yAxis: horizontal ? valueAxis : stageAxis,
    tooltip: {
      ...tooltipFrame(theme),
      trigger: 'item',
      formatter: ({ dataIndex }: { dataIndex: number }) => {
        const stage = stages[dataIndex];
        if (!stage) return '';
        return tooltipHtml(stage.name, [
          { color: fill, name: stage.text, value: stage.conversion ?? '' },
        ]);
      },
    },
    series: [
      horizontal ? labelled : unseen,
      {
        type: 'bar',
        stack: 'funnel',
        barCategoryGap: '16%',
        // A stage of nothing keeps a sliver, so it reads as a stage of zero
        // rather than as one that failed to draw.
        barMinHeight: 3,
        data: values,
        itemStyle: { color: fill, borderRadius: 2 },
        emphasis: { itemStyle: { color: emphasized(theme, fill) } },
      },
      horizontal ? unseen : labelled,
    ],
  };
}
