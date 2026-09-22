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

import {
  isDateCell,
  type AnalysisDateUnit,
  type FieldOption,
  type FieldTone,
  type NumberFormat,
  type SummaryFunction,
} from '../model/index.js';
import { readInstant } from '../filter/index.js';
import type { MetricFunction } from '../analysis/index.js';
import type { MessageKey } from './messages.js';
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
  /** How a number of this field is written, when the field says. */
  numberFormat?: NumberFormat;
  /** For a date histogram group: its keys are the starts of these buckets. */
  dateUnit?: AnalysisDateUnit;
  /** The zone those buckets were cut in, when the group named one. */
  timeZone?: string;
}

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
            // A wall-clock string that names only a day is a day, on a
            // datetime field as anywhere else: it is how a date condition
            // says "the whole day" (kernels.md), and printing 12:00:00 AM
            // beside it states a moment nobody wrote.
            ...(time.dayOnly ? {} : { timeStyle: 'medium' as const }),
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
 *
 * `dayOnly` says the string carried no time of day, which is a meaning and
 * not a gap: a bound written as a calendar day stands for the whole of it.
 */
function readTime(
  value: unknown,
  timeZone: string | undefined,
): { date: Date; timeZone: string | undefined; dayOnly?: true } | undefined {
  // The date kind's own reader, so what a cell shows and what the record
  // kernel calls a column's earliest are the same reading of the same value.
  const instant = readInstant(value);
  if (!instant) return undefined;
  const date = new Date(instant.ms);
  if (!instant.wallClock) return { date, timeZone };
  return instant.dayOnly
    ? { date, timeZone: 'UTC', dayOnly: true }
    : { date, timeZone: 'UTC' };
}

/**
 * What a summary function is called under a column of these values.
 *
 * `MIN` of a number is its smallest and `MIN` of a moment is its earliest —
 * one word each, in the vocabulary of what is being summarised, rather than
 * one word stretched over both. The reading decides, because the reading is
 * what the column shows; every other function keeps its one name.
 */
export function summaryFunctionKey(
  fn: SummaryFunction,
  cell?: string,
): MessageKey {
  return (fn === 'MIN' || fn === 'MAX') && isDateCell(cell)
    ? `label.summary.fn.date.${fn}`
    : `label.summary.fn.${fn}`;
}

/**
 * What an analysis column is called on screen.
 *
 * A group is its field. A metric is two words the kernel hands over separately
 * — the field and the summary — because only a catalogue knows their order:
 * 「金额 的 平均」 and "Average of Amount" are the same header. Composing it
 * here is what makes two summaries of one field two different headers, where
 * the alias (`amount_1`) named the machine and the label named them both the
 * same. A count is neither: it counts records rather than summarising a
 * field, so it says so in one word.
 */
export function columnTitle(
  column: { label: string; fn?: MetricFunction; named?: true },
  messages: MessageFormatters,
): string {
  // A name the analyst gave is the whole title (D20 显示名).
  if (column.named || column.fn === undefined) return column.label;
  if (column.fn === 'COUNT') return messages.label('label.analysis.row-count');
  // A derived metric is arithmetic over other metrics: no field stands behind
  // it, so its stored name is all there is to show.
  if (column.fn === 'DERIVED') return column.label;
  return messages.label('label.summary.of', {
    field: column.label,
    fn: messages.label(`label.summary.fn.${column.fn}`),
  });
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

/** A number in the format its field declared; as written when it has none. */
export function formatNumber(
  value: number,
  format?: NumberFormat,
  locale?: string,
): string {
  const formatter = format && numberFormatter(format, locale);
  return formatter ? formatter.format(value) : String(value);
}

/**
 * A value an analysis shows when its field's kind has nothing to add: a number
 * in its format, or grouped the surface's way without one; a boolean in the
 * catalogue's words; anything else as text. A table cell, a chart axis and a
 * tooltip read the same, so a currency metric is not a bare number on the axis.
 *
 * `locale` is the surface's language, and it is not optional in spirit: a
 * number left to `toLocaleString()` is grouped for whatever machine the page
 * happens to run on, which is the one language nobody chose. It stays optional
 * in the signature because a format may pin its own.
 */
export function valueText(
  value: unknown,
  messages: MessageFormatters,
  format?: NumberFormat,
  locale?: string,
): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'number') {
    // A number with no usable format is still grouped, and grouped in the
    // surface's language rather than the machine's — which is what the bare
    // `toLocaleString()` here could not do. A format Intl refuses to build
    // gives way to that same plain grouping.
    const formatter =
      (format && numberFormatter(format, locale)) ??
      numberFormatter({}, locale);
    return formatter ? formatter.format(value) : String(value);
  }
  if (typeof value === 'boolean')
    return messages.label(value ? 'label.value.yes' : 'label.value.no');
  if (typeof value === 'string') return value;
  return JSON.stringify(value) ?? '';
}

