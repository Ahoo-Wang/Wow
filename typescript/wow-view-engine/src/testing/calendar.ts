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
  AggregationDatePart,
  AggregationDateUnit,
  type DateHistogramAggregationGroup,
  type DatePartAggregationGroup,
} from '@ahoo-wang/wow-client';
import { mod } from './values.js';

const HOUR_MS = 3_600_000;
const DAY_MS = 24 * HOUR_MS;

/**
 * The units a bucket shorter than a day is cut at, in milliseconds. Inside a
 * local day the clock does not jump on, such a bucket is a slot of this width
 * counted from local midnight — the wall-clock hour, minute or second, which
 * is what Wow keys a sub-day bucket by in any zone, a half-hour one too.
 */
const SUB_DAY_WIDTHS: Partial<Record<AggregationDateUnit, number>> = {
  [AggregationDateUnit.HOUR]: HOUR_MS,
  [AggregationDateUnit.MINUTE]: 60_000,
  [AggregationDateUnit.SECOND]: 1_000,
};

/** One calendar day in a zone: `[start, end)` and the bucket it falls in. */
interface LocalDay {
  start: number;
  end: number;
  /** The day's bucket, for a unit of a day or more. */
  bucket: number;
  /** A day of 24 hours the zone's offset does not change during. */
  regular: boolean;
}

/**
 * The bucket start of each instant, per (unit, zone), shared by every
 * source: it is a function of the calendar alone, so a bucketer built for one
 * source's documents is right for any other's.
 */
const bucketers = new Map<string, (at: number) => number>();

/**
 * The bucket start an instant falls in, for one unit in one zone: the start
 * of its unit in the zone, in epoch milliseconds, which is the key the
 * service answers a date bucket with.
 *
 * Every bucket of a day or more starts at a local midnight, so the bucket is
 * a property of the calendar day: it is worked out once per day the rows
 * touch and looked up after that, where converting each row into the zone
 * cost a conversion per row per query. A shorter bucket is counted from the
 * day's start (`SUB_DAY_WIDTHS`), except on a day the clock jumps, which is
 * worked out row by row from the wall clock.
 *
 * The week starts on Monday, as `wow-mongo` truncates it (`$dateTrunc` with
 * `startOfWeek: "Monday"`); a quarter starts in January, April, July or
 * October. The zone arithmetic is the platform's (`Intl.DateTimeFormat`):
 * dayjs's timezone plugin answers from the host's own zone rules and was off
 * by an hour on the days the host's clock moves.
 */
export function bucketerOf(
  unit: AggregationDateUnit,
  zone: string,
): (at: number) => number {
  const key = `${unit}|${zone}`;
  let bucketer = bucketers.get(key);
  if (!bucketer) {
    bucketer = newBucketer(unit, zone);
    bucketers.set(key, bucketer);
  }
  return bucketer;
}

function newBucketer(
  unit: AggregationDateUnit,
  zone: string,
): (at: number) => number {
  if (!Object.values(AggregationDateUnit).includes(unit))
    throw new Error(`The memory source does not bucket by ${unit}.`);
  const clock = wallClock(zone);
  // A UTC day overlaps at most two local days, so each holds a short list.
  const days = new Map<number, LocalDay[]>();
  const dayOf = (at: number): LocalDay => {
    const known = days
      .get(Math.floor(at / DAY_MS))
      ?.find(day => day.start <= at && at < day.end);
    if (known) return known;
    const day = localDay(at, unit, clock);
    const last = Math.floor((day.end - 1) / DAY_MS);
    for (let index = Math.floor(day.start / DAY_MS); index <= last; index++) {
      const held = days.get(index);
      if (held) held.push(day);
      else days.set(index, [day]);
    }
    return day;
  };
  const width = SUB_DAY_WIDTHS[unit];
  if (width === undefined) return at => dayOf(at).bucket;
  return at => {
    const day = dayOf(at);
    if (day.regular)
      return day.start + Math.floor((at - day.start) / width) * width;
    // The wall clock's own remainder, taken off the instant: the hour keeps
    // the offset it is read in, as `wow-mongo` truncates it.
    const wall = clock.wall(at);
    return at - (mod(wall, DAY_MS) % width);
  };
}

