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

import { CalendarIcon } from 'lucide-react';
import type { FilterValue } from '../../../model/index.js';
import {
  writeValue,
  type AbsoluteDateTimeValue,
} from '../../../filter/index.js';
import { Button } from '../../components/button.js';
import { Calendar } from '../../components/calendar.js';
import { Popover, PopoverTrigger } from '../../components/popover.js';
import { PopoverContent } from '../../popups.js';
import { useViewMessages } from '../../MessagesProvider.js';

/**
 * A day, or the two ends of a span of them, off a calendar.
 *
 * One component for both because a range is the same calendar with a second
 * bound: `dateRange` picks two, `date` picks one, and the trigger reads back
 * whichever it has.
 */
export function AbsoluteDate({
  value,
  onChange,
  label,
  disabled,
  range,
  withTime,
}: {
  value: AbsoluteDateTimeValue;
  onChange(value: FilterValue): void;
  label: string;
  disabled?: boolean;
  range: boolean;
  withTime: boolean;
}) {
  const messages = useViewMessages();
  const blank = messages.label('label.date.pick');
  const from = parseDate(value.from);
  const to = parseDate(value.to);

  return (
    <Popover>
      <PopoverTrigger
        render={<Button variant="outline" size="sm" disabled={disabled} />}
        aria-label={label}
      >
        <CalendarIcon data-icon="inline-start" />
        {formatDate(from, withTime, blank)}
        {range ? ` – ${formatDate(to, withTime, blank)}` : ''}
      </PopoverTrigger>
      <PopoverContent className="w-auto p-0">
        {range ? (
          <Calendar
            mode="range"
            autoFocus
            selected={{ from, to }}
            onSelect={selected =>
              onChange(
                // A calendar with nothing left on it is a blank leaf, not a
                // condition missing its lower bound: `from` is required, and
                // keeping the old one would leave the pill asking something
                // the user has just taken back.
                selected?.from
                  ? writeValue({
                      ...value,
                      from: storeDate(selected.from, withTime),
                      to: selected.to && storeDate(selected.to, withTime),
                    })
                  : null,
              )
            }
          />
        ) : (
          <Calendar
            mode="single"
            autoFocus
            selected={from}
            onSelect={selected =>
              onChange(
                selected
                  ? writeValue({
                      ...value,
                      from: storeDate(selected, withTime),
                    })
                  : null,
              )
            }
          />
        )}
      </PopoverContent>
    </Popover>
  );
}

/** A calendar day as stored: `2026-01-31`, no time of day, no offset. */
const DATE_ONLY = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * A stored bound, as a `Date` for the calendar. A date-only string is the
 * local day it names, read field by field: `new Date('2026-01-31')` reads it
 * as UTC midnight, which west of Greenwich is the evening of the 30th.
 */
function parseDate(text?: string): Date | undefined {
  if (!text) return undefined;
  const day = DATE_ONLY.exec(text);
  const parsed = day
    ? new Date(Number(day[1]), Number(day[2]) - 1, Number(day[3]))
    : new Date(text);
  return Number.isNaN(parsed.getTime()) ? undefined : parsed;
}

/**
 * A calendar pick, as stored. With a time of day it is a moment and keeps
 * its instant; without one it is the day the user pointed at, stored as
 * `YYYY-MM-DD` from the local calendar fields so the kernel resolves it in
 * the runtime's or the condition's zone. `toISOString()` would pin local
 * midnight to UTC with a `Z`, which the kernel rightly takes as one fixed
 * moment — no zone ever applied, and a range's last day fell off the end.
 */
function storeDate(date: Date, withTime: boolean): string {
  if (withTime) return date.toISOString();
  const pad = (part: number) => String(part).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function formatDate(
  date: Date | undefined,
  withTime: boolean,
  blank: string,
): string {
  if (!date) return blank;
  return withTime ? date.toLocaleString() : date.toLocaleDateString();
}
