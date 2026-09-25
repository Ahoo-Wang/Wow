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
import type { AnalysisDateUnit } from '../model/index.js';
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
 * A window of hours is a distance from now: the last 6 hours run from six
 * hours ago to now. A window of days or longer is whole days (D39), the way
 * Wow's own `RECENT_DAYS` reads one: the last 30 days are today and the 29
 * before it, from the first moment of the first to the last of today, and
 * the next 7 days are today and the six after it. Cut at the time of day it
 * was read, the window put half a day at its start, and a chart by the day
 * drew that half as a dip every morning. Weeks, months, quarters and years
 * step the calendar the same way — the last 3 months are the days from three
 * months before tomorrow to the end of today. Quarters are expressed in
 * months, which every dayjs build understands.
 */
function relativeWindow(
  reference: Dayjs,
  value: RelativeDateTimeValue,
): ClosedInstantRange {
  const amount = value.unit === 'quarter' ? value.amount * 3 : value.amount;
  const unit = value.unit === 'quarter' ? 'month' : value.unit;
  const future = value.direction === 'future';
  if (unit === 'hour') {
    const offset = future
      ? reference.add(amount, unit)
      : reference.subtract(amount, unit);
    return future ? bounds(reference, offset) : bounds(offset, reference);
  }
  const today = reference.startOf('day');
  return future
    ? bounds(today, today.add(amount, unit).subtract(1, 'millisecond'))
    : bounds(
        today.add(1, 'day').subtract(amount, unit),
        reference.endOf('day'),
      );
}

