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
import { useCallback, useId, useLayoutEffect, useMemo, useRef } from 'react';
import type { DateRange } from 'react-day-picker';
import { cn } from 'cn';
import type { FilterValue } from '../../../model/index.js';
import {
  writeValue,
  type AbsoluteDateTimeValue,
} from '../../../filter/index.js';
import { Button } from '../../components/button.js';
import { SurfaceCalendar } from './calendar.js';
import {
  Field,
  FieldDescription,
  FieldGroup,
  FieldLabel,
} from '../../components/field.js';
import { Input } from '../../components/input.js';
import { Popover, PopoverTrigger } from '../../components/popover.js';
import { PopoverContent } from '../../popups.js';
import { SPACE } from '../../layout.js';
import { useViewMessages } from '../../MessagesProvider.js';
import { displayValue, type DisplayContext } from '../../display.js';
import { useSurfaceDisplay } from '../../ViewSurface.js';

/**
 * A day, or the two ends of a span of them, off a calendar — with the time
 * of day beside it where the field carries one.
 *
 * One component for both because a range is the same calendar with a second
 * bound: `dateRange` picks two, `date` picks one, and the trigger reads back
 * whichever it has. The time of day belongs to the same control rather than
 * to a second one: the calendar and the clock are two halves of one bound,
 * and either of them writes the whole value once (D17-1).
 */
export function AbsoluteDate({
  value,
  onChange,
  label,
  disabled,
  invalid,
  range,
  withTime,
}: {
  value: AbsoluteDateTimeValue;
  onChange(value: FilterValue): void;
  label: string;
  disabled?: boolean;
  /** Whether the condition this date belongs to has been refused. */
  invalid?: boolean;
  range: boolean;
  withTime: boolean;
}) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  const blank = messages.label('label.date.pick');
  const from = readBound(value.from, withTime);
  const to = readBound(value.to, withTime);

  /**
   * One submission for the whole control: whichever half moved, the leaf is
   * rewritten from both. A bound with no day is no bound — a calendar with
   * nothing left on it blanks the leaf rather than leaving the pill asking
   * something the user has just taken back.
   */
  const put = (next: { from?: DayTime; to?: DayTime }) => {
    const start = next.from ?? from;
    const end = next.to ?? to;
    if (start.day === '') {
      onChange(null);
      return;
    }
    onChange(
      writeValue(
        range
          ? {
              ...value,
              from: storeBound(start),
              to: end.day === '' ? undefined : storeBound(end),
            }
          : { ...value, from: storeBound(start) },
      ),
    );
  };

  // The registry's calendar draws its parts as components made anew on each
  // of its renders, so every render of it is a fresh calendar: the day under
  // the pointer and the focus inside the grid gone. A board renders often —
  // its panels answering, a refresh — so the calendar is held to its own
  // inputs: the days picked, and a pick that reads the latest bounds.
  const pickRange = (selected: DateRange | undefined) =>
    put({
      from: { ...from, day: dayOf(selected?.from) },
      to: { ...to, day: dayOf(selected?.to) },
    });
  const pickDay = (selected: Date | undefined) =>
    put({ from: { ...from, day: dayOf(selected) } });
  const picks = useRef({ range: pickRange, day: pickDay });
  useLayoutEffect(() => {
    picks.current = { range: pickRange, day: pickDay };
  });
  const onRange = useCallback(
    (selected: DateRange | undefined) => picks.current.range(selected),
    [],
  );
  const onDay = useCallback(
    (selected: Date | undefined) => picks.current.day(selected),
    [],
  );
  const days = useMemo(
    () => ({ from: parseDay(from.day), to: parseDay(to.day) }),
    [from.day, to.day],
  );
  const day = useMemo(() => parseDay(from.day), [from.day]);

  return (
    <Popover>
      <PopoverTrigger
        render={<Button variant="outline" size="sm" disabled={disabled} />}
        aria-label={label}
        aria-invalid={invalid}
      >
        <CalendarIcon data-icon="inline-start" />
        {formatDate(value.from, withTime, blank, display)}
        {range ? ` – ${formatDate(value.to, withTime, blank, display)}` : ''}
      </PopoverTrigger>
      {/* Base UI gives a popover `role="dialog"`, and a dialog with no name
          is one axe reports and a screen reader announces as nothing at all.
          The control's own name is the right one: the calendar and the clock
          in here are two halves of that one value. */}
      {/* Sized by what is in it, with a floor. `w-auto` alone gave the
          popover the calendar's own 212px — narrower than the 235px trigger
          that opened it — and the clock under it was laid out in what a grid
          of day numbers happened to leave: 「起始时刻」 wrapped mid-word in
          48px and the hint ran to three lines. The floor is the calendar's
          own width (7 cells plus its padding), so nothing below it is sized
          by the grid above it. */}
      <PopoverContent className="w-auto min-w-[17rem] p-0" aria-label={label}>
        {range ? (
          <SurfaceCalendar
            mode="range"
            autoFocus
            selected={days}
            onSelect={onRange}
          />
        ) : (
          <SurfaceCalendar
            mode="single"
            autoFocus
            selected={day}
            onSelect={onDay}
          />
        )}
        {withTime && (
          <TimeOfDay
            range={range}
            disabled={disabled}
            from={from}
            to={to}
            onFrom={time => put({ from: { ...from, time } })}
            onTo={time => put({ to: { ...to, time } })}
          />
        )}
      </PopoverContent>
    </Popover>
  );
}

