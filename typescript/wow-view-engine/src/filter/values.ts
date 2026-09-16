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

/** "The last 7 days": a window ending at the evaluation moment. */
export interface RelativeDateTimeValue {
  type: 'relative';
  amount: number;
  unit: RelativeDateUnit;
}

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

export type DateTimePreset =
  'today' | 'yesterday' | 'thisWeek' | 'thisMonth' | 'thisQuarter' | 'thisYear';

export const DATE_TIME_PRESETS: readonly DateTimePreset[] = [
  'today',
  'yesterday',
  'thisWeek',
  'thisMonth',
  'thisQuarter',
  'thisYear',
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

export function isNumberRange(value: unknown): value is NumberRange {
  return (
    Array.isArray(value) &&
    value.length === 2 &&
    value.every(isFiniteNumber) &&
    value[0] <= value[1]
  );
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
        RELATIVE_DATE_UNITS.includes(value.unit as RelativeDateUnit)
      );
    case 'preset':
      return DATE_TIME_PRESETS.includes(value.preset as DateTimePreset);
    default:
      return false;
  }
}
