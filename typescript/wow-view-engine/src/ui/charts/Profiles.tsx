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
import type { ParallelData, RadarData } from '../../analysis/index.js';
import { CHART_COLOR_SLOTS } from '../../model/index.js';
import { pointAnchor } from '../analysis/DrillMenu.js';
import { useViewMessages } from '../MessagesProvider.js';
import { ChartLegend } from './ChartLegend.js';
import { chartNotes } from './ChartNotes.js';
import { EChart, type ChartClick } from './EChart.js';
import type { FamilyProps } from './family.js';
import { legendAt } from './legend.js';
import { useChartMotion } from './motion.js';
import { drawnProfiles, parallelOption, radarOption } from './profileOption.js';
import type { ChartTheme } from './theme.js';

/**
 * A radar or parallel axes, drawn by ECharts from `radarOption` or
 * `parallelOption`: a shape or a line per group, an axis per metric. A
 * pressed shape or line is its group. The legend names the groups while
 * each wears a colour of its own; what was left out is said over it —
 * a radar's groups past the palette, the groups missing a number.
 */
export function Profiles({
  data,
  spec,
  className,
  label,
  column,
  seriesName,
  name,
  onPick,
  highlight,
}: FamilyProps<RadarData | ParallelData>) {
  const animate = useChartMotion();
  const messages = useViewMessages();
  const pickable = onPick !== undefined;
  const option = useCallback(
    (theme: ChartTheme) => {
      const context = {
        spec,
        label,
        column,
        seriesName,
        animate,
        pickable,
        highlight,
      };
      return data.type === 'radar'
        ? radarOption(data, context, theme)
        : parallelOption(data, context, theme);
    },
    [data, spec, label, column, seriesName, animate, pickable, highlight],
  );
  const drawn = useMemo(
    () => drawnProfiles(data, { spec, label, seriesName }),
    [data, spec, label, seriesName],
  );
  const onClick = useMemo(
    () =>
      onPick &&
      ((click: ChartClick) => {
        if (click.componentType !== 'series') return;
        const profile = drawn[click.dataIndex];
        if (!profile) return;
        onPick(
          profile.row,
          pointAnchor(click.event?.event ?? { clientX: 0, clientY: 0 }),
        );
      }),
    [onPick, drawn],
  );
  // A key is worth drawing while each group wears a colour of its own.
  const at = legendAt(
    spec?.legend,
    drawn.length > 1 && drawn.length <= CHART_COLOR_SLOTS,
  );
  const entries = drawn.map(profile => ({
    key: profile.key,
    label: profile.name,
    color: profile.color,
  }));
  const notes =
    data.omitted > 0
      ? [
          messages.label(`label.chart.${data.type}.omitted`, {
            count: data.omitted,
          }),
        ]
      : [];
  // What was left out is said above the plot, and the key goes with it
  // there; without such a note the key stands where the spec puts it.
  const key =
    at === undefined
      ? undefined
      : (placed: 'top' | 'bottom' | 'right') => (
          <ChartLegend at={placed} entries={entries} />
        );
  const legend =
    notes.length > 0 || at === undefined || key === undefined
      ? chartNotes(notes, `${data.type}-notes`, key)
      : { at, node: key };
  return (
    <EChart
      name={name}
      className={className}
      option={option}
      onClick={onClick}
      chunk="statistics"
      legend={legend}
      legendEntries={at ? entries : undefined}
      data={{
        'data-chart': data.type,
        'data-marks': data.profiles.length,
        'data-axes': data.metrics.length,
      }}
    />
  );
}
