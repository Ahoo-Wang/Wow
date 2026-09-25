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
import type { HierarchyData, SankeyData } from '../../analysis/index.js';
import { pointAnchor } from '../analysis/DrillMenu.js';
import { useViewMessages } from '../MessagesProvider.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { chartNotes } from './ChartNotes.js';
import { EChart, type ChartClick } from './EChart.js';
import type { FamilyProps } from './family.js';
import {
  drawnFlow,
  drawnParts,
  partOf,
  sankeyOption,
  sunburstOption,
  treeOption,
} from './hierarchyOption.js';
import { useChartMotion } from './motion.js';
import type { ChartTheme } from './theme.js';

/**
 * A sunburst, a tree or a sankey, drawn by ECharts from its option: a
 * whole broken down level by level, or an amount flowing from one level
 * to the next. A press on a part that is one group of the result — an
 * innermost arc or node, a band of a two-level flow — opens the follow-up
 * menu on it; over it, how many rows have no size and, over rows cut short,
 * that the shares are of the groups shown.
 */
export function Hierarchy({
  data,
  spec,
  className,
  label,
  column,
  name,
  onPick,
  cutShort,
  highlight,
}: FamilyProps<HierarchyData | SankeyData>) {
  const animate = useChartMotion();
  const messages = useViewMessages();
  const { locale } = useSurfaceDisplay();
  const pickable = onPick !== undefined;
  const option = useCallback(
    (theme: ChartTheme) => {
      const context = {
        spec,
        label,
        column,
        locale,
        animate,
        pickable,
        highlight,
      };
      return data.type === 'sankey'
        ? sankeyOption(data, context, theme)
        : data.type === 'sunburst'
          ? sunburstOption(data, context, theme)
          : treeOption(data, context, theme);
    },
    [data, spec, label, column, locale, animate, pickable, highlight],
  );
  const onClick = useMemo(
    () =>
      onPick &&
      ((click: ChartClick & { dataType?: string; data?: { id?: unknown } }) => {
        if (click.componentType !== 'series') return;
        const anchor = pointAnchor(
          click.event?.event ?? { clientX: 0, clientY: 0 },
        );
        if (data.type === 'sankey') {
          if (click.dataType !== 'edge' || spec?.sankey?.levels.length !== 2)
            return;
          const band = drawnFlow(data, { spec, label }).bands[click.dataIndex];
          if (band) onPick(band.row, anchor);
          return;
        }
        const place = partOf(click.data);
        const part =
          place === undefined
            ? undefined
            : drawnParts(data, { spec, label, locale })[place];
        if (part?.leaf) onPick(part.row, anchor);
      }),
    [onPick, data, spec, label, locale],
  );
  const notes = [
    ...(cutShort && data.type !== 'sankey'
      ? [messages.label('label.chart.share-basis')]
      : []),
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
      chunk="hierarchy"
      legend={chartNotes(notes, `${data.type}-notes`)}
      data={{
        'data-chart': data.type,
        'data-marks':
          data.type === 'sankey'
            ? data.links.length
            : drawnParts(data, { spec, label, locale }).filter(
                part => part.leaf,
              ).length,
      }}
    />
  );
}
