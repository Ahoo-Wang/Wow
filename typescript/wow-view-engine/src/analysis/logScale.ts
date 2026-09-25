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

import type { CartesianSpec } from '../model/index.js';
import type { CartesianData } from './cartesian.js';

/**
 * Whether a log scale can carry these numbers (D33 batch E): every one of
 * them above zero. A log axis steps by powers of ten and has no place for 0
 * or a negative number — the library drops such a mark without a word — so
 * over values that hold one the choice is greyed with why, and a saved log
 * scale is drawn linear and says so. A value not measured is no number and
 * is not judged; nor is an axis with none, which has nothing to misdraw.
 *
 * Internal: the options page and the renderers read it off this module.
 */
export function logScaleFits(
  values: readonly (number | null | undefined)[],
): boolean {
  return values.every(value => typeof value !== 'number' || value > 0);
}

/**
 * The numbers one value axis of a cartesian chart measures: every point of
 * every series the spec puts on that side, a filled-in 0 among them — it is
 * drawn at 0, which a log axis has no place for either.
 */
export function cartesianAxisValues(
  data: CartesianData,
  spec: CartesianSpec | undefined,
  side: 'left' | 'right',
): (number | null)[] {
  const onSide = new Set(
    data.series
      .filter(entry => {
        const axis = spec?.series.find(
          one => one.metric === entry.metric,
        )?.axis;
        return (axis ?? 'left') === side;
      })
      .map(entry => entry.key),
  );
  return data.points.flatMap(point =>
    [...onSide].map(key => point.values[key] ?? null),
  );
}
