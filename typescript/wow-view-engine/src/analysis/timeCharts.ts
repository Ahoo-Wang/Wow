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
  CHART_COLOR_SLOTS,
  type AnalysisViewConfig,
  type CalendarSpec,
  type RecordData,
  type ThemeRiverSpec,
} from '../model/index.js';
import { readInstant } from '../filter/index.js';
import {
  absenceReader,
  num,
  OTHER_SERIES_KEY,
  seriesKey,
} from './chartRows.js';
import { forwardInTime, timeGroup, withoutHoles } from './timeAxis.js';

/** One day of a calendar: its bucket, its date in the zone, its number. */
export interface CalendarDay {
  /** The bucket key the row carried: what a press drills. */
  at: unknown;
  /** The day as the calendar places it, `YYYY-MM-DD` in the bucket's zone. */
  date: string;
  value: number;
}

export interface CalendarData {
  type: 'calendar';
  /** The measured days, earliest first; a day no row holds is no cell. */
  days: CalendarDay[];
  /** The years the days fall in, earliest first: one calendar each. */
  years: number[];
  /** The smallest and the largest measured number: the colour scale's ends. */
  low: number;
  high: number;
}

/** `YYYY-MM-DD` of an instant in a zone. */
function dayIn(ms: number, zone: string): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: zone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(ms);
}

/**
 * A calendar heatmap's days: each row a day of the one daily dimension, its
 * date written in the zone its bucket was cut in, earliest first. A day the
 * rows lack is no cell — a calendar draws "no group" as nothing, as a
 * heatmap does — so the colour scale's ends are measured days only, never a
 * 0 written in.
 */
export function shapeCalendar(
  spec: CalendarSpec,
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
  timeZone: string,
): CalendarData {
  const group = timeGroup(config, spec.date);
  const days: CalendarDay[] = [];
  for (const row of forwardInTime(rows, entry => entry[spec.date])) {
    const value = num(row, spec.value);
    const instant = readInstant(row[spec.date]);
    if (value === null || !instant) continue;
    const zone = instant.wallClock ? 'UTC' : (group?.timeZone ?? timeZone);
    days.push({ at: row[spec.date], date: dayIn(instant.ms, zone), value });
  }
  const values = days.map(day => day.value);
  return {
    type: 'calendar',
    days,
    years: [...new Set(days.map(day => Number(day.date.slice(0, 4))))],
    low: values.length > 0 ? Math.min(...values) : 0,
    high: values.length > 0 ? Math.max(...values) : 0,
  };
}

/** One stream of a theme river: a split value, or the folded rest. */
export interface RiverStream {
  key: string;
  /** The split value it stands for; left out on the folded 「其他」. */
  value?: unknown;
  other?: true;
}

export interface ThemeRiverData {
  type: 'themeRiver';
  /** The time axis, earliest first and without holes. */
  times: unknown[];
  /** Largest first; past the palette the smallest folded into one 「其他」. */
  streams: RiverStream[];
  /** `values[time][stream]`: a number at every point, which a river needs. */
  values: number[][];
  /**
   * How many points were written in as 0 with no row to say so and nothing
   * to say the group was empty — rows cut short, groups kept by 「只保留」.
   * A river cannot leave a hole, so the drawing says how many it guessed.
   */
  uncertain: number;
}

/**
 * A theme river's streams over time: the one date dimension forward and
 * without holes, a stream per value of the other dimension, each point its
 * row's number. A point the rows lack is 0 — a stream cannot break — and is
 * counted as `uncertain` unless the group is known to have had no records
 * (`absenceReader`). Past the palette the smallest streams are folded into
 * one grey 「其他」, their numbers added — the metric adds up, or the river
 * refuses it, and every row of the folded streams is on hand.
 */
export function shapeThemeRiver(
  spec: ThemeRiverSpec,
  config: AnalysisViewConfig,
  rows: readonly RecordData[],
  timeZone: string,
): ThemeRiverData {
  const group = timeGroup(config, spec.x);
  const same = (key: unknown) => key;
  let times = forwardInTime(
    [
      ...new Map(
        rows.map(row => [seriesKey(row[spec.x]), row[spec.x]]),
      ).values(),
    ],
    same,
  );
  if (group) times = withoutHoles(times, same, group, timeZone, same);
  const known = absenceReader(config, rows);
  const totals = new Map<string, { value: unknown; total: number }>();
  const cells = new Map<string, number>();
  for (const row of rows) {
    const key = seriesKey(row[spec.splitBy]);
    const value = num(row, spec.value) ?? 0;
    const entry = totals.get(key) ?? { value: row[spec.splitBy], total: 0 };
    entry.total += value;
    totals.set(key, entry);
    cells.set(`${seriesKey(row[spec.x])}\u0000${key}`, value);
  }
  const ranked = [...totals.entries()].sort((a, b) => b[1].total - a[1].total);
  const folds = ranked.length > CHART_COLOR_SLOTS;
  const kept = folds ? ranked.slice(0, CHART_COLOR_SLOTS - 1) : ranked;
  const rest = folds ? ranked.slice(CHART_COLOR_SLOTS - 1) : [];
  let uncertain = 0;
  const at = (time: unknown, key: string, value: unknown): number => {
    const cell = cells.get(`${seriesKey(time)}\u0000${key}`);
    if (cell !== undefined) return cell;
    if (!known({ [spec.x]: time, [spec.splitBy]: value })) uncertain += 1;
    return 0;
  };
  const values = times.map(time => [
    ...kept.map(([key, entry]) => at(time, key, entry.value)),
    ...(folds
      ? [
          rest.reduce(
            (sum, [key, entry]) => sum + at(time, key, entry.value),
            0,
          ),
        ]
      : []),
  ]);
  return {
    type: 'themeRiver',
    times,
    streams: [
      ...kept.map(([key, entry]) => ({ key, value: entry.value })),
      ...(folds ? [{ key: OTHER_SERIES_KEY, other: true as const }] : []),
    ],
    values,
    uncertain,
  };
}
