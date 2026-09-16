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

import dayjs, { type Dayjs } from 'dayjs';
import isoWeek from 'dayjs/plugin/isoWeek';
import quarterOfYear from 'dayjs/plugin/quarterOfYear';
import timezone from 'dayjs/plugin/timezone';
import utc from 'dayjs/plugin/utc';
import type { DateTimeFilterValue, RelativeDateTimeValue } from './values.js';

// Zones, week starts, quarters and month lengths are what a date library is
// for; these four plugins cover every window the presets and relative values
// need, and keep this file free of hand-written calendar arithmetic.
dayjs.extend(utc);
dayjs.extend(timezone);
dayjs.extend(isoWeek);
dayjs.extend(quarterOfYear);

/**
 * Resolving a relative or preset value happens at compile time against the
 * injected moment: the kernel never reads the system clock, and the same
 * config run tomorrow produces a different window on purpose.
 */
export interface InstantRange {
  /** Inclusive lower bound, ISO 8601. */
  from: string;
  /** Inclusive upper bound, ISO 8601; absent means open-ended. */
  to?: string;
}

/** Quarters are expressed in months, which every dayjs build understands. */
function startOfRelativeWindow(
  reference: Dayjs,
  value: RelativeDateTimeValue,
): Dayjs {
  switch (value.unit) {
    case 'hour':
      return reference.subtract(value.amount, 'hour');
    case 'day':
      return reference.subtract(value.amount, 'day');
    case 'week':
      return reference.subtract(value.amount, 'week');
    case 'month':
      return reference.subtract(value.amount, 'month');
    case 'quarter':
      return reference.subtract(value.amount * 3, 'month');
    case 'year':
      return reference.subtract(value.amount, 'year');
  }
}

function period(reference: Dayjs, value: DateTimeFilterValue): InstantRange {
  if (value.type !== 'preset') throw new Error('not a preset value');
  switch (value.preset) {
    case 'today':
      return bounds(reference.startOf('day'), reference.endOf('day'));
    case 'yesterday': {
      const day = reference.subtract(1, 'day');
      return bounds(day.startOf('day'), day.endOf('day'));
    }
    case 'thisWeek':
      // ISO weeks start on Monday, independent of the runtime locale.
      return bounds(reference.startOf('isoWeek'), reference.endOf('isoWeek'));
    case 'thisMonth':
      return bounds(reference.startOf('month'), reference.endOf('month'));
    case 'thisQuarter':
      return bounds(reference.startOf('quarter'), reference.endOf('quarter'));
    case 'thisYear':
      return bounds(reference.startOf('year'), reference.endOf('year'));
  }
}

function bounds(from: Dayjs, to: Dayjs): InstantRange {
  return { from: from.toISOString(), to: to.toISOString() };
}

export function resolveDateTimeRange(
  value: DateTimeFilterValue,
  now: Date,
  timeZone: string,
): InstantRange {
  if (value.type === 'absolute') {
    return value.to === undefined
      ? { from: value.from }
      : { from: value.from, to: value.to };
  }

  const reference = dayjs(now).tz(timeZone);
  if (value.type === 'relative') {
    return bounds(startOfRelativeWindow(reference, value), reference);
  }
  return period(reference, value);
}
