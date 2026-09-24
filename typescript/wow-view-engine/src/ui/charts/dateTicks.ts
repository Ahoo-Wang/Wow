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

import { useMemo } from 'react';
import type { AnalysisColumnView } from '../../analysis/index.js';
import { readInstant } from '../../filter/index.js';
import type { AnalysisDateUnit } from '../../model/index.js';
import type { DisplayContext } from '../display.js';
import { useSurfaceDisplay } from '../ViewSurface.js';

/**
 * The ticks of a time axis, written short: 「9月1日」 and `Sep 1` rather than
 * 「2026年9月1日」 under every bar.
 *
 * A date bucket reads whole in a table cell, a tooltip and the reading
 * table (`displayValue`), where it stands alone. On an axis it stands in a
 * row of its neighbours, which already say the year: the year on each of
 * thirty ticks is thirty times the same word, and it is what slanted the
 * names 45° and ate 70–90px under the plot (2026-09-23 audit P1-1). So, as
 * Metabase writes a time axis, the unit's own part is written — the day, the
 * month, the quarter, the time of day — and what it sits in only where it
 * changes: the year on the first tick and where a new year begins; for
 * hours and minutes, the day as well.
 *
 * Read in the same zone and language `displayValue` reads the bucket in —
 * the zone the group cut it in, else the surface's; a wall-clock key at
 * UTC, which prints it as written — so the short tick and the whole date in
 * the tooltip name the same day.
 */
export type DateTicks = (
  alias: string | undefined,
  values: readonly unknown[],
) => (string | undefined)[] | undefined;

/**
 * The short ticks of the axis an alias is drawn along, or `undefined` when
 * its column is not a date bucket; within them, `undefined` where a key is
 * no time, which the axis then writes as its column reads it.
 */
export function useDateTicks(
  columns: readonly AnalysisColumnView[] | undefined,
): DateTicks {
  const display = useSurfaceDisplay();
  return useMemo(() => {
    const byAlias = new Map(
      (columns ?? []).map(column => [column.alias, column]),
    );
    return (alias, values) => {
      const column = alias === undefined ? undefined : byAlias.get(alias);
      return column?.dateUnit === undefined
        ? undefined
        : shortDateTicks(values, column.dateUnit, {
            locale: display.locale,
            timeZone: column.timeZone ?? display.timeZone,
          });
    };
  }, [columns, display]);
}

/**
 * Each bucket key as its tick, the larger part written only where it
 * changes from the tick before (the first tick has none before it). A year
 * is already one word, so a year axis is left as its column reads it.
 */
export function shortDateTicks(
  values: readonly unknown[],
  unit: AnalysisDateUnit,
  { locale, timeZone }: DisplayContext,
): (string | undefined)[] | undefined {
  if (unit === 'YEAR') return undefined;
  let before: { year: string; day: string } | undefined;
  return values.map(value => {
    const instant =
      value === null || value === undefined ? undefined : readInstant(value);
    if (!instant) return undefined;
    const date = new Date(instant.ms);
    const zone = instant.wallClock ? 'UTC' : timeZone;
    const year = format(date, 'en-US', { year: 'numeric', timeZone: zone });
    const day = format(date, 'en-US', { dateStyle: 'short', timeZone: zone });
    const newYear = before?.year !== year;
    const newDay = before?.day !== day;
    before = { year, day };
    return tick(date, unit, zone, locale, newYear, newDay);
  });
}

function tick(
  date: Date,
  unit: Exclude<AnalysisDateUnit, 'YEAR'>,
  timeZone: string | undefined,
  locale: string | undefined,
  newYear: boolean,
  newDay: boolean,
): string {
  // Years, quarters and months are cut on the Gregorian calendar, so they
  // are named in it whatever calendar the language would pick.
  const inYear: Intl.DateTimeFormatOptions = newYear
    ? { year: 'numeric', calendar: 'gregory', timeZone }
    : { calendar: 'gregory', timeZone };
  switch (unit) {
    case 'QUARTER': {
      const month = Number(
        format(date, 'en-US', { month: 'numeric', timeZone }),
      );
      const quarter = `Q${Math.floor((month - 1) / 3) + 1}`;
      return newYear ? `${format(date, locale, inYear)} ${quarter}` : quarter;
    }
    case 'MONTH':
      return format(date, locale, { ...inYear, month: 'short' });
    case 'WEEK':
    case 'DAY':
      return format(date, locale, {
        ...inYear,
        month: 'short',
        day: 'numeric',
      });
    case 'HOUR':
    case 'MINUTE':
    case 'SECOND':
      return format(date, locale, {
        ...(newDay ? { ...inYear, month: 'short', day: 'numeric' } : {}),
        hour: '2-digit',
        minute: '2-digit',
        ...(unit === 'SECOND' ? { second: '2-digit' } : {}),
        hourCycle: 'h23',
        timeZone,
      });
  }
}

const formatters = new Map<string, Intl.DateTimeFormat>();

/**
 * A formatter per locale and options, built once. A language or a zone the
 * runtime refuses gives way, as `displayValue`'s does, rather than leaving
 * the axis unwritten.
 */
function format(
  date: Date,
  locale: string | undefined,
  options: Intl.DateTimeFormatOptions,
): string {
  const key = JSON.stringify([locale ?? null, options]);
  let found = formatters.get(key);
  if (!found) {
    found =
      build(locale, options) ??
      build(undefined, options) ??
      new Intl.DateTimeFormat(undefined, { ...options, timeZone: undefined });
    formatters.set(key, found);
  }
  return found.format(date);
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
