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
import type { CalendarData, ThemeRiverData } from '../../analysis/index.js';
import { pointAnchor } from '../analysis/DrillMenu.js';
import { useViewMessages } from '../MessagesProvider.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { ChartLegend } from './ChartLegend.js';
import { chartNotes } from './ChartNotes.js';
import { EChart, type ChartClick } from './EChart.js';
import type { FamilyProps } from './family.js';
import { legendAt } from './legend.js';
import { useChartMotion } from './motion.js';
import type { ChartTheme } from './theme.js';
import {
  calendarOption,
  dayOf,
  drawnStreams,
  themeRiverOption,
} from './timeOption.js';

/**
 * A calendar heatmap or a theme river, drawn by ECharts from its option. A
 * pressed day is its group; a pressed stream at a bucket is that stream's
 * group there, the folded 「其他」 none. A river says over itself how many
 * points it drew as 0 with nothing to say the group was empty.
 */
export function TimeCharts({
  data,
  spec,
  className,
  label,
  column,
  seriesName,
  name,
  onPick,
  highlight,
}: FamilyProps<CalendarData | ThemeRiverData>) {
  const animate = useChartMotion();
  const messages = useViewMessages();
  const { locale } = useSurfaceDisplay();
  const other = messages.label('label.chart.other');
  const pickable = onPick !== undefined;
  const option = useCallback(
    (theme: ChartTheme) => {
      const context = {
        spec,
        label,
        column,
        seriesName,
        locale,
        animate,
        pickable,
        highlight,
        other,
      };
      return data.type === 'calendar'
        ? calendarOption(data, context, theme)
        : themeRiverOption(data, context, theme);
    },
    [
      data,
      spec,
      label,
      column,
      seriesName,
      locale,
      animate,
      pickable,
      highlight,
      other,
    ],
  );
  const streams = useMemo(
    () =>
      data.type === 'themeRiver'
        ? drawnStreams(data, { spec, label, seriesName, other })
        : [],
    [data, spec, label, seriesName, other],
  );
  const onClick = useMemo(
    () =>
      onPick &&
      ((click: ChartClick & { data?: unknown }) => {
        if (click.componentType !== 'series') return;
        const anchor = pointAnchor(
          click.event?.event ?? { clientX: 0, clientY: 0 },
        );
        if (data.type === 'calendar') {
          const at = dayOf(click.data as { id?: unknown } | undefined);
          const day = at === undefined ? undefined : data.days[at];
          const alias = spec?.calendar?.date;
          if (day && alias !== undefined) onPick({ [alias]: day.at }, anchor);
          return;
        }
        const river = spec?.themeRiver;
        const point = click.data as [number, number, string] | undefined;
        const stream = data.streams.find(entry => entry.key === point?.[2]);
        if (!river || !point || !stream || stream.other) return;
        onPick(
          { [river.x]: data.times[point[0]], [river.splitBy]: stream.value },
          anchor,
        );
      }),
    [onPick, data, spec],
  );
  const at =
    data.type === 'themeRiver'
      ? legendAt(spec?.legend, streams.length > 1)
      : undefined;
  const entries = streams.map(stream => ({
    key: stream.key,
    label: stream.name,
    color: stream.color,
  }));
  const notes =
    data.type === 'themeRiver' && data.uncertain > 0
      ? [
          messages.label('label.chart.themeRiver.uncertain', {
            count: data.uncertain,
          }),
        ]
      : [];
  const key =
    at === undefined
      ? undefined
      : (placed: 'top' | 'bottom' | 'right') => (
          <ChartLegend at={placed} entries={entries} />
        );
  return (
    <EChart
      name={name}
      className={className}
      option={option}
      onClick={onClick}
      chunk="time"
      legend={
        notes.length > 0 || at === undefined || key === undefined
          ? chartNotes(notes, `${data.type}-notes`, key)
          : { at, node: key }
      }
      legendEntries={at ? entries : undefined}
      data={{
        'data-chart': data.type,
        'data-marks':
          data.type === 'calendar' ? data.days.length : data.streams.length,
        ...(data.type === 'calendar'
          ? { 'data-years': data.years.length }
          : {}),
      }}
    />
  );
}
