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

import { onTestFinished, vi } from 'vitest';

/**
 * The day the calendar tests take for today: a local noon in September 2026,
 * the month their values sit in, and none of the days they pick or name
 * (16, 18, 20, 21) so a day's accessible name is never 「Today, …」.
 */
export const CALENDAR_TODAY = new Date(2026, 8, 25, 12, 0, 0);

/**
 * Pins `Date` — and only `Date`, so user-event and the popups keep their real
 * timers — to `at` for the rest of the current test.
 *
 * The picker opens on today's month (react-day-picker's default, with no
 * `defaultMonth` given): a test that looks for 「September 20」 found it only
 * while the wall clock was in September 2026, and every one of them broke on
 * 1 October. That includes the tests whose value is already in September —
 * the calendar does not open on the month of the value it holds.
 */
export function pinClock(at: Date = CALENDAR_TODAY): void {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(at);
  onTestFinished(() => {
    vi.useRealTimers();
  });
}
