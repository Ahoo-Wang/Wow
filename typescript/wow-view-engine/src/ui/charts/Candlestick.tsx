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

import { useCallback, useMemo } from 'react';
import type { CandlestickData } from '../../analysis/index.js';
import { pointAnchor } from '../analysis/DrillMenu.js';
import { useViewMessages } from '../MessagesProvider.js';
import { candlestickOption, drawnCandles } from './candlestickOption.js';
import { chartNotes } from './ChartNotes.js';
import { EChart, type ChartClick } from './EChart.js';
import type { FamilyProps } from './family.js';
import { useChartMotion } from './motion.js';
import type { ChartTheme } from './theme.js';

/**
 * A candlestick (K 线), drawn by ECharts from `candlestickOption`: a candle
 * per period. A pressed candle is its period. Over it, how many periods lack
 * one of the four numbers and are not drawn.
 */
export function Candlestick({
  data,
  spec,
  className,
  label,
  column,
  name,
  onPick,
  highlight,
}: FamilyProps<CandlestickData>) {
  const animate = useChartMotion();
  const messages = useViewMessages();
  const pickable = onPick !== undefined;
  const words = useMemo(
    () => ({
      rise: messages.label('label.chart.candlestick.rise'),
      fall: messages.label('label.chart.candlestick.fall'),
      flat: messages.label('label.chart.candlestick.flat'),
    }),
    [messages],
  );
  const option = useCallback(
    (theme: ChartTheme) =>
      candlestickOption(
        data,
        { spec, label, column, animate, pickable, highlight, words },
        theme,
      ),
    [data, spec, label, column, animate, pickable, highlight, words],
  );
  const onClick = useMemo(
    () =>
      onPick &&
      ((click: ChartClick) => {
        if (click.componentType !== 'series') return;
        const candle = drawnCandles(data, { spec, label })[click.dataIndex];
        if (!candle) return;
        onPick(
          candle.row,
          pointAnchor(click.event?.event ?? { clientX: 0, clientY: 0 }),
        );
      }),
    [onPick, data, spec, label],
  );
  const notes =
    data.omitted > 0
      ? [
          messages.label('label.chart.candlestick.omitted', {
            count: data.omitted,
          }),
        ]
      : [];
  return (
    <EChart
      name={name}
      className={className}
      option={option}
      onClick={onClick}
      chunk="statistics"
      legend={chartNotes(notes, 'candlestick-notes')}
      data={{
        'data-chart': 'candlestick',
        'data-marks': data.candles.length,
      }}
    />
  );
}
