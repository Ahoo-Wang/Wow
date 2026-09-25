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
 * Values of the built-in kinds. Each one stores what the user meant, not what
 * the query will contain: a relative range stays relative until compilation.
 */

export type StringFilterValue = string | string[];

export type NumberFilterValue = number | number[] | NumberRange;

/** Inclusive bounds of a `BETWEEN`. */
export type NumberRange = [number, number];

export type BooleanFilterValue = boolean;

export type EnumFilterValue = (string | number)[];

export interface ReferenceItem {
  id: string | number;
  /** Snapshot of the label, so reopening a view needs no lookup. */
  label: string;
}

export interface ReferenceFilterValue {
  items: ReferenceItem[];
}

export type DateTimeFilterValue =
  AbsoluteDateTimeValue | RelativeDateTimeValue | PresetDateTimeValue;

/** ISO 8601 instants or dates; `to` is omitted for an open upper bound. */
export interface AbsoluteDateTimeValue {
  type: 'absolute';
  from: string;
  to?: string;
  /** Overrides the runtime zone for this condition only. */
  timeZone?: string;
}

/**
 * A window measured from the evaluation moment: "the last 7 days", or "the
 * next 7 days". Both are ordinary business questions — one asks what
 * happened, the other what is due — and only the direction differs.
 */
export interface RelativeDateTimeValue {
  type: 'relative';
  amount: number;
  unit: RelativeDateUnit;
  /** Which side of now the window lies on; the past when unsaid. */
  direction?: RelativeDateDirection;
}

export type RelativeDateDirection = 'past' | 'future';

/**
 * How far a relative window may reach, in any unit.
 *
 * A `Date` holds about 273,000 years either side of the epoch; beyond that
 * dayjs yields an invalid instant and `toISOString` throws. 100,000 years is
 * safely inside that in the largest unit, and in the smallest, 100,000 hours
 * is eleven years, so no unit is cut short of anything a person would ask.
 * The shape check admits any positive integer; the kind's `validate` applies
 * this bound and reports it by name.
 */
export const MAX_RELATIVE_DATE_AMOUNT = 100_000;

export const RELATIVE_DATE_DIRECTIONS: readonly RelativeDateDirection[] = [
  'past',
  'future',
];

export type RelativeDateUnit =
  'hour' | 'day' | 'week' | 'month' | 'quarter' | 'year';

export const RELATIVE_DATE_UNITS: readonly RelativeDateUnit[] = [
  'hour',
  'day',
  'week',
  'month',
  'quarter',
  'year',
];

/** A named calendar window, resolved against the evaluation moment. */
export interface PresetDateTimeValue {
  type: 'preset';
  preset: DateTimePreset;
}

/**
 * Named calendar windows, in three directions. The set was previously only
 * the current period plus yesterday, which left "last month" as inexpressible
 * as "next week" — both of them ordinary things to ask a business system.
 *
 * And each period so far (D38): `monthToDate` is 「本月至今」, the month's
 * first moment to now, and `lastMonthToDate` 「上月同期（至今）」, last
 * month's first moment to the same moment of it — "this month against the
 * same days of last month" without two absolute dates that go stale.
 */
export type DateTimePreset =
  | 'today'
  | 'yesterday'
  | 'tomorrow'
  | 'thisWeek'
  | 'lastWeek'
  | 'nextWeek'
  | 'thisMonth'
  | 'lastMonth'
  | 'nextMonth'
  | 'thisQuarter'
  | 'lastQuarter'
  | 'nextQuarter'
  | 'thisYear'
  | 'lastYear'
  | 'nextYear'
  | 'weekToDate'
  | 'lastWeekToDate'
  | 'monthToDate'
  | 'lastMonthToDate'
  | 'quarterToDate'
  | 'lastQuarterToDate'
  | 'yearToDate'
  | 'lastYearToDate';

export const DATE_TIME_PRESETS: readonly DateTimePreset[] = [
  'today',
  'yesterday',
  'tomorrow',
  'thisWeek',
  'lastWeek',
  'nextWeek',
  'thisMonth',
  'lastMonth',
  'nextMonth',
  'thisQuarter',
  'lastQuarter',
  'nextQuarter',
  'thisYear',
  'lastYear',
  'nextYear',
  'weekToDate',
  'lastWeekToDate',
  'monthToDate',
  'lastMonthToDate',
  'quarterToDate',
  'lastQuarterToDate',
  'yearToDate',
  'lastYearToDate',
];

export function isPlainObject(
  value: unknown,
): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

export function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

export function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0;
}

/**
 * A string that still says something once trimmed. Whitespace is nothing
 * typed, so wherever a blank value is "not asked yet" a whitespace one is too.
 */
export function isNonBlankString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0;
}

/**
 * The shape of a `BETWEEN` value: two finite numbers, in whichever order they
 * were typed.
 *
 * Order is a separate question deliberately. Folding it in here made `[5, 1]`
 * report "enter a range of two numbers" — which it is — where a date saying
 * the same thing reports that the range starts after it ends. Two bounds the
 * user can see and one message they can act on.
 */
export function isNumberRange(value: unknown): value is NumberRange {
  return (
    Array.isArray(value) && value.length === 2 && value.every(isFiniteNumber)
  );
}

/** Whether a range's lower bound really is the lower one. */
export function isOrderedRange(range: NumberRange): boolean {
  return range[0] <= range[1];
}

export function isReferenceFilterValue(
  value: unknown,
): value is ReferenceFilterValue {
  if (!isPlainObject(value) || !Array.isArray(value.items)) return false;
  return value.items.every(
    item =>
      isPlainObject(item) &&
      (typeof item.id === 'string' || isFiniteNumber(item.id)) &&
      typeof item.label === 'string',
  );
}

export function isDateTimeFilterValue(
  value: unknown,
): value is DateTimeFilterValue {
  if (!isPlainObject(value)) return false;
  switch (value.type) {
    case 'absolute':
      return (
        isNonEmptyString(value.from) &&
        (value.to === undefined || isNonEmptyString(value.to)) &&
        (value.timeZone === undefined || isNonEmptyString(value.timeZone))
      );
    case 'relative':
      return (
        isFiniteNumber(value.amount) &&
        Number.isInteger(value.amount) &&
        value.amount > 0 &&
        RELATIVE_DATE_UNITS.includes(value.unit as RelativeDateUnit) &&
        (value.direction === undefined ||
          RELATIVE_DATE_DIRECTIONS.includes(
            value.direction as RelativeDateDirection,
          ))
      );
    case 'preset':
      return DATE_TIME_PRESETS.includes(value.preset as DateTimePreset);
    default:
      return false;
  }
}
