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
import type { TreemapData } from '../../analysis/index.js';
import { pointAnchor } from '../analysis/DrillMenu.js';
import { useViewMessages } from '../MessagesProvider.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { EChart, type ChartClick } from './EChart.js';
import type { FamilyProps } from './family.js';
import { useChartMotion } from './motion.js';
import type { ChartTheme } from './theme.js';
import { drawnTiles, treemapOption } from './treemapOption.js';

/**
 * A treemap, drawn by ECharts from `treemapOption`: tiles by area, each
 * named on itself. A pressed tile is its group — both of them, when it
 * nests — as a pressed cell of a heatmap is. What it could not draw is
 * said over it: the groups whose number is not above zero, which have no
 * area, and — over rows cut short — that the shares are of the groups
 * shown.
 */
export function Treemap({
  data,
  spec,
  className,
  label,
  column,
  name,
  onPick,
  cutShort,
  highlight,
}: FamilyProps<TreemapData>) {
  const animate = useChartMotion();
  const messages = useViewMessages();
  const { locale } = useSurfaceDisplay();
  const pickable = onPick !== undefined;
  const option = useCallback(
    (theme: ChartTheme) =>
      treemapOption(
        data,
        { spec, label, column, locale, animate, pickable, highlight },
        theme,
      ),
    [data, spec, label, column, locale, animate, pickable, highlight],
  );
  const onClick = useMemo(
    () =>
      onPick &&
      ((click: ChartClick) => {
        const id = (click as ChartClick & { data?: { id?: string } }).data?.id;
        if (click.componentType !== 'series' || !id?.startsWith('t')) return;
        const tile = drawnTiles(data, { spec, label, locale })[
          Number(id.slice(1))
        ];
        if (!tile) return;
        onPick(
          tile.row,
          pointAnchor(click.event?.event ?? { clientX: 0, clientY: 0 }),
        );
      }),
    [onPick, data, spec, label, locale],
  );
  const notes = [
    ...(cutShort ? [messages.label('label.chart.share-basis')] : []),
    ...(data.omitted > 0
      ? [
          messages.label('label.chart.treemap.omitted', {
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
      legend={
        notes.length > 0
          ? {
              at: 'top',
              node: (
                <span
                  data-slot="treemap-notes"
                  className="flex flex-wrap gap-x-3 text-muted-foreground"
                >
                  {notes.map(note => (
                    <span key={note}>{note}</span>
                  ))}
                </span>
              ),
            }
          : undefined
      }
      data={{
        'data-chart': 'treemap',
        'data-marks': drawnTiles(data, { spec, label, locale }).length,
        'data-nested': data.nested ? 'on' : 'off',
      }}
    />
  );
}
