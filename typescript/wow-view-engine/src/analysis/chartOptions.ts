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
  type CartesianSpec,
  type ChartSpec,
  type ChartType,
  type FunnelSpec,
  type RecordData,
} from '../model/index.js';
import { familyOf, type OptionsTab } from './chartFamilies.js';

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
 * cartesian family alone has numeric axes worth a page; a scatter's only
 * choices are which metrics it plots; the table's one option is its totals
 * row, which is a display choice.
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

/** Whether every series is stacked; a lone series is not "stacked". */
export function isStacked(spec: CartesianSpec): boolean {
  return (
    spec.series.length > 0 &&
    spec.series.every(series => series.stack !== undefined)
  );
}

/**
 * Stacking is one choice for the whole chart — series stacked in twos and
 * threes is a spec the panel does not offer — so every series joins the
 * one stack or leaves it.
 *
 * Joining it brings every series back onto the one axis: segments that sit
 * on top of each other are being added up, and two scales cannot be added.
 * A stack across two axes is one stack per axis, drawn at the same place
 * and the same width, so the taller one simply hides the other — a chart
 * that reads as broken rather than as a sum. Unstacking leaves them where
 * the stack put them; which series is measured against what is a choice,
 * and the panel does not guess at an old one.
 */
export function withStacked(spec: CartesianSpec, on: boolean): CartesianSpec {
  return {
    ...spec,
    series: spec.series.map(series =>
      on
        ? { ...without(series, 'axis'), stack: 'all' }
        : without(series, 'stack'),
    ),
  };
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
    funnel.stages.order.length > 0
  )
    return chart;
  return {
    ...chart,
    funnel: withStageOrder(funnel, stageValues(rows, funnel.stages.category)),
  };
}

export function withStageOrder(spec: FunnelSpec, order: string[]): FunnelSpec {
  if (spec.stages.from !== 'group') return spec;
  return { ...spec, stages: { ...spec.stages, order } };
}
