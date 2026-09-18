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

import dayjs, { type Dayjs } from 'dayjs';
import isoWeek from 'dayjs/plugin/isoWeek';
import quarterOfYear from 'dayjs/plugin/quarterOfYear';
import timezone from 'dayjs/plugin/timezone';
import utc from 'dayjs/plugin/utc';
import type {
  DateTimeFilterValue,
  DateTimePreset,
  PresetDateTimeValue,
  RelativeDateTimeValue,
} from './values.js';

// Zones, week starts, quarters and month lengths are what a date library is
// for; these four plugins cover every window the presets and relative values
// need, and keep this file free of hand-written calendar arithmetic.
dayjs.extend(utc);
dayjs.extend(timezone);
dayjs.extend(isoWeek);
dayjs.extend(quarterOfYear);

/**
 * Resolving a relative or preset value happens at compile time against the
 * injected moment: the kernel never reads the system clock, and the same
 * config run tomorrow produces a different window on purpose.
 */
export interface InstantRange {
  /** Inclusive lower bound, ISO 8601. */
  from: string;
  /** Inclusive upper bound, ISO 8601; absent means open-ended. */
  to?: string;
}

/** A window with both bounds, which every relative and preset value has. */
type ClosedInstantRange = Required<InstantRange>;

/**
 * Which side of a range a bound stands on. A string that names a whole day
 * is a different instant on each side — its first millisecond as a lower
 * bound, its last as an upper one — and only the side asking can tell which.
 */
export type RangeEdge = 'start' | 'end';

/**
 * Where a relative window starts and ends.
 *
 * A past window runs from `amount` units ago to now; a future one from now to
 * `amount` units ahead. Quarters are expressed in months, which every dayjs
 * build understands.
 */
function relativeWindow(
  reference: Dayjs,
  value: RelativeDateTimeValue,
): ClosedInstantRange {
  const months = value.unit === 'quarter' ? value.amount * 3 : value.amount;
  const unit = value.unit === 'quarter' ? 'month' : value.unit;
  const offset =
    value.direction === 'future'
      ? reference.add(months, unit)
      : reference.subtract(months, unit);
  return value.direction === 'future'
    ? bounds(reference, offset)
    : bounds(offset, reference);
}

/** A named calendar window: which period, and how far from this one. */
function period(
  reference: Dayjs,
  value: PresetDateTimeValue,
): ClosedInstantRange {
  const { unit, shift } = PERIODS[value.preset];
  const at = shift === 0 ? reference : shiftBy(reference, unit, shift);
  // `isoWeek` comes from a plugin and types as its own overload, so it is
  // narrowed here rather than widening every other unit to match it.
  return unit === 'isoWeek'
    ? bounds(at.startOf('isoWeek'), at.endOf('isoWeek'))
    : bounds(at.startOf(unit), at.endOf(unit));
}

/**
 * Steps a whole period. `isoWeek` starts on Monday independent of the runtime
 * locale, and dayjs shifts it as a plain week; a quarter it counts in months.
 * Landing anywhere inside the neighbouring period is enough, because the
 * caller takes that period's bounds.
 */
function shiftBy(reference: Dayjs, unit: PeriodUnit, shift: number): Dayjs {
  if (unit === 'quarter') return reference.add(shift * 3, 'month');
  if (unit === 'isoWeek') return reference.add(shift, 'week');
  return reference.add(shift, unit);
}

/** Each preset as a period and an offset from the current one. */
const PERIODS: Readonly<
  Record<DateTimePreset, { unit: PeriodUnit; shift: number }>
> = {
  today: { unit: 'day', shift: 0 },
  yesterday: { unit: 'day', shift: -1 },
  tomorrow: { unit: 'day', shift: 1 },
  thisWeek: { unit: 'isoWeek', shift: 0 },
  lastWeek: { unit: 'isoWeek', shift: -1 },
  nextWeek: { unit: 'isoWeek', shift: 1 },
  thisMonth: { unit: 'month', shift: 0 },
  lastMonth: { unit: 'month', shift: -1 },
  nextMonth: { unit: 'month', shift: 1 },
  thisQuarter: { unit: 'quarter', shift: 0 },
  lastQuarter: { unit: 'quarter', shift: -1 },
  nextQuarter: { unit: 'quarter', shift: 1 },
  thisYear: { unit: 'year', shift: 0 },
  lastYear: { unit: 'year', shift: -1 },
  nextYear: { unit: 'year', shift: 1 },
};

