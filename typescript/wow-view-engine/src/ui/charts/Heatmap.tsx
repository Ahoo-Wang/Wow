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
import { valueLabelsOn, type HeatmapData } from '../../analysis/index.js';
import { pointAnchor } from '../analysis/DrillMenu.js';
import { categoryFit } from './cartesianFit.js';
import { EChart, type ChartClick } from './EChart.js';
import { faded, type Lit } from './highlight.js';
import type { FamilyProps } from './family.js';
import { heatmapLabelsFit, heatmapOption } from './heatmapOption.js';
import { measureText } from './measure.js';
import { useChartMotion } from './motion.js';
import type { ChartTheme } from './theme.js';

/**
 * A heatmap, drawn by ECharts from `heatmapOption` (D21), with its colour
 * scale under the cells. A pressed cell is the group of its row and its
 * column; an empty one is no group, and draws nothing to press.
 */
export function Heatmap({
  data,
  spec,
  className,
  label,
  column,
  name,
  onPick,
  highlight,
}: FamilyProps<HeatmapData>) {
  const animate = useChartMotion();
  const pickable = onPick !== undefined;
  // The cell pressed, when a press set the board's filter (D22 I): the
  // cells are drawn in the order the option lists them, empty ones left out.
  const heatmap = spec?.heatmap;
  const lit = useMemo<Lit | undefined>(() => {
    if (!highlight || !heatmap) return undefined;
    const drawn = data.cells.flatMap((row, y) =>
      row.flatMap((cell, x) => (cell === null ? [] : [{ x, y }])),
    );
    return (_series, at) => {
      const cell = drawn[at];
      return (
        cell !== undefined &&
        highlight({
          [heatmap.x]: data.xs[cell.x],
          [heatmap.y]: data.ys[cell.y],
        })
      );
    };
  }, [highlight, heatmap, data]);
  const option = useCallback(
    (theme: ChartTheme) =>
      faded(
        heatmapOption(data, { spec, label, column, animate, pickable }, theme),
        lit,
      ),
    [data, spec, label, column, animate, pickable, lit],
  );
  // The names along the bottom turn as a bar chart's do, and the numbers
  // are written in every cell or in none (`heatmapLabelsFit`).
  const labelled = valueLabelsOn(spec);
  const adapt = useCallback(
    (
      width: number,
      height: number,
      _window: unknown,
      text: ChartTheme['text'],
    ) => {
      const measure = (name: string) => measureText(name, undefined, text.size);
      return {
        ...categoryFit(
          data.xs.map(x => label(spec?.heatmap?.x, x)),
          width,
          measure,
          false,
        ),
        ...(labelled
          ? {
              series: [
                {
                  label: {
                    show: heatmapLabelsFit(
                      data,
                      { spec, label },
                      width,
                      height,
                      measure,
                      text,
                    ),
                  },
                },
              ],
            }
          : {}),
      };
    },
    [data, spec, label, labelled],
  );
  const onClick = useMemo(
    () =>
      onPick &&
      ((click: ChartClick) => {
        const heatmap = spec?.heatmap;
        const [x, y] = Array.isArray(click.value)
          ? (click.value as number[])
          : [];
        if (click.componentType !== 'series' || !heatmap) return;
        if (x === undefined || y === undefined) return;
        onPick(
          { [heatmap.x]: data.xs[x], [heatmap.y]: data.ys[y] },
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
      adapt={adapt}
      onClick={onClick}
      data={{
        'data-chart': 'heatmap',
        'data-marks': data.cells.flat().filter(cell => cell !== null).length,
        'data-labels': valueLabelsOn(spec) ? 'on' : 'off',
        ...(lit
          ? {
              'data-highlighted': data.cells
                .flat()
                .filter(cell => cell !== null)
                .filter((_cell, at) => lit(0, at)).length,
            }
          : {}),
      }}
    />
  );
}
