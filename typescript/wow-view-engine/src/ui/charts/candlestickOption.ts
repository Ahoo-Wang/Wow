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
import type { CandlestickData } from '../../analysis/index.js';
import type { ChartSpec, RecordData } from '../../model/index.js';
import { categoryTick, sideTitle } from './axis.js';
import type { ColumnTitle, ValueLabel } from './family.js';
import { FADED_OPACITY } from './highlight.js';
import { chartText, type ChartTheme } from './theme.js';
import { tooltipFrame, tooltipHtml } from './tooltip.js';

/** What a candlestick reads besides its candles. */
export interface CandlestickContext {
  spec?: ChartSpec;
  label: ValueLabel;
  column: ColumnTitle;
  animate: boolean;
  pickable: boolean;
  /** The period a press set the board's filter to (D22 I): the rest faint. */
  highlight?: (row: RecordData) => boolean;
  /** How a candle's direction is said in the tooltip. */
  words: { rise: string; fall: string; flat: string };
}

/** One drawn candle, as the tooltip, the press and the reading take it. */
export interface DrawnCandle {
  /** The period it stands for, keyed by the dimension's alias. */
  row: RecordData;
  /** Its name, as its column reads it. */
  name: string;
}

/** The widest a candle grows: its body is read by its two ends. */
const CANDLE_MAX_WIDTH = 24;

/** Every candle, in the order drawn; its place is how a press is read back. */
export function drawnCandles(
  data: CandlestickData,
  { spec, label }: Pick<CandlestickContext, 'spec' | 'label'>,
): DrawnCandle[] {
  const x = spec?.candlestick?.x;
  return data.candles.map(candle => ({
    row: x === undefined ? {} : { [x]: candle.x },
    name: label(x, candle.x),
  }));
}

/**
 * A candlestick as the library draws it: a body from the open to the close,
 * wicks to the highest and the lowest — the four numbers Wow computed. A
 * candle that closed above its open wears the host's rise colour and one
 * that closed below its fall colour (`--_fve-rise` / `--_fve-fall`, which
 * the change convention crosses for a market that reads red as up); the
 * tooltip says the direction in words as well, since red and green are one
 * colour to a colour-blind eye. The value axis is the field's and does not
 * start at 0: a price is read against itself.
 */
export function candlestickOption(
  data: CandlestickData,
  context: CandlestickContext,
  theme: ChartTheme,
): EChartsCoreOption {
  const { spec, label, column, animate, pickable, highlight, words } = context;
  const candlestick = spec?.candlestick;
  const candles = drawnCandles(data, context);
  const rise = theme.resolve('var(--_fve-rise)');
  const fall = theme.resolve('var(--_fve-fall)');
  const anyLit = highlight
    ? candles.some(candle => highlight(candle.row))
    : false;
  const titleStyle = { color: theme.axis.color, fontWeight: 500 };
  const measured = candlestick?.close;
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
      data: candles.map(candle => candle.name),
      name: column(candlestick?.x),
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
        const candle = data.candles[dataIndex];
        if (!candle || !candlestick) return '';
        const tone = candle.direction === 'fall' ? fall : rise;
        // Each number named by its column — 「成交价的期初值」 — in the order a
        // K line is read, and the direction in words after them.
        return tooltipHtml(candles[dataIndex]?.name ?? '', [
          ...(['open', 'high', 'low', 'close'] as const).map(slot => ({
            color: tone,
            name: column(candlestick[slot]) ?? slot,
            value: label(candlestick[slot], candle[slot]),
          })),
          { color: tone, name: '', value: words[candle.direction] },
        ]);
      },
    },
    series: [
      {
        type: 'candlestick',
        cursor: pickable ? 'pointer' : 'default',
        barMaxWidth: CANDLE_MAX_WIDTH,
        // The library's order: open, close, lowest, highest.
        data: data.candles.map((candle, index) => ({
          value: [candle.open, candle.close, candle.low, candle.high],
          ...(anyLit && !highlight?.(candles[index].row)
            ? { itemStyle: { opacity: FADED_OPACITY } }
            : {}),
        })),
        itemStyle: {
          color: rise,
          color0: fall,
          borderColor: rise,
          borderColor0: fall,
          borderWidth: theme.line.width * 0.75,
        },
        emphasis: { itemStyle: { borderWidth: theme.line.width } },
      },
    ],
  };
}
