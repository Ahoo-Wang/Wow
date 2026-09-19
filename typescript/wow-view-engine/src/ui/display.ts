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

import type {
  AnalysisDateUnit,
  FieldOption,
  NumberFormat,
} from '../model/index.js';
import type { MessageFormatters } from './MessagesProvider.js';

/** Where a value is shown: the language, and the zone its times read in. */
export interface DisplayContext {
  /** A BCP 47 tag; the runtime's own when left out. */
  locale?: string;
  /** An IANA zone; the runtime's own when left out. */
  timeZone?: string;
}

/** What a column knows about the field behind it. */
export interface DisplayField {
  kind?: string;
  /** Renderer key; the kind's when the field names none. */
  cell?: string;
  options?: readonly FieldOption[];
  /** For a date histogram group: its keys are the starts of these buckets. */
  dateUnit?: AnalysisDateUnit;
  /** The zone those buckets were cut in, when the group named one. */
  timeZone?: string;
}

const EPOCH = /^-?\d+$/;

/**
 * A day, or a day and a time, with no offset: `2026-09-18`, or
 * `2026-09-18T09:30:00` as Java writes a `LocalDateTime`. It names a time on
 * a clock rather than a moment, and the filter kernel reads it on the
 * engine's; shown on any other clock it would move.
 */
const WALL_CLOCK =
  /^(\d{4}-\d{2}-\d{2})(?:[T ](\d{2}:\d{2})(?::(\d{2})(?:\.(\d+))?)?)?$/;

/**
 * A value as its field shows it, or `undefined` when the field's kind has
 * nothing to add and the caller's own rendering stands: a number keeps its
 * format, a boolean its wording.
 *
 * Wow keeps a time as epoch milliseconds, so a raw table is a column of
 * thirteen-digit numbers; and an enum is a code the definition has already
 * named. Times read in the context's zone, which is the engine's: the one a
 * relative filter such as "today" is evaluated in, so what a row is filtered
 * by and what it shows agree.
 */
export function displayValue(
  value: unknown,
  field: DisplayField,
  context: DisplayContext,
): string | undefined {
  if (value === null || value === undefined) return undefined;
  if (field.options && field.options.length > 0) {
    const label = optionLabel(value, field.options);
    if (label !== undefined) return label;
  }
  if (field.dateUnit !== undefined) {
    const time = readTime(value, field.timeZone ?? context.timeZone);
    return time
      ? bucket(time.date, field.dateUnit, time.timeZone, context.locale)
      : undefined;
  }
  switch (field.cell ?? field.kind) {
    case 'datetime': {
      const time = readTime(value, context.timeZone);
      return time
        ? format(time.date, context.locale, {
            dateStyle: 'medium',
            timeStyle: 'medium',
            timeZone: time.timeZone,
          })
        : undefined;
    }
    case 'date': {
      const time = readTime(value, context.timeZone);
      return time
        ? format(time.date, context.locale, {
            dateStyle: 'medium',
            timeZone: time.timeZone,
          })
        : undefined;
    }
    default:
      return undefined;
  }
}

/**
 * A time and the zone to show it in. An instant shows in `timeZone`. A
 * wall-clock string is read as if at UTC and shown in UTC, which prints it as
 * written whatever zone is in force: `2026-09-18` stays the 18th in Los
 * Angeles, and `09:30` stays 09:30 in a browser on another clock.
 */
function readTime(
  value: unknown,
  timeZone: string | undefined,
): { date: Date; timeZone: string | undefined } | undefined {
  const wall = typeof value === 'string' ? WALL_CLOCK.exec(value.trim()) : null;
  if (wall) {
    const [, day, minutes = '00:00', seconds = '00', fraction = ''] = wall;
    // A Date holds milliseconds; Java writes up to nine digits.
    const millis = `${fraction}000`.slice(0, 3);
    const written = `${day}T${minutes}:${seconds}`;
    const date = new Date(`${written}.${millis}Z`);
    // `Date` rolls a day that does not exist over into the next month, and
    // 24:00 into the next day: `2025-02-29` would show as the 1st of March.
    // What it does not read back as written is left for the caller to print.
    return !Number.isNaN(date.getTime()) &&
      date.toISOString().startsWith(written)
      ? { date, timeZone: 'UTC' }
      : undefined;
  }
  const date = toDate(value);
  return date && { date, timeZone };
}

