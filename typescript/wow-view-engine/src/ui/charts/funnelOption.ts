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
import type { ChartTheme } from './theme.js';
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
 * A funnel as the library draws it: a centred shape, each stage as wide as
 * its value against the first, in the order the stages were given — never
 * re-sorted, since a funnel's order is the business's. Each stage says its
 * name, its value and its conversion beside it; the heading over the
 * percentages says what they are relative to (`conversionHeading`), drawn
 * above the shape. It wears the palette's first slot, as one series does.
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
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: { fontFamily: theme.fontFamily, fontSize: 12 },
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
      {
        type: 'funnel',
        orient: horizontal ? 'horizontal' : 'vertical',
        sort: 'none',
        gap: 2,
        // A stage of nothing keeps a sliver, so it reads as a stage of zero
        // rather than as one that failed to draw.
        minSize: '4%',
        maxSize: '100%',
        ...(horizontal
          ? { left: '4%', right: '4%', top: 8, bottom: 56 }
          : { left: '8%', right: '38%', top: 8, bottom: 8 }),
        data: stages.map(stage => ({
          name: stage.name,
          value: Math.max(0, stage.value),
        })),
        itemStyle: { color: fill, borderColor: theme.ground, borderWidth: 1 },
        label: {
          show: true,
          position: horizontal ? 'bottom' : 'right',
          color: theme.foreground,
          fontSize: 12,
          formatter: ({ dataIndex }: { dataIndex: number }) => {
            const stage = stages[dataIndex];
            if (!stage) return '';
            return `{name|${stage.name}}  {value|${stage.text}}${
              stage.conversion === undefined
                ? ''
                : `  {rate|${stage.conversion}}`
            }`;
          },
          rich: {
            name: { color: theme.muted },
            value: { color: theme.foreground, fontWeight: 500 },
            rate: { color: theme.muted },
          },
        },
        labelLine: { show: !horizontal, lineStyle: { color: theme.border } },
        emphasis: { label: { fontSize: 12 } },
      },
    ],
  };
}
