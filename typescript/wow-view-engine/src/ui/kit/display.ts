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
  DEFAULT_APPROXIMATE_METRICS,
  isDateCell,
  type AnalysisDatePart,
  type AnalysisDateUnit,
  type FieldNumeric,
  type RecordData,
  type FieldOption,
  type NumberFormat,
  type SummaryFunction,
  type EpochTimeUnit,
} from '../../model/index.js';
import { readInstant } from '../../filter/index.js';
import {
  DEFAULT_MISSING_KEY,
  wordReferences,
  type MetricCondition,
  type MetricFunction,
} from '../../analysis/index.js';
import { recordValue } from '../../record/index.js';
import { datePartValue } from './datePart.js';
import { formatNumber } from './numbers.js';
import type { MessageKey } from './messages.js';
import type { MessageFormatters } from './MessagesProvider.js';
import { badgeEntries, optionLabel, type BadgeEntry } from './badges.js';

/** Where a value is shown: the language, and the zone its times read in. */
export interface DisplayContext {
  /** A BCP 47 tag; the runtime's own when left out. */
  locale?: string;
  /** An IANA zone; the runtime's own when left out. */
  timeZone?: string;
  /**
   * How a definition's words are shown here (`useSay`): an option's label
   * is said in them. Left out, a label reads as it is written — a keyed
   * definition's (`text(key)`) as its key, marker and all — so a host that
   * calls `cellText` or `displayValue` itself for such a definition passes
   * `useSay()` here (a surface's own context, `useSurfaceDisplay`, carries
   * it already).
   */
  say?: (value: string) => string;
  /**
   * The clock "now" is read from: a table cell leaves out the year of a
   * time in the current one (`tableTime`). A workbench and an embed pass
   * their engine's `environment`, so a page whose clock is pinned reads
   * the same every day; the runtime's own when left out.
   */
  clock?: { now(): Date };
}