/** A zone's wall clock: an instant read as if its wall time were UTC. */
interface WallClock {
  wall(at: number): number;
  /** The instant a wall time names (`instantAt`). */
  instant(wall: number): number;
}

const clocks = new Map<string, WallClock>();

function wallClock(zone: string): WallClock {
  let clock = clocks.get(zone);
  if (clock) return clock;
  const format = new Intl.DateTimeFormat('en-US', {
    timeZone: zone,
    hourCycle: 'h23',
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
    minute: 'numeric',
    second: 'numeric',
    era: 'short',
  });
  const wall = (at: number): number => {
    const parts: Record<string, string> = {};
    for (const { type, value } of format.formatToParts(at)) parts[type] = value;
    const year =
      parts.era === 'BC' ? 1 - Number(parts.year) : Number(parts.year);
    const date = new Date(0);
    date.setUTCFullYear(year, Number(parts.month) - 1, Number(parts.day));
    date.setUTCHours(
      Number(parts.hour),
      Number(parts.minute),
      Number(parts.second),
      mod(at, 1_000),
    );
    return date.getTime();
  };
  clock = { wall, instant: local => instantAt(local, wall) };
  clocks.set(zone, clock);
  return clock;
}

/**
 * The instant a wall time names in a zone, as `java.time` resolves one — and
 * so as Wow's dense grid does (`ZonedDateTime`): a wall time the clock passes
 * twice is the earlier instant; one the clock skips is moved later by the
 * gap, so a midnight the zone skips starts its day when the clock resumes.
 */
function instantAt(local: number, wall: (at: number) => number): number {
  const before = wall(local - DAY_MS) - (local - DAY_MS);
  const after = wall(local + DAY_MS) - (local + DAY_MS);
  const named = [local - before, local - after]
    .filter(at => wall(at) === local)
    .sort((left, right) => left - right);
  return named[0] ?? local - before;
}

function localDay(
  at: number,
  unit: AggregationDateUnit,
  clock: WallClock,
): LocalDay {
  // The wall date, as a UTC midnight: calendar arithmetic on it is plain.
  const date = clock.wall(at) - mod(clock.wall(at), DAY_MS);
  const start = clock.instant(date);
  const end = clock.instant(date + DAY_MS);
  const regular =
    end - start === DAY_MS &&
    clock.wall(start) - start === clock.wall(end - 1) - (end - 1);
  return { start, end, regular, bucket: dayBucket(date, start, unit, clock) };
}

/**
 * The bucket of the local day on wall date `date` (a UTC midnight), which
 * starts at the instant `start`, for a unit of a day or more.
 */
function dayBucket(
  date: number,
  start: number,
  unit: AggregationDateUnit,
  clock: WallClock,
): number {
  const day = new Date(date);
  const year = day.getUTCFullYear();
  const month = day.getUTCMonth();
  const first = (monthIndex: number) => {
    const midnight = new Date(0);
    midnight.setUTCFullYear(year, monthIndex, 1);
    return clock.instant(midnight.getTime());
  };
  switch (unit) {
    case AggregationDateUnit.WEEK:
      // Days back to Monday: Sunday is day 0 to `Date` and 6 here.
      return clock.instant(date - ((day.getUTCDay() + 6) % 7) * DAY_MS);
    case AggregationDateUnit.MONTH:
      return first(month);
    case AggregationDateUnit.QUARTER:
      return first(month - (month % 3));
    case AggregationDateUnit.YEAR:
      return first(0);
    default:
      return start;
  }
}

/**
 * The zone a date histogram buckets in, or a date part reads in: its own, or
 * UTC, as Wow's `AggregationGroup` defaults it — not the reader's.
 */
