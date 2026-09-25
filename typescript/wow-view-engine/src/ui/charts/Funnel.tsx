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
import { conversionHeading, type FamilyProps } from './family.js';
import { funnelOption } from './funnelOption.js';
import { useChartMotion } from './motion.js';
import type { ChartTheme } from './theme.js';

/**
 * A funnel, drawn by ECharts from `funnelOption` (D21): the centred shape
 * Metabase draws rather than the left-aligned bars it replaced, each stage
 * with its name, value and conversion beside it. What the percentages are
 * relative to is said once, over the drawing, where the heading of their
 * column used to stand (`conversionHeading`) — a bare 「25%」 reads as a
 * share of the whole, which it is only against the first stage. A funnel
 * that accumulates says that there too: its numbers are "reached at least
 * this stage", not the stage's own rows the table shows, and a number that
 * differs from the table with nothing beside it reads as a wrong one.
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
  const converts = data.stages.some(stage => stage.conversion !== undefined);
  const conversion = messages.label(
    conversionHeading(spec?.funnel?.conversion),
  );
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
          { spec, label, column, locale, conversion, animate, pickable },
          theme,
        ),
        lit,
      ),
    [data, spec, label, column, locale, conversion, animate, pickable, lit],
  );
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
      legend={
        converts || cumulative
          ? {
              at: 'top',
              node: (
                <span className="flex flex-wrap gap-x-3 text-muted-foreground">
                  {cumulative && (
                    <span data-slot="funnel-cumulative-note">
                      {messages.label('label.chart.column.cumulative')}
                    </span>
                  )}
                  {converts && (
                    <span data-slot="funnel-conversion-heading">
                      {conversion}
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
      }}
    />
  );
}
