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

import { NumberField as NumberFieldPrimitive } from '@base-ui/react';
import { PlusIcon, XIcon } from 'lucide-react';
import { useEffect, useId, useRef, useState, type RefObject } from 'react';
import { cn } from 'cn';
import { Button } from '../../components/button.js';
import {
  Field,
  FieldDescription,
  FieldGroup,
  FieldLabel,
} from '../../components/field.js';
import {
  InputGroup,
  InputGroupAddon,
  InputGroupInput,
  InputGroupText,
} from '../../components/input-group.js';
import { IconButton } from '../../IconButton.js';
import { useSurfaceAnnouncer } from '../../Announcer.js';
import { SPACE } from '../../layout.js';
import { useViewMessages } from '../../MessagesProvider.js';
import {
  clampSegment,
  hourIsComplete,
  parseTime,
  writeTime,
  type TimeSegments,
} from './timeOfDay.js';

/**
 * The clock half of the calendar (用户 2026-09-25): a date is a whole day
 * until a time is asked for. Folded, it says 「按整天」 and offers
 * 「+ 指定时刻」; open, one field a bound — 「起始时刻」 and 「截止时刻」 on a
 * range — each typed as hours and minutes, each cleared back to the whole
 * day by its ×, and 「移除时刻」 clears both and folds it again.
 *
 * It used to be a native `<input type="time">` a bound, with seconds: a
 * clock nobody asked to the second, in the browser's own dark dropdown
 * rather than the surface's look, and open on every date field whether a
 * time was wanted or not. What an empty time means is the hint under it
 * either way — the day itself, read as an interval.
 */
export function TimeOfDay({
  range,
  disabled,
  from,
  to,
  onFrom,
  onTo,
}: {
  range: boolean;
  disabled?: boolean;
  /** Each bound's day (`''` while none is picked) and its time of day. */
  from: { day: string; time: string };
  to: { day: string; time: string };
  onFrom(time: string): void;
  onTo(time: string): void;
}) {
  const messages = useViewMessages();
  const announcer = useSurfaceAnnouncer('date-time-status');
  const timed = from.time !== '' || to.time !== '';
  const [open, setOpen] = useState(timed);
  // A time that arrives from elsewhere — a saved view, a brush — opens it.
  if (timed && !open) setOpen(true);
  const firstHour = useRef<HTMLInputElement>(null);
  const add = useRef<HTMLButtonElement>(null);
  // Where the keyboard goes once the section has changed shape under it:
  // the control that took the pressed one's place (U-02).
  const land = useRef<'hour' | 'add' | null>(null);
  useEffect(() => {
    const target = land.current;
    land.current = null;
    if (target !== null) (target === 'hour' ? firstHour : add).current?.focus();
  }, [open]);

  const labelFrom = messages.label(
    range ? 'label.date.time-from' : 'label.date.time',
  );
  const labelTo = messages.label('label.date.time-to');

  return (
    <FieldGroup className={cn('border-t p-3', SPACE.ROWS)}>
      {open ? (
        <>
          <TimeField
            label={labelFrom}
            value={from.time}
            disabled={disabled || from.day === ''}
            onChange={onFrom}
            onCleared={() =>
              announcer.say(
                messages.label('label.date.time-cleared', { time: labelFrom }),
              )
            }
            hourRef={firstHour}
          />
          {range && (
            <TimeField
              label={labelTo}
              value={to.time}
              disabled={disabled || to.day === ''}
              onChange={onTo}
              onCleared={() =>
                announcer.say(
                  messages.label('label.date.time-cleared', { time: labelTo }),
                )
              }
            />
          )}
          <Button
            type="button"
            variant="ghost"
            size="sm"
            data-slot="date-time-remove"
            className="self-start"
            disabled={disabled}
            onClick={() => {
              if (from.time !== '') onFrom('');
              if (range && to.time !== '') onTo('');
              setOpen(false);
              land.current = 'add';
              announcer.say(messages.label('label.date.time-removed'));
            }}
          >
            <XIcon data-icon="inline-start" />
            {messages.label('label.date.time-remove')}
          </Button>
        </>
      ) : (
        <div className="flex items-center justify-between gap-2">
          <span
            data-slot="date-time-whole-day"
            className="text-muted-foreground text-sm"
          >
            {messages.label('label.date.time-whole-day')}
          </span>
          <Button
            ref={add}
            type="button"
            variant="ghost"
            size="sm"
            data-slot="date-time-add"
            disabled={disabled || from.day === ''}
            onClick={() => {
              setOpen(true);
              land.current = 'hour';
            }}
          >
            <PlusIcon data-icon="inline-start" />
            {messages.label('label.date.time-add')}
          </Button>
        </div>
      )}
      {/* What an empty time means is the whole point of the control, and it
          is not guessable: left empty a bound is the day itself, read as an
          interval — its first moment on the way in, its last on the way
          out. */}
      <FieldDescription>
        {messages.label('label.date.time-hint')}
      </FieldDescription>
      {announcer.region}
    </FieldGroup>
  );
}

