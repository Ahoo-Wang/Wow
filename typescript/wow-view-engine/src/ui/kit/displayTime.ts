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

/**
 * A time as the surface reads it: an instant, a wall-clock day or a bucket
 * key shown in the surface's language and zone (`readTime`, `bucket`), the
 * short reading a table cell writes (`tableTime`), and the two readings a
 * file name and a 「更新于」 line need (`isoDay`, `readingTime`) — every one
 * through the same cache of formatters (`format`), so a page of times builds
 * each formatter once.
 */

import {
  AggregationDateUnits,
  type AggregationDateUnitName,
  type EpochTimeUnit,
} from '../../model/index.js';
import { readInstant } from '../../filter/index.js';
import type { DisplayContext, DisplayField } from './display.js';

/**
 * A time and the zone to show it in. An instant shows in `timeZone`. A
 * wall-clock string is read as if at UTC and shown in UTC, which prints it as
 * written whatever zone is in force: `2026-09-18` stays the 18th in Los
 * Angeles, and `09:30` stays 09:30 in a browser on another clock.
 *
 * `dayOnly` says the string carried no time of day, which is a meaning and
 * not a gap: a bound written as a calendar day stands for the whole of it.
 */
export function readTime(
  value: unknown,
  timeZone: string | undefined,
  unit?: EpochTimeUnit,
): { date: Date; timeZone: string | undefined; dayOnly?: true } | undefined {
  // The date kind's own reader, so what a cell shows and what the record
  // kernel calls a column's earliest are the same reading of the same value.
  const instant = readInstant(value, unit);
  if (!instant) return undefined;
  const date = new Date(instant.ms);
  if (!instant.wallClock) return { date, timeZone };
  return instant.dayOnly
    ? { date, timeZone: 'UTC', dayOnly: true }
    : { date, timeZone: 'UTC' };
}

/**
 * A time as a table cell writes it: short, and numeric where the language
 * writes it so — `09-17 21:19` in Chinese, `Sep 17, 9:19 PM` in English —
 * with the year only when it is not the current one (`2025-09-17 21:19`,
 * `Sep 17, 2025, 9:19 PM`), and the seconds only when the column asks for
 * them (`timePrecision: 'second'`). `undefined` for anything that is not a
 * moment read as a `datetime`, or a day that names no time of day, which
 * reads as `displayValue` says.
 *
 * The whole time, seconds and year included, is what `displayValue` says,
 * and the cell carries it as its title (second review R2-23: two columns of
 * 「2026年9月17日 21:19:08」 were 360px of a 1440px table).
 */
export function tableTime(
  value: unknown,
  field: DisplayField,
  context: DisplayContext,
): string | undefined {
  if (
    value === null ||
    value === undefined ||
    (field.cell ?? field.kind) !== 'datetime' ||
    field.dateUnit !== undefined ||
    field.datePart !== undefined ||
    (field.options !== undefined && field.options.length > 0)
  )
    return undefined;
  const time = readTime(value, context.timeZone, field.timeUnit);
  if (!time || time.dayOnly) return undefined;
  const now = context.clock?.now() ?? new Date();
  const thisYear =
    yearOf(time.date, time.timeZone) === yearOf(now, context.timeZone);
  const seconds = field.timePrecision === 'second';
  if (writesNumeric(context.locale)) {
    const parts = numericParts(time.date, time.timeZone);
    const day = `${parts.month}-${parts.day}`;
    const clock = `${parts.hour}:${parts.minute}${seconds ? `:${parts.second}` : ''}`;
    return `${thisYear ? day : `${parts.year}-${day}`} ${clock}`;
  }
  return format(time.date, context.locale, {
    ...(thisYear ? {} : { year: 'numeric' }),
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    ...(seconds ? { second: '2-digit' } : {}),
    timeZone: time.timeZone,
  });
}

/** The Gregorian year of a moment on a clock. */
function yearOf(date: Date, timeZone: string | undefined): string {
  return format(date, 'en-US', gregorianYear(timeZone));
}

/**
 * Whether the language writes a short date in numbers: Chinese writes
 * 「09-17 21:19」 where English writes a month's name.
 */
function writesNumeric(locale: string | undefined): boolean {
  const resolved = formatter(locale, {}).resolvedOptions().locale;
  return resolved.split('-')[0] === 'zh';
}