export function zoneOf(
  group: DateHistogramAggregationGroup | DatePartAggregationGroup,
): string {
  return group.timeZone ?? 'UTC';
}

/** The keys a date part runs through, first and last (Wow's `DatePartFill`). */
export const PART_DOMAINS: Record<
  AggregationDatePart,
  [first: number, last: number]
> = {
  [AggregationDatePart.DAY_OF_WEEK]: [1, 7],
  [AggregationDatePart.HOUR_OF_DAY]: [0, 23],
  [AggregationDatePart.DAY_OF_MONTH]: [1, 31],
  [AggregationDatePart.MONTH_OF_YEAR]: [1, 12],
};

/** How a date part reads an instant, on the zone's wall clock. */
export function parterOf(
  part: AggregationDatePart,
  zone: string,
): (at: number) => number {
  if (!Object.values(AggregationDatePart).includes(part))
    throw new Error(`The memory source does not read the date part ${part}.`);
  const clock = wallClock(zone);
  return at => {
    const wall = new Date(clock.wall(at));
    switch (part) {
      case AggregationDatePart.DAY_OF_WEEK:
        return wall.getUTCDay() === 0 ? 7 : wall.getUTCDay();
      case AggregationDatePart.HOUR_OF_DAY:
        return wall.getUTCHours();
      case AggregationDatePart.DAY_OF_MONTH:
        return wall.getUTCDate();
      case AggregationDatePart.MONTH_OF_YEAR:
        return wall.getUTCMonth() + 1;
    }
  };
}

/** More buckets than any answer can show (Wow's limit is 10,000). */
const MAX_DENSE_BUCKETS = 100_000;

/**
 * How far to look past a bucket's start for the next one, and how far to
 * step while the look still lands in the same bucket. The first look is
 * shorter than the unit can be; each step is shorter than any bucket, so no
 * bucket is stepped over.
 */
const NEXT_BUCKET: Record<AggregationDateUnit, [first: number, step: number]> =
  {
    [AggregationDateUnit.SECOND]: [1_000, 1_000],
    [AggregationDateUnit.MINUTE]: [60_000, 60_000],
    [AggregationDateUnit.HOUR]: [15 * 60_000, 15 * 60_000],
    [AggregationDateUnit.DAY]: [22 * HOUR_MS, HOUR_MS],
    [AggregationDateUnit.WEEK]: [166 * HOUR_MS, HOUR_MS],
    [AggregationDateUnit.MONTH]: [27 * DAY_MS, HOUR_MS],
    [AggregationDateUnit.QUARTER]: [88 * DAY_MS, HOUR_MS],
    [AggregationDateUnit.YEAR]: [364 * DAY_MS, HOUR_MS],
  };

/**
 * The keys of a dense date histogram, in order, as Wow answers `dense`: every
 * bucket of the unit between the first and the last key that has records —
 * not beyond them. A local date the zone skipped is no bucket; the next
 * bucket start after a start is the one the next instant falls in, so a
 * skipped day is stepped over rather than invented.
 */
export function denseKeys(
  keys: readonly number[],
  group: DateHistogramAggregationGroup,
): number[] {
  if (keys.length === 0) return [];
  const sorted = [...keys].sort((left, right) => left - right);
  const last = sorted[sorted.length - 1];
  const bucketOf = bucketerOf(group.unit, zoneOf(group));
  const [first, step] = NEXT_BUCKET[group.unit];
  const answer: number[] = [];
  for (let key = sorted[0]; ;) {
    answer.push(key);
    if (answer.length > MAX_DENSE_BUCKETS)
      throw new Error(
        `The memory source fills at most ${MAX_DENSE_BUCKETS} dense buckets.`,
      );
    if (key >= last) break;
    let probe = key + first;
    while (bucketOf(probe) <= key) probe += step;
    key = bucketOf(probe);
  }
  return answer;
}
