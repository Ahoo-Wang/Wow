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

/**
 * A time of day as its two typed segments, to the minute (用户 2026-09-25
 * 定「精确到分」): `null` for a segment nothing is typed in.
 */
export interface TimeSegments {
  hour: number | null;
  minute: number | null;
}

/** The largest value each segment takes: 00–23 and 00–59. */
export const SEGMENT_MAX = { hour: 23, minute: 59 } as const;

const TIME = /^(\d{2}):(\d{2})(?::\d{2}(?:\.\d+)?)?$/;

/**
 * A stored time of day — `HH:mm`, or `HH:mm:ss` as this control used to
 * write it and a host's own config may hold — as its two segments; the
 * seconds are not shown, and are gone once the time is written again.
 * Nothing, or anything else, is two blank segments: the whole day.
 */
export function parseTime(text: string): TimeSegments {
  const match = TIME.exec(text);
  if (!match) return { hour: null, minute: null };
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  if (hour > SEGMENT_MAX.hour || minute > SEGMENT_MAX.minute)
    return { hour: null, minute: null };
  return { hour, minute };
}

/**
 * A typed segment held inside its range, a whole number: what a box that
 * says 00–23 can mean by 25, or by -1, or by 7.5. `null` stays `null`.
 */
export function clampSegment(
  value: number | null,
  segment: keyof typeof SEGMENT_MAX,
): number | null {
  if (value === null || Number.isNaN(value)) return null;
  return Math.min(SEGMENT_MAX[segment], Math.max(0, Math.trunc(value)));
}

/**
 * The time as stored: `HH:mm`, a segment left blank beside a typed one
 * standing at 00 — 「9 点」 is 09:00 — and `''` when neither is typed, which
 * is the whole day.
 */
export function writeTime({ hour, minute }: TimeSegments): string {
  if (hour === null && minute === null) return '';
  return `${pad(hour ?? 0)}:${pad(minute ?? 0)}`;
}

/**
 * Whether the text typed in the hour box is a whole hour already, so the
 * keyboard moves on to the minutes: two digits, or one no hour starts with
 * a second digit after (3–9).
 */
export function hourIsComplete(text: string): boolean {
  return /^\d{2}$/.test(text) || /^[3-9]$/.test(text);
}

function pad(part: number): string {
  return String(part).padStart(2, '0');
}
