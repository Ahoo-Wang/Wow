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

/** One number, or the two ends of a range. */
export function NumberValue({
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
