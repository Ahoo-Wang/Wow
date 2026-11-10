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

import { queryField } from '../field.js';
import { FilterOperator } from './operator.js';
import type {
  BeforeTodayFilter,
  CalendarFilter,
  DaysFilter,
  NowFilter,
  RelativeTimeFilterOptions,
} from './types.js';
import {
  requireLocalTime,
  requireDuration,
  validateDays,
  validateRelativeTimeOptions,
} from './validate.js';

function calendar<FIELDS extends string>(
  op: CalendarFilter['op'],
  field: FIELDS,
  options: RelativeTimeFilterOptions,
): CalendarFilter<FIELDS> {
  return {
    ...validateRelativeTimeOptions(options),
    op,
    field: queryField(field),
  };
}

function dayWindow<FIELDS extends string>(
  op: DaysFilter['op'],
  field: FIELDS,
  days: number,
  options: RelativeTimeFilterOptions,
): DaysFilter<FIELDS> {
  validateDays(op, days);
  return {
    ...validateRelativeTimeOptions(options),
    op,
    field: queryField(field),
    days,
  };
}

function nowRelative<FIELDS extends string>(
  op: NowFilter['op'],
  field: FIELDS,
  offset: string,
  options: RelativeTimeFilterOptions,
): NowFilter<FIELDS> {
  requireDuration(op, offset);
  return {
    ...validateRelativeTimeOptions(options),
    op,
    field: queryField(field),
    offset,
  };
}

