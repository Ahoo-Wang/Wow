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

import {
  without,
  type CartesianSeries,
  type CartesianSpec,
  type ChartSpec,
  type ChartType,
  type FunnelSpec,
  type RecordData,
} from '../model/index.js';
import { familyOf, seriesMark, type OptionsTab } from './chartFamilies.js';

/**
 * The rules behind the visualization panel's second level (D20 屏 J): what
 * a chart's options are made of, and how one setting changes the spec
 * without breaking another. The panel itself is markup; every edit that
 * has a rule in it lives here, where it can be tested without a DOM.
 */

/** What the panel shows as chosen: a chart type, or the table. */
export type Picked = ChartType | 'table';

/**
 * Which pages this chart has. Every drawn family has data slots; the
 * cartesian family and the scatter have numeric axes worth a page — their
 * titles, bounds, formats and scales (D33 batch E); the table's one option
 * is its totals row, which is a display choice.
 */
export function optionTabs(picked: Picked): readonly OptionsTab[] {
  return picked === 'table' ? TABLE_TABS : familyOf(picked).tabs;
}

/** The table's one option is its totals row, a display choice. */
const TABLE_TABS: readonly OptionsTab[] = ['display'];

/**
 * An alias put in one slot, taken out of the other if it was there: a chart
 * that split by the axis it plots along is `chart.splitBy.same-as-x`, so
 * choosing for one slot what the other holds swaps them rather than
 * doubling up. The same rule places a heatmap's two axes and a scatter's
 * two metrics.
 */
export function withSlot<S extends object, K extends keyof S>(
  spec: S,
  at: K,
  other: K,
  alias: string,
): S {
  const swapped = spec[other] === alias ? { [other]: spec[at] } : {};
  return { ...spec, [at]: alias, ...swapped };
}

/**
 * Whether a series of a chart of this type stacks: a bar or an area does, a
 * line never. Stacked, a line's point is drawn at the running sum while its
 * label says its own value — 「¥640」 at the height of ¥1920 — and a line
 * has no band under it to show the part it adds, so the reader can only
 * read the point off the axis, and reads the sum (2026-09-23 audit P0-2).
 * Metabase offers stacking on bars and areas alone.
 */
export function stacks(
  type: ChartType,
  series: Pick<CartesianSeries, 'type'>,
): boolean {
  return seriesMark(type, series) !== 'line';
}

/**
 * Whether the display page offers stacking for this type at all: a line
 * chart's every series is a line, so there is nothing that could stack.
 */
export function offersStacking(type: ChartType): boolean {
  return type !== 'line';
}

/**
 * Whether every series that can stack is stacked; a lone series is not
 * "stacked", and neither is a chart with nothing that stacks. A combo's
 * lines are left out of the question: they draw their own values whatever
 * the bars do.
 */
export function isStacked(
  spec: CartesianSpec,
  type: ChartType = 'bar',
): boolean {
  const stackable = spec.series.filter(series => stacks(type, series));
  return (
    stackable.length > 0 &&
    stackable.every(series => series.stack !== undefined)
  );
}

/**
 * Stacking is one choice for the whole chart — series stacked in twos and
 * threes is a spec the panel does not offer — so every series that can
 * stack joins the one stack or leaves it. A combo's line stays out of it,
 * and keeps its axis: it is measured, not added.
 *
 * Joining it brings every stacked series back onto the one axis: segments
 * that sit on top of each other are being added up, and two scales cannot
 * be added. A stack across two axes is one stack per axis, drawn at the
 * same place and the same width, so the taller one simply hides the other —
 * a chart that reads as broken rather than as a sum. Unstacking leaves them
 * where the stack put them; which series is measured against what is a
 * choice, and the panel does not guess at an old one.
 */
export function withStacked(
  spec: CartesianSpec,
  on: boolean,
  type: ChartType = 'bar',
): CartesianSpec {
  // Shares are a way of reading a stack; with no stack there is nothing
  // left for them to be shares of.
  const rest = on ? spec : without(spec, 'percentStack');
  return {
    ...rest,
    series: spec.series.map(series =>
      !on
        ? without(series, 'stack')
        : stacks(type, series)
          ? { ...without(series, 'axis'), stack: 'all' }
          : series,
    ),
  };
}

/**
 * Whether the display page offers 「百分比堆叠」 for this chart: a bar or an
 * area chart — a combo's lines would be drawn against a scale of shares —
 * whose every series adds up (`additive`: a share is a part of a sum, and
 * an average has no whole to be part of), with something to stack — a
 * split, or two series or more. One series alone would be 100% everywhere.
 */
