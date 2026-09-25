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
import type { BoxplotData } from '../../analysis/index.js';
import { pointAnchor } from '../analysis/DrillMenu.js';
import { useViewMessages } from '../MessagesProvider.js';
import { boxplotOption, drawnBoxes } from './boxplotOption.js';
import { chartNotes } from './ChartNotes.js';
import { EChart, type ChartClick } from './EChart.js';
import type { FamilyProps } from './family.js';
import { useChartMotion } from './motion.js';
import type { ChartTheme } from './theme.js';

/**
 * A boxplot, drawn by ECharts from `boxplotOption`: a box per group. A
 * pressed box is its group. Over it, that the quartiles and the median are
 * approximate — Wow's percentiles are — and how many groups lack one of
 * the five numbers and are not drawn.
 */
export function Boxplot({
  data,
  spec,
  className,
  label,
  column,
  name,
  onPick,
  highlight,
}: FamilyProps<BoxplotData>) {
  const animate = useChartMotion();
  const messages = useViewMessages();
  const pickable = onPick !== undefined;
  const option = useCallback(
    (theme: ChartTheme) =>
      boxplotOption(
        data,
        { spec, label, column, animate, pickable, highlight },
        theme,
      ),
    [data, spec, label, column, animate, pickable, highlight],
  );
  const onClick = useMemo(
    () =>
      onPick &&
      ((click: ChartClick) => {
        if (click.componentType !== 'series') return;
        const box = drawnBoxes(data, { spec, label })[click.dataIndex];
        if (!box) return;
        onPick(
          box.row,
          pointAnchor(click.event?.event ?? { clientX: 0, clientY: 0 }),
        );
      }),
    [onPick, data, spec, label],
  );
  const notes = [
    ...(data.approximate
      ? [messages.label('label.chart.boxplot.approximate')]
      : []),
    ...(data.omitted > 0
      ? [
          messages.label('label.chart.boxplot.omitted', {
            count: data.omitted,
          }),
        ]
      : []),
  ];
  return (
    <EChart
      name={name}
      className={className}
      option={option}
      onClick={onClick}
      chunk="statistics"
      legend={chartNotes(notes, 'boxplot-notes')}
      data={{
        'data-chart': 'boxplot',
        'data-marks': data.boxes.length,
      }}
    />
  );
}
