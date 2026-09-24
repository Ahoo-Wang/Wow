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
import { valueLabelsOn, type WaterfallData } from '../../analysis/index.js';
import type { ChartSpec } from '../../model/index.js';
import { categoryTick, sideTitle } from './axis.js';
import type { ColumnTitle, ValueLabel } from './family.js';
import { color } from './palette.js';
import { emphasized, type ChartTheme } from './theme.js';
import { tooltipFrame, tooltipHtml } from './tooltip.js';

/** What a waterfall reads besides its steps. */
export interface WaterfallContext {
  spec?: ChartSpec;
  label: ValueLabel;
  column: ColumnTitle;
  animate: boolean;
  pickable: boolean;
  /** The words the drawing writes itself, in the surface's language. */
  words: {
    total: string;
    increase: string;
    decrease: string;
    running: string;
  };
}

/** One bar as drawn: a step, or the closing total. */
export interface DrawnBar {
  /** The name under it: the step's group, or 「合计」. */
  name: string;
  /** Where it runs from and to on the value axis. */
  from: number;
  to: number;
  /** A rise, a fall, or the total the steps arrive at. */
  kind: 'up' | 'down' | 'total';
  /** Its number as a label writes it: a step signed, the total not. */
  text: string;
  /** The same, whole, for the tooltip. */
  whole: string;
  /** The running total after it, whole; a step only. */
  running?: string;
}

/** A change written with its sign: a rise is 「+12」, a fall 「-12」. */
function signed(value: number, text: string): string {
  return value > 0 ? `+${text}` : text;
}

/**
 * The bars a waterfall draws, in order: one a step, floating from the
 * running total before it to the one after, and — unless the spec asked
 * for none — the total the steps arrive at, standing on zero. A step that
 * changes nothing is a rise of nothing, drawn as a sliver.
 */
export function drawnBars(
  data: WaterfallData,
  { spec, label, words }: Pick<WaterfallContext, 'spec' | 'label' | 'words'>,
): DrawnBar[] {
  const alias = spec?.waterfall?.value;
  const bars: DrawnBar[] = data.steps.map(step => ({
    name: label(spec?.waterfall?.x, step.x),
    from: step.start,
    to: step.end,
    kind: step.value < 0 ? 'down' : 'up',
    text: signed(step.value, label(alias, step.value, true)),
    whole: signed(step.value, label(alias, step.value)),
    running: label(alias, step.end),
  }));
  if (data.total !== undefined)
    bars.push({
      name: words.total,
      from: 0,
      to: data.total,
      kind: 'total',
      text: label(alias, data.total, true),
      whole: label(alias, data.total),
    });
  return bars;
}

/**
 * A waterfall as the library draws it: bars stacked on an unseen base, so
 * each floats where the steps before it left the running total (D33 Q55;
 * the library's own bars, nothing more to load). A rise wears the page's
 * success colour and a fall its destructive one — the status tokens, read
 * through the theme, so every preset and mode colours them its own way —
 * and the closing total the palette's first slot, as one series does.
 *
 * Its number is written past the bar's end, over a rise and under a fall,
 * in the foreground ink: on the page rather than on the fill, so it reads
 * on either colour in either mode. The stack adds whatever the sign
 * (`stackStrategy: 'all'`), so a step that crosses zero floats across it.
 */
export function waterfallOption(
  data: WaterfallData,
  context: WaterfallContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const { spec, label, column, animate, pickable } = context;
  const bars = drawnBars(data, context);
  const fills = {
    up: theme.resolve('var(--success)'),
    down: theme.resolve('var(--destructive)'),
    total: theme.resolve(color(0)),
  };
  const kindWord = {
    up: context.words.increase,
    down: context.words.decrease,
    total: context.words.total,
  };
  const labelled = valueLabelsOn(spec);
  const alias = spec?.waterfall?.value;
  const titleStyle = { color: theme.muted, fontWeight: 500 };
  const stacked = {
    type: 'bar',
    stack: 'waterfall',
    stackStrategy: 'all',
  };
  return {
    animation: animate,
    animationDuration: 300,
    textStyle: { fontFamily: theme.fontFamily, fontSize: 12 },
    grid: {
      left: 4,
      right: 16,
      top: 24,
      bottom: 8,
      outerBoundsMode: 'same',
      outerBoundsContain: 'all',
    },
    xAxis: {
      type: 'category',
      data: bars.map(bar => bar.name),
      name: column(spec?.waterfall?.x),
      nameLocation: 'middle',
      nameGap: 28,
      nameMoveOverlap: true,
      nameTextStyle: titleStyle,
      axisTick: { show: false },
      axisLine: { lineStyle: { color: theme.border } },
      axisLabel: {
        color: theme.muted,
        hideOverlap: true,
        formatter: (name: string) => categoryTick(name),
      },
    },
    yAxis: {
      type: 'value',
      ...sideTitle(column(alias), 'left', 'end', titleStyle, 16),
      axisLabel: {
        color: theme.muted,
        formatter: (value: number) => label(alias, value, true),
      },
      splitLine: { lineStyle: { color: theme.border } },
    },
    tooltip: {
      ...tooltipFrame(theme),
      trigger: 'item',
      formatter: ({ dataIndex }: { dataIndex: number }) => {
        const bar = bars[dataIndex];
        if (!bar) return '';
        return tooltipHtml(bar.name, [
          {
            color: fills[bar.kind],
            name: kindWord[bar.kind],
            value: bar.whole,
          },
          ...(bar.running === undefined
            ? []
            : [
                {
                  color: 'transparent',
                  name: context.words.running,
                  value: bar.running,
                },
              ]),
        ]);
      },
    },
    series: [
      {
        ...stacked,
        id: 'base',
        silent: true,
        tooltip: { show: false },
        itemStyle: { color: 'transparent' },
        emphasis: { disabled: true },
        data: bars.map(bar => Math.min(bar.from, bar.to)),
      },
      {
        ...stacked,
        // `s0`: the one series whose every datum is a group (`faded`).
        id: 's0',
        cursor: pickable ? 'pointer' : 'default',
        barCategoryGap: '24%',
        // A step of nothing keeps a sliver, so it reads as a step of zero.
        barMinHeight: 2,
        data: bars.map(bar => ({
          value: Math.abs(bar.to - bar.from),
          itemStyle: { color: fills[bar.kind], borderRadius: 2 },
          emphasis: {
            itemStyle: { color: emphasized(theme, fills[bar.kind]) },
          },
          label: {
            show: labelled,
            position: bar.to < bar.from ? 'bottom' : 'top',
            color: theme.foreground,
            fontSize: 11,
            formatter: () => bar.text,
          },
        })),
        labelLayout: { hideOverlap: true },
      },
    ],
  };
}
