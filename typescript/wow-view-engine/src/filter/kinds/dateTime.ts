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

import { filter, TimeUnit, type FilterExpression } from '@ahoo-wang/wow-client';
import dayjs from 'dayjs';
import {
  temporalOf,
  type AnalysisDateUnit,
  type FieldDefinition,
  type FieldKindId,
  type FieldTemporal,
  type FilterOperatorName,
  type EpochTimeUnit,
} from '../../model/index.js';
import type { FieldKindDescription, FilterSummaryValue } from '../describe.js';
import { issue, readValue, type FieldKind } from '../fieldKind.js';
import {
  isValidTimeZone,
  periodOf,
  resolveDateTimeBound,
  resolveDateTimeRange,
} from '../time.js';
import {
  isDateTimeFilterValue,
  MAX_RELATIVE_DATE_AMOUNT,
  type DateTimeFilterValue,
  type RelativeDateTimeValue,
} from '../values.js';
import {
  compilePresence,
  describePresenceParts,
  isPresenceOperator,
  PRESENCE_OPERATORS,
} from './presence.js';

function isParsableInstant(text: string): boolean {
  return !Number.isNaN(Date.parse(text));
}

/**
 * A day, or a day and a time, with no offset: `2026-09-18`, or
 * `2026-09-18T09:30:00` as Java writes a `LocalDateTime`. It names a time on
 * a clock rather than a moment, so it is read on the engine's own clock;
 * read on any other it would move.
 */
const WALL_CLOCK =
  /^(\d{4}-\d{2}-\d{2})(?:[T ](\d{2}:\d{2})(?::(\d{2})(?:\.(\d+))?)?)?$/;

/** Epoch milliseconds, which is how Wow keeps a time, written as digits. */
const EPOCH = /^-?\d+$/;

/** The instant a value of this kind names, and what kind of value said it. */
export interface DateInstant {
  /** Milliseconds since the epoch. */
  ms: number;
  /**
   * The value named a time on a clock rather than a moment, so `ms` is it
   * read at UTC — which is what prints it as written wherever it is shown.
   */
  wallClock?: true;
  /** That wall-clock value carried no time of day, which is a meaning. */
  dayOnly?: true;
}

/**
 * The instant one of this kind's values names, or `undefined` when it names
 * none.
 *
 * This is the kind's own reading of a value, and the one reading in the
 * package: a cell shows a date through it (`ui/display.ts`), and the record
 * kernel orders a column's values by it to find its earliest and its latest
 * (`record/project.ts`). `Date.parse` alone would not do — Wow keeps a time
 * as epoch milliseconds, which arrives as a number or as a string of digits,
 * and a wall-clock day has to be read at UTC so the 18th is not the 17th in
 * Los Angeles. Anything the two agree on they agree on because they ask
 * here.
 */
export function readInstant(
  value: unknown,
  /**
   * The unit a number counts in: the field's own (`epochUnitOf`), since a
   * Wow time kept in seconds is a thousand times smaller than one in
   * milliseconds and read as milliseconds lands in January 1970.
   */
  unit: EpochTimeUnit = 'MILLISECONDS',
): DateInstant | undefined {
  const wall = typeof value === 'string' ? WALL_CLOCK.exec(value.trim()) : null;
  if (wall) {
    const [, day, hourMinute, seconds = '00', fraction = ''] = wall;
    // A Date holds milliseconds; Java writes up to nine digits.
    const millis = `${fraction}000`.slice(0, 3);
    const written = `${day}T${hourMinute ?? '00:00'}:${seconds}`;
    const date = new Date(`${written}.${millis}Z`);
    // `Date` rolls a day that does not exist over into the next month, and
    // 24:00 into the next day: `2025-02-29` would read as the 1st of March.
    // What does not read back as written is not an instant at all.
    if (Number.isNaN(date.getTime()) || !date.toISOString().startsWith(written))
      return undefined;
    return hourMinute === undefined
      ? { ms: date.getTime(), wallClock: true, dayOnly: true }
      : { ms: date.getTime(), wallClock: true };
  }
  const ms = millisOf(value, unit === 'SECONDS' ? 1000 : 1);
  return ms === undefined ? undefined : { ms };
}

function millisOf(value: unknown, scale: number): number | undefined {
  const date =
    value instanceof Date
      ? value
      : typeof value === 'number'
        ? new Date(value * scale)
        : typeof value === 'string' && value.trim() !== ''
          ? new Date(EPOCH.test(value) ? Number(value) * scale : value)
          : undefined;
  return date && !Number.isNaN(date.getTime()) ? date.getTime() : undefined;
}

