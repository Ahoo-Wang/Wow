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
import { valueLabelsOn, type CartesianData } from '../../analysis/index.js';
import type { ChartSpec, RecordData } from '../../model/index.js';
import { pointAnchor } from '../analysis/DrillMenu.js';
import { useViewMessages } from '../MessagesProvider.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { cartesianFit } from './cartesianFit.js';
import { optionOf } from './cartesianOption.js';
import { cartesianPlan } from './cartesianPlan.js';
import { ChartLegend } from './ChartLegend.js';
import { EChart, type ChartClick } from './EChart.js';
import { faded, type Lit } from './highlight.js';
import type { FamilyProps } from './family.js';
import { legendAt } from './legend.js';
import { measureText } from './measure.js';
import { useChartMotion } from './motion.js';
import type { ChartTheme } from './theme.js';

/**
 * Bar, line, area and combo, drawn by ECharts from `cartesianOption`
 * (docs/design/decisions.md D21): the legend beside the plot, the category
 * names fitted to the width, and a press on a mark handed back as its group.
 */
export function Cartesian({
  data,
  spec,
  className,
  label,
  column,
  dateTicks,
  name,
  onPick,
  highlight,
  filled,
}: FamilyProps<CartesianData>) {
  const animate = useChartMotion();
  const messages = useViewMessages();
  const { locale } = useSurfaceDisplay();
  const join = messages.label('label.filter.join');
  const pickable = onPick !== undefined;
  // A time axis writes its ticks short — the year only where it changes.
  const ticks = useMemo(
    () =>
      dateTicks?.(
        spec?.cartesian?.x,
        data.points.map(point => point.x),
      ),
    [dateTicks, spec, data],
  );
  // The group pressed, when a press set the board's filter: every other
  // mark drawn faint (D22 I).
  const lit = useMemo<Lit | undefined>(() => {
    const cartesian = spec?.cartesian;
    if (!highlight || !cartesian) return undefined;
    return (series, at) => {
      const entry = data.series[series];
      const point = data.points[at];
      return (
        entry !== undefined &&
        point !== undefined &&
        highlight(groupOf(cartesian, point.x, entry.value))
      );
    };
  }, [highlight, spec, data]);
  // What the chart decides before it has a theme or a size, once: which
  // way it lies, its stacks, its scales, what each label writes.
  const plan = useMemo(
    () =>
      cartesianPlan(data, {
        spec,
        label,
        column,
        locale,
        animate,
        pickable,
        ticks,
        join,
        filled,
      }),
    [data, spec, label, column, locale, animate, pickable, ticks, join, filled],
  );
  const option = useCallback(
    (theme: ChartTheme) => faded(optionOf(plan, theme), lit),
    [plan, lit],
  );
  // The names that fit, and the value labels there is room for.
  const adapt = useCallback(
    (width: number, height: number) =>
      cartesianFit(plan, width, height, text => measureText(text)),
    [plan],
  );
  const series = plan.series;
  const at = legendAt(spec?.legend, series.length > 1);
  // The group a bar stands for: its category, and the split value when the
  // series is one — named by the aliases the spec put on the axes, which is
  // what the kernel reads a row by.
  const onClick = useMemo(
    () =>
      onPick &&
      ((click: ChartClick) => {
        const entry = data.series[click.seriesIndex ?? -1];
        const point = data.points[click.dataIndex];
        const cartesian = spec?.cartesian;
        if (click.componentType !== 'series' || !entry || !point || !cartesian)
          return;
        onPick(
          groupOf(cartesian, point.x, entry.value),
          pointAnchor(click.event?.event ?? { clientX: 0, clientY: 0 }),
        );
      }),
    [onPick, data, spec],
  );
  const marks = data.points.reduce(
    (count, point) =>
      count +
      data.series.filter(entry => typeof point.values[entry.key] === 'number')
        .length,
    0,
  );
  return (
    <EChart
      name={name}
      className={className}
      option={option}
      adapt={adapt}
      onClick={onClick}
      legend={
        at && {
          at,
          node: placed => (
            <ChartLegend
              at={placed}
              entries={series.map(entry => ({
                key: entry.key,
                label: entry.name,
                color: entry.color,
              }))}
            />
          ),
        }
      }
      data={{
        'data-chart': data.chart,
        'data-marks': marks,
        'data-labels': valueLabelsOn(spec) ? 'on' : 'off',
        ...(lit ? { 'data-highlighted': litCount(data, lit) } : {}),
        'data-orientation': plan.horizontal ? 'horizontal' : 'vertical',
      }}
    />
  );
}

/**
 * The group a mark stands for: its category, and the split value when the
 * series is one — named by the aliases the spec put on the axes, which is
 * what the kernel reads a row by.
 */
function groupOf(
  cartesian: NonNullable<ChartSpec['cartesian']>,
  x: unknown,
  split: unknown,
): RecordData {
  return {
    [cartesian.x]: x,
    ...(cartesian.splitBy === undefined ? {} : { [cartesian.splitBy]: split }),
  };
}

/** How many drawn marks stand for the group pressed. */
function litCount(data: CartesianData, lit: Lit): number {
  let count = 0;
  data.series.forEach((entry, series) =>
    data.points.forEach((point, at) => {
      if (typeof point.values[entry.key] === 'number' && lit(series, at))
        count += 1;
    }),
  );
  return count;
}
