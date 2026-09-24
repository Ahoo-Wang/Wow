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

import { useCallback, useMemo, type ReactNode } from 'react';
import { valueLabelsOn, type CartesianData } from '../../analysis/index.js';
import type { ChartSpec, RecordData } from '../../model/index.js';
import { pointAnchor } from '../analysis/DrillMenu.js';
import { useViewMessages } from '../MessagesProvider.js';
import { useSurfaceDisplay } from '../ViewSurface.js';
import { cartesianFit } from './cartesianFit.js';
import { optionOf } from './cartesianOption.js';
import { cartesianPlan, withoutHidden } from './cartesianPlan.js';
import { zooms, type ZoomWindow } from './cartesianZoom.js';
import { ChartLegend } from './ChartLegend.js';
import { EChart, type ChartClick } from './EChart.js';
import { faded, type Lit } from './highlight.js';
import type { FamilyProps } from './family.js';
import { legendAt } from './legend.js';
import { DERIVED_STROKE } from './cartesianMarks.js';
import { gapNotes, markWords } from './markWords.js';
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
  hidden,
  onToggleSeries,
  zoomGestures,
}: FamilyProps<CartesianData>) {
  const animate = useChartMotion();
  const messages = useViewMessages();
  const { locale } = useSurfaceDisplay();
  const join = messages.label('label.filter.join');
  const against = messages.label('label.chart.change.against');
  // What the lines and points drawn over the marks are called (batch B).
  const words = useMemo(() => markWords(messages), [messages]);
  // What the spec asked to draw over the marks and the rows could not
  // carry, each said with why above the plot (Q53).
  const notes = useMemo(
    () => gapNotes(messages, data, column),
    [messages, data, column],
  );
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
  // The same short ticks for some of the buckets: the ones a thinned axis
  // names (`datedFit`).
  const tickFor = useMemo(
    () =>
      ticks &&
      ((values: readonly unknown[]) => dateTicks?.(spec?.cartesian?.x, values)),
    [ticks, dateTicks, spec],
  );
  // The series drawn, in the order the marks are: the legend's switched-off
  // ones taken out, so a mark's index names its series.
  const drawn = useMemo(() => withoutHidden(data, hidden), [data, hidden]);
  // The group pressed, when a press set the board's filter: every other
  // mark drawn faint (D22 I).
  const lit = useMemo<Lit | undefined>(() => {
    const cartesian = spec?.cartesian;
    if (!highlight || !cartesian) return undefined;
    return (series, at) => {
      const entry = drawn.series[series];
      const point = data.points[at];
      return (
        entry !== undefined &&
        point !== undefined &&
        highlight(groupOf(cartesian, point.x, entry.value))
      );
    };
  }, [highlight, spec, data, drawn]);
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
        tickFor,
        join,
        filled,
        hidden,
        against,
        zoomGestures,
        words,
      }),
    [
      data,
      spec,
      label,
      column,
      locale,
      animate,
      pickable,
      ticks,
      tickFor,
      join,
      filled,
      hidden,
      against,
      zoomGestures,
      words,
    ],
  );
  const option = useCallback(
    (theme: ChartTheme) => faded(optionOf(plan, theme), lit),
    [plan, lit],
  );
  // The names that fit, and the value labels there is room for.
  const adapt = useCallback(
    (width: number, height: number, window?: ZoomWindow) =>
      cartesianFit(plan, width, height, text => measureText(text), window),
    [plan],
  );
  // The legend lists every series, the switched-off ones too, and after
  // them the derived lines, each a dash in the ink it is drawn in.
  const legend = [
    ...plan.legend.map(entry => ({
      key: entry.key,
      label: entry.name,
      color: entry.color,
      hidden: hidden?.has(entry.key) === true,
    })),
    ...plan.derivedLegend.map(line => ({
      key: line.key,
      label: line.name,
      color: 'currentColor',
      dashed: DERIVED_STROKE[line.kind],
      hidden: hidden?.has(line.key) === true,
    })),
  ];
  const listed = legendAt(spec?.legend, legend.length > 1);
  // A note has somewhere to stand even with no legend: above the plot.
  const at = listed ?? (notes.length > 0 ? 'top' : undefined);
  // The group a bar stands for: its category, and the split value when the
  // series is one — named by the aliases the spec put on the axes, which is
  // what the kernel reads a row by.
  const onClick = useMemo(
    () =>
      onPick &&
      ((click: ChartClick) => {
        const entry = drawn.series[click.seriesIndex ?? -1];
        const point = data.points[click.dataIndex];
        const cartesian = spec?.cartesian;
        if (click.componentType !== 'series' || !entry || !point || !cartesian)
          return;
        onPick(
          groupOf(cartesian, point.x, entry.value),
          pointAnchor(click.event?.event ?? { clientX: 0, clientY: 0 }),
        );
      }),
    [onPick, data, drawn, spec],
  );
  const marks = data.points.reduce(
    (count, point) =>
      count +
      drawn.series.filter(entry => typeof point.values[entry.key] === 'number')
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
      zoomFor={data}
      legend={
        at && {
          at,
          node: placed => (
            <ChartNotes notes={notes}>
              {listed && (
                <ChartLegend
                  at={placed}
                  onToggle={onToggleSeries}
                  entries={legend}
                />
              )}
            </ChartNotes>
          ),
        }
      }
      data={{
        'data-chart': data.chart,
        'data-marks': marks,
        'data-labels': valueLabelsOn(spec) ? 'on' : 'off',
        ...(lit ? { 'data-highlighted': litCount(drawn, lit) } : {}),
        'data-orientation': plan.horizontal ? 'horizontal' : 'vertical',
        // How a long axis zooms: by its slider, by gestures too, or not.
        'data-zoom': zooms(plan)
          ? zoomGestures
            ? 'gestures'
            : 'slider'
          : undefined,
      }}
    />
  );
}

/**
 * What a chart says about what it did not draw, above its legend: one quiet
 * line a reason (`gapNotes`). Without notes, the legend as it is.
 */
function ChartNotes({
  notes,
  children,
}: {
  notes: readonly string[];
  children: ReactNode;
}) {
  if (notes.length === 0) return children;
  return (
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
      {children}
    </div>
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