/**
 * A resolved bound as the field keeps its time (`FieldDefinition.temporal`).
 *
 * The window arithmetic answers in ISO 8601 whatever the field is, because a
 * zone, a day's end and a preset are calendar questions; only the last step
 * is the store's. Sent as text to an epoch field, every bound was refused by
 * Wow's schema validation — the compensation service answered each date
 * condition with `Filter value does not match [eventTime]` — so an epoch
 * field is sent the integer it compares against. Text that names no instant
 * is passed on as it came: the validator has already refused it, and the
 * compiler stays total.
 */
function storedInstant(
  instant: string,
  temporal: FieldTemporal,
): string | number {
  if (temporal.type === 'date') return instant;
  const ms = Date.parse(instant);
  if (Number.isNaN(ms)) return instant;
  // An inclusive upper bound is the range's last millisecond; its second is
  // still inside the range, so flooring is right on both edges.
  return temporal.timeUnit === 'SECONDS' ? Math.floor(ms / 1000) : ms;
}

/** The window a value names, as a phrase and as parts: for `BETWEEN`. */
function describeWindow(value: DateTimeFilterValue): DescribedValue {
  switch (value.type) {
    case 'absolute':
      // An upper edge nobody gave is not a range with one side missing: the
      // compiler emits `filter.gte(from)`, so the condition in force is a
      // `GTE` and the summary says the operator that actually ran. `from` is
      // never missing — `isDateTimeFilterValue` refuses a value without one,
      // so that shape reaches `describe` as unreadable rather than as a
      // range with a hole in it.
      return value.to === undefined
        ? {
            text: `from ${value.from}`,
            operator: 'GTE',
            value: { kind: 'text', value: value.from },
          }
        : {
            text: `${value.from} ~ ${value.to}`,
            value: rangeParts(value.from, value.to, value.timeZone),
          };
    case 'relative':
      // Reading "last 7 days" beside a query that ran forwards would be worse
      // than saying nothing: the summary bar is where a user checks what is
      // actually in force.
      return {
        text: `${value.direction === 'future' ? 'next' : 'last'} ${unitsOf(value)}`,
        value: relativeParts(value, 'window'),
      };
    case 'preset':
      return {
        text: describePreset(value),
        value: { kind: 'preset', preset: value.preset },
      };
  }
}

/**
 * Two bounds, the one period they are exactly, or the whole periods they
 * run over — which only a range that says its zone can be: without one,
 * whose calendar the day is on is the engine's to say at compile time, and
 * this reading has no engine.
 */
function rangeParts(
  from: string,
  to: string,
  timeZone: string | undefined,
): FilterSummaryValue {
  if (timeZone === undefined) return { kind: 'range', from, to };
  const unit = periodOf(from, to, timeZone);
  if (unit !== null) return { kind: 'period', unit, from, timeZone };
  const stretch = stretchOf(from, to, timeZone);
  return stretch === null
    ? { kind: 'range', from, to }
    : { kind: 'periods', ...stretch, from, timeZone };
}

/** A time on a clock, to the millisecond, with no zone of its own. */
const WALL_FORMAT = 'YYYY-MM-DDTHH:mm:ss.SSS';

/**
 * The periods a stretch may run over, coarsest first, as dayjs steps them.
 * A week is left out: seven days from any midnight are a week (`periodOf`),
 * so a stretch of them is a stretch of days, and reads as one.
 */
const STRETCH_UNITS = [
  ['YEAR', 1, 'year'],
  ['QUARTER', 3, 'month'],
  ['MONTH', 1, 'month'],
  ['DAY', 1, 'day'],
] as const;

/**
 * Whether a range runs over whole periods of one unit, more than one of
 * them — the first one's start to the last one's end — and where the last
 * one starts: what a brushed stretch of a date axis opens its records under
 * (D33 Q52), which reads as 「9月1日 ～ 9月3日」 rather than as two instants
 * to the millisecond. Stepped on the zone's wall clock, as a bucket is, so a
 * stretch across a clock change is still whole days. `null` for anything
 * else, one period included (`periodOf` says that one).
 */
function stretchOf(
  from: string,
  to: string,
  timeZone: string,
): { unit: AnalysisDateUnit; last: string } | null {
  if (!isValidTimeZone(timeZone)) return null;
  const value = { type: 'absolute', from, to, timeZone } as const;
  const epoch = new Date(0);
  const start = Date.parse(
    resolveDateTimeBound(value, epoch, timeZone, 'start'),
  );
  const end = Date.parse(resolveDateTimeBound(value, epoch, timeZone, 'end'));
  if (Number.isNaN(start) || Number.isNaN(end) || end <= start) return null;
  const wall = (ms: number) =>
    dayjs.utc(dayjs(ms).tz(timeZone).format(WALL_FORMAT));
  const inZone = (time: dayjs.Dayjs) =>
    dayjs.tz(time.format(WALL_FORMAT), timeZone).valueOf();
  for (const [unit, step, by] of STRETCH_UNITS) {
    const last = inZone(wall(end + 1).subtract(step, by));
    const firstEnd = inZone(wall(start).add(step, by)) - 1;
    if (last <= start || firstEnd >= end) continue;
    if (
      periodOf(
        new Date(start).toISOString(),
        new Date(firstEnd).toISOString(),
        timeZone,
      ) === unit &&
      periodOf(
        new Date(last).toISOString(),
        new Date(end).toISOString(),
        timeZone,
      ) === unit
    )
      return { unit, last: new Date(last).toISOString() };
  }
  return null;
}

