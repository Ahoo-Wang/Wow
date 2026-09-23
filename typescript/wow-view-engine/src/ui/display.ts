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
  type RecordData,
  type FieldOption,
  type FieldTone,
  type NumberFormat,
  type SummaryFunction,
} from '../model/index.js';
import { readInstant } from '../filter/index.js';
import { wordReferences, type MetricFunction } from '../analysis/index.js';
import { recordValue } from '../record/index.js';
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
  /**
   * For an array of objects, the element field each element is read by and
   * its name within the element (`FieldDefinition.elementTitle`).
   */
  elementTitle?: ElementTitleField;
  /**
   * For an array of objects that declares its elements, each element field
   * by its name within an element — what the record detail lays one
   * element out by (`RecordCardField.elements`).
   */
  elements?: readonly ElementField[];
}

/** One field of an element, by its name within the element. */
export interface ElementField extends DisplayField {
  field: string;
  label: string;
}

/** The element field an array of objects is read by, one element each. */
export interface ElementTitleField extends DisplayField {
  /** Its name within one element. */
  name: string;
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
 * what the column shows; every other function keeps its one name. A record
 * column's summary and an analysis metric are named by this one rule, so the
 * latest of a datetime is 「最晚」 under a record column, in an analysis
 * header and in the tray's summary select alike.
 */
export function summaryFunctionKey(
  fn: SummaryFunction | Exclude<MetricFunction, 'DERIVED'>,
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
 *
 * A percentile wears 「≈」 in front of it (D20 口径). Wow computes percentiles
 * approximately, and a p95 that prints to two decimals beside an exact sum
 * reads as exact — the sign is one character and travels everywhere the
 * header does, the chart's axis and legend included. The word behind the sign
 * is `label.analysis.approximate`, which the header's `title` carries.
 */
export function columnTitle(
  column: {
    label: string;
    fn?: MetricFunction;
    named?: true;
    /** The reading of the values under it: a date's `MIN` is its earliest. */
    cell?: string;
  },
  messages: MessageFormatters,
): string {
  // A name the analyst gave is the whole title (D20 显示名). A derived
  // metric's text marks each metric it refers to (`metricReferenceText`);
  // each is worded as that metric's own column is.
  const label = wordReferences(column.label, (fn, referenced) =>
    columnTitle({ label: referenced, fn }, messages),
  );
  if (column.named || column.fn === undefined) return label;
  if (column.fn === 'COUNT') return messages.label('label.analysis.row-count');
  // A derived metric is arithmetic over other metrics: no field stands behind
  // it, so its stored name is all there is to show.
  if (column.fn === 'DERIVED') return label;
  const title = messages.label('label.summary.of', {
    field: label,
    fn: messages.label(summaryFunctionKey(column.fn, column.cell)),
  });
  return column.fn === 'PERCENTILE' ? `${APPROXIMATELY} ${title}` : title;
}

/** The one character that says a number is not exact. */
const APPROXIMATELY = '≈';

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
 * A number as this surface prints it: in the format its field declared, and
 * grouped in the surface's language when it declared none.
 *
 * It is the one fallback for every number the UI writes — a record cell, a
 * summary, an analysis cell, a chart's axis, a count inside a sentence — so a
 * total reads 「534,897」 in the record view and in the analysis view alike,
 * where the record side used to print 「534897」. A field whose numbers are
 * names rather than quantities (a year, an employee number) says so with
 * `numberFormat: { useGrouping: false }`. A format Intl refuses to build gives
 * way to the same plain grouping.
 *
 * `locale` is the surface's language, and it is not optional in spirit: a
 * number left to `toLocaleString()` is grouped for whatever machine the page
 * happens to run on, which is the one language nobody chose. It stays optional
 * in the signature because a format may pin its own.
 */
export function formatNumber(
  value: number,
  format?: NumberFormat,
  locale?: string,
): string {
  const formatter =
    (format && numberFormatter(format, locale)) ?? numberFormatter({}, locale);
  return formatter ? formatter.format(value) : String(value);
}

/**
 * A value an analysis shows when its field's kind has nothing to add: a number
 * as `formatNumber` prints it; a boolean in the catalogue's words; anything
 * else as text. A table cell, a chart axis and a tooltip read the same, so a
 * currency metric is not a bare number on the axis.
 */
export function valueText(
  value: unknown,
  messages: MessageFormatters,
  format?: NumberFormat,
  locale?: string,
): string {
  if (value === null || value === undefined) return '';
  if (typeof value === 'number') return formatNumber(value, format, locale);
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
 * Several badges, several elements or several plain values read as one list
 * joined by the catalogue's separator, the way the cell reads out loud; an
 * array of objects or an object reads as `heldReading` says.
 */
export function cellText(
  value: unknown,
  field: DisplayField,
  messages: MessageFormatters,
  context: DisplayContext,
): string {
  if (value === null || value === undefined) return '';
  const held = heldReading(value, field, messages, context);
  if (held)
    return 'text' in held ? held.text : labelsOf(held.elements, messages);
  const badges = badgeEntries(value, field);
  if (badges) return labelsOf(badges, messages);
  // A list of plain values, one reading each: an enum's labels, a list of
  // dates each in the surface's zone — never `["a","b"]`.
  if (Array.isArray(value))
    return value
      .map(item => cellText(item, field, messages, context))
      .join(messages.label('label.filter.join'));
  const shown = displayValue(value, field, context);
  if (shown !== undefined) return shown;
  if (typeof value === 'number')
    return formatNumber(value, field.numberFormat, context.locale);
  if (typeof value === 'bigint') return value.toString();
  return valueText(value, messages, field.numberFormat, context.locale);
}

/**
 * Several readings as one line, joined by the catalogue's list separator —
 * 「、」 in Chinese, ", " in English — the one every read-out list in this
 * package is joined by.
 */
export function labelsOf(
  entries: readonly BadgeEntry[],
  messages: MessageFormatters,
): string {
  return entries
    .map(entry => entry.label)
    .join(messages.label('label.filter.join'));
}

/**
 * What a value holding structure reads as: an array of objects, or an
 * object. `undefined` for anything else — a scalar, or a list of scalars —
 * which the readings after it answer.
 *
 * **Never as JSON.** On the Wow event stream the events of one stream sit in
 * an array `body` of objects, each carrying its payload; written out, one
 * cell held a stack trace and pushed the table far off the screen, while
 * saying nothing an operator asked — which step was this? So:
 *
 * - with an element title declared, the array reads as **its elements**,
 *   each by that field and the way that field reads (`elements`): an enum
 *   element field wears its option's label and tone, a string its text. One
 *   entry per element, as a list of tags is one badge per tag: joined into
 *   one they would read as one name with a comma in it. An element whose
 *   title is empty is still an element, and reads as untitled rather than
 *   dropping out of the count;
 * - without one, it reads as **how many** it holds — 「3 项」 — which is true,
 *   short, and all that can be said without knowing what an element is;
 * - an object that is not an array reads as how many fields it holds, for
 *   the same reason, unless the field names a title, when it is the one
 *   element it is.
 *
 * An empty array or object holds nothing and reads as nothing, as an empty
 * list of tags draws no badge.
 */
export function heldReading(
  value: unknown,
  field: DisplayField,
  messages: MessageFormatters,
  context: DisplayContext,
): { elements: BadgeEntry[] } | { text: string } | undefined {
  const title = field.elementTitle;
  if (Array.isArray(value)) {
    if (title)
      return {
        elements: value.map(element =>
          elementEntry(element, title, messages, context),
        ),
      };
    if (!value.some(isStructured)) return undefined;
    return { text: countText(value.length, 'items', messages) };
  }
  if (!isStructured(value)) return undefined;
  if (title)
    return { elements: [elementEntry(value, title, messages, context)] };
  return { text: countText(Object.keys(value).length, 'fields', messages) };
}

/** One element, by its title field: that field's label and, for a single choice, its tone. */
function elementEntry(
  element: unknown,
  title: ElementTitleField,
  messages: MessageFormatters,
  context: DisplayContext,
): BadgeEntry {
  const value = isStructured(element)
    ? recordValue(element as RecordData, title.name)
    : undefined;
  const label =
    cellText(value, title, messages, context) ||
    messages.label('label.value.untitled');
  const badges = badgeEntries(value, title);
  const tone = badges?.length === 1 ? badges[0].tone : undefined;
  return { value, label, ...(tone ? { tone } : {}) };
}

function countText(
  count: number,
  noun: 'items' | 'fields',
  messages: MessageFormatters,
): string {
  if (count === 0) return '';
  return count === 1
    ? messages.label(`label.value.${noun}-one`)
    : messages.label(`label.value.${noun}`, { count });
}

/** An object a reader would otherwise see as JSON: a record, or a list. */
function isStructured(value: unknown): value is object {
  return typeof value === 'object' && value !== null;
}

/**
 * One cell as a CSV file holds it: the screen's reading (`cellText`), except
 * a number no format was declared for, which is written as the number it is.
 *
 * On screen such a number is grouped for the reader — 534,897 — but a CSV is
 * read by a spreadsheet, and `534,897` in a CSV is a string no column sums.
 * A number whose field *declares* a format (a currency, a percentage) keeps
 * it: the author said how it reads, and the file says the same.
 */
export function csvCellText(
  value: unknown,
  field: DisplayField,
  messages: MessageFormatters,
  context: DisplayContext,
): string {
  if (typeof value === 'number' && field.numberFormat === undefined)
    return Number.isFinite(value) ? String(value) : '';
  return cellText(value, field, messages, context);
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