/** A named calendar window: which period, and how far from this one. */
function period(
  reference: Dayjs,
  value: PresetDateTimeValue,
): ClosedInstantRange {
  const { unit, shift, toDate } = PERIODS[value.preset];
  const at = shift === 0 ? reference : shiftBy(reference, unit, shift);
  // `isoWeek` comes from a plugin and types as its own overload, so it is
  // narrowed here rather than widening every other unit to match it.
  const start = unit === 'isoWeek' ? at.startOf('isoWeek') : at.startOf(unit);
  // A period so far ends at the moment it is read — now, or the same
  // moment of the period before: 09-22 10:00 is 08-22 10:00 a month back,
  // and a date the month before does not have is its last day (dayjs
  // clamps 03-31 to 02-28), so the stretch is never longer than the month.
  if (toDate) return bounds(start, at);
  return bounds(
    start,
    unit === 'isoWeek' ? at.endOf('isoWeek') : at.endOf(unit),
  );
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

/**
 * Each preset as a period and an offset from the current one, and whether
 * it stops at the moment it is read rather than at the period's end.
 */
const PERIODS: Readonly<
  Record<DateTimePreset, { unit: PeriodUnit; shift: number; toDate?: true }>
> = {
  today: { unit: 'day', shift: 0 },
  yesterday: { unit: 'day', shift: -1 },
  dayBeforeYesterday: { unit: 'day', shift: -2 },
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
  weekToDate: { unit: 'isoWeek', shift: 0, toDate: true },
  lastWeekToDate: { unit: 'isoWeek', shift: -1, toDate: true },
  monthToDate: { unit: 'month', shift: 0, toDate: true },
  lastMonthToDate: { unit: 'month', shift: -1, toDate: true },
  quarterToDate: { unit: 'quarter', shift: 0, toDate: true },
  lastQuarterToDate: { unit: 'quarter', shift: -1, toDate: true },
  yearToDate: { unit: 'year', shift: 0, toDate: true },
  lastYearToDate: { unit: 'year', shift: -1, toDate: true },
};

type PeriodUnit = 'day' | 'isoWeek' | 'month' | 'quarter' | 'year';

function bounds(from: Dayjs, to: Dayjs): ClosedInstantRange {
  return { from: clampedIso(from, 'start'), to: clampedIso(to, 'end') };
}

/**
 * The instants a `Date` can name lie within this many milliseconds of the
 * epoch, on either side; dayjs returns an invalid instant past them.
 */
const MAX_INSTANT_MS = 8.64e15;

/**
 * An instant as ISO 8601, or the farthest instant on that side when the
 * arithmetic ran off the calendar. `validateFilter` bounds a relative amount
 * before compilation, so this is reached only by a tree that skipped it, and
 * a compiler that throws `RangeError` on such a tree turns a refused config
 * into a crash.
 */
function clampedIso(instant: Dayjs, edge: RangeEdge): string {
  if (instant.isValid()) return instant.toISOString();
  return new Date(
    edge === 'start' ? -MAX_INSTANT_MS : MAX_INSTANT_MS,
  ).toISOString();
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
 *
 * A relative value stands on its far edge whichever side asks. Its near edge
 * is `now`, and "on or before now" is not what anyone typed: "7 days" is a
 * distance from this moment, so `GTE` and `LTE` alike compare against the
 * instant 7 days ago, or 7 days ahead when the window runs forwards. A
 * preset is a calendar period and keeps the edge asked for: `LTE today` is
 * the end of today.
 */
export function resolveDateTimeBound(
  value: DateTimeFilterValue,
  now: Date,
  timeZone: string,
  edge: RangeEdge,
): string {
  if (value.type === 'relative') {
    const window = windowAt(value, now, timeZone);
    return value.direction === 'future' ? window.to : window.from;
  }
  if (value.type === 'preset')
    return windowAt(value, now, timeZone)[edge === 'start' ? 'from' : 'to'];
  const zone = value.timeZone ?? timeZone;
  return edge === 'start'
    ? instantIn(value.from, zone, 'start')
    : instantIn(value.to ?? value.from, zone, 'end');
}

/**
 * Each period a range may be exactly, as dayjs names the unit it starts on
 * and how far on the next one starts: a week is seven days from a midnight,
 * a quarter three months.
 */
const PERIOD_UNITS = [
  ['SECOND', 'second', 1, 'second'],
  ['MINUTE', 'minute', 1, 'minute'],
  ['HOUR', 'hour', 1, 'hour'],
  ['DAY', 'day', 1, 'day'],
  ['WEEK', 'day', 7, 'day'],
  ['MONTH', 'month', 1, 'month'],
  ['QUARTER', 'quarter', 3, 'month'],
  ['YEAR', 'year', 1, 'year'],
] as const;

/** A time on a clock, to the millisecond, with no zone of its own. */
const WALL_CLOCK = 'YYYY-MM-DDTHH:mm:ss.SSS';

/** A clock unit is as long as it always is; a calendar one is not. */
const CLOCK_MS: Partial<Record<AnalysisDateUnit, number>> = {
  SECOND: 1000,
  MINUTE: 60_000,
  HOUR: 3_600_000,
};

/**
 * Which period of the calendar in `timeZone` a range is exactly, if any:
 * the one a date bucket names (`bucketRange`), written as a closed range —
 * its first instant to the last millisecond before the next period starts.
 * A week is the seven days from a midnight, whichever day that is: it reads
 * as 「9月21日 起的一周」, which is true of any seven such days, where a
 * week day of our choosing would not match a source that starts weeks on
 * another one. A calendar period is stepped on the zone's wall clock, as
 * the bucket was, so a day a clock change makes 23 or 25 hours long is a day.
 *
 * Both edges must match to the millisecond, so an answer is never a
 * rounding: a range a millisecond short of a day is a range. A string that
 * names no instant, or a zone the runtime cannot resolve, is no period.
 */
export function periodOf(
  from: string,
  to: string,
  timeZone: string,
): AnalysisDateUnit | null {
  if (!isValidTimeZone(timeZone)) return null;
  const start = Date.parse(instantIn(from, timeZone, 'start'));
  const end = Date.parse(instantIn(to, timeZone, 'end'));
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return null;
  // The calendar arithmetic is done on the wall clock and read back in the
  // zone: dayjs moves a zoned time by the offset it started at, so on the
  // day of a clock change its own `startOf` and `add` are an hour out.
  const wall = dayjs.utc(dayjs(start).tz(timeZone).format(WALL_CLOCK));
  const inZone = (time: Dayjs) =>
    dayjs.tz(time.format(WALL_CLOCK), timeZone).valueOf();
  for (const [unit, startsOn, step, by] of PERIOD_UNITS) {
    const first = wall.startOf(startsOn);
    if (inZone(first) !== start) continue;
    const clock = CLOCK_MS[unit];
    const next =
      clock === undefined ? inZone(first.add(step, by)) : start + clock;
    if (next - 1 === end) return unit;
  }
  return null;
}
