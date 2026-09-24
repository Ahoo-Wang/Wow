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
import type { WaterfallData } from '../../analysis/index.js';
import { pointAnchor } from '../analysis/DrillMenu.js';
import { useViewMessages } from '../MessagesProvider.js';
import { categoryFit } from './cartesianFit.js';
import { EChart, type ChartClick } from './EChart.js';
import type { FamilyProps } from './family.js';
import { faded, type Lit } from './highlight.js';
import { measureText } from './measure.js';
import { useChartMotion } from './motion.js';
import type { ChartTheme } from './theme.js';
import { drawnBars, waterfallOption } from './waterfallOption.js';

/**
 * A waterfall, drawn by ECharts from `waterfallOption`: each step a bar
 * floating on the running total, the closing total on zero. A pressed step
 * is its group, as a pressed bar is; the total stands for no group and
 * presses nothing. Over rows cut short the total is of the groups shown,
 * which is said over the drawing, as a pie says its shares are.
 */
export function Waterfall({
  data,
  spec,
  className,
  label,
  column,
  name,
  onPick,
  cutShort,
  highlight,
}: FamilyProps<WaterfallData>) {
  const animate = useChartMotion();
  const messages = useViewMessages();
  const pickable = onPick !== undefined;
  const words = useMemo(
    () => ({
      total: messages.label('label.chart.total'),
      increase: messages.label('label.chart.waterfall.increase'),
      decrease: messages.label('label.chart.waterfall.decrease'),
      running: messages.label('label.chart.column.running'),
    }),
    [messages],
  );
  const x = spec?.waterfall?.x;
  const lit = useMemo<Lit | undefined>(
    () =>
      highlight && x !== undefined
        ? (_series, at) => {
            const step = data.steps[at];
            return step !== undefined && highlight({ [x]: step.x });
          }
        : undefined,
    [highlight, x, data],
  );
  const option = useCallback(
    (theme: ChartTheme) =>
      faded(
        waterfallOption(
          data,
          { spec, label, column, animate, pickable, words },
          theme,
        ),
        lit,
      ),
    [data, spec, label, column, animate, pickable, words, lit],
  );
  // The names along the bottom turn as a bar chart's do.
  const adapt = useCallback(
    (width: number) =>
      categoryFit(
        drawnBars(data, { spec, label, words }).map(bar => bar.name),
        width,
        text => measureText(text),
        false,
      ),
    [data, spec, label, words],
  );
  const onClick = useMemo(
    () =>
      onPick &&
      x !== undefined &&
      ((click: ChartClick) => {
        const step = data.steps[click.dataIndex];
        if (click.componentType !== 'series' || !step) return;
        onPick(
          { [x]: step.x },
          pointAnchor(click.event?.event ?? { clientX: 0, clientY: 0 }),
        );
      }),
    [onPick, data, x],
  );
  const totalled = data.total !== undefined;
  return (
    <EChart
      name={name}
      className={className}
      option={option}
      adapt={adapt}
      onClick={onClick || undefined}
      legend={
        cutShort && totalled
          ? {
              at: 'top',
              node: (
                <span
                  data-slot="waterfall-total-basis"
                  className="text-muted-foreground"
                >
                  {messages.label('label.chart.waterfall.total-basis')}
                </span>
              ),
            }
          : undefined
      }
      data={{
        'data-chart': 'waterfall',
        'data-marks': data.steps.length + (totalled ? 1 : 0),
        'data-total': totalled ? 'on' : 'off',
      }}
    />
  );
}
