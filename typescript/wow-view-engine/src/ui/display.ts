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
  FieldTone,
  FilterGroupOperator,
  FilterOperatorName,
  NumberFormat,
} from '../model/index.js';
import {
  isGroupItem,
  type FilterSummaryItem,
  type FilterSummaryValue,
} from '../filter/index.js';
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
  if (typeof value === 'number') return formatNumber(value, field.numberFormat);
  if (typeof value === 'bigint') return value.toString();
  return valueText(value, messages, field.numberFormat);
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

/**
 * One applied condition as the bar says it, in the wording in force.
 *
 * `FilterSummaryItem.text` is the kind's own English line and stays what a
 * host reading it gets; this is what the badge shows. The kernel hands over
 * parts — a field, an operator and a closed union of value shapes — and the
 * words come from the catalogue, the values from the rules above: option
 * labels the kind already resolved, dates in the surface's language and zone,
 * numbers in their field's format.
 */
export function summaryText(
  item: FilterSummaryItem,
  messages: MessageFormatters,
  context: DisplayContext,
): string {
  if (isGroupItem(item))
    return groupText(item.group ?? 'and', item.items ?? [], messages, context);

  const said: string[] = [];
  if (item.label !== undefined) said.push(item.label);

  // A predicate reads its own conditions out where a value would stand.
  if (item.items !== undefined) {
    if (item.items.length === 0) {
      said.push(messages.label('label.filter.any-entry'));
      return said.join(' ');
    }
    pushWord(said, conditionWord(item, messages));
    said.push(groupText(item.group ?? 'and', item.items, messages, context));
    return said.join(' ');
  }

  // A value this kind cannot read is no condition to report, so the field's
  // name is all that is true of it — unless the field itself is gone, where
  // the question stands and only its answer is unreadable.
  const value = item.value;
  if (value === undefined || (value.kind === 'blank' && !item.unresolved))
    return said.join(' ');

  pushWord(said, conditionWord(item, messages));
  const shown = summaryValue(value, item, messages, context);
  if (shown !== '') said.push(shown);
  return said.join(' ');
}

/**
 * The conditions of one group, joined and prefixed by the word for its own
 * operator. A group inside a group is parenthesised, as it is in `text`.
 *
 * One condition under "all of" or "any of" is said plainly: neither word adds
 * anything to a single condition. "None of" is not a joiner but a negation,
 * so it is said whatever it holds.
 */
function groupText(
  op: FilterGroupOperator,
  items: readonly FilterSummaryItem[],
  messages: MessageFormatters,
  context: DisplayContext,
): string {
  const parts = items.map(child =>
    isGroupItem(child)
      ? `(${summaryText(child, messages, context)})`
      : summaryText(child, messages, context),
  );
  if (parts.length === 1 && op !== 'nor') return parts[0];
  const joined = parts.join(messages.label('label.filter.join'));
  return `${groupWord(op, messages)} ${joined}`;
}

function groupWord(
  op: FilterGroupOperator,
  messages: MessageFormatters,
): string {
  if (op === 'or') return messages.label('label.filter.any-of');
  if (op === 'nor') return messages.label('label.filter.none-of');
  return messages.label('label.filter.all-of');
}

/**
 * How this condition reads: the relation the kind named, or the operator's
 * own word. `IN` over an array asks whether the array contains any of the
 * candidates, and "is any of" would say the opposite thing about a scalar,
 * so a kind that knows better says so and this prefers it.
 */
/** A word nobody has is not a gap in the sentence. */
function pushWord(said: string[], word: string): void {
  if (word !== '') said.push(word);
}

function conditionWord(
  item: FilterSummaryItem,
  messages: MessageFormatters,
): string {
  if (item.relation) return messages.label(`label.relation.${item.relation}`);
  return item.operator ? operatorWord(item.operator, messages) : '';
}

/**
 * The catalogue names every `FilterOperator`; the derived spelling is the
 * fallback for one a host's own kind offers, as it is in the condition pill.
 */
function operatorWord(
  operator: FilterOperatorName,
  messages: MessageFormatters,
): string {
  return messages.label(
    `label.operator.${operator}`,
    undefined,
    operator.split('_').join(' ').toLowerCase(),
  );
}

function summaryValue(
  value: FilterSummaryValue,
  item: FilterSummaryItem,
  messages: MessageFormatters,
  context: DisplayContext,
): string {
  switch (value.kind) {
    // The operator is the whole condition, or there is nothing readable
    // beside it; either way it has already been said.
    case 'none':
    case 'blank':
      return '';
    case 'text':
      return value.label ?? asField(value.value, item, messages, context);
    case 'list':
      return value.values
        .map(
          (raw, index) =>
            value.labels?.[index] ?? asField(raw, item, messages, context),
        )
        .join(messages.label('label.filter.join'));
    case 'range': {
      const from = asField(value.from, item, messages, context);
      return `${from} ~ ${asField(value.to, item, messages, context)}`;
    }
    case 'relative':
      // A span, or the moment at the end of it — the same stored value, two
      // different conditions, and the phrase has to say which.
      return messages.label(
        `label.relative.${value.bound}.${value.direction}`,
        {
          amount: valueText(value.amount, messages),
          unit: messages.label(`label.relative.unit.${value.unit}`),
        },
      );
    case 'preset':
      return messages.label(`label.relative.preset.${value.preset}`);
  }
}

/**
 * One raw value of a condition, shown the way its field shows it — by the
 * same `cell ?? kind` rule the table follows, so a number carrying a
 * millisecond instant under `cell: 'date'` is a date in both places.
 */
function asField(
  value: string | number | boolean,
  item: FilterSummaryItem,
  messages: MessageFormatters,
  context: DisplayContext,
): string {
  return (
    displayValue(value, { kind: item.kind, cell: item.cell }, context) ??
    valueText(value, messages, item.numberFormat)
  );
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
