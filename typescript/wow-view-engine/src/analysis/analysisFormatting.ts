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

import { fixedTimeZoneOffset } from '../lib/timeZone.js';
import type { DeepReadonly } from '../lib/types.js';
import type { AnalysisResultColumn } from './analysisModel.js';
// Bound retention when host-defined formats vary; reuse expensive Intl instances across cells.
const numberFormats = new Map<string, Intl.NumberFormat>();
function numberFormat(locale: string, options: Intl.NumberFormatOptions) {
  const key = JSON.stringify([locale, options]);
  let formatter = numberFormats.get(key);
  if (!formatter) {
    formatter = new Intl.NumberFormat(locale, options);
    if (numberFormats.size >= 64)
      numberFormats.delete(numberFormats.keys().next().value!);
    numberFormats.set(key, formatter);
  }
  return formatter;
}
/** Display-only formatting. Keep the original value for titles, sorting and tuple keys. */
export function formatAnalysisValue(
  value: unknown,
  column?: DeepReadonly<AnalysisResultColumn>,
  timeZone?: string,
): string {
  if (value === null || value === undefined) return '无值';
  const label = column?.options?.find(option => option.value === value)?.label;
  if (label !== undefined) return label;
  try {
    if (column?.valueType === 'datetime' && typeof value === 'number') {
      const offset = fixedTimeZoneOffset(timeZone);
      return new Intl.DateTimeFormat('zh-CN', {
        timeZone: offset === undefined ? (timeZone ?? 'UTC') : 'UTC',
        dateStyle: 'medium',
        timeStyle: 'medium',
      }).format(value + (offset ?? 0) * 60000);
    }
    if (typeof value === 'number') {
      const { locale = 'zh-CN', ...options } = column?.numberFormat ?? {};
      if (column?.aggregation === 'COUNT' || column?.format === 'count')
        return numberFormat(locale, {
          useGrouping: true,
          maximumFractionDigits: 0,
        }).format(value);
      return numberFormat(
        locale,
        Object.keys(options).length
          ? options
          : value !== 0 && Math.abs(value) < 0.001
            ? { maximumSignificantDigits: 6 }
            : { maximumFractionDigits: 3 },
      ).format(value);
    }
  } catch {
    /* Invalid host format settings must not hide admitted data. */
  }
  return String(value);
}