/**
 * The clock half of the control: one box for a single bound, one per end for
 * a range.
 *
 * A native time input is what the registry's own calendar-and-time pattern
 * uses, and it is the only control that is a clock in every language and on
 * every keyboard — hours, minutes and seconds as separate spin fields, all
 * of them reachable with the arrow keys. `step="1"` is what puts the seconds
 * field there; they are optional in the value, and a time given without them
 * is stored on the whole second.
 *
 * A box is disabled while its own end has no day: a time of day is not a
 * moment until something says which day it is on, and seeding the day from
 * the clock is exactly what a blank date condition must not do.
 */
function TimeOfDay({
  range,
  disabled,
  from,
  to,
  onFrom,
  onTo,
}: {
  range: boolean;
  disabled?: boolean;
  from: DayTime;
  to: DayTime;
  onFrom(time: string): void;
  onTo(time: string): void;
}) {
  const messages = useViewMessages();
  const id = useId();

  // Label above box, which is `Field`'s own default and what the registry's
  // forms are. Side by side, the two of them shared the popover's width with
  // the box: 「起始时刻」 was given 48px and broke across two lines in the
  // middle of a word, and the same 48px would not have held `From time`
  // either — a name that has to be read twice to be read at all.
  return (
    <FieldGroup className={cn('border-t p-3', SPACE.ROWS)}>
      <Field>
        <FieldLabel htmlFor={`${id}-from`}>
          {messages.label(range ? 'label.date.time-from' : 'label.date.time')}
        </FieldLabel>
        <Input
          id={`${id}-from`}
          type="time"
          step="1"
          className="w-full"
          disabled={disabled || from.day === ''}
          value={from.time}
          onChange={event => onFrom(readTime(event.target.value))}
        />
      </Field>
      {range && (
        <Field>
          <FieldLabel htmlFor={`${id}-to`}>
            {messages.label('label.date.time-to')}
          </FieldLabel>
          <Input
            id={`${id}-to`}
            type="time"
            step="1"
            className="w-full"
            disabled={disabled || to.day === ''}
            value={to.time}
            onChange={event => onTo(readTime(event.target.value))}
          />
        </Field>
      )}
      {/* What an empty box means is the whole point of the control, and it
          is not guessable: left empty a bound is the day itself, read as an
          interval — its first millisecond on the way in, its last on the way
          out. */}
      <FieldDescription>
        {messages.label('label.date.time-hint')}
      </FieldDescription>
    </FieldGroup>
  );
}

/**
 * One bound as the two controls hold it: the calendar day, and the time of
 * day when the bound names one. An empty `time` is the interval reading —
 * the whole day — and not midnight.
 */