/** The English phrase a bound or a window reads as, and the parts behind it. */
interface DescribedValue {
  text: string;
  value: FilterSummaryValue;
  /** Set where the condition in force is not the one the leaf spells. */
  operator?: FilterOperatorName;
}

function relativeParts(
  value: DateTimeFilterValue & { type: 'relative' },
  bound: 'window' | 'instant',
): FilterSummaryValue {
  return {
    kind: 'relative',
    amount: value.amount,
    unit: value.unit,
    direction: value.direction ?? 'past',
    bound,
  };
}

/**
 * The one instant a single-bound operator compares against, as a phrase.
 * It follows `resolveDateTimeBound`: a relative value is a distance from
 * now, so "on or before 7 day ago" is the bound in force, where "last 7 day"
 * would read as the window the query does not run over.
 */
function describeBound(
  value: DateTimeFilterValue,
  operator: FilterOperatorName,
): DescribedValue {
  const side = operator === 'GTE' ? 'on or after' : 'on or before';
  switch (value.type) {
    case 'absolute': {
      // The bound the operator asks for: a range's lower edge for `GTE`, its
      // upper one for `LTE`, and the one date either way when it has only one.
      const edge = operator === 'GTE' ? value.from : (value.to ?? value.from);
      return { text: `${side} ${edge}`, value: { kind: 'text', value: edge } };
    }
    case 'relative':
      // Not the window `BETWEEN` asks for: `resolveDateTimeBound` stands on
      // the far edge, so this compares against the moment seven days ago,
      // and "in the last 7 days" would name a span the query never ran over.
      return {
        text: `${side} ${unitsOf(value)} ${value.direction === 'future' ? 'ahead' : 'ago'}`,
        value: relativeParts(value, 'instant'),
      };
    case 'preset':
      return {
        text: `${side} ${describePreset(value)}`,
        value: { kind: 'preset', preset: value.preset },
      };
  }
}

/** `nextQuarter` is a key, not a phrase, and there are fifteen of them. */
function describePreset(value: DateTimeFilterValue & { type: 'preset' }) {
  return value.preset.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase();
}

/**
 * The two operators that compare with the service's own clock (Wow's N6,
 * 9.2.0 and later): strictly before, or strictly after, the moment the
 * service reads when it runs the query. The moment is the service's, not
 * this engine's `ctx.now`, so a saved "timed out" never goes stale and no
 * browser's clock decides it. Neither reads the leaf's value: the operator
 * is the whole condition, as a presence question is.
 */
const NOW_OPERATORS: readonly FilterOperatorName[] = [
  'BEFORE_NOW',
  'AFTER_NOW',
];

function isNowOperator(operator: FilterOperatorName): boolean {
  return NOW_OPERATORS.includes(operator);
}

/**
 * `BEFORE_NOW` / `AFTER_NOW` on this field, the service's clock encoded the
 * way the field keeps its time: a count of seconds is said so, anything else
 * is Wow's default of milliseconds (a store's own date type takes none).
 */
function compileNow(
  field: FieldDefinition,
  operator: FilterOperatorName,
): FilterExpression {
  const temporal = temporalOf(field);
  const options =
    temporal.type === 'epoch' && temporal.timeUnit === 'SECONDS'
      ? { timeUnit: TimeUnit.SECONDS }
      : {};
  return operator === 'BEFORE_NOW'
    ? filter.beforeNow(field.name, undefined, options)
    : filter.afterNow(field.name, undefined, options);
}

/** "before now" / "after now": the operator is the condition. */
function describeNow(
  operator: FilterOperatorName,
  field: FieldDefinition,
): FieldKindDescription {
  const side = operator === 'BEFORE_NOW' ? 'before' : 'after';
  return { text: `${field.label} ${side} now`, value: { kind: 'none' } };
}

/**
 * Dates are the reason configs store intent rather than compiled values: a
 * saved "last 7 days" must mean the last seven days on every later run, so the
 * window is resolved at compile time against the injected moment and zone.
 */