type PeriodUnit = 'day' | 'isoWeek' | 'month' | 'quarter' | 'year';

function bounds(from: Dayjs, to: Dayjs): ClosedInstantRange {
  return { from: from.toISOString(), to: to.toISOString() };
}

/**
 * An instant that already names its own offset, as `...Z` or `...+09:00`.
 * Such a string means one moment whatever zone is in force, so resolving it
 * against a zone would be wrong rather than merely unnecessary.
 */
const EXPLICIT_OFFSET = /(?:Z|[+-]\d{2}:?\d{2})$/i;

/**
 * A calendar day and nothing more: `2026-01-31`. Its dashes sit between the
 * fields, never before a trailing `HH:MM`, so it is not an offset.
 */
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

/**
 * A wall-clock string read in a zone.
 *
 * `2026-01-01` and `2026-01-01T09:00` name a time on a clock, not a moment:
 * which moment depends on whose clock. That is what `timeZone` answers, and
 * leaving it unapplied was how a per-condition zone came to be stored,
 * validated and then quietly ignored.
 *
 * A date alone names the whole day, so on the `end` edge it is that day's
 * last millisecond: `to: 2026-01-31` used to resolve to the day's first
 * instant, and a range said to run through the 31st stopped before it began.
 * A string with a time of day is one instant on either edge.
 */
function instantIn(text: string, timeZone: string, edge: RangeEdge): string {
  if (EXPLICIT_OFFSET.test(text)) return text;
  const read = dayjs.tz(text, timeZone);
  // An unparsable string is the validator's to report, not this function's to
  // guess at; passing it through keeps compilation total.
  if (!read.isValid()) return text;
  const wholeDay = edge === 'end' && DATE_ONLY.test(text);
  return (wholeDay ? read.endOf('day') : read).toISOString();
}

/** Whether a runtime can resolve this zone; an unknown one makes dayjs throw. */
export function isValidTimeZone(timeZone: string): boolean {
  try {
    new Intl.DateTimeFormat('en-US', { timeZone });
    return true;
  } catch {
    return false;
  }
}

/** The window a relative or preset value names at the injected moment. */
function windowAt(
  value: RelativeDateTimeValue | PresetDateTimeValue,
  now: Date,
  timeZone: string,
): ClosedInstantRange {
  const reference = dayjs(now).tz(timeZone);
  return value.type === 'relative'
    ? relativeWindow(reference, value)
    : period(reference, value);
}

/**
 * The range a value names: `from` on its start edge, `to` on its end edge,
 * so a date-only `to` reaches the end of that day. An absolute value without
 * `to` stays open-ended.
 */
export function resolveDateTimeRange(
  value: DateTimeFilterValue,
  now: Date,
  timeZone: string,
): InstantRange {
  if (value.type !== 'absolute') return windowAt(value, now, timeZone);
  // A condition may pin its own zone; otherwise the runtime's applies.
  const zone = value.timeZone ?? timeZone;
  const from = instantIn(value.from, zone, 'start');
  return value.to === undefined
    ? { from }
    : { from, to: instantIn(value.to, zone, 'end') };
}

/**
 * One instant of a value, for the operators that take a single bound: `GTE`
 * asks for the start edge, `LTE` for the end. An absolute value with only
 * `from` stands on `from` for both — read at the end edge, a date-only `from`
 * is the end of its day, which is what "on or before the 31st" means.
 */
export function resolveDateTimeBound(
  value: DateTimeFilterValue,
  now: Date,
  timeZone: string,
  edge: RangeEdge,
): string {
  if (value.type !== 'absolute')
    return windowAt(value, now, timeZone)[edge === 'start' ? 'from' : 'to'];
  const zone = value.timeZone ?? timeZone;
  return edge === 'start'
    ? instantIn(value.from, zone, 'start')
    : instantIn(value.to ?? value.from, zone, 'end');
}