/** What a column knows about the field behind it. */
export interface DisplayField {
  kind?: string;
  /** Renderer key; the kind's when the field names none. */
  cell?: string;
  options?: readonly FieldOption[];
  /** How a number of this field is written, when the field says. */
  numberFormat?: NumberFormat;
  /**
   * For money whose currency each record holds: where the row holds it
   * (`currencyPathOf`). A reader holding the row writes the value in that
   * currency (`inRowCurrency`).
   */
  currencyPath?: string;
  /**
   * What the number is by the source's semantics, where that decides how
   * it reads (`RecordColumnView.numeric`): a file holds it raw.
   */
  numeric?: FieldNumeric;
  /** For a date histogram group: its keys are the starts of these buckets. */
  dateUnit?: AnalysisDateUnit;
  /** The zone those buckets were cut in, when the group named one. */
  timeZone?: string;
  /** For a calendar part group: its keys are this part's integers. */
  datePart?: AnalysisDatePart;
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
  /** A time field kept in epoch seconds (`epochUnitOf`); milliseconds when unsaid. */
  timeUnit?: EpochTimeUnit;
  /**
   * How finely a time of day is read where the reading is short.
   *
   * A filter's times are set to the minute (用户 2026-09-25), so a condition
   * says 「09:05」, not 「09:05:00」 — `'minute'`, used by the date control's
   * trigger and the condition's summary (`displayValue`). A time that does
   * carry seconds still says them, so no bound is ever shown other than it
   * runs.
   *
   * A table cell is short by default (`tableTime`): to the minute, unless
   * the column says `'second'` (`RecordColumnView.timePrecision`). Every
   * other reading of a record's value keeps the seconds.
   */
  timePrecision?: 'minute' | 'second';
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
    const label = optionLabel(value, field.options, context.say);
    if (label !== undefined) return label;
  }
  if (field.datePart !== undefined)
    return datePartValue(value, field.datePart, context.locale);
  if (field.dateUnit !== undefined) {
    const time = readTime(value, field.timeZone ?? context.timeZone);
    return time
      ? bucket(time.date, field.dateUnit, time.timeZone, context.locale)
      : undefined;
  }
  switch (field.cell ?? field.kind) {
    case 'datetime': {
      const time = readTime(value, context.timeZone, field.timeUnit);
      return time
        ? format(time.date, context.locale, {
            dateStyle: 'medium',
            // A wall-clock string that names only a day is a day, on a
            // datetime field as anywhere else: it is how a date condition
            // says "the whole day" (kernels.md), and printing 12:00:00 AM
            // beside it states a moment nobody wrote.
            // `short` is hours and minutes: a filter's time on the minute.
            timeStyle: time.dayOnly
              ? undefined
              : field.timePrecision === 'minute' &&
                  time.date.getTime() % 60_000 === 0
                ? 'short'
                : 'medium',
            timeZone: time.timeZone,
          })
        : undefined;
    }
    case 'date': {
      const time = readTime(value, context.timeZone, field.timeUnit);
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
 * A group is its field — and a time group says what one of its rows spans,
 * 「创建时间（按日）」, because a bucket key reads as a moment and a column of
 * 「9月1日」 does not say whether each row is that day or that month. A metric
 * is two words the kernel hands over separately — the field and the summary
 * — because only a catalogue knows their order: 「金额的平均」 and "Average
 * of Amount" are the same header. Composing it
 * here is what makes two summaries of one field two different headers, where
 * the alias (`amount_1`) named the machine and the label named them both the
 * same. A count is neither: it counts records rather than summarising a
 * field, so it says so in one word.
 *
 * A metric the source estimates wears 「≈」 in front of it (D20 口径): the
 * types in `estimated`, which the projection reads off what the source says
 * it estimates (`AnalysisProjection.approximate`, from
 * `AnalysisCapability.approximate` — a descriptor's `analysis.approximate`,
 * percentiles when nothing says otherwise). A p95
 * that prints to two decimals beside an exact sum reads as exact — the sign
 * is one character and travels everywhere the header does, the chart's axis
 * and legend included. The word behind the sign is
 * `label.analysis.approximate`, which the table header's tooltip and
 * description carry (`SortableHeader`'s `note`).
 *
 * A metric with a condition of its own says it after a 「·」 (D20 显示名):
 * 「金额的总和 · 已发运」 when the condition keeps one value of one field,
 * 「金额的总和 · 有条件」 otherwise (`metricCondition`). Without it a sum over
 * the shipped orders wore the header of a sum over all of them, and a
 * region with none shipped read as a region with no sales.
 */
export function columnTitle(
  column: {
    label: string;
    fn?: MetricFunction;
    /**
     * Whether the source estimates it (`AnalysisColumnView.approximate`);
     * left out, read off `estimated`.
     */
    approximate?: boolean;
    named?: true;
    /** The reading of the values under it: a date's `MIN` is its earliest. */
    cell?: string;
    /** A time group's granularity: what one of its rows spans. */
    dateUnit?: AnalysisDateUnit;
    /** A calendar part group's part: what its rows fold together. */
    datePart?: AnalysisDatePart;
    /** A metric's own condition: the one value it keeps, when it is one. */
    condition?: Pick<MetricCondition, 'value'>;
  },
  messages: MessageFormatters,
  /**
   * The metric types the source estimates (`AnalysisProjection.approximate`);
   * percentiles where the caller holds no view.
   */
  estimated: readonly string[] = DEFAULT_APPROXIMATE_METRICS,
): string {
  // A name the analyst gave is the whole title (D20 显示名). A derived
  // metric's text marks each metric it refers to (`metricReferenceText`);
  // each is worded as that metric's own column is, condition included.
  // A key in it is said here, where the title is shown (D2).
  const label = messages.say(
    wordReferences(column.label, (fn, referenced, condition) =>
      columnTitle(
        { label: referenced, fn, ...(condition ? { condition } : {}) },
        messages,
        estimated,
      ),
    ),
  );
  if (column.named) return label;
  if (column.fn === undefined) {
    if (column.datePart !== undefined)
      return messages.label(`label.analysis.part.${column.datePart}`, {
        field: label,
      });
    return column.dateUnit === undefined
      ? label
      : messages.label(`label.analysis.dated.${column.dateUnit}`, {
          field: label,
        });
  }
  // A derived metric is arithmetic over other metrics: no field stands behind
  // it, so its stored name is all there is to show.
  if (column.fn === 'DERIVED') return label;
  const title =
    column.fn === 'COUNT'
      ? messages.label('label.analysis.row-count')
      : messages.label('label.summary.of', {
          field: label,
          fn: messages.label(summaryFunctionKey(column.fn, column.cell)),
        });
  return conditionedTitle(
    (column.approximate ?? estimated.includes(column.fn))
      ? `${APPROXIMATELY} ${title}`
      : title,
    column.condition,
    messages,
  );
}

/** A metric's title and, after a 「·」, the condition it counts under. */
function conditionedTitle(
  metric: string,
  condition: Pick<MetricCondition, 'value'> | undefined,
  messages: MessageFormatters,
): string {
  if (condition === undefined) return metric;
  return condition.value === undefined
    ? messages.label('label.analysis.metric-conditioned', { metric })
    : messages.label('label.analysis.metric-where', {
        metric,
        value: condition.value,
      });
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
 * The bucket of records with no value, in the surface's words — 「（空）」 —
 * when `value` is the engine's sentinel key for a group that keeps one
 * (`AnalysisColumnView.missingKey`); `undefined` for any other value.
 *
 * The sentinel is a stored key, one string in every language, because it
 * travels to Wow and back as the bucket's own key; what a reader sees is the
 * interface's to say. A key the analyst wrote themselves — 「未上报城市」 —
 * is their name for the bucket and reads as written, and a value that only
 * spells the sentinel on a group without one is some record's own value.
 */
export function missingText(
  value: unknown,
  column: { missingKey?: string; role?: 'group' | 'metric' },
  messages: MessageFormatters,
): string | undefined {
  // A group whose key is nothing at all is the records with no value too:
  // a date dimension keeps no sentinel key, and its bucket of records with
  // no date came back unnamed — a blank tick, a blank first cell,
  // 「从 2024年 Q3 到 ，」 (second review R2-P1-4).
  const none = column.role === 'group' && value == null;
  return none || (value === DEFAULT_MISSING_KEY && column.missingKey === value)
    ? messages.label('label.analysis.missing-group')
    : undefined;
}

const WHOLE_DIGITS = /^-?(?:0|[1-9]\d{0,299})$/;

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
  if (typeof value === 'number' || typeof value === 'bigint')
    return formatNumber(value, format, locale);
  if (typeof value === 'boolean')
    return messages.label(value ? 'label.value.yes' : 'label.value.no');
  // A whole number past 2^53 a source sent as digits, to keep them exact, in
  // a column that declared a number format: grouped as that format says,
  // every digit kept (Intl reads a string of digits exactly).
  if (typeof value === 'string')
    return format && WHOLE_DIGITS.test(value)
      ? formatNumber(BigInt(value), format, locale)
      : value;
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
 *
 * An option's label is said through `context.say`: give it (`useSay()`)
 * for a definition written in keys, or the text carries the keys (D2).
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
  const badges = badgeEntries(value, field, context.say);
  if (badges) return labelsOf(badges, messages);
  // A list of plain values, one reading each: an enum's labels, a list of
  // dates each in the surface's zone — never `["a","b"]`.
  if (Array.isArray(value))
    return value
      .map(item => cellText(item, field, messages, context))
      .join(messages.label('label.filter.join'));
  const shown = displayValue(value, field, context);
  if (shown !== undefined) return shown;
  if (typeof value === 'number' || typeof value === 'bigint')
    return formatNumber(value, field.numberFormat, context.locale);
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
  const badges = badgeEntries(value, title, context.say);
  const tone = badges?.length === 1 ? badges[0].tone : undefined;
  return { value, label, ...(tone ? { tone } : {}) };
}

function countText(
  count: number,
  noun: 'items' | 'fields',
  messages: MessageFormatters,
): string {
  if (count === 0) return '';
  return messages.label(`label.value.${noun}`, { count });
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
 * it: the author said how it reads, and the file says the same. One whose
 * reading comes from the source's semantics instead (`numeric`: a decimal,
 * money) is written as the number it is, and money's currency goes in a
 * column of its own beside it (`currencyCsvText`) — a column of `¥1,204.50`
 * and `JP¥1,205` is text no spreadsheet adds up, nor tells apart.
 */
export function csvCellText(
  value: unknown,
  field: DisplayField,
  messages: MessageFormatters,
  context: DisplayContext,
): string {
  // Written raw unless the author's own format decides how it reads.
  if (typeof value === 'number' && !(field.numberFormat && !field.numeric))
    return Number.isFinite(value) ? String(value) : '';
  return cellText(value, field, messages, context);
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