/** A number in the format its field declared; as written when it has none. */
export function formatNumber(value: number, format?: NumberFormat): string {
  const formatter = format && numberFormatter(format);
  return formatter ? formatter.format(value) : String(value);
}

/**
 * A value an analysis shows when its field's kind has nothing to add: a number
 * in its format, or grouped the runtime's way without one; a boolean in the
 * catalogue's words; anything else as text. A table cell and a chart category
 * read the same, so a currency group is not a bare number on the axis.
 */
export function valueText(
  value: unknown,
  messages: MessageFormatters,
  format?: NumberFormat,
): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'number') {
    const formatter = format && numberFormatter(format);
    return formatter ? formatter.format(value) : value.toLocaleString();
  }
  if (typeof value === 'boolean')
    return messages.label(value ? 'label.value.yes' : 'label.value.no');
  if (typeof value === 'string') return value;
  return JSON.stringify(value) ?? '';
}

/** The label of each value an enum holds; `undefined` when none is known. */
function optionLabel(
  value: unknown,
  options: readonly FieldOption[],
): string | undefined {
  const labelOf = (item: unknown) =>
    options.find(option => option.value === item)?.label;
  if (!Array.isArray(value)) return labelOf(value);
  const labels = value.map(item => labelOf(item) ?? String(item));
  return value.some(item => labelOf(item) !== undefined)
    ? labels.join(', ')
    : undefined;
}

function toDate(value: unknown): Date | undefined {
  const date =
    value instanceof Date
      ? value
      : typeof value === 'number'
        ? new Date(value)
        : typeof value === 'string' && value.trim() !== ''
          ? new Date(EPOCH.test(value) ? Number(value) : value)
          : undefined;
  return date && !Number.isNaN(date.getTime()) ? date : undefined;
}

/**
 * A bucket key as the bucket it starts: a day, a month, a quarter.
 *
 * Years, quarters and months are cut on the Gregorian calendar, so they are
 * named in it whatever calendar the language would pick: a Persian or a Hijri
 * month would name a period the bucket does not cover. The quarter is counted in digits a
 * number can be read from, not in the language's numerals.
 */
function bucket(
  date: Date,
  unit: AnalysisDateUnit,
  timeZone: string | undefined,
  locale: string | undefined,
): string {
  switch (unit) {
    case 'YEAR':
      return format(date, locale, gregorianYear(timeZone));
    case 'QUARTER': {
      const month = Number(
        format(date, 'en-US', { month: 'numeric', timeZone }),
      );
      const quarter = Math.floor((month - 1) / 3) + 1;
      return `${format(date, locale, gregorianYear(timeZone))} Q${quarter}`;
    }
    case 'MONTH':
      return format(date, locale, {
        ...gregorianYear(timeZone),
        month: 'long',
      });
    case 'WEEK':
    case 'DAY':
      return format(date, locale, { dateStyle: 'medium', timeZone });
    case 'HOUR':
    case 'MINUTE':
      return format(date, locale, {
        dateStyle: 'medium',
        timeStyle: 'short',
        timeZone,
      });
    case 'SECOND':
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

function format(
  date: Date,
  locale: string | undefined,
  options: Intl.DateTimeFormatOptions,
): string {
  return formatter(locale, options).format(date);
}

const numberFormatters = new Map<string, Intl.NumberFormat | null>();

/**
 * The formatter a field's `numberFormat` asks for, built once, or `null` when
 * Intl will not build it. The type admits what Intl refuses — a locale such
 * as `zh_CN`, a currency style with no currency — and the throw used to take
 * the whole table down mid-render. The language gives way first, as a date's
 * does; a format that still fails is dropped, and the number shows unformatted.
 */
function numberFormatter(format: NumberFormat): Intl.NumberFormat | null {
  const key = JSON.stringify(format);
  let found = numberFormatters.get(key);
  if (found === undefined) {
    const { locale, ...options } = format;
    found =
      buildNumber(locale, options) ?? buildNumber(undefined, options) ?? null;
    numberFormatters.set(key, found);
  }
  return found;
}

function buildNumber(
  locale: string | undefined,
  options: Intl.NumberFormatOptions,
): Intl.NumberFormat | undefined {
  try {
    return new Intl.NumberFormat(locale, options);
  } catch {
    return undefined;
  }
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
