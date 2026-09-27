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

import type { Locale } from "@/i18n.tsx";

/** A moment the way an operator compares them: month, day and the second. */
export function formatMoment(epochMillis: number, locale: Locale): string {
  return new Intl.DateTimeFormat(locale, {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
  }).format(epochMillis);
}

/** The hour and minute of a moment, for a figure read at a glance. */
export function formatClock(epochMillis: number, locale: Locale): string {
  return new Intl.DateTimeFormat(locale, {
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).format(epochMillis);
}

const UNITS: readonly [Intl.RelativeTimeFormatUnit, number][] = [
  ["day", 86_400_000],
  ["hour", 3_600_000],
  ["minute", 60_000],
];

/**
 * How far a moment is from `now`, in the largest whole unit it spans —
 * 「52 分钟后」, "4 hours ago" — and "now" under a minute.
 */
export function formatRelative(
  epochMillis: number,
  now: number,
  locale: Locale,
): string {
  const format = new Intl.RelativeTimeFormat(locale, { numeric: "auto" });
  const delta = epochMillis - now;
  for (const [unit, size] of UNITS)
    if (Math.abs(delta) >= size)
      return format.format(Math.trunc(delta / size), unit);
  return format.format(0, "second");
}