/** A moment's calendar and clock fields, two digits each, on a 24-hour clock. */
function numericParts(
  date: Date,
  timeZone: string | undefined,
): Record<'year' | 'month' | 'day' | 'hour' | 'minute' | 'second', string> {
  const parts = formatter('en-US', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
    calendar: 'gregory',
    timeZone,
  }).formatToParts(date);
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find(one => one.type === type)?.value ?? '';
  return {
    year: part('year'),
    month: part('month'),
    day: part('day'),
    hour: part('hour'),
    minute: part('minute'),
    second: part('second'),
  };
}

/**
 * One day as `2026-09-20`, on the surface's clock.
 *
 * `en-CA` is what writes a date in that order whatever the host's language,
 * and the calendar is pinned to the Gregorian one: a file named for a Hijri
 * day would sort beside nothing and name a day the data is not filed under.
 * It is the zone the surface shows times in, so the day in the file's name is
 * the day its rows read as.
 */
export function isoDay(date: Date, context: DisplayContext): string {
  return format(date, 'en-CA', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    calendar: 'gregory',
    timeZone: context.timeZone,
  });
}

/**
 * When something was read, as a reader glances at it (「更新于 10:32」): the
 * time of day on the surface's clock, in its language, and the date before
 * it once that was not today — a board left open overnight must not pass
 * yesterday's numbers off as this morning's.
 */
export function readingTime(
  at: Date,
  now: Date,
  context: DisplayContext,
): string {
  const today = isoDay(at, context) === isoDay(now, context);
  return format(at, context.locale, {
    ...(today ? {} : { dateStyle: 'medium' }),
    timeStyle: 'short',
    timeZone: context.timeZone,
  });
}

/**
 * A bucket key as the bucket it starts: a day, a month, a quarter.
 *
 * Years, quarters and months are cut on the Gregorian calendar, so they are
 * named in it whatever calendar the language would pick: a Persian or a Hijri
 * month would name a period the bucket does not cover. The quarter is counted in digits a
 * number can be read from, not in the language's numerals.
 */
export function bucket(
  date: Date,
  unit: AggregationDateUnitName,
  timeZone: string | undefined,
  locale: string | undefined,
): string {
  switch (unit) {
    case AggregationDateUnits.YEAR:
      return format(date, locale, gregorianYear(timeZone));
    case AggregationDateUnits.QUARTER: {
      const month = Number(
        format(date, 'en-US', { month: 'numeric', timeZone }),
      );
      const quarter = Math.floor((month - 1) / 3) + 1;
      return `${format(date, locale, gregorianYear(timeZone))} Q${quarter}`;
    }
    case AggregationDateUnits.MONTH:
      return format(date, locale, {
        ...gregorianYear(timeZone),
        month: 'long',
      });
    case AggregationDateUnits.WEEK:
    case AggregationDateUnits.DAY:
      return format(date, locale, { dateStyle: 'medium', timeZone });
    case AggregationDateUnits.HOUR:
    case AggregationDateUnits.MINUTE:
      return format(date, locale, {
        dateStyle: 'medium',
        timeStyle: 'short',
        timeZone,
      });
    case AggregationDateUnits.SECOND:
      return format(date, locale, {
        dateStyle: 'medium',
        timeStyle: 'medium',
        timeZone,
      });
  }
}

function gregorianYear(
  timeZone: string | undefined,
): Intl.DateTimeFormatOptions {
  return { year: 'numeric', calendar: 'gregory', timeZone };
}

export function format(
  date: Date,
  locale: string | undefined,
  options: Intl.DateTimeFormatOptions,
): string {
  return formatter(locale, options).format(date);
}

const formatters = new Map<string, Intl.DateTimeFormat>();

/**
 * A formatter per locale and options, built once: a table formats every cell
 * of a page, and building one is the expensive part. A bad setting never
 * leaves the value unshown. An unknown language gives way first, since a time
 * on the wrong clock is wrong where one in the runtime's language is only
 * foreign; then an unknown zone; and the runtime's own is the last resort.
 */
function formatter(
  locale: string | undefined,
  options: Intl.DateTimeFormatOptions,
): Intl.DateTimeFormat {
  const key = JSON.stringify([locale ?? null, options]);
  let found = formatters.get(key);
  if (!found) {
    const anyZone = { ...options, timeZone: undefined };
    found =
      build(locale, options) ??
      build(undefined, options) ??
      build(locale, anyZone) ??
      new Intl.DateTimeFormat(undefined, anyZone);
    formatters.set(key, found);
  }
  return found;
}

function build(
  locale: string | undefined,
  options: Intl.DateTimeFormatOptions,
): Intl.DateTimeFormat | undefined {
  try {
    return new Intl.DateTimeFormat(locale, options);
  } catch {
    return undefined;
  }
}
