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
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from './components/popover.js';
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from './components/select.js';

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
        <ChoiceValue
          label={label}
          disabled={disabled}
          value={value === true ? 'true' : 'false'}
          items={[
            { label: 'True', value: 'true' },
            { label: 'False', value: 'false' },
          ]}
          onChange={next => onChange(next === 'true')}
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
      placeholder={multiple ? 'Comma separated' : undefined}
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

function NumberValue({
  value,
  onChange,
  label,
  disabled,
  range,
  multiple,
}: ValueProps & { range: boolean; multiple: boolean }) {
  if (range || multiple) {
    const parts = Array.isArray(value) ? value : [];
    return (
      <div className="flex items-center gap-2">
        {[0, 1].map(index => (
          <Input
            key={index}
            type="number"
            aria-label={`${label} ${index === 0 ? 'from' : 'to'}`}
            disabled={disabled}
            value={numberText(parts[index])}
            onChange={event => {
              const next = [...parts];
              next[index] = Number(event.target.value);
              onChange(next);
            }}
          />
        ))}
      </div>
    );
  }

  return (
    <Input
      type="number"
      aria-label={label}
      disabled={disabled}
      value={numberText(value)}
      onChange={event => onChange(Number(event.target.value))}
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

function ChoiceValue({
  label,
  disabled,
  value,
  items,
  onChange,
}: {
  label: string;
  disabled?: boolean;
  value: string;
  items: { label: string; value: string }[];
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
const DATE_SHAPES: { label: string; value: DateShape }[] = [
  { label: 'On a date', value: 'absolute' },
  { label: 'Relative', value: 'relative' },
  { label: 'A period', value: 'preset' },
];

/**
 * Which side of now a relative window lies on. It reads as the sentence the
 * row makes — "in the last 7 days", "in the next 7 days" — so the direction
 * carries the wording and the shape above stays neutral.
 */
const DATE_DIRECTIONS: { label: string; value: RelativeDateDirection }[] = [
  { label: 'In the last', value: 'past' },
  { label: 'In the next', value: 'future' },
];

function DateValue({
  value,
  onChange,
  label,
  disabled,
  range,
  withTime,
}: ValueProps & { range: boolean; withTime: boolean }) {
  const current = asDateValue(value);

  return (
    <div className="flex flex-wrap items-center gap-2">
      <ChoiceValue
        label={`${label} kind`}
        disabled={disabled}
        value={current.type}
        items={DATE_SHAPES}
        onChange={next =>
          onChange(writeValue(emptyDateValue(next as DateShape)))
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
            label={`${label} direction`}
            disabled={disabled}
            value={current.direction ?? 'past'}
            items={DATE_DIRECTIONS}
            onChange={next =>
              onChange(
                writeValue({
                  ...current,
                  direction: next as RelativeDateDirection,
                }),
              )
            }
          />
          <Input
            type="number"
            aria-label={`${label} amount`}
            disabled={disabled}
            className="w-20"
            value={String(current.amount)}
            onChange={event =>
              onChange(
                writeValue({ ...current, amount: Number(event.target.value) }),
              )
            }
          />
          <ChoiceValue
            label={`${label} unit`}
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
          label={`${label} period`}
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
  const from = parseDate(value.from);
  const to = parseDate(value.to);

  return (
    <Popover>
      <PopoverTrigger
        render={<Button variant="outline" size="sm" disabled={disabled} />}
        aria-label={label}
      >
        <CalendarIcon data-icon="inline-start" />
        {formatDate(from, withTime)}
        {range ? ` – ${formatDate(to, withTime)}` : ''}
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
                  from: toIso(selected?.from) ?? value.from,
                  to: toIso(selected?.to),
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
                writeValue({ ...value, from: toIso(selected) ?? value.from }),
              )
            }
          />
        )}
      </PopoverContent>
    </Popover>
  );
}

function asDateValue(value: FilterValue): DateTimeFilterValue {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    const shape = (value as { type?: unknown }).type;
    if (shape === 'absolute' || shape === 'relative' || shape === 'preset')
      return value as unknown as DateTimeFilterValue;
  }
  return emptyDateValue('absolute');
}

function emptyDateValue(shape: DateShape): DateTimeFilterValue {
  if (shape === 'relative') return { type: 'relative', amount: 7, unit: 'day' };
  if (shape === 'preset') return { type: 'preset', preset: 'today' };
  return { type: 'absolute', from: new Date().toISOString() };
}

function parseDate(iso?: string): Date | undefined {
  if (!iso) return undefined;
  const parsed = new Date(iso);
  return Number.isNaN(parsed.getTime()) ? undefined : parsed;
}

function toIso(date?: Date): string | undefined {
  return date ? date.toISOString() : undefined;
}

function formatDate(date: Date | undefined, withTime: boolean): string {
  if (!date) return 'Pick a date';
  return withTime ? date.toLocaleString() : date.toLocaleDateString();
}
