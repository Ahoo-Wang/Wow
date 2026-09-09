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

import { TZDate } from '@date-fns/tz';
import { fixedTimeZoneOffset } from '../lib/timeZone.js';
import type { FilterDateTimeValue } from './filterModel.js';

/** Keep incomplete/invalid input available for correction; discard only valid subsecond precision. */
export function timeToSeconds(value: string): string {
  return value.replace(/^((?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d)\.\d{1,9}$/, '$1');
}

/** Filter controls use seconds; the query protocol still carries epoch milliseconds. */
export function dateTimeToSeconds(value: unknown): unknown {
  if (typeof value === 'number' && Number.isFinite(value))
    return Math.floor(value / 1000) * 1000;
  if (
    value &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    'time' in value &&
    typeof value.time === 'string'
  )
    return { ...value, time: timeToSeconds(value.time) };
  return value;
}

export function dateText(date: Date): string {
  return [
    String(date.getFullYear()).padStart(4, '0'),
    String(date.getMonth() + 1).padStart(2, '0'),
    String(date.getDate()).padStart(2, '0'),
  ].join('-');
}

export function calendarDate(value: string | undefined): Date | undefined {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return undefined;
  const [year, month, day] = value.split('-').map(Number);
  const date = new Date(0);
  date.setFullYear(year, month - 1, day);
  date.setHours(0, 0, 0, 0);
  return dateText(date) === value ? date : undefined;
}

export function dateTimeValue(
  value: unknown,
  timeZone?: string,
): FilterDateTimeValue {
  if (value === undefined || value === null) return {};
  if (typeof value === 'object' && !Array.isArray(value))
    return value as FilterDateTimeValue;
  if (typeof value === 'number' && Number.isFinite(value)) {
    try {
      const offset = fixedTimeZoneOffset(timeZone);
      const date =
        offset !== undefined
          ? new TZDate(value + offset * 60_000, 'UTC')
          : timeZone
            ? new TZDate(value, timeZone)
            : new Date(value);
      if (Number.isFinite(date.getTime())) {
        const time = [date.getHours(), date.getMinutes(), date.getSeconds()]
          .map(part => String(part).padStart(2, '0'))
          .join(':');
        return {
          date: dateText(date),
          offsetMinutes:
            offset === undefined ? date.getTimezoneOffset() : -offset,
          time:
            time +
            (date.getMilliseconds()
              ? `.${String(date.getMilliseconds()).padStart(3, '0')}`
              : ''),
        };
      }
    } catch {
      /* The compiler reports invalid field timezone metadata. */
    }
  }
  return { date: String(value) };
}