function createDateKind(id: FieldKindId, withTime: boolean): FieldKind {
  return {
    id,
    operators: [
      'BETWEEN',
      'GTE',
      'LTE',
      ...NOW_OPERATORS,
      ...PRESENCE_OPERATORS,
    ],
    defaultOperator: 'BETWEEN',

    emptyValue() {
      // Not today: seeding a window would cut the list to one day the moment
      // the user picked a date field.
      return null;
    },

    validate({ value, operator, path }) {
      if (isPresenceOperator(operator) || isNowOperator(operator)) return [];
      if (!isDateTimeFilterValue(value))
        return [issue('filter.value.expected-date', path)];
      if (value.type === 'absolute') {
        if (!isParsableInstant(value.from))
          return [issue('filter.value.unparsable-date', path)];
        if (value.to !== undefined && !isParsableInstant(value.to))
          return [issue('filter.value.unparsable-date', path)];
        if (
          value.to !== undefined &&
          Date.parse(value.to) < Date.parse(value.from)
        )
          return [issue('filter.value.inverted-range', path)];
        // A zone no runtime knows makes the compiler throw rather than
        // produce a query, so it is refused here where it can be reported.
        if (value.timeZone !== undefined && !isValidTimeZone(value.timeZone))
          return [
            issue('filter.value.unknown-time-zone', path, {
              timeZone: value.timeZone,
            }),
          ];
      }
      // Past the bound the arithmetic runs off the calendar, and a compiler
      // that clamps would quietly answer a different question.
      if (value.type === 'relative' && value.amount > MAX_RELATIVE_DATE_AMOUNT)
        return [
          issue('filter.value.relative-too-large', path, {
            max: MAX_RELATIVE_DATE_AMOUNT,
          }),
        ];
      return [];
    },

    compile({ leaf, field, now, timeZone }): FilterExpression {
      const presence = compilePresence(field.name, leaf.operator);
      if (presence) return presence;
      if (isNowOperator(leaf.operator)) return compileNow(field, leaf.operator);

      const value = readValue<DateTimeFilterValue>(leaf.value);
      const temporal = temporalOf(field);
      const stored = (instant: string) => storedInstant(instant, temporal);
      // A condition's own zone is applied inside; what arrives here is the
      // runtime's, used for everything relative to the evaluation moment.
      // A single bound is asked for by edge, so "on or before the 31st"
      // reaches the end of that day rather than stopping at its start.
      if (leaf.operator === 'GTE')
        return filter.gte(
          field.name,
          stored(resolveDateTimeBound(value, now, timeZone, 'start')),
        );
      if (leaf.operator === 'LTE')
        return filter.lte(
          field.name,
          stored(resolveDateTimeBound(value, now, timeZone, 'end')),
        );
      const range = resolveDateTimeRange(value, now, timeZone);
      return range.to === undefined
        ? filter.gte(field.name, stored(range.from))
        : filter.between(field.name, stored(range.from), stored(range.to));
    },

    editor(operator, _field, value) {
      if (isPresenceOperator(operator) || isNowOperator(operator))
        return { input: 'none' };
      if (isDateTimeFilterValue(value) && value.type === 'relative')
        return { input: 'relativeDate', withTime };
      if (operator === 'BETWEEN')
        return { input: 'dateRange', range: true, withTime };
      return { input: 'date', withTime };
    },

    describe({ leaf, field }) {
      const presence = describePresenceParts(leaf.operator, field);
      if (presence) return presence;
      if (isNowOperator(leaf.operator))
        return describeNow(leaf.operator, field);
      // A window this kind cannot read has no phrase; `describeWindow` would
      // fall off its switch and print `undefined` beside the field's name.
      if (!isDateTimeFilterValue(leaf.value))
        return { text: field.label, value: { kind: 'blank' } };
      const value = readValue<DateTimeFilterValue>(leaf.value);
      const described =
        leaf.operator === 'GTE' || leaf.operator === 'LTE'
          ? describeBound(value, leaf.operator)
          : describeWindow(value);
      return {
        text: `${field.label} ${described.text}`,
        value: described.value,
        ...(described.operator ? { operator: described.operator } : {}),
      };
    },
  };
}

/** A calendar day, without a time of day. */
export const dateFieldKind: FieldKind = createDateKind('date', false);

/** An instant, with a time of day. */
export const dateTimeFieldKind: FieldKind = createDateKind('datetime', true);

/**
 * The amount with its unit, counted as English counts it: 「1 day」,
 * 「7 days」. The kernel's `text` is plain English; the surface words it
 * through its catalogue (`label.relative.*`).
 */
function unitsOf(value: RelativeDateTimeValue): string {
  return `${value.amount} ${value.unit}${value.amount === 1 ? '' : 's'}`;
}
