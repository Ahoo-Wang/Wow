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

import type { AnalysisDateUnit } from '../model/index.js';

/*
 * Date-histogram buckets as instants: where one starts and ends in a zone,
 * and what a zone's wall clock shows. Drilling, the time axis and the
 * granularity all cut time here, so it is a leaf none of them owns.
 */

/** The instant a bucket starts, and the instant the next one does. */
export interface BucketRange {
  from: number;
  to: number;
}

const MS = { SECOND: 1000, MINUTE: 60_000, HOUR: 3_600_000 } as const;

/**
 * The bucket a date-histogram key names: `[from, to)` in epoch milliseconds,
 * where `to` is the next bucket's start in `timeZone`.
 *
 * Calendar units advance on the zone's wall clock — a month is the same day
 * of the next month, a week is seven days later — so a bucket that crosses a
 * daylight-saving change is as long as the calendar says, not 24 hours.
 * Clock units advance by their length; Wow cuts hours on the wall clock too,
 * which agrees except across the one hour a year that repeats or is skipped.
 */
export function bucketRange(
  unit: AnalysisDateUnit,
  start: number,
  timeZone: string,
): BucketRange {
  switch (unit) {
    case 'SECOND':
    case 'MINUTE':
    case 'HOUR':
      return { from: start, to: start + MS[unit] };
    default: {
      const wall = zonedParts(start, timeZone);
      const next = advance(wall, unit);
      return { from: start, to: zonedToUtc(next, timeZone) };
    }
  }
}

/**
 * The start of the bucket of `unit` that holds `ms`, cut in `timeZone` the
 * way Wow cuts a date histogram: a calendar unit on the zone's wall clock —
 * a week from its Monday — an hour on the wall-clock hour, a minute and a
 * second by their length. The inverse of `bucketRange`'s question: that
 * one is given a bucket's start, this one any moment inside it.
 */
export function bucketStart(
  unit: AnalysisDateUnit,
  ms: number,
  timeZone: string,
): number {
  if (unit === 'SECOND' || unit === 'MINUTE')
    return Math.floor(ms / MS[unit]) * MS[unit];
  const wall = zonedParts(ms, timeZone);
  const top = { ...wall, minute: 0, second: 0 };
  switch (unit) {
    case 'HOUR':
      return zonedToUtc(top, timeZone);
    case 'DAY':
      return zonedToUtc({ ...top, hour: 0 }, timeZone);
    case 'WEEK': {
      const weekday = new Date(
        Date.UTC(wall.year, wall.month - 1, wall.day),
      ).getUTCDay();
      // Monday is day 0 of an ISO week; Sunday is its sixth.
      const back = (weekday + 6) % 7;
      return zonedToUtc({ ...top, hour: 0, day: wall.day - back }, timeZone);
    }
    case 'MONTH':
      return zonedToUtc({ ...top, hour: 0, day: 1 }, timeZone);
    case 'QUARTER':
      return zonedToUtc(
        {
          ...top,
          hour: 0,
          day: 1,
          month: Math.floor((wall.month - 1) / 3) * 3 + 1,
        },
        timeZone,
      );
    case 'YEAR':
      return zonedToUtc({ ...top, hour: 0, day: 1, month: 1 }, timeZone);
  }
}

interface WallClock {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
}

function advance(
  wall: WallClock,
  unit: Exclude<AnalysisDateUnit, 'SECOND' | 'MINUTE' | 'HOUR'>,
): WallClock {
  switch (unit) {
    case 'DAY':
      return { ...wall, day: wall.day + 1 };
    case 'WEEK':
      return { ...wall, day: wall.day + 7 };
    case 'MONTH':
      return { ...wall, month: wall.month + 1 };
    case 'QUARTER':
      return { ...wall, month: wall.month + 3 };
    case 'YEAR':
      return { ...wall, year: wall.year + 1 };
  }
}

/**
 * What a clock in `timeZone` shows at `ms`, as the instant that wall-clock
 * time is at UTC — the axis a wall-clock bucket key (`2026-09-18`) is read on.
 */
export function wallClockAt(ms: number, timeZone: string): number {
  return zonedToUtc(zonedParts(ms, timeZone), 'UTC');
}

/**
 * One wall-clock reader per zone, kept for the life of the page. Building an
 * `Intl.DateTimeFormat` costs far more than asking one: a result of ten
 * thousand days reads the clock three times a bucket, and a reader built for
 * each reading spent seconds there — three in Chromium, more in WebKit.
 */
const wallClocks = new Map<string, Intl.DateTimeFormat>();

function wallClockOf(timeZone: string): Intl.DateTimeFormat {
  let found = wallClocks.get(timeZone);
  if (!found) {
    found = new Intl.DateTimeFormat('en-US', {
      timeZone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
    wallClocks.set(timeZone, found);
  }
  return found;
}

function zonedParts(ms: number, timeZone: string): WallClock {
  const parts = wallClockOf(timeZone).formatToParts(new Date(ms));
  const read = (type: Intl.DateTimeFormatPartTypes) =>
    Number(parts.find(part => part.type === type)?.value ?? '0');
  return {
    year: read('year'),
    month: read('month'),
    day: read('day'),
    hour: read('hour'),
    minute: read('minute'),
    second: read('second'),
  };
}

/**
 * The instant a wall-clock time in `timeZone` names. `Date.UTC` carries an
 * overflowing day or month into the next, which is what `advance` relies on.
 * The zone's offset is read at a first guess and once more at the answer,
 * which settles every transition but the repeated hour, where the earlier
 * reading is kept.
 */
function zonedToUtc(wall: WallClock, timeZone: string): number {
  const asUtc = Date.UTC(
    wall.year,
    wall.month - 1,
    wall.day,
    wall.hour,
    wall.minute,
    wall.second,
  );
  const offsetAt = (ms: number) => {
    const there = zonedParts(ms, timeZone);
    return (
      Date.UTC(
        there.year,
        there.month - 1,
        there.day,
        there.hour,
        there.minute,
        there.second,
      ) - ms
    );
  };
  const guess = asUtc - offsetAt(asUtc);
  return asUtc - offsetAt(guess);
}
