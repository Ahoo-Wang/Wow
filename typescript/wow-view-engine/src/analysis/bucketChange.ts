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

import { readInstant } from '../filter/index.js';
import type { CartesianData } from './chart.js';

/**
 * How one bucket's number moved from the bucket right before it: the
 * difference, and that difference as a share of the earlier number — `null`
 * where the earlier number is 0, which no share can be taken of.
 */
export interface BucketChange {
  delta: number;
  ratio: number | null;
}

/**
 * The change of series `key` at point `index` from the point before it, on
 * a time axis whose points are consecutive buckets (`CartesianData.timeline`)
 * — the tooltip's 「较上一期」 (docs/design/decisions.md D33, Q59).
 *
 * It asks for nothing the rows do not hold: both numbers are the chart's own,
 * so there is no second query. And it says nothing it cannot know: not on a
 * category axis, where the point before is no earlier period; not on a time
 * axis with a bucket skipped; not for the first bucket, nor for the missing
 * value's bucket or the one after it; and not where either number is missing
 * — a hole the kernel could not tell was empty, or `missing: 'gap'`. A 0 the
 * kernel filled in is a known 0 (`absenceReader`), and is compared like any.
 */
export function bucketChange(
  data: CartesianData,
  key: string,
  index: number,
): BucketChange | undefined {
  if (data.timeline !== true || index < 1) return undefined;
  const at = data.points[index];
  const before = data.points[index - 1];
  if (!at || !before || !readInstant(at.x) || !readInstant(before.x))
    return undefined;
  const value = at.values[key];
  const was = before.values[key];
  if (typeof value !== 'number' || typeof was !== 'number') return undefined;
  const delta = value - was;
  return { delta, ratio: was === 0 ? null : delta / Math.abs(was) };
}
