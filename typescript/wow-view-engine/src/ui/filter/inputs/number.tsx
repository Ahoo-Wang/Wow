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
import { PlusIcon, XIcon } from 'lucide-react';
import { isFiniteNumber } from '../../../filter/index.js';
import { Badge } from '../../components/badge.js';
import { Button } from '../../components/button.js';
import { Input } from '../../components/input.js';
import { useViewMessages } from '../../MessagesProvider.js';
import type { ValueProps } from './shared.js';

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
  const messages = useViewMessages();
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
      // A condition with no value yet is a normal editing state rather than
      // a mistake, so the box says what is missing.
      placeholder={messages.label('label.filter.not-set')}
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

/** One number, the two ends of a range, or a list of as many as are wanted. */
export function NumberValue({
  value,
  onChange,
  label,
  disabled,
  range,
  multiple,
}: ValueProps & { range: boolean; multiple: boolean }) {
  // A list and a range are two different shapes and used to share one pair
  // of boxes, which capped `IN` at the two ends a range has.
  if (multiple)
    return (
      <NumberListValue
        value={value}
        onChange={onChange}
        label={label}
        disabled={disabled}
      />
    );

  if (range)
    return (
      <NumberRangeValue
        value={value}
        onChange={onChange}
        label={label}
        disabled={disabled}
      />
    );

  return (
    <NumberInput
      label={label}
      disabled={disabled}
      value={value}
      onNumber={onChange}
    />
  );
}

/** The two ends of a `BETWEEN`, either of which may still be blank. */
function NumberRangeValue({ value, onChange, label, disabled }: ValueProps) {
  const messages = useViewMessages();
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
            // Two empty ends are the kind's blank value, not a range between
            // nothing and nothing.
            onChange(written.every(end => end === null) ? null : written);
          }}
        />
      ))}
    </div>
  );
}

/**
 * The values of an `IN` / `NOT_IN`, as a list that grows.
 *
 * `filter/kinds/number.ts` has always admitted an array of any length; this
 * control is what limited it to two, by borrowing the range's pair of boxes.
 * Values are committed one at a time — Enter in the entry field, the add
 * button, or leaving the field moves what was typed into the list — and each
 * one carries its own remove button, named after the value so the buttons are
 * told apart. Leaving the field counts because Apply is somewhere else on the
 * panel: reaching for it blurs the entry first, and a number the user had just
 * typed would otherwise be dropped by the very click meant to run it.
 */
function NumberListValue({ value, onChange, label, disabled }: ValueProps) {
  const messages = useViewMessages();
  const [entry, setEntry] = useState('');
  const values = (Array.isArray(value) ? value : []).filter(isFiniteNumber);
  const typed = parseNumber(entry);

  // An empty entry, or half a number, is a normal editing state rather than
  // a mistake — adding it commits nothing and says nothing. That is why the
  // button is not disabled instead: a control that refuses without a word is
  // worse than one that has nothing to do yet.
  function add() {
    if (typeof typed !== 'number') return;
    setEntry('');
    // The same number twice asks nothing more of the query, and it would
    // give two remove buttons the very same accessible name. It is taken —
    // the entry field clears — and changes nothing.
    if (values.includes(typed)) return;
    onChange([...values, typed]);
  }

  return (
    <div className="flex min-w-0 flex-wrap items-center gap-1">
      {values.map(entryValue => (
        <Badge key={entryValue} variant="secondary" className="gap-0.5 pr-0.5">
          {entryValue}
          <Button
            variant="ghost"
            size="icon-sm"
            className="size-4"
            aria-label={messages.label('label.filter.remove-value', {
              value: String(entryValue),
            })}
            disabled={disabled}
            onClick={() => onChange(values.filter(kept => kept !== entryValue))}
          >
            <XIcon />
          </Button>
        </Badge>
      ))}
      <Input
        type="number"
        aria-label={messages.label('label.filter.new-value-of', {
          field: label,
        })}
        className="h-7 w-24"
        disabled={disabled}
        value={entry}
        placeholder={messages.label('label.filter.not-set')}
        onChange={event => setEntry(event.target.value)}
        // Apply is a button elsewhere on the panel, and pressing it blurs this
        // field first. A number typed and not yet added would be thrown away
        // by the very click that was meant to run the query with it, so
        // leaving the field commits it on exactly the terms Enter does.
        onBlur={add}
        onKeyDown={event => {
          if (event.key !== 'Enter' || event.nativeEvent.isComposing) return;
          // `FilterPanel` applies the draft on an Enter from anywhere inside
          // it, except on a control that answers Enter itself. This is one:
          // one keystroke, one meaning, so the panel never sees this press.
          event.preventDefault();
          event.stopPropagation();
          add();
        }}
      />
      <Button
        variant="ghost"
        size="icon-sm"
        aria-label={messages.label('label.filter.add-value-of', {
          field: label,
        })}
        disabled={disabled}
        onClick={add}
      >
        <PlusIcon />
      </Button>
    </div>
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

function numberText(value: unknown): string {
  return typeof value === 'number' && Number.isFinite(value)
    ? String(value)
    : '';
}