/**
 * One cell as text, for somewhere a React node cannot go — a CSV file, a
 * copied selection, a title attribute.
 *
 * It is the same reading `cellValue` draws and deliberately the same code
 * path: the badges come from `badgeEntries`, the times and enums from
 * `displayValue`, the numbers and booleans from the rules under them. What it
 * drops is only what a node carries and a line of text cannot — a badge is
 * its label, a link is its URL, a clamped paragraph is the whole paragraph.
 * Several badges read as one comma-separated list, the way the cell reads
 * out loud.
 */
export function cellText(
  value: unknown,
  field: DisplayField,
  messages: MessageFormatters,
  context: DisplayContext,
): string {
  if (value === null || value === undefined) return '';
  const badges = badgeEntries(value, field);
  if (badges) return badges.map(entry => entry.label).join(', ');
  const shown = displayValue(value, field, context);
  if (shown !== undefined) return shown;
  if (typeof value === 'number')
    return formatNumber(value, field.numberFormat, context.locale);
  if (typeof value === 'bigint') return value.toString();
  return valueText(value, messages, field.numberFormat, context.locale);
}

/** One badge: the value the record holds, the label and tone it wears. */
export interface BadgeEntry {
  value: unknown;
  label: string;
  /** The matching option's tone; absent when no option names this value. */
  tone?: FieldTone;
}

/**
 * The badges a cell wears, or `undefined` when it wears none.
 *
 * Three readings land here and they differ in what they require, not in what
 * they produce. `enum` is inferred: the renderer is the kind's own, so a
 * badge is only justified when the definition declares the choices *and*
 * names at least one of the values — a pill around a code nobody named only
 * makes the code look deliberate. `status` and `tags` were asked for by name,
 * so the definition has already answered that question and a value no option
 * names still wears its pill, showing the code it came as.
 *
 * An array gets one badge per entry whichever reading it is: joined into a
 * single pill they would read as one status with a comma in its name.
 *
 * Each entry carries the raw value beside its label, because labels are not
 * identities: `FieldOption.label` is free text a definition may repeat, and a
 * list of values may repeat too, so the caller needs something better than
 * the label to tell two badges apart. The tone rides along from the matching
 * option, since the caller holding a label no longer has the option it came
 * from.
 */
export function badgeEntries(
  value: unknown,
  field: DisplayField,
): BadgeEntry[] | undefined {
  const cell = field.cell ?? field.kind;
  if (cell !== 'enum' && cell !== 'status' && cell !== 'tags') return undefined;
  if (value === null || value === undefined) return undefined;
  const options = field.options ?? [];
  const items = Array.isArray(value) ? value : [value];
  if (cell === 'enum') {
    if (options.length === 0) return undefined;
    const labels = optionLabels(items, options);
    return labels?.map((label, index) => badge(items[index], label, options));
  }
  return items.map(item =>
    badge(item, optionOf(item, options)?.label ?? String(item), options),
  );
}

function badge(
  value: unknown,
  label: string,
  options: readonly FieldOption[],
): BadgeEntry {
  const tone = optionOf(value, options)?.tone;
  return { value, label, ...(tone ? { tone } : {}) };
}

function optionOf(
  value: unknown,
  options: readonly FieldOption[],
): FieldOption | undefined {
  return options.find(option => option.value === value);
}

/** The label of each value an enum holds; `undefined` when none is known. */
function optionLabel(
  value: unknown,
  options: readonly FieldOption[],
): string | undefined {
  return optionLabels(value, options)?.join(', ');
}

/**
 * One label per value, in order, or `undefined` when the options name none of
 * them — a code the definition no longer lists is shown as it came, but a
 * value nothing at all is known about is left to the caller's own rendering.
 */
function optionLabels(
  value: unknown,
  options: readonly FieldOption[],
): string[] | undefined {
  const labelOf = (item: unknown) => optionOf(item, options)?.label;
  const items = Array.isArray(value) ? value : [value];
  return items.some(item => labelOf(item) !== undefined)
    ? items.map(item => labelOf(item) ?? String(item))
    : undefined;
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
function numberFormatter(
  format: NumberFormat,
  surfaceLocale?: string,
): Intl.NumberFormat | null {
  // The format's own language first, then the surface's: a definition that
  // names one has a reason, and a surface in zh-CN showing `CN¥` where the
  // page around it says 「¥」 is a number formatted for somebody else.
  const key = JSON.stringify([format, surfaceLocale ?? null]);
  let found = numberFormatters.get(key);
  if (found === undefined) {
    const { locale, ...options } = format;
    found =
      buildNumber(locale ?? surfaceLocale, options) ??
      buildNumber(surfaceLocale, options) ??
      buildNumber(undefined, options) ??
      null;
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
