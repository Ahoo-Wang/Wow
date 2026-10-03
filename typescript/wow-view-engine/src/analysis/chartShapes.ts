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
  CHART_COLOR_SLOTS,
  type AnalysisViewConfig,
  type RecordData,
} from '../model/index.js';
import type { HeatmapData, PieData, PieSlice, ScatterData } from './chart.js';
import { num, seriesKey } from './chartRows.js';
import {
  alongPart,
  forwardInTime,
  knownWindow,
  partGroup,
  timeGroup,
  withoutHoles,
  type DateGroup,
} from './timeAxis.js';

/**
 * The shaping of the three families that have no file of their own — a
 * pie, a heatmap, a scatter — and what the time families share: the period
 * still under way, named on their data. `shapeChart` reaches each through
 * the family's row of `FAMILY_RULES` (`familyRules.ts`).
 */

/**
 * Composite key of a heatmap cell. The row key is length-prefixed rather than
 * separated by a character, because no character is barred from a group value
 * and a separator one of them held would split the pair somewhere else.
 */
function cellKey(y: unknown, x: unknown): string {
  const row = seriesKey(y);
  return `${row.length}:${row}${seriesKey(x)}`;
}

/** The chart's data, with the period still under way named when there is one. */
export function withUnfinished<T extends { unfinished?: { at: unknown } }>(
  data: T,
  find: (data: T) => { at: unknown } | undefined,
): T {
  const unfinished = find(data);
  return unfinished ? { ...data, unfinished } : data;
}

/**
 * A pie folds its tail into "other" at `maxSlices`, and at the palette's size
 * when nothing says otherwise — and never past it. The palette holds
 * `CHART_COLOR_SLOTS` colours and a ninth slice would wear the first one
 * again: two wedges one colour, and a legend that cannot say which is which.
 * Folding is only a sum, which every pie can take: its metric adds up, or it
 * is no pie (`chart.pie.not-additive`, D33 Q56).
 *
 * A pie has no axis, so its slices keep the rows' order even over time;
 * once folded they go largest first, since "the rest" means the smallest.
 */
export function shapePie(
  spec: NonNullable<AnalysisViewConfig['chart']['pie']>,
  rows: readonly RecordData[],
): PieData {
  const slices: PieSlice[] = rows.map(row => ({
    category: row[spec.category],
    value: num(row, spec.value) ?? 0,
  }));
  const cap = Math.min(spec.maxSlices ?? CHART_COLOR_SLOTS, CHART_COLOR_SLOTS);
  if (slices.length <= cap) return { type: 'pie', slices };

  const sorted = [...slices].sort((a, b) => b.value - a.value);
  const kept = sorted.slice(0, cap - 1);
  const other = sorted
    .slice(cap - 1)
    .reduce((total, slice) => total + slice.value, 0);
  return {
    type: 'pie',
    slices: [...kept, { category: null, value: other, other: true }],
  };
}

/**
 * A time row or column runs without holes too, as any time axis does, and
 * out to the window its conditions pin when the result is whole
 * (`knownWindow`, as a cartesian axis); the cells of a bucket that had no
 * rows are empty, as every cell the query returned no row for is — a heatmap
 * draws "no group" as no cell, one combination at a time.
 */
export function shapeHeatmap(
  spec: NonNullable<AnalysisViewConfig['chart']['heatmap']>,
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
  timeZone: string,
  now?: Date,
): HeatmapData {
  let xs: unknown[] = [];
  let ys: unknown[] = [];
  const cells = new Map<string, number | null>();

  for (const row of rows) {
    const x = row[spec.x];
    const y = row[spec.y];
    if (!xs.includes(x)) xs.push(x);
    if (!ys.includes(y)) ys.push(y);
    cells.set(cellKey(y, x), num(row, spec.value));
  }
  // Columns run left to right and rows top to bottom, so either one over
  // time reads forward; the cells follow, being looked up by key.
  const across = timeGroup(config, spec.x);
  const down = timeGroup(config, spec.y);
  const same = (key: unknown) => key;
  const window = (axis: DateGroup) =>
    knownWindow(axis, config, rows.length, now, timeZone);
  if (across)
    xs = withoutHoles(
      forwardInTime(xs, same),
      same,
      across,
      timeZone,
      same,
      window(across),
    );
  if (down)
    ys = withoutHoles(
      forwardInTime(ys, same),
      same,
      down,
      timeZone,
      same,
      window(down),
    );
  // A calendar part runs its whole cycle: 星期 × 时段 is seven rows of
  // twenty-four columns whichever hours had orders.
  const cycleAcross = partGroup(config, spec.x);
  const cycleDown = partGroup(config, spec.y);
  if (cycleAcross) xs = alongPart(xs, same, cycleAcross, same);
  if (cycleDown) ys = alongPart(ys, same, cycleDown, same);

  return {
    type: 'heatmap',
    xs,
    ys,
    cells: ys.map(y => xs.map(x => cells.get(cellKey(y, x)) ?? null)),
  };
}

export function shapeScatter(
  spec: NonNullable<AnalysisViewConfig['chart']['scatter']>,
  rows: readonly RecordData[],
): ScatterData {
  return {
    type: 'scatter',
    points: rows.map(row => ({
      category: row[spec.category],
      x: num(row, spec.x) ?? 0,
      y: num(row, spec.y) ?? 0,
      ...(spec.size === undefined ? {} : { size: num(row, spec.size) ?? 0 }),
    })),
  };
}
