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
import { valueLabelsOn, type PieData } from '../../analysis/index.js';
import { pointAnchor } from '../analysis/DrillMenu.js';
import { AnalysisEmpty } from '../analysis/EmptyResult.js';
import { useViewMessages } from '../MessagesProvider.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { formatShare } from './axis.js';
import { ChartLegend } from './ChartLegend.js';
import { EChart, type ChartClick } from './EChart.js';
import { faded, type Lit } from './highlight.js';
import type { FamilyProps } from './family.js';
import { legendAt } from './legend.js';
import { measureText } from './measure.js';
import { useChartMotion } from './motion.js';
import { drawnSlices, pieCaptions, pieFit, pieOption } from './pieOption.js';
import type { ChartTheme } from './theme.js';

/**
 * How wide a pie's plot grows beside its legend, against its height: the
 * circle and room either side for the shares written outside it. Wider, the
 * legend stood a hand's width from the slices it names.
 */
export const PIE_HUG = 1.3;

/**
 * A pie or a donut, drawn by ECharts from `pieOption` (D21).
 *
 * The legend is the key to the picture, so it is always there unless the
 * spec says none — beside the pie by default, as Metabase draws a donut's,
 * and under it on a frame too narrow for both (`LEGEND_BESIDE_MIN`) — and
 * it leads with what the slices measure, the column's own title, then each
 * slice with its share. The labels outside the slices are written whole or
 * not at all, as the plot's size allows (`pieFit`): the legend holds every
 * share either way. When the rows are the first groups of more
 * the lead also says the shares are of those groups: the remainder is not
 * on the pie, and a share read as of the whole would be wrong by exactly it.
 */
export function PieSlices({
  data,
  spec,
  className,
  label,
  column,
  seriesName,
  toneOf,
  adds,
  name,
  onPick,
  cutShort,
  highlight,
}: FamilyProps<PieData>) {
  const animate = useChartMotion();
  const messages = useViewMessages();
  const { locale } = useSurfaceDisplay();
  const other = messages.label('label.chart.other');
  const total = messages.label('label.chart.total');
  const measured = spec?.pie?.value;
  const additive = adds?.(measured) ?? false;
  const pickable = onPick !== undefined;
  // The slice pressed, when a press set the board's filter: every other
  // drawn faint (D22 I); the merged remainder stands for no one group.
  const category = spec?.pie?.category;
  const lit = useMemo<Lit | undefined>(
    () =>
      highlight && category !== undefined
        ? (_series, at) => {
            const slice = data.slices[at];
            return (
              slice !== undefined &&
              slice.other !== true &&
              highlight({ [category]: slice.category })
            );
          }
        : undefined,
    [highlight, category, data],
  );
  const option = useCallback(
    (theme: ChartTheme) =>
      faded(
        pieOption(
          data,
          {
            spec,
            label,
            seriesName,
            toneOf,
            locale,
            other,
            total,
            adds: additive,
            animate,
            pickable,
          },
          theme,
        ),
        lit,
      ),
    [
      data,
      spec,
      label,
      seriesName,
      toneOf,
      locale,
      other,
      total,
      additive,
      animate,
      pickable,
      lit,
    ],
  );
  const slices = useMemo(
    () => drawnSlices(data, { spec, label, other, seriesName, toneOf }),
    [data, spec, label, other, seriesName, toneOf],
  );
  const donut = spec?.pie?.donut === true;
  // Whether the plot has room for the labels, whole (`pieFit`).
  const adapt = useCallback(
    (
      width: number,
      height: number,
      _window: unknown,
      text: ChartTheme['text'],
    ) =>
      pieFit(
        pieCaptions(data, { spec, label, locale, other, seriesName }),
        width,
        height,
        caption => measureText(caption, undefined, text.size),
        donut,
        text,
      ),
    [data, spec, label, locale, other, seriesName, donut],
  );
  // The merged remainder is not a group of the result: it stands for
  // several, and no one condition selects them.
  const onClick = useMemo(
    () =>
      onPick &&
      ((click: ChartClick) => {
        const slice = data.slices[click.dataIndex];
        const category = spec?.pie?.category;
        if (
          click.componentType !== 'series' ||
          !slice ||
          slice.other === true ||
          category === undefined
        )
          return;
        onPick(
          { [category]: slice.category },
          pointAnchor(click.event?.event ?? { clientX: 0, clientY: 0 }),
        );
      }),
    [onPick, data, spec],
  );
  const at = legendAt(spec?.legend, true, 'right');
  const entries = slices.map(slice => ({
    key: slice.key,
    label: slice.name,
    color: slice.color,
    ...(slice.share === undefined
      ? {}
      : { value: formatShare(slice.share, locale) }),
  }));
  const measure = column(measured);
  // No slice is no pie: the library drew a grey ring, and the legend its
  // lead alone. A result of no groups says so wherever the pie is drawn.
  if (data.slices.length === 0) return <AnalysisEmpty />;
  return (
    <EChart
      name={name}
      className={className}
      option={option}
      adapt={adapt}
      onClick={onClick}
      hug={PIE_HUG}
      legendEntries={at ? entries : undefined}
      legend={
        at && {
          at,
          node: placed => (
            <ChartLegend
              at={placed}
              lead={
                measure !== undefined && (
                  <li
                    data-slot="pie-measure"
                    className="text-foreground font-medium"
                  >
                    {cutShort
                      ? `${measure} · ${messages.label('label.chart.share-basis')}`
                      : measure}
                  </li>
                )
              }
              entries={entries}
            />
          ),
        }
      }
      data={{
        'data-chart': spec?.pie?.donut === true ? 'donut' : 'pie',
        'data-marks': data.slices.length,
        'data-labels': valueLabelsOn(spec) ? 'on' : 'off',
        ...(lit
          ? {
              'data-highlighted': data.slices.filter((_slice, at) => lit(0, at))
                .length,
            }
          : {}),
      }}
    />
  );
}