/**
 * One bound's time: an hour and a minute box, each a Base UI `NumberField`
 * (typed digits only, arrow keys step it, held inside 00–23 and 00–59,
 * shown with two digits), the keyboard moving on to the minutes once a
 * whole hour is typed, and × back to the whole day.
 *
 * The segments are kept here as typed, so emptying the hour to type a new
 * one does not take the minutes with it; the stored time follows each
 * keystroke, a blank segment beside a typed one standing at 00.
 */
function TimeField({
  label,
  value,
  disabled,
  onChange,
  onCleared,
  hourRef,
}: {
  label: string;
  value: string;
  disabled: boolean;
  onChange(time: string): void;
  onCleared(): void;
  hourRef?: RefObject<HTMLInputElement | null>;
}) {
  const messages = useViewMessages();
  const id = useId();
  const [segments, setSegments] = useState<TimeSegments>(() =>
    parseTime(value),
  );
  const [seen, setSeen] = useState(value);
  if (value !== seen) {
    setSeen(value);
    if (value !== writeTime(segments)) setSegments(parseTime(value));
  }
  const ownHour = useRef<HTMLInputElement>(null);
  const hour = hourRef ?? ownHour;
  const minute = useRef<HTMLInputElement>(null);

  // Back to the hour once a × has emptied the boxes — after they have
  // redrawn empty: a number field keeps the text it is focused on.
  const refocus = useRef(false);
  useEffect(() => {
    if (!refocus.current) return;
    refocus.current = false;
    hour.current?.focus();
  }, [segments, hour]);

  const put = (next: TimeSegments) => {
    setSegments(next);
    onChange(writeTime(next));
  };
  const set = writeTime(segments) !== '';

  return (
    <Field data-slot="date-time-field" data-disabled={disabled || undefined}>
      <FieldLabel id={`${id}-label`}>{label}</FieldLabel>
      <InputGroup
        aria-labelledby={`${id}-label`}
        data-disabled={disabled || undefined}
      >
        <Segment
          inputRef={hour}
          label={messages.label('label.date.time-hour', { time: label })}
          value={segments.hour}
          max={23}
          disabled={disabled}
          onValue={next =>
            put({ ...segments, hour: clampSegment(next, 'hour') })
          }
          onText={text => {
            if (hourIsComplete(text)) {
              minute.current?.focus();
              minute.current?.select();
            }
          }}
        />
        <InputGroupText aria-hidden="true" className="px-0">
          :
        </InputGroupText>
        <Segment
          inputRef={minute}
          label={messages.label('label.date.time-minute', { time: label })}
          value={segments.minute}
          max={59}
          disabled={disabled}
          onValue={next =>
            put({ ...segments, minute: clampSegment(next, 'minute') })
          }
        />
        {/* The × at the far end, where every other clearable box keeps it. */}
        <InputGroupAddon align="inline-end" className="ml-auto">
          {set && (
            <IconButton
              type="button"
              variant="ghost"
              size="icon-xs"
              data-slot="date-time-clear"
              disabled={disabled}
              label={messages.label('label.date.time-clear', { time: label })}
              onClick={() => {
                put({ hour: null, minute: null });
                refocus.current = true;
                onCleared();
              }}
            >
              <XIcon />
            </IconButton>
          )}
        </InputGroupAddon>
      </InputGroup>
    </Field>
  );
}

/** One two-digit segment of a time. */
function Segment({
  inputRef,
  label,
  value,
  max,
  disabled,
  onValue,
  onText,
}: {
  inputRef: RefObject<HTMLInputElement | null>;
  label: string;
  value: number | null;
  max: number;
  disabled: boolean;
  onValue(value: number | null): void;
  onText?(text: string): void;
}) {
  return (
    <NumberFieldPrimitive.Root
      value={value}
      min={0}
      max={max}
      step={1}
      format={{ minimumIntegerDigits: 2, useGrouping: false }}
      disabled={disabled}
      onValueChange={next => onValue(next)}
      className="contents"
    >
      <NumberFieldPrimitive.Input
        ref={inputRef}
        aria-label={label}
        placeholder="--"
        maxLength={2}
        // On the key's way up, once the field has taken the digit: moving
        // the focus while the input event is still being handled blurs the
        // box before the primitive has read it, and it never shows its
        // value again.
        onKeyUp={event => {
          if (/^\d$/.test(event.key)) onText?.(event.currentTarget.value);
        }}
        render={
          <InputGroupInput className="w-8 flex-none px-0 text-center tabular-nums" />
        }
      />
    </NumberFieldPrimitive.Root>
  );
}
