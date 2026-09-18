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

import { useState } from 'react';
import { CalendarIcon } from 'lucide-react';
import type { FieldOption, FilterValue } from '../model/index.js';
import {
  DATE_TIME_PRESETS,
  RELATIVE_DATE_UNITS,
  type RelativeDateDirection,
  writeValue,
  type AbsoluteDateTimeValue,
  type DateTimeFilterValue,
  type DateTimePreset,
  type EditorDescriptor,
  type RelativeDateUnit,
} from '../filter/index.js';
import { Button } from './components/button.js';
import { Calendar } from './components/calendar.js';
import { Input } from './components/input.js';
import { Popover, PopoverTrigger } from './components/popover.js';
import {
  Select,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './components/select.js';
import { PopoverContent, SelectContent } from './popups.js';
import { useViewMessages } from './MessagesProvider.js';

export interface FilterValueEditorProps {
  /** What the field's kind says this operator needs. */
  editor: EditorDescriptor;
  value: FilterValue;
  onChange(value: FilterValue): void;
  label: string;
  disabled?: boolean;
  /** Candidates for a `remote` editor; typed entry without one. */
  options?: FieldOption[];
}

/**
 * One value control, chosen by the descriptor the field kind produced.
 *
 * The kind decides the shape of a value and the editor it implies; this
 * component only renders that decision, which is why a custom kind needs no
 * change here beyond a renderer for a shape it invents.
 */
export function FilterValueEditor({
  editor,
  value,
  onChange,
  label,
  disabled,
  options,
}: FilterValueEditorProps) {
  switch (editor.input) {
    case 'none':
      return null;

    case 'number':
      return (
        <NumberValue
          value={value}
          onChange={onChange}
          label={label}
          disabled={disabled}
          range={editor.range === true}
          multiple={editor.multiple === true}
        />
      );

    case 'boolean':
      return (
        <BooleanValue
          value={value}
          onChange={onChange}
          label={label}
          disabled={disabled}
        />
      );

    case 'select':
      return (
        <OptionValue
          label={label}
          disabled={disabled}
          value={value}
          multiple={editor.multiple === true}
          options={editor.options ?? []}
          onChange={onChange}
        />
      );

    case 'remote':
      return options ? (
        <OptionValue
          label={label}
          disabled={disabled}
          value={value}
          multiple={editor.multiple === true}
          options={options}
          onChange={onChange}
        />
      ) : (
        <TextValue
          value={value}
          onChange={onChange}
          label={label}
          disabled={disabled}
          multiple={editor.multiple === true}
        />
      );

    case 'date':
    case 'dateRange':
    case 'relativeDate':
      return (
        <DateValue
          value={value}
          onChange={onChange}
          label={label}
          disabled={disabled}
          range={editor.input === 'dateRange'}
          withTime={editor.withTime === true}
        />
      );

    default:
      return (
        <TextValue
          value={value}
          onChange={onChange}
          label={label}
          disabled={disabled}
          multiple={editor.multiple === true}
        />
      );
  }
}

interface ValueProps {
  value: FilterValue;
  onChange(value: FilterValue): void;
  label: string;
  disabled?: boolean;
}

function TextValue({
  value,
  onChange,
  label,
  disabled,
  multiple,
}: ValueProps & { multiple: boolean }) {
  const messages = useViewMessages();
  // A list is parsed on the way out, but the raw text stays on screen while
  // it is typed: re-deriving it from the parsed list would eat the comma
  // separating the values, and a second value could never be entered. The
  // draft lives only while the value in force is the very list it produced —
  // the reference a host feeds back. A replacement, equal or not, wins.
  const [draft, setDraft] = useState<{
    text: string;
    parsed: FilterValue;
  } | null>(null);
  const text =
    draft !== null && draft.parsed === value
      ? draft.text
      : Array.isArray(value)
        ? value.map(scalarText).join(', ')
        : scalarText(value);

  return (
    <Input
      aria-label={label}
      disabled={disabled}
      value={text}
      placeholder={
        multiple ? messages.label('label.filter.comma-separated') : undefined
      }
      onChange={event => {
        const raw = event.target.value;
        if (!multiple) {
          onChange(raw);
          return;
        }
        const parsed = raw
          .split(',')
          .map(part => part.trim())
          .filter(part => part.length > 0);
        setDraft({ text: raw, parsed });
        onChange(parsed);
      }}
    />
  );
}

/**
 * A number field that lets a half-typed number stay half typed.
 *
 * Parsing every keystroke straight into the condition is what made an emptied
 * field read as "equals 0" — `Number('')` — and a half-typed one as `NaN`,
 * which no kind admits. The raw text stays here until it parses; an emptied
 * field is reported as `null`, which is what a kind calls "not asked yet".
 */
export function NumberInput({
  value,
  onNumber,
  label,
  disabled,
  className,
}: {
  /** The number in force, if there is one. */
  value: unknown;
  /** Called only when the text parses; `null` once the field is empty. */
  onNumber(value: number | null): void;
  label: string;
  disabled?: boolean;
  className?: string;
}) {
  // The draft stands while the value in force is either the one it produced
  // or the one it was written over. The second case is a caller that refused
  // it — a row limit has no blank — and the field still has to clear.
  const [draft, setDraft] = useState<{
    text: string;
    was: unknown;
    becomes: unknown;
  } | null>(null);
  const text =
    draft !== null &&
    (Object.is(value, draft.becomes) || Object.is(value, draft.was))
      ? draft.text
      : numberText(value);

  return (
    <Input
      type="number"
      aria-label={label}
      className={className}
      disabled={disabled}
      value={text}
      onChange={event => {
        const typed = event.target.value;
        const parsed = parseNumber(typed);
        setDraft({
          text: typed,
          was: value,
          becomes: parsed === undefined ? value : parsed,
        });
        if (parsed !== undefined) onNumber(parsed);
      }}
    />
  );
}

/** `undefined` while the text is not a number yet, `null` once it is empty. */
function parseNumber(text: string): number | null | undefined {
  if (text.trim().length === 0) return null;
  const parsed = Number(text);
  return Number.isFinite(parsed) ? parsed : undefined;
}

/** One end of a range, as a number or as nothing. */
function numberOrBlank(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function NumberValue({
  value,
  onChange,
  label,
  disabled,
  range,
  multiple,
}: ValueProps & { range: boolean; multiple: boolean }) {
  const messages = useViewMessages();
  if (range || multiple) {
    const parts = Array.isArray(value) ? value : [];
    const ends = [numberOrBlank(parts[0]), numberOrBlank(parts[1])];
    return (
      <div className="flex items-center gap-2">
        {[0, 1].map(index => (
          <NumberInput
            key={index}
            label={messages.label(
              index === 0 ? 'label.filter.range-from' : 'label.filter.range-to',
              { field: label },
            )}
            disabled={disabled}
            value={ends[index]}
            onNumber={next => {
              const written = index === 0 ? [next, ends[1]] : [ends[0], next];
              // Two empty ends are the kind's blank value, not a range
              // between nothing and nothing.
              onChange(
                written.every(end => end === null)
                  ? multiple
                    ? []
                    : null
                  : written,
              );
            }}
          />
        ))}
      </div>
    );
  }

  return (
    <NumberInput
      label={label}
      disabled={disabled}
      value={value}
      onNumber={onChange}
    />
  );
}

/** Only scalars are shown as text; an object value has its own editor. */
function scalarText(value: unknown): string {
  switch (typeof value) {
    case 'string':
      return value;
    case 'number':
    case 'boolean':
    case 'bigint':
      return value.toString();
    default:
      return '';
  }
}

function numberText(value: unknown): string {
  return typeof value === 'number' && Number.isFinite(value)
    ? String(value)
    : '';
}

/**
 * Yes or no, and neither.
 *
 * A blank leaf used to show "False", so a row the user had only just added
 * read as a condition already narrowing the list — and picking False, which
 * is what it looked like they had, changed nothing and fired no event.
 */
function BooleanValue({ value, onChange, label, disabled }: ValueProps) {
  const messages = useViewMessages();
  return (
    <ChoiceValue
      label={label}
      disabled={disabled}
      value={typeof value === 'boolean' ? String(value) : null}
      placeholder={messages.label('label.filter.choose')}
      items={[
        { label: messages.label('label.boolean.true'), value: 'true' },
        { label: messages.label('label.boolean.false'), value: 'false' },
      ]}
      onChange={next => onChange(next === 'true')}
    />
  );
}

function ChoiceValue({
  label,
  disabled,
  value,
  items,
  placeholder,
  onChange,
}: {
  label: string;
  disabled?: boolean;
  /** `null` shows the placeholder: nothing has been chosen yet. */
  value: string | null;
  items: { label: string; value: string }[];
  placeholder?: string;
  onChange(value: string): void;
}) {
  return (
    <Select
      items={items}
      value={value}
      disabled={disabled}
      onValueChange={next => onChange(String(next))}
    >
      <SelectTrigger aria-label={label} size="sm">
        <SelectValue placeholder={placeholder} />
      </SelectTrigger>
      <SelectContent>
        <SelectGroup>
          {items.map(item => (
            <SelectItem key={item.value} value={item.value}>
              {item.label}
            </SelectItem>
          ))}
        </SelectGroup>
      </SelectContent>
    </Select>
  );
}

function OptionValue({
  label,
  disabled,
  value,
  multiple,
  options,
  onChange,
}: ValueProps & { multiple: boolean; options: readonly FieldOption[] }) {
  const items = options.map(option => ({
    label: option.label,
    value: String(option.value),
  }));
  const byText = new Map(options.map(option => [String(option.value), option]));
  const selected = (Array.isArray(value) ? value : [value])
    .map(scalarText)
    .filter(entry => entry.length > 0);

  return (
    <Select
      items={items}
      multiple={multiple}
      disabled={disabled}
      value={multiple ? selected : (selected[0] ?? null)}
      onValueChange={next => {
        const chosen = (Array.isArray(next) ? next : [next])
          .filter((entry): entry is string => typeof entry === 'string')
          .map(entry => byText.get(entry)?.value ?? entry);
        onChange(multiple ? chosen : (chosen[0] ?? null));
      }}
    >
      <SelectTrigger aria-label={label} size="sm" className="min-w-40">
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        <SelectGroup>
          {items.map(item => (
            <SelectItem key={item.value} value={item.value}>
              {item.label}
            </SelectItem>
          ))}
        </SelectGroup>
      </SelectContent>
    </Select>
  );
}

type DateShape = DateTimeFilterValue['type'];

/** `relative` no longer means backwards, so the shape no longer says it. */
const DATE_SHAPES: readonly DateShape[] = ['absolute', 'relative', 'preset'];

/**
 * Which side of now a relative window lies on. It reads as the sentence the
 * row makes — "in the last 7 days", "in the next 7 days" — so the direction
 * carries the wording and the shape above stays neutral.
 */
const DATE_DIRECTIONS: readonly RelativeDateDirection[] = ['past', 'future'];

function DateValue({
  value,
  onChange,
  label,
  disabled,
  range,
  withTime,
}: ValueProps & { range: boolean; withTime: boolean }) {
  const messages = useViewMessages();
  const stored = readDateValue(value);
  // Which shape a blank row is in. Emptying the amount of "in the last 7
  // days" blanks the leaf, and without this the editor would jump back to
  // the calendar under the user's hands.
  const [shape, setShape] = useState<DateShape>(stored?.type ?? 'absolute');
  if (stored !== null && stored.type !== shape) setShape(stored.type);
  const current = stored ?? emptyDateValue(shape, withTime);

  return (
    <div className="flex flex-wrap items-center gap-2">
      <ChoiceValue
        label={messages.label('label.date.shape-of', { field: label })}
        disabled={disabled}
        value={current.type}
        items={DATE_SHAPES.map(shape => ({
          label: messages.label(`label.date.${shape}`),
          value: shape,
        }))}
        onChange={next =>
          onChange(writeValue(emptyDateValue(next as DateShape, withTime)))
        }
      />
      {current.type === 'absolute' && (
        <AbsoluteDate
          value={current}
          onChange={onChange}
          label={label}
          disabled={disabled}
          range={range}
          withTime={withTime}
        />
      )}
      {current.type === 'relative' && (
        <div className="flex items-center gap-2">
          <ChoiceValue
            label={messages.label('label.date.direction-of', {
              field: label,
            })}
            disabled={disabled}
            value={current.direction ?? 'past'}
            items={DATE_DIRECTIONS.map(direction => ({
              label: messages.label(`label.date.${direction}`),
              value: direction,
            }))}
            onChange={next =>
              onChange(
                writeValue({
                  ...current,
                  direction: next as RelativeDateDirection,
                }),
              )
            }
          />
          <NumberInput
            label={messages.label('label.date.amount-of', { field: label })}
            disabled={disabled}
            className="w-20"
            value={current.amount}
            onNumber={next =>
              // An emptied amount is a row still being written, not a window
              // of zero days: the leaf goes blank and compiles to nothing.
              onChange(
                next === null ? null : writeValue({ ...current, amount: next }),
              )
            }
          />
          <ChoiceValue
            label={messages.label('label.date.unit-of', { field: label })}
            disabled={disabled}
            value={current.unit}
            items={RELATIVE_DATE_UNITS.map(unit => ({
              label: unit,
              value: unit,
            }))}
            onChange={next =>
              onChange(
                writeValue({ ...current, unit: next as RelativeDateUnit }),
              )
            }
          />
        </div>
      )}
      {current.type === 'preset' && (
        <ChoiceValue
          label={messages.label('label.date.period-of', { field: label })}
          disabled={disabled}
          value={current.preset}
          items={DATE_TIME_PRESETS.map(preset => ({
            label: preset,
            value: preset,
          }))}
          onChange={next =>
            onChange(
              writeValue({ type: 'preset', preset: next as DateTimePreset }),
            )
          }
        />
      )}
    </div>
  );
}

function AbsoluteDate({
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
                writeValue({
                  ...value,
                  from: selected?.from
                    ? storeDate(selected.from, withTime)
                    : value.from,
                  to: selected?.to && storeDate(selected.to, withTime),
                }),
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
                writeValue({
                  ...value,
                  from: selected ? storeDate(selected, withTime) : value.from,
                }),
              )
            }
          />
        )}
      </PopoverContent>
    </Popover>
  );
}

/** The value as one of the three date shapes, or `null` while it is blank. */
function readDateValue(value: FilterValue): DateTimeFilterValue | null {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const shape = (value as { type?: unknown }).type;
    if (shape === 'absolute' || shape === 'relative' || shape === 'preset')
      return value as unknown as DateTimeFilterValue;
  }
  return null;
}

function emptyDateValue(
  shape: DateShape,
  withTime: boolean,
): DateTimeFilterValue {
  if (shape === 'relative') return { type: 'relative', amount: 7, unit: 'day' };
  if (shape === 'preset') return { type: 'preset', preset: 'today' };
  // The UI may read the clock; the kernel never does.
  return { type: 'absolute', from: storeDate(new Date(), withTime) };
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
