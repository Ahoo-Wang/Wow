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

import type { MetricPeriod } from '../../analysis/index.js';
import type { MessageFormatters } from '../MessagesProvider.js';

const DAY_MS = 86_400_000;

/** The calendar numbers of a moment in `zone`. */
function partsOf(ms: number, zone: string) {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: zone,
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: 'numeric',
    minute: 'numeric',
    second: 'numeric',
    hourCycle: 'h23',
  }).formatToParts(new Date(ms));
  const read = (type: Intl.DateTimeFormatPartTypes) =>
    Number(parts.find(part => part.type === type)?.value ?? 0);
  return {
    year: read('year'),
    month: read('month'),
    day: read('day'),
    midnight:
      read('hour') === 0 &&
      read('minute') === 0 &&
      read('second') === 0 &&
      ms % 1000 === 0,
  };
}

/**
 * The part of a period a trend card's dates hold (`MetricPeriod.span`), as
 * the card names it over its number: one day as that day
 * (「2026年9月21日」), days of one month as 「2026年9月15日–21日」, of one
 * year as 「2026年8月25日–9月5日」, and any other two moments as both
 * written out — with the time where an end falls inside a day.
 */
export function spanName(
  span: NonNullable<MetricPeriod['span']>,
  locale: string | undefined,
  messages: MessageFormatters,
): string {
  const { from, to, zone } = span;
  const start = partsOf(from, zone);
  const end = partsOf(to, zone);
  const date = (ms: number) =>
    new Intl.DateTimeFormat(locale, {
      dateStyle: 'medium',
      timeZone: zone,
    }).format(new Date(ms));
  if (!start.midnight || !end.midnight) {
    const moment = (ms: number) =>
      new Intl.DateTimeFormat(locale, {
        dateStyle: 'medium',
        timeStyle: 'short',
        timeZone: zone,
      }).format(new Date(ms));
    return messages.label('label.chart.period.range', {
      from: moment(from),
      to: moment(to),
    });
  }
  // `to` is the first moment past the span: its last day is the one before.
  const lastDay = to - DAY_MS / 2;
  const last = partsOf(lastDay, zone);
  if (last.year !== start.year)
    return messages.label('label.chart.period.range', {
      from: date(from),
      to: date(lastDay),
    });
  if (last.month === start.month && last.day === start.day) return date(from);
  const write = (ms: number, options: Intl.DateTimeFormatOptions) =>
    new Intl.DateTimeFormat(locale, { ...options, timeZone: zone }).format(
      new Date(ms),
    );
  // The year once, and the month once where both ends share it: the
  // locale's own pieces (「9月15日」「21日」, 「September 15」「21」), put
  // in order by the catalogue.
  return messages.label('label.chart.period.range.in-year', {
    year: write(from, { year: 'numeric' }),
    start: write(from, { month: 'long', day: 'numeric' }),
    end: write(
      lastDay,
      last.month === start.month
        ? { day: 'numeric' }
        : { month: 'long', day: 'numeric' },
    ),
  });
}