interface DayTime {
  /** `2026-01-31`, or `''` while nothing is picked. */
  day: string;
  /** `15:30:00`, or `''` when the bound names only a day. */
  time: string;
}

/**
 * A day and a time with no offset: `2026-01-31`, `2026-01-31T15:30`,
 * `2026-01-31T15:30:00`. Its dashes sit between the date's fields, never
 * before a trailing `HH:mm`, so it is never an offset.
 */
const WALL_CLOCK = /^(\d{4}-\d{2}-\d{2})(?:[T ](\d{2}:\d{2})(?::(\d{2}))?)?$/;

/**
 * A stored bound as the controls read it.
 *
 * A wall-clock string is taken field by field: `new Date('2026-01-31')`
 * reads it as UTC midnight, which west of Greenwich is the evening of the
 * 30th. An instant that names its own offset — what this editor used to
 * store, and what a host's own config may hold — is shown on the local
 * clock, so the day and the time are the ones the user sees beside it.
 *
 * A field that carries no time of day has none whatever its stored string
 * says: there is no control for it, so keeping one would write it back
 * invisibly, and a plain date field would come out of this editor asking for
 * midnight instead of for the day.
 */
function readBound(text: string | undefined, withTime: boolean): DayTime {
  if (!text) return { day: '', time: '' };
  const wall = WALL_CLOCK.exec(text);
  const read = wall
    ? {
        day: wall[1],
        time: wall[2] === undefined ? '' : `${wall[2]}:${wall[3] ?? '00'}`,
      }
    : instantAt(text);
  return withTime ? read : { day: read.day, time: '' };
}

/** An instant that names its own offset, on the local clock. */
function instantAt(text: string): DayTime {
  const parsed = new Date(text);
  if (Number.isNaN(parsed.getTime())) return { day: '', time: '' };
  return { day: dayOf(parsed), time: timeOf(parsed) };
}

/**
 * A bound as stored: the day the user pointed at, and the time beside it
 * when one was given, both from the local calendar fields and with no
 * offset. It names a time on a clock rather than a moment, which is what
 * lets the kernel resolve it in the runtime's or the condition's zone.
 *
 * `toISOString()` pinned local midnight to UTC with a `Z`, which the kernel
 * rightly takes as one fixed moment — no zone ever applied, no interval
 * reading for an empty time, and a range's last day fell off the end.
 */
function storeBound(bound: DayTime): string {
  return bound.time === '' ? bound.day : `${bound.day}T${bound.time}`;
}

/** What the native control gives back: nothing, `HH:mm`, or `HH:mm:ss`. */
function readTime(raw: string): string {
  if (raw === '') return '';
  return raw.length === 5 ? `${raw}:00` : raw;
}

/** A stored day as the `Date` the calendar selects by. */
function parseDay(day: string): Date | undefined {
  if (day === '') return undefined;
  const [year, month, date] = day.split('-').map(Number);
  return new Date(year, month - 1, date);
}

function dayOf(date?: Date): string {
  if (!date) return '';
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

function timeOf(date: Date): string {
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function pad(part: number): string {
  return String(part).padStart(2, '0');
}

/**
 * A bound as the trigger reads it back.
 *
 * It formats the value **as stored** rather than the `Date` the calendar
 * holds, and through the surface's own formatter: `toLocaleString()` takes
 * the browser's language and the browser's clock, so the same condition read
 * one way in the picker and another in the applied bar, the pill's summary
 * and the table cell beside it — a view pinned to `Asia/Shanghai` showed a
 * London reader the London hour of the moment they had just picked in
 * Shanghai. `displayValue` is the one rule for both shapes stored here: an
 * instant reads in the surface's zone, and a bare `2026-01-31` reads as the
 * 31st on every clock there is.
 */
function formatDate(
  stored: string | undefined,
  withTime: boolean,
  blank: string,
  display: DisplayContext,
): string {
  if (!stored) return blank;
  return (
    displayValue(stored, { cell: withTime ? 'datetime' : 'date' }, display) ??
    blank
  );
}
