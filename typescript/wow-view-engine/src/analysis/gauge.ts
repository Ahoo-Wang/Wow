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

import type { GaugeSpec, RecordData } from '../model/index.js';
import { num } from './chartRows.js';

export interface GaugeData {
  type: 'gauge';
  /** The one number, `null` when the row has none. */
  value: number | null;
  /** Where it should reach, when the spec names a target. */
  target?: number;
  /** The share of the target reached (`value / target`), over a target above 0. */
  reached?: number;
  /** The scale's two ends, `min` below `max`. */
  min: number;
  max: number;
  /**
   * The value lies past an end the spec pinned: the needle stops at that
   * end, and the drawing says the number is off the scale rather than
   * letting the needle lie about it.
   */
  beyond?: 'above' | 'below';
}

/**
 * A gauge's number and its scale: the one row's value, the spec's target,
 * and the two ends — the spec's where it pins them, otherwise from 0 (or
 * the value, when it is below 0) to a round number at or past the value
 * and the target (`roundUp`), so a needle left to the kernel never runs
 * off its dial and the target always stands on it.
 */
export function shapeGauge(
  spec: GaugeSpec,
  rows: readonly RecordData[],
): GaugeData {
  const row = rows[0];
  const value = row === undefined ? null : num(row, spec.metric);
  const target = spec.target;
  const reached =
    value !== null && target !== undefined && target > 0
      ? value / target
      : undefined;
  const reaches = [value ?? 0, target ?? 0];
  const min = spec.min ?? roundDown(Math.min(...reaches));
  let max = spec.max ?? roundUp(Math.max(...reaches));
  // A scale with no length has nowhere to put a needle.
  if (!(max > min)) max = min + 1;
  const beyond =
    value === null
      ? undefined
      : value > max
        ? 'above'
        : value < min
          ? 'below'
          : undefined;
  return {
    type: 'gauge',
    value,
    ...(target === undefined ? {} : { target }),
    ...(reached === undefined ? {} : { reached }),
    min,
    max,
    ...(beyond === undefined ? {} : { beyond }),
  };
}

/**
 * The round number at or above `value`: one, two, two and a half or five
 * times a power of ten — 870 becomes 1,000, 1,230 becomes 2,000. A scale
 * whose end is a round number reads its ticks at a glance.
 */
export function roundUp(value: number): number {
  // Nothing above 0 to reach: the scale ends at 0, as it starts there.
  if (value <= 0) return 0;
  const power = 10 ** Math.floor(Math.log10(value));
  const step = [1, 2, 2.5, 5, 10].find(
    factor => factor * power >= value * (1 - Number.EPSILON),
  )!;
  return step * power;
}

/** 0, or below it the round number at or under `value`, by the same steps. */
function roundDown(value: number): number {
  return value >= 0 ? 0 : -roundUp(-value);
}
