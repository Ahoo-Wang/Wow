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

/** What one value axis has to reach: its marks, lines and whether it counts. */
export interface AxisReach {
  /** The lowest a mark or a line reaches; 0 or below, since bars start at 0. */
  low: number;
  /** The highest a mark or a line reaches; 0 or above. */
  high: number;
  /** Every number on it is whole: its steps are whole too (`allWhole`). */
  whole: boolean;
}

/** An axis's scale: its two ends and the step between its ticks. */
export interface NiceScale {
  min: number;
  max: number;
  interval: number;
}

/** The leading digits a step is written in: 1, 2, 2.5 and 5 of a power of ten. */
const NICE = [1, 2, 2.5, 5];

/** How many steps an axis is cut into, and the count preferred among them. */
const STEPS = { fewest: 2, most: 8, preferred: 5 };

/**
 * The cost of a step count away from the preferred one, against the room
 * it wastes: a scale of two steps that fits the data snugly still beats one
 * of five that leaves half the plot empty.
 */
const OFF_PREFERRED = 0.1;

/**
 * The smallest nice step of at least `wanted`: 1, 2, 2.5 or 5 times a power
 * of ten — and, on an axis of whole numbers, a whole one of at least 1,
 * since a count stepped by 0.5 writes its format's rounding as a second
 * 「1」 (and 2.5 records is no number of anything).
 */
export function niceStep(wanted: number, whole: boolean): number {
  if (!(wanted > 0)) return 1;
  let power = 10 ** Math.floor(Math.log10(wanted));
  for (;;) {
    for (const lead of NICE) {
      const step = exact(lead * power);
      if (step < wanted) continue;
      if (whole && (step < 1 || !Number.isInteger(step))) continue;
      return step;
    }
    power *= 10;
  }
}

/**
 * The scales of one plot's value axes — one or two — cut into the same
 * number of steps, with zero on the same line.
 *
 * With two axes there is one set of gridlines, and the right axis's ticks
 * have to stand on it: the library's `alignTicks` did that by stretching
 * the follower into whatever step the leader's count gave it, and a count
 * following an amount came out in halves (「0.5」「1.5」 records, found in
 * the 2026-09-23 audit). Here every axis gets a nice step of its own and
 * they share only the count — so each reads in round numbers, a count in
 * whole ones, and the ticks line up by construction. Zero shares a line
 * too: two sets of bars starting from two baselines would not be one chart.
 *
 * The count is the one that wastes the least of the plot on the worst of
 * the axes, a count near five preferred. Owning the scale also lets the
 * value labels be judged against the space a mark really gets
 * (`cartesianFit`), which the library's own rounding would keep to itself.
 */
export function sharedScales(axes: readonly AxisReach[]): NiceScale[] {
  const negative = axes.some(axis => axis.low < 0);
  const positive = axes.some(axis => axis.high > 0) || !negative;
  let best: { score: number; scales: NiceScale[] } | undefined;
  for (let steps = STEPS.fewest; steps <= STEPS.most; steps += 1) {
    for (let below = negative ? 1 : 0; below <= steps; below += 1) {
      const above = steps - below;
      if (positive && above === 0) continue;
      if (!negative && below > 0) break;
      const scales = axes.map(axis => {
        const wanted = Math.max(
          above > 0 ? axis.high / above : 0,
          below > 0 ? -axis.low / below : 0,
        );
        const step = niceStep(wanted, axis.whole);
        return {
          min: exact(-below * step),
          max: exact(above * step),
          interval: step,
        };
      });
      const waste = Math.max(
        ...scales.map((scale, index) => {
          const span = axes[index].high - axes[index].low;
          return span > 0 ? (scale.max - scale.min) / span : 1;
        }),
      );
      const score = waste + OFF_PREFERRED * Math.abs(steps - STEPS.preferred);
      if (!best || score < best.score) best = { score, scales };
    }
  }
  return best!.scales;
}

/** A float as the decimal it stands for: 3 × 0.1 is 0.3, not 0.30000000000000004. */
function exact(value: number): number {
  return Number(value.toPrecision(12));
}
