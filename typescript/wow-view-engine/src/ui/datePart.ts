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

import { DATE_PART_DOMAINS, type AnalysisDatePart } from '../model/index.js';

/**
 * A calendar part's key as the part is called in the reader's language:
 * the ISO weekday 1 as 「周一」 / "Mon", the hour 20 as 「20时」 / "20:00", the
 * month 9 as 「9月」 / "Sep", the day 3 as 「3日」 / "3". Each is formatted
 * off a fixed day of a known weekday in UTC, so no zone moves it: the key
 * was read in its zone by the source already. A key outside the part's
 * domain is not one of its values and is shown as it came.
 */
export function datePartValue(
  value: unknown,
  part: AnalysisDatePart,
  locale: string | undefined,
): string | undefined {
  const key = typeof value === 'string' ? Number(value) : value;
  if (typeof key !== 'number' || !Number.isInteger(key)) return undefined;
  const [first, last] = DATE_PART_DOMAINS[part];
  if (key < first || key > last) return undefined;
  const utc = { timeZone: 'UTC' } as const;
  switch (part) {
    case 'DAY_OF_WEEK':
      // 2024-01-01 was a Monday, ISO weekday 1.
      return format(new Date(Date.UTC(2024, 0, key)), locale, {
        ...utc,
        weekday: 'short',
      });
    case 'HOUR_OF_DAY': {
      const hour = format(new Date(Date.UTC(2024, 0, 1, key)), locale, {
        ...utc,
        hour: 'numeric',
        hourCycle: 'h23',
      });
      // A language whose hour is a bare number (English's 「20」) reads it
      // as a clock time instead, so it is not taken for a count.
      return /^\d+$/.test(hour) ? `${key}:00` : hour;
    }
    case 'DAY_OF_MONTH':
      return format(new Date(Date.UTC(2024, 0, key)), locale, {
        ...utc,
        day: 'numeric',
      });
    case 'MONTH_OF_YEAR':
      return format(new Date(Date.UTC(2024, key - 1, 1)), locale, {
        ...utc,
        month: 'short',
      });
  }
}

const formatters = new Map<string, Intl.DateTimeFormat>();

/** One formatter per language and options; an unknown language gives way. */
function format(
  date: Date,
  locale: string | undefined,
  options: Intl.DateTimeFormatOptions,
): string {
  const key = JSON.stringify([locale ?? null, options]);
  let found = formatters.get(key);
  if (!found) {
    try {
      found = new Intl.DateTimeFormat(locale, options);
    } catch {
      found = new Intl.DateTimeFormat(undefined, options);
    }
    formatters.set(key, found);
  }
  return found.format(date);
}