/** The filters on a time field: calendar periods, days relative to today, and instants relative to the server's now. */
export const timeFilters = {
  /**
   * Matches times within today, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'today', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.today('state.createTime', { zoneId: 'Asia/Shanghai' });
   * ```
   */
  today<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.TODAY, field, options);
  },
  /**
   * Matches times earlier than today at the local time `time`
   * (`field < today at time`) in the configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param time - 24-hour local time: `HH:mm`, `HH:mm:ss` or `HH:mm:ss.S` with
   *   1 to 9 fraction digits.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'before-today', field, time, timeUnit }`, plus `zoneId`
   *   and `datePattern` when set.
   * @throws TypeError If `time` is not a string in one of the formats above,
   *   `field` is not a valid query field path, `zoneId` is blank or an invalid UTC offset,
   *   `datePattern` is blank or not a valid `java.time` pattern, or
   *   `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.beforeToday('state.createTime', '12:00');
   * ```
   */
  beforeToday<FIELDS extends string>(
    field: FIELDS,
    time: string,
    options: RelativeTimeFilterOptions = {},
  ): BeforeTodayFilter<FIELDS> {
    const localTime = requireLocalTime(time);
    return {
      ...validateRelativeTimeOptions(options),
      op: FilterOperator.BEFORE_TODAY,
      field: queryField(field),
      time: localTime,
    };
  },
  /**
   * Matches times within tomorrow, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'tomorrow', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.tomorrow('state.createTime');
   * ```
   */
  tomorrow<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.TOMORROW, field, options);
  },
  /**
   * Matches times within the current Monday-start week, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'this-week', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.thisWeek('state.createTime');
   * ```
   */
  thisWeek<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.THIS_WEEK, field, options);
  },
  /**
   * Matches times within the next Monday-start week, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'next-week', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.nextWeek('state.createTime');
   * ```
   */
  nextWeek<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.NEXT_WEEK, field, options);
  },
  /**
   * Matches times within the previous Monday-start week, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'last-week', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.lastWeek('state.createTime');
   * ```
   */
  lastWeek<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.LAST_WEEK, field, options);
  },
  /**
   * Matches times within the current calendar month, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'this-month', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.thisMonth('state.createTime');
   * ```
   */
  thisMonth<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.THIS_MONTH, field, options);
  },
  /**
   * Matches times within the previous calendar month, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'last-month', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.lastMonth('state.createTime');
   * ```
   */
  lastMonth<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.LAST_MONTH, field, options);
  },
  /**
   * Matches times within yesterday, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'yesterday', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.yesterday('state.createTime');
   * ```
   */
  yesterday<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.YESTERDAY, field, options);
  },
  /**
   * Matches times within the next calendar month, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'next-month', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.nextMonth('state.createTime');
   * ```
   */
  nextMonth<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.NEXT_MONTH, field, options);
  },
  /**
   * Matches times within the previous calendar year, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'last-year', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.lastYear('state.createTime');
   * ```
   */
  lastYear<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.LAST_YEAR, field, options);
  },
  /**
   * Matches times within the current calendar year, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'this-year', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.thisYear('state.createTime', { timeUnit: TimeUnit.SECONDS });
   * ```
   */
  thisYear<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.THIS_YEAR, field, options);
  },
  /**
   * Matches times within the next calendar year, as the half-open range `[start, end)` in the
   * configured time zone.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'next-year', field, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `field` is not a valid query field path, `zoneId` is blank or an
   *   invalid UTC offset, `datePattern` is blank or not a valid
   *   `java.time` pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.nextYear('state.createTime');
   * ```
   */
  nextYear<FIELDS extends string>(
    field: FIELDS,
    options: RelativeTimeFilterOptions = {},
  ): CalendarFilter<FIELDS> {
    return calendar(FilterOperator.NEXT_YEAR, field, options);
  },
  /**
   * Matches times from the start of the day `days - 1` days ago until the end
   * of today. `recentDays(field, 7)` covers today and the six previous days.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param days - Number of calendar days, including today; an integer from
   *   1 to 2147483647.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'recent-days', field, days, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `days` is not an integer from 1 to 2147483647,
   *   `field` is not a valid query field path, `zoneId` is blank or an invalid UTC offset,
   *   `datePattern` is blank or not a valid `java.time` pattern, or
   *   `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.recentDays('state.createTime', 7);
   * ```
   */
  recentDays<FIELDS extends string>(
    field: FIELDS,
    days: number,
    options: RelativeTimeFilterOptions = {},
  ): DaysFilter<FIELDS> {
    return dayWindow(FilterOperator.RECENT_DAYS, field, days, options);
  },
  /**
   * Matches times before the start of the day `days - 1` days ago, that is,
   * before the window that `recentDays` covers with the same `days`.
   *
   * @param field - Query field path, e.g. `state.status`.
   * @param days - Number of calendar days, including today; an integer from
   *   1 to 2147483647.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'earlier-days', field, days, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `days` is not an integer from 1 to 2147483647,
   *   `field` is not a valid query field path, `zoneId` is blank or an invalid UTC offset,
   *   `datePattern` is blank or not a valid `java.time` pattern, or
   *   `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * filter.earlierDays('state.createTime', 7);
   * ```
   */
  earlierDays<FIELDS extends string>(
    field: FIELDS,
    days: number,
    options: RelativeTimeFilterOptions = {},
  ): DaysFilter<FIELDS> {
    return dayWindow(FilterOperator.EARLIER_DAYS, field, days, options);
  },
  /**
   * Matches times strictly before the server's `now + offset`
   * (`field < now + offset`). The server reads its clock once per query,
   * so a saved query never depends on the client's clock, and the moment is
   * encoded the way the field stores time: an epoch in `timeUnit`, or text
   * in `datePattern` and `zoneId`.
   *
   * Needs a Wow server of 9.2.0 or later; an earlier one refuses `BEFORE_NOW`.
   *
   * @param field - Query field path, e.g. `state.timeoutAt`.
   * @param offset - ISO-8601 duration added to now, as
   *   `java.time.Duration.parse` reads it: days, hours, minutes and seconds,
   *   each optionally signed, e.g. `PT0S`, `-PT30M` or `P1DT2H`. A negative
   *   offset looks back. Defaults to `PT0S`, now itself.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'before-now', field, offset, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `offset` is not an ISO-8601 duration in that
   *   grammar, `field` is not a valid query field path, `zoneId` is blank or
   *   an invalid UTC offset, `datePattern` is blank or not a valid `java.time`
   *   pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * // Timed out: the deadline has passed.
   * filter.beforeNow('state.timeoutAt');
   * ```
   */
  beforeNow<FIELDS extends string>(
    field: FIELDS,
    offset = 'PT0S',
    options: RelativeTimeFilterOptions = {},
  ): NowFilter<FIELDS> {
    return nowRelative(FilterOperator.BEFORE_NOW, field, offset, options);
  },
  /**
   * Matches times strictly after the server's `now + offset`
   * (`field > now + offset`). The server reads its clock once per query,
   * so a saved query never depends on the client's clock, and the moment is
   * encoded the way the field stores time: an epoch in `timeUnit`, or text
   * in `datePattern` and `zoneId`.
   *
   * Needs a Wow server of 9.2.0 or later; an earlier one refuses `AFTER_NOW`.
   *
   * @param field - Query field path, e.g. `state.timeoutAt`.
   * @param offset - ISO-8601 duration added to now, as
   *   `java.time.Duration.parse` reads it: days, hours, minutes and seconds,
   *   each optionally signed, e.g. `PT0S`, `-PT30M` or `P1DT2H`. A negative
   *   offset looks back. Defaults to `PT0S`, now itself.
   * @param options - Optional `zoneId`, `datePattern` and `timeUnit`; see
   *   {@link RelativeTimeFilterOptions}. `timeUnit` defaults to
   *   `TimeUnit.MILLISECONDS`.
   * @returns `{ op: 'after-now', field, offset, timeUnit }`, plus `zoneId` and
   *   `datePattern` when set.
   * @throws TypeError If `offset` is not an ISO-8601 duration in that
   *   grammar, `field` is not a valid query field path, `zoneId` is blank or
   *   an invalid UTC offset, `datePattern` is blank or not a valid `java.time`
   *   pattern, or `timeUnit` is not a {@link TimeUnit} member.
   * @example
   * ```typescript
   * // Created in the last 30 minutes.
   * filter.afterNow('state.createTime', '-PT30M');
   * ```
   */
  afterNow<FIELDS extends string>(
    field: FIELDS,
    offset = 'PT0S',
    options: RelativeTimeFilterOptions = {},
  ): NowFilter<FIELDS> {
    return nowRelative(FilterOperator.AFTER_NOW, field, offset, options);
  },
};
