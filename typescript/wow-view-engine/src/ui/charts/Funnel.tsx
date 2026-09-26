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
import type { FunnelData } from '../../analysis/index.js';
import { pointAnchor } from '../analysis/DrillMenu.js';
import { useViewMessages } from '../MessagesProvider.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { EChart, type ChartClick } from './EChart.js';
import { faded, type Lit } from './highlight.js';
import type { FamilyProps } from './family.js';
import { funnelFit, funnelPlotHeight } from './funnelFit.js';
import { drawnStages, funnelOption, type FunnelWords } from './funnelOption.js';
import { useChartMotion } from './motion.js';
import type { ChartTheme } from './theme.js';

/**
 * A funnel, drawn by ECharts' own funnel from `funnelOption` and fitted to
 * its plot by `funnelFit` (2026-09-25): a trapezoid a stage, the words in
 * or beside it, and between two stages what was lost — the step that lost
 * the largest share said so in words. Over the drawing, one line says the
 * whole funnel's conversion (「总转化 85.4%（交易完成 / 下单）」) and one
 * what its numbers are: the percentage beside a stage is of the first, the
 * words between two are the drop from the one before. A funnel that
 * accumulates says that there too: its numbers are "reached at least this
 * stage", not the stage's own rows the table shows, and a number that
 * differs from the table with nothing beside it reads as a wrong one.
 *
 * Its plot is as tall as its stages need (`funnelPlotHeight`) rather than
 * the frame's 16:9 — unless the host sizes it, as a board's panel does
 * (`className`), when the stages are centred in the panel at their
 * longest.
 *
 * A funnel staged by a dimension's values is pressed a stage at a time, as
 * a bar is (D33 batch C): the stage is its group, which opens the follow-up
 * menu or sets a board's filter as the panel's click says, and on a board
 * the stage pressed stands out. A funnel staged by metrics has no group to
 * press.
 */
export function Funnel({
  data,
  spec,
  className,
  label,
  column,
  name,
  onPick,
  highlight,
}: FamilyProps<FunnelData>) {
  const animate = useChartMotion();
  const messages = useViewMessages();
  const { locale } = useSurfaceDisplay();
  const words = useMemo<FunnelWords>(
    () => ({
      value: messages.label('label.chart.column.value'),
      fromPrevious: messages.label('label.chart.column.conversion.previous'),
      fromFirst: messages.label('label.chart.column.conversion.first'),
      drop: messages.label('label.chart.column.drop'),
      largest: messages.label('label.chart.funnel.largest-drop'),
    }),
    [messages],
  );
  const drawn = useMemo(
    () => drawnStages(data, { spec, label, column, locale }),
    [data, spec, label, column, locale],
  );
  const first = drawn[0];
  const last = drawn[drawn.length - 1];
  // The whole funnel's conversion, when there is a first to be one of.
  const overall =
    drawn.length > 1 && first && last && last.share !== undefined
      ? messages.label('label.chart.funnel.overall', {
          share: last.share,
          last: last.name,
          first: first.name,
        })
      : undefined;
  const cumulative = data.cumulative === true;
  const stages = spec?.funnel?.stages;
  // The dimension a stage is a value of, when it is one.
  const category = stages?.from === 'group' ? stages.category : undefined;
  const pickable = onPick !== undefined && category !== undefined;
  const lit = useMemo<Lit | undefined>(
    () =>
      highlight && category !== undefined
        ? (_series, at) => {
            const stage = data.stages[at];
            return (
              stage !== undefined &&
              'group' in stage &&
              highlight({ [category]: stage.group })
            );
          }
        : undefined,
    [highlight, category, data],
  );
  const option = useCallback(
    (theme: ChartTheme) =>
      faded(
        funnelOption(
          data,
          { spec, label, column, locale, words, animate, pickable },
          theme,
        ),
        lit,
      ),
    [data, spec, label, column, locale, words, animate, pickable, lit],
  );
  const adapt = useMemo(
    () => funnelFit(data, { spec, label, column, locale, words }),
    [data, spec, label, column, locale, words],
  );
  const horizontal = spec?.funnel?.orientation === 'horizontal';
  const onClick = useMemo(
    () =>
      onPick &&
      category !== undefined &&
      ((click: ChartClick) => {
        const stage = data.stages[click.dataIndex];
        if (click.componentType !== 'series' || !stage || !('group' in stage))
          return;
        onPick(
          { [category]: stage.group },
          pointAnchor(click.event?.event ?? { clientX: 0, clientY: 0 }),
        );
      }),
    [onPick, category, data],
  );
  return (
    <EChart
      name={name}
      className={className}
      option={option}
      onClick={onClick || undefined}
      adapt={adapt}
      chunk="statistics"
      // A board's panel sizes its chart; anywhere else the stages do.
      plotHeight={
        className === undefined
          ? funnelPlotHeight(data.stages.length, horizontal)
          : undefined
      }
      legend={
        drawn.length > 1 || cumulative
          ? {
              at: 'top',
              node: (
                <span className="flex flex-wrap gap-x-3 text-muted-foreground">
                  {cumulative && (
                    <span data-slot="funnel-cumulative-note">
                      {messages.label('label.chart.column.cumulative')}
                    </span>
                  )}
                  {overall !== undefined && (
                    <span
                      data-slot="funnel-overall"
                      className="font-medium text-foreground"
                    >
                      {overall}
                    </span>
                  )}
                  {drawn.length > 1 && (
                    <span data-slot="funnel-key">
                      {messages.label('label.chart.funnel.key')}
                    </span>
                  )}
                </span>
              ),
            }
          : undefined
      }
      data={{
        'data-chart': 'funnel',
        'data-marks': data.stages.length,
        'data-orientation': spec?.funnel?.orientation ?? 'vertical',
        'data-cumulative': cumulative ? 'on' : 'off',
        'data-largest-drop': data.largestDrop,
      }}
    />
  );
}
