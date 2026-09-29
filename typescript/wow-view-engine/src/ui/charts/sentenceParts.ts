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

import type { ReadingContext } from './reading.js';

/*
 * The pieces every chart sentence is built from (`sentence.ts`): the
 * highest and the lowest of what was measured, as a sentence names them,
 * and which way a run of values went.
 */

/** One measured number of the chart, and what it is called. */
export interface Named {
  name: string;
  value: number | null;
  /** The column it prints as. */
  alias?: string;
}

/** The highest and the lowest measured number; none without one. */
export function highLow(
  items: readonly Named[],
):
  | { high: Named & { value: number }; low: Named & { value: number } }
  | undefined {
  let high: (Named & { value: number }) | undefined;
  let low: (Named & { value: number }) | undefined;
  for (const item of items) {
    if (typeof item.value !== 'number') continue;
    const measured = { ...item, value: item.value };
    if (!high || measured.value > high.value) high = measured;
    if (!low || measured.value < low.value) low = measured;
  }
  return high && low ? { high, low } : undefined;
}

/** The two extremes as a sentence's parameters, each as its column prints. */
export function said(
  ctx: ReadingContext,
  { high, low }: NonNullable<ReturnType<typeof highLow>>,
) {
  return {
    high: high.name,
    highValue: ctx.label(high.alias, high.value),
    low: low.name,
    lowValue: ctx.label(low.alias, low.value),
  };
}

/**
 * Which way a run of values went, read along all of them: the least-squares
 * line through them, from where it starts to where it ends (`direction`).
 * One outlying first or last period no longer decides it.
 */
export function fittedDirection(
  values: readonly number[],
): 'up' | 'down' | 'flat' {
  const n = values.length;
  if (n < 2) return 'flat';
  const meanX = (n - 1) / 2;
  const meanY = values.reduce((sum, value) => sum + value, 0) / n;
  let across = 0;
  let spread = 0;
  values.forEach((value, index) => {
    across += (index - meanX) * (value - meanY);
    spread += (index - meanX) ** 2;
  });
  const slope = spread === 0 ? 0 : across / spread;
  return direction(meanY - slope * meanX, meanY + slope * meanX);
}

/**
 * Which way a line went from its first point to its last: up or down past a
 * twentieth of where it started, level otherwise.
 */
export function direction(from: number, to: number): 'up' | 'down' | 'flat' {
  const change = to - from;
  const scale = Math.abs(from) || Math.abs(to);
  if (scale === 0 || Math.abs(change) <= scale * 0.05) return 'flat';
  return change > 0 ? 'up' : 'down';
}
