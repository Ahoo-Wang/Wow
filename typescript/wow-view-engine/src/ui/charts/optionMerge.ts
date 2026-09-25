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

import type { EChartsCoreOption } from 'echarts/core';

/**
 * `over` laid onto `base`, object by object, and a list of objects — the
 * series — item by item, as the library merges an option handed to a
 * drawing it already holds: a pie's radius adjusted on a resize and the
 * same adjustment folded into a new drawing land alike. Anything else
 * replaces, a list of numbers included.
 */
export function merged(
  base: EChartsCoreOption,
  over: EChartsCoreOption,
): EChartsCoreOption {
  const out: EChartsCoreOption = { ...base };
  for (const [key, value] of Object.entries(over)) {
    const under = out[key];
    out[key] =
      isPlain(under) && isPlain(value)
        ? merged(under, value)
        : isPlainList(under) && isPlainList(value)
          ? Array.from(
              { length: Math.max(under.length, value.length) },
              (_, index) =>
                value[index] === undefined
                  ? under[index]
                  : under[index] === undefined
                    ? value[index]
                    : merged(under[index], value[index]),
            )
          : value;
  }
  return out;
}

function isPlainList(value: unknown): value is EChartsCoreOption[] {
  return Array.isArray(value) && value.length > 0 && value.every(isPlain);
}

function isPlain(value: unknown): value is EChartsCoreOption {
  return (
    typeof value === 'object' &&
    value !== null &&
    Object.getPrototypeOf(value) === Object.prototype
  );
}
