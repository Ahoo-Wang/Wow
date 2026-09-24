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

import { AGGREGATION_LIMITS } from '@ahoo-wang/wow-client';
import type { AnalysisGroup, AnalysisViewConfig } from '../model/index.js';
import { readInstant, type DateInstant } from '../filter/index.js';
import { bucketRange, wallClockAt } from './drill.js';

/**
 * A time axis as the chart projection draws one: earliest first whatever
 * the rows' order (`forwardInTime`), and without holes between its first
 * and its last bucket (`withoutHoles`). What a hole holds is the caller's
 * to say; where it falls is time's.
 */

export type DateGroup = Extract<AnalysisGroup, { type: 'DATE_HISTOGRAM' }>;

/**
 * The time dimension — a date bucket — `alias` names in the config the rows
 * came from, if it names one.
 */
export function timeGroup(
  config: AnalysisViewConfig,
  alias: string | undefined,
): DateGroup | undefined {
  return config.groups.find(
    (group): group is DateGroup =>
      group.alias === alias && group.type === 'DATE_HISTOGRAM',
  );
}

/**
 * `items` earliest first, by the bucket `at` reads off each.
 *
 * A time axis runs forward whatever order the rows came in. The rows are in
 * the view's sort, and a view of the last thirty days is sorted newest first
 * on purpose — its table leads with today — but the same rows drawn in that
 * order put today on the left and yesterday to its right: every line slopes
 * the wrong way and every bar reads backwards, with nothing on screen to say
 * so. The table is the view's to order; an axis is time's.
 *
 * A bucket is read the way the drill reads it (`readInstant`), so a key that
 * arrives as epoch milliseconds, as a string of digits or as a wall-clock day
 * sorts the same. One that names no instant — a missing-value sentinel — is
 * no point in time, so it goes after the last one, and ties and unreadable
 * keys keep the order they came in.
 */
export function forwardInTime<T>(
  items: readonly T[],
  at: (item: T) => unknown,
): T[] {
  return items
    .map((item, index) => ({ item, index, ms: readInstant(at(item))?.ms }))
    .sort((a, b) => {
      if (a.ms === undefined || b.ms === undefined)
        return a.ms === b.ms ? a.index - b.index : a.ms === undefined ? 1 : -1;
      return a.ms - b.ms || a.index - b.index;
    })
    .map(entry => entry.item);
}

/**
 * The most buckets a filled time axis runs to: as many as a `dense` query
 * could have answered. Past it the rows are left as they came rather than
 * padded into an axis no source would return.
 */
const MAX_RUN = AGGREGATION_LIMITS.MAX_LIMIT;

/**
 * `items` — already earliest first (`forwardInTime`) — with a hole for every
 * bucket of `group`'s unit that is missing between the first and the last.
 *
 * A time axis is time: a day with no rows is still a day. Drawn as a
 * category axis of the buckets that came back, 8/20 sat next to 8/23, a bar
 * chart read as consecutive days and a line smoothed three days of zero into
 * a slope — and "which days did nothing happen" is the question an operator
 * looks at a daily chart to answer.
 *
 * The buckets are stepped with the drill's own `bucketRange`, in the zone the
 * histogram was cut in: the group's `timeZone`, else the engine's. A bucket
 * written as a wall-clock day (`2026-09-18`) names a day and no zone, and is
 * stepped at UTC, where `readInstant` reads it. Every present bucket must lie
 * on the stepped run — a key cut in another zone, or a week that starts on
 * another day, does not — and when one does not, nothing is filled: a hole
 * the kernel cannot place is left out rather than invented. So is a `dense`
 * histogram, which the source has filled already. A bucket that names no
 * moment — the missing-value sentinel — stays after the last one, and a hole
 * is written the way the first bucket was (a number, digits, a day), so the
 * UI reads it as its column does.
 */
export function withoutHoles<T>(
  items: readonly T[],
  at: (item: T) => unknown,
  group: DateGroup,
  timeZone: string,
  hole: (key: unknown) => T,
): T[] {
  if (group.dense === true) return [...items];
  const timed: { item: T; key: unknown; instant: DateInstant }[] = [];
  const untimed: T[] = [];
  for (const item of items) {
    const key = at(item);
    const instant = readInstant(key);
    if (instant) timed.push({ item, key, instant });
    else untimed.push(item);
  }
  if (timed.length < 2) return [...items];

  const sample = timed[0];
  const zone = sample.instant.wallClock ? 'UTC' : (group.timeZone ?? timeZone);
  const byMs = new Map<number, T[]>();
  for (const { item, instant } of timed)
    byMs.set(instant.ms, [...(byMs.get(instant.ms) ?? []), item]);
  const first = Math.min(...byMs.keys());
  const last = Math.max(...byMs.keys());

  const run: number[] = [];
  for (let start = first; start <= last;) {
    if (run.length === MAX_RUN) return [...items];
    run.push(start);
    const next = bucketRange(group.unit, start, zone).to;
    if (!(next > start)) return [...items];
    start = next;
  }
  if (run.length === byMs.size) return [...items];
  const stepped = new Set(run);
  if ([...byMs.keys()].some(ms => !stepped.has(ms))) return [...items];

  return [
    ...run.flatMap(ms => byMs.get(ms) ?? [hole(keyLike(sample, ms))]),
    ...untimed,
  ];
}

/**
 * The bucket `key` starts, as the moments it runs from and to, stepped in
 * the zone `withoutHoles` steps in: a wall-clock key at UTC, where
 * `readInstant` reads it, anything else in the group's zone or the engine's.
 * `ended` says whether it was over at `now`: a wall-clock key names a day
 * and no zone, so it is over once the clock in that zone has passed its end;
 * `left` is how long it still ran from `now`, where it had not ended.
 * Undefined for a key that names no moment — the missing-value sentinel.
 */
export function bucketSpan(
  group: DateGroup,
  key: unknown,
  timeZone: string,
  now?: Date,
): { from: number; to: number; ended: boolean; left?: number } | undefined {
  const instant = readInstant(key);
  if (!instant) return undefined;
  const zone = group.timeZone ?? timeZone;
  const to = bucketRange(
    group.unit,
    instant.ms,
    instant.wallClock ? 'UTC' : zone,
  ).to;
  const at =
    now === undefined
      ? undefined
      : instant.wallClock
        ? wallClockAt(now.getTime(), zone)
        : now.getTime();
  return at === undefined || at >= to
    ? { from: instant.ms, to, ended: true }
    : { from: instant.ms, to, ended: false, left: to - at };
}

/** Epoch milliseconds, written as digits. */
const DIGITS = /^-?\d+$/;

/** A bucket starting at `ms`, written as `sample`'s key was. */
function keyLike(
  sample: { key: unknown; instant: DateInstant },
  ms: number,
): unknown {
  const { key, instant } = sample;
  if (typeof key !== 'string') return key instanceof Date ? new Date(ms) : ms;
  const written = key.trim();
  if (DIGITS.test(written)) return String(ms);
  const iso = new Date(ms).toISOString();
  if (!instant.wallClock) return iso;
  if (instant.dayOnly) return iso.slice(0, 10);
  // `2026-09-18T09:30`, `2026-09-18 09:30:00.000000`: the same separator
  // and as many digits of the time as the sample wrote.
  const time = `${iso.slice(11, 23)}000000`.slice(0, written.length - 11);
  return `${iso.slice(0, 10)}${written[10]}${time}`;
}

/**
 * The host's zone, which is the engine's unless the host configured another
 * (`runtime/environment.ts`); a caller that knows the engine's passes it.
 */
export function hostTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}
