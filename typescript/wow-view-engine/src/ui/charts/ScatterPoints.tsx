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
import type { ScatterData } from '../../analysis/index.js';
import { pointAnchor } from '../analysis/DrillMenu.js';
import { useViewMessages } from '../MessagesProvider.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { EChart, type ChartClick } from './EChart.js';
import { faded, type Lit } from './highlight.js';
import type { FamilyProps } from './family.js';
import { useChartMotion } from './motion.js';
import {
  scatterLogOn,
  scatterLogRefused,
  scatterOption,
} from './scatterOption.js';
import type { ChartTheme } from './theme.js';

/** A scatter, drawn by ECharts from `scatterOption` (D21). */
export function ScatterPoints({
  data,
  spec,
  className,
  label,
  column,
  name,
  onPick,
  highlight,
}: FamilyProps<ScatterData>) {
  const animate = useChartMotion();
  const messages = useViewMessages();
  const { locale } = useSurfaceDisplay();
  const x = messages.label('label.chart.column.x');
  const y = messages.label('label.chart.column.y');
  const pickable = onPick !== undefined;
  // The point pressed, when a press set the board's filter (D22 I).
  const category = spec?.scatter?.category;
  const lit = useMemo<Lit | undefined>(
    () =>
      highlight && category !== undefined
        ? (_series, at) => {
            const point = data.points[at];
            return (
              point !== undefined && highlight({ [category]: point.category })
            );
          }
        : undefined,
    [highlight, category, data],
  );
  const option = useCallback(
    (theme: ChartTheme) =>
      faded(
        scatterOption(
          data,
          {
            spec,
            label,
            column,
            fallback: { x, y },
            animate,
            pickable,
            locale,
          },
          theme,
        ),
        lit,
      ),
    [data, spec, label, column, x, y, animate, pickable, locale, lit],
  );
  // A log scale the points refuse is drawn linear, and says why over the
  // plot, as a cartesian chart's does (D33 batch E).
  const notes = scatterLogRefused(data, spec).map(which =>
    messages.label('label.chart.log-refused', {
      axis: messages.label(`label.chart.axis.${which}`),
    }),
  );
  const onClick = useMemo(
    () =>
      onPick &&
      ((click: ChartClick) => {
        const point = data.points[click.dataIndex];
        const category = spec?.scatter?.category;
        if (
          click.componentType !== 'series' ||
          !point ||
          category === undefined
        )
          return;
        onPick(
          { [category]: point.category },
          pointAnchor(click.event?.event ?? { clientX: 0, clientY: 0 }),
        );
      }),
    [onPick, data, spec],
  );
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
                <div className="flex min-w-0 flex-col gap-1">
                  {notes.map(note => (
                    <p
                      key={note}
                      data-slot="chart-gap-note"
                      className="text-muted-foreground"
                    >
                      {note}
                    </p>
                  ))}
                </div>
              ),
            }
          : undefined
      }
      data={{
        'data-chart': 'scatter',
        'data-log':
          (['x', 'y'] as const)
            .filter(which => scatterLogOn(data, spec, which))
            .join(' ') || undefined,
        'data-marks': data.points.length,
        ...(lit
          ? {
              'data-highlighted': data.points.filter((_point, at) => lit(0, at))
                .length,
            }
          : {}),
      }}
    />
  );
}
