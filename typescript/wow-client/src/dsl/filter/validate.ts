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

// Internal: not re-exported by filter/index.ts. The argument checks the
// filter builders share.
import { validateDatePattern } from './datePattern.js';
import { StringComparison, TimeUnit, type FilterOperator } from './operator.js';
import type { FilterLiteral, RelativeTimeFilterOptions } from './types.js';

const LOCAL_TIME_PATTERN =
  /^([01][0-9]|2[0-3]):[0-5][0-9](?::[0-5][0-9](?:\.[0-9]{1,9})?)?$/;
const OFFSET_ZONE_PATTERN =
  /^(?:UTC|GMT|UT)?[+-](\d{1,2}|\d{4}|\d{6}|\d{2}:\d{2}|\d{2}:\d{2}:\d{2})$/;
const OFFSET_ZONE_CANDIDATE_PATTERN = /^(?:UTC|GMT|UT)?[+-]/;
// java.time.Duration.parse: signed days, hours, minutes and seconds (up to
// nine fraction digits after `.` or `,`), at least one of them, in that order,
// case-insensitive. Weeks, months and years are not durations.
const DURATION_PATTERN =
  /^[-+]?P(?=.*[0-9])(?:[-+]?[0-9]+D)?(?:T(?=[-+]?[0-9])(?:[-+]?[0-9]+H)?(?:[-+]?[0-9]+M)?(?:[-+]?[0-9]+(?:[.,][0-9]{0,9})?S)?)?$/i;

export function filterLiteral<T extends FilterLiteral>(
  value: T,
  nullable: boolean,
): T {
  const valid =
    value === null
      ? nullable
      : typeof value === 'string' ||
        typeof value === 'boolean' ||
        (typeof value === 'number' && Number.isFinite(value));
  if (!valid) {
    throw new TypeError('Filter value must be a JSON scalar.');
  }
  return value;
}

export function requiredString(name: string, value: string): string {
  if (typeof value !== 'string') {
    throw new TypeError(`${name} must be a string.`);
  }
  return value;
}

export function validateStringComparison(comparison: StringComparison): void {
  if (
    comparison !== StringComparison.CASE_SENSITIVE &&
    comparison !== StringComparison.CASE_INSENSITIVE
  ) {
    throw new TypeError(
      `String comparison is invalid: [${String(comparison)}].`,
    );
  }
}

export function requireNonEmpty(
  name: string,
  values: readonly unknown[],
): void {
  if (values.length === 0) {
    throw new TypeError(`${name} cannot be empty.`);
  }
  if (values.some(value => value === null || value === undefined)) {
    throw new TypeError(`${name} cannot contain null.`);
  }
}

function isValidOffsetZone(zoneId: string): boolean {
  const match = OFFSET_ZONE_PATTERN.exec(zoneId);
  if (!match) return false;
  const offset = match[1];
  const parts = offset.includes(':')
    ? offset.split(':')
    : offset.length <= 2
      ? [offset]
      : [offset.slice(0, 2), offset.slice(2, 4), offset.slice(4, 6)];
  const [hours, minutes = 0, seconds = 0] = parts.map(Number);
  return (
    minutes <= 59 &&
    seconds <= 59 &&
    (hours < 18 || (hours === 18 && minutes === 0 && seconds === 0))
  );
}

export function validateRelativeTimeOptions({
  zoneId,
  datePattern,
  timeUnit = TimeUnit.MILLISECONDS,
}: RelativeTimeFilterOptions): RelativeTimeFilterOptions & {
  timeUnit: TimeUnit;
} {
  if (zoneId !== undefined) {
    if (typeof zoneId !== 'string' || !zoneId.trim()) {
      throw new TypeError('zoneId cannot be blank.');
    }
    if (
      OFFSET_ZONE_CANDIDATE_PATTERN.test(zoneId) &&
      !isValidOffsetZone(zoneId)
    ) {
      throw new TypeError(`zoneId is invalid: [${zoneId}].`);
    }
  }
  if (datePattern !== undefined) {
    validateDatePattern(datePattern);
  }
  if (!Object.values(TimeUnit).includes(timeUnit)) {
    throw new TypeError(`timeUnit is invalid: [${String(timeUnit)}].`);
  }
  return {
    ...(zoneId === undefined ? {} : { zoneId }),
    ...(datePattern === undefined ? {} : { datePattern }),
    timeUnit,
  };
}

export function validateDays(operator: FilterOperator, days: number): void {
  if (!Number.isInteger(days) || days < 1 || days > 2_147_483_647) {
    throw new TypeError(`${operator} days must be a positive JVM Int.`);
  }
}

/**
 * Admits the ISO-8601 duration `BEFORE_NOW` and `AFTER_NOW` add to the
 * server's now, in the grammar `java.time.Duration.parse` accepts. A value
 * too large for a `Duration` is left to the server.
 */
export function requireDuration(
  operator: FilterOperator,
  offset: string,
): string {
  if (typeof offset !== 'string' || !DURATION_PATTERN.test(offset)) {
    throw new TypeError(
      `${operator} offset must be an ISO-8601 duration such as PT0S or -PT30M.`,
    );
  }
  return offset;
}

/** Admits the 24-hour local time `BEFORE_TODAY` compares against. */
export function requireLocalTime(time: string): string {
  if (typeof time !== 'string' || !LOCAL_TIME_PATTERN.test(time)) {
    throw new TypeError('BEFORE_TODAY time is invalid.');
  }
  return time;
}
