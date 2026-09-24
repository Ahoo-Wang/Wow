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

import type {
  AnalysisViewConfig,
  RecordData,
  WaterfallSpec,
} from '../model/index.js';
import { num } from './chartRows.js';
import { forwardInTime, timeGroup } from './timeAxis.js';

/** One step of a waterfall: its group, its change, and where it floats. */
export interface WaterfallStep {
  /** The group value the step stands for. */
  x: unknown;
  /** The change the step brings, as its row measured it. */
  value: number;
  /** The running total before the step. */
  start: number;
  /** The running total after it: `start + value`. */
  end: number;
}

export interface WaterfallData {
  type: 'waterfall';
  steps: WaterfallStep[];
  /**
   * The total the steps arrive at, drawn as the closing bar from zero —
   * left out when the spec asked for none (`WaterfallSpec.total`). It is
   * the sum of the steps drawn, so the chart closes on itself; over rows
   * cut short it is the total of the groups shown, which a drawing says.
   */
  total?: number;
}

/**
 * A waterfall's steps, each its row's number and the running total it
 * starts and ends at, from zero. A step over time runs earliest first, as
 * every time axis does; any other dimension keeps the rows' order, which is
 * the view's sort — the steps are read in that order, so it is the
 * analyst's. A period the rows lack is no step: nothing changed in it that
 * the rows know of, and a 0 written in would say what the query did not.
 */
export function shapeWaterfall(
  spec: WaterfallSpec,
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
): WaterfallData {
  const ordered = timeGroup(config, spec.x)
    ? forwardInTime(rows, row => row[spec.x])
    : rows;
  let running = 0;
  const steps = ordered.map(row => {
    const value = num(row, spec.value) ?? 0;
    const start = running;
    running += value;
    return { x: row[spec.x], value, start, end: running };
  });
  return {
    type: 'waterfall',
    steps,
    ...(spec.total === false ? {} : { total: running }),
  };
}
