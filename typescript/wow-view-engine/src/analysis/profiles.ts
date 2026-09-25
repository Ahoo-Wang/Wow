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
  type ParallelSpec,
  type RadarSpec,
  type RecordData,
} from '../model/index.js';
import { num } from './chartRows.js';
import { forwardInTime, timeGroup } from './timeAxis.js';

/**
 * A group's profile: its value on each of several metrics, in the order
 * the spec lists them — one shape of a radar, one line of parallel axes.
 */
export interface ChartProfile {
  /** The group value it stands for. */
  group: unknown;
  /** One number per metric, in the spec's order. */
  values: number[];
}

export interface RadarData {
  type: 'radar';
  /** The metric aliases, one axis each, in the order they go round. */
  metrics: string[];
  /** One shape per group, at most `CHART_COLOR_SLOTS`, in the rows' order. */
  profiles: ChartProfile[];
  /**
   * How many groups are not drawn: past the palette's size a shape would
   * wear another's colour, and past a handful the shapes cover each other
   * anyway — the first ones in the view's sort are drawn, the table has all.
   * A group missing one of the numbers is counted here too: a shape with a
   * hole in it is no outline.
   */
  omitted: number;
}

export interface ParallelData {
  type: 'parallel';
  /** The metric aliases, one axis each, left to right. */
  metrics: string[];
  /** One line per group, in the rows' order. */
  profiles: ChartProfile[];
  /** How many groups miss one of the numbers, which no line can cross. */
  omitted: number;
}

/**
 * A radar's or parallel axes' metrics (`fitChartSlots`): the ones the spec
 * lists that the shape still measures, in its order — the analyst's, set
 * on the options page — once each, and every quantity while it lists none.
 */
export function profileAxes(
  listed: readonly string[] | undefined,
  quantities: readonly string[],
): string[] {
  const named = (listed ?? []).filter(alias => quantities.includes(alias));
  return named.length > 0 ? [...new Set(named)] : [...quantities];
}

/**
 * The profiles of the rows: each group's numbers on the spec's metrics,
 * a group over time earliest first as every time axis runs, any other in
 * the rows' order — the view's sort, which decides which groups are the
 * first. A row that lacks a number is left out and counted.
 */
function profilesOf(
  spec: RadarSpec | ParallelSpec,
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
): { profiles: ChartProfile[]; missing: number } {
  const ordered = timeGroup(config, spec.category)
    ? forwardInTime(rows, row => row[spec.category])
    : rows;
  const profiles: ChartProfile[] = [];
  let missing = 0;
  for (const row of ordered) {
    const values = spec.metrics.map(alias => num(row, alias));
    if (values.some(value => value === null)) missing += 1;
    else
      profiles.push({ group: row[spec.category], values: values as number[] });
  }
  return { profiles, missing };
}

/**
 * A radar's shapes: every group's profile up to the palette's size, the
 * rest counted rather than drawn in a colour another shape already wears.
 */
export function shapeRadar(
  spec: RadarSpec,
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
): RadarData {
  const { profiles, missing } = profilesOf(spec, config, rows);
  return {
    type: 'radar',
    metrics: [...spec.metrics],
    profiles: profiles.slice(0, CHART_COLOR_SLOTS),
    omitted: missing + Math.max(0, profiles.length - CHART_COLOR_SLOTS),
  };
}

/**
 * Parallel axes' lines: every group's profile. Lines past the palette's
 * size are drawn in one hue (`parallelOption`), since a line is read by
 * where it runs, and the tooltip names it.
 */
export function shapeParallel(
  spec: ParallelSpec,
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
): ParallelData {
  const { profiles, missing } = profilesOf(spec, config, rows);
  return {
    type: 'parallel',
    metrics: [...spec.metrics],
    profiles,
    omitted: missing,
  };
}