export function offersPercentStack(
  spec: CartesianSpec,
  type: ChartType,
  additive: ReadonlySet<string>,
): boolean {
  return (
    (type === 'bar' || type === 'area') &&
    spec.series.length > 0 &&
    spec.series.every(series => additive.has(series.metric)) &&
    (spec.splitBy !== undefined || spec.series.length > 1)
  );
}

/** Whether the chart draws its stacks as shares: stacked, and asked to. */
export function isPercentStacked(
  spec: CartesianSpec,
  type: ChartType = 'bar',
): boolean {
  return spec.percentStack === true && isStacked(spec, type);
}

/**
 * 「百分比堆叠」 on or off. On stacks the chart as well (`withStacked`) —
 * shares are a reading of a stack, and a box that turned on and drew the
 * same side-by-side bars would be a box that did nothing; off leaves the
 * stack standing and draws its values again.
 */
export function withPercentStack(
  spec: CartesianSpec,
  on: boolean,
  type: ChartType = 'bar',
): CartesianSpec {
  return on
    ? { ...withStacked(spec, true, type), percentStack: true }
    : without(spec, 'percentStack');
}

export function isSmooth(spec: CartesianSpec): boolean {
  return (
    spec.series.length > 0 &&
    spec.series.every(series => series.smooth === true)
  );
}

export function withSmooth(spec: CartesianSpec, on: boolean): CartesianSpec {
  return {
    ...spec,
    series: spec.series.map(series =>
      on ? { ...series, smooth: true } : without(series, 'smooth'),
    ),
  };
}

/**
 * The list with the item at `from` taken out and put back at `to`, or as it
 * was when either place is not one this list has.
 *
 * Both ways a list on this panel is reordered end up here: an arrow key on a
 * handle names the place one step away, and a drop names the place it landed
 * on, which may be any of them.
 */
export function withMovedTo<T>(
  items: readonly T[],
  from: number,
  to: number,
): T[] {
  if (from < 0 || from >= items.length || to < 0 || to >= items.length)
    return [...items];
  const next = [...items];
  next.splice(to, 0, ...next.splice(from, 1));
  return next;
}

/** The list with the item at `index` moved one step, or as it was at an end. */
export function withMoved<T>(
  items: readonly T[],
  index: number,
  step: -1 | 1,
): T[] {
  return withMovedTo(items, index, index + step);
}

/**
 * The values of a group as the result lists them, once each and in the
 * order the rows came — the order the stages of a funnel are offered in
 * before the analyst puts them in business order. Only a text value can be
 * a stage: the kernel reads a stage's name back as a string key, and a
 * number or a boolean would never match itself.
 */
export function stageValues(
  rows: readonly RecordData[],
  category: string,
): string[] {
  const seen = new Set<string>();
  for (const row of rows) {
    const value = row[category];
    if (typeof value === 'string') seen.add(value);
  }
  return [...seen];
}

/**
 * A funnel whose stages are a group's values needs their order, which is
 * business knowledge the kernel does not have (`fitChartSlots` leaves it
 * empty). Until the analyst has said otherwise, the order the result came
 * in is the one to start from — a funnel of no stages draws nothing, and a
 * chart that draws nothing when picked reads as broken.
 *
 * An order of one stage is no order either — a view saved before a funnel
 * was offered only where it draws can hold one, and picked again it would
 * refuse again — so it is completed the same way: the stage it names first,
 * then the values the rows add. An order of two or more is the analyst's
 * and is kept as it stands.
 */
export function withStagesFrom(
  chart: ChartSpec,
  rows: readonly RecordData[],
): ChartSpec {
  const funnel = chart.funnel;
  if (
    chart.type !== 'funnel' ||
    !funnel ||
    funnel.stages.from !== 'group' ||
    funnel.stages.order.length >= 2
  )
    return chart;
  const named = funnel.stages.order;
  return {
    ...chart,
    funnel: withStageOrder(funnel, [
      ...named,
      ...stageValues(rows, funnel.stages.category).filter(
        value => !named.includes(value),
      ),
    ]),
  };
}

export function withStageOrder(spec: FunnelSpec, order: string[]): FunnelSpec {
  if (spec.stages.from !== 'group') return spec;
  return { ...spec, stages: { ...spec.stages, order } };
}
