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
import { isFiniteNumber } from '../../../filter/index.js';
import { useViewMessages } from '../../MessagesProvider.js';
import { useSurfaceDisplay } from '../../ViewSurface.js';
import { PillInput, type ControlChromeProps } from '../../variants.js';
import { ValueChips } from './chips.js';
import { RangeRow, type ValueProps } from './shared.js';

/**
 * A number field, on Base UI's `NumberField`.
 *
 * It used to be `<Input type="number">` with a draft record of its own, so
 * that a half-typed number could stay half typed and an emptied field could
 * say `null` rather than `Number('') === 0`. That is exactly the primitive's
 * problem, solved: it keeps the text and the number apart, refuses characters
 * that could never be part of a number, reports the parsed number as it is
 * typed and `null` once the field is empty, formats on blur in the surface's
 * locale, and carries the `inputmode`, `aria-roledescription` and arrow-key
 * stepping a number field owes (D16). The box itself is the vendored `Input`,
 * rendered through the primitive so it looks like every other field here.
 *
 * Chromeless by default, because a condition pill is the field around it
 * (D12). The analysis editor's row limit is the same control standing on
 * its own, and asks for the registry's box with `chrome="box"`.
 */
export function NumberInput({
  value,
  onNumber,
  label,
  disabled,
  invalid,
  chrome,
  className,
  placeholder,
  describedBy,
}: ControlChromeProps & {
  /** The number in force, if there is one. */
  value: unknown;
  /** Called only when the text parses; `null` once the field is empty. */
  onNumber(value: number | null): void;
  label: string;
  disabled?: boolean;
  invalid?: boolean;
  className?: string;
  /**
   * What an empty box stands for, where it stands for something: the row
   * limit's blank is the N a view starts at, not a value still to be set.
   */
  placeholder?: string;
  /** The id of the sentence that says why the box is refused. */
  describedBy?: string;
}) {
  const messages = useViewMessages();
  const { locale } = useSurfaceDisplay();
  return (
    <NumberFieldPrimitive.Root
      value={numberOrBlank(value)}
      onValueChange={next => onNumber(next)}
      locale={locale}
      disabled={disabled}
      // The primitive's root is a wrapper with nothing of its own to draw,
      // and the field's box is the input, so the width a caller asks for is
      // the input's and the wrapper stays out of the layout.
      className="contents"
    >
      <NumberFieldPrimitive.Input
        render={
          <PillInput
            aria-label={label}
            aria-invalid={invalid}
            aria-describedby={describedBy}
            chrome={chrome}
            className={className}
          />
        }
        // A condition with no value yet is a normal editing state rather
        // than a mistake, so the box says what is missing.
        placeholder={placeholder ?? messages.label('label.filter.not-set')}
      />
    </NumberFieldPrimitive.Root>
  );
}

/** One number, the two ends of a range, or a list of as many as are wanted. */
export function NumberValue({
  value,
  onChange,
  label,
  disabled,
  invalid,
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
        invalid={invalid}
      />
    );

  if (range)
    return (
      <NumberRangeValue
        value={value}
        onChange={onChange}
        label={label}
        disabled={disabled}
        invalid={invalid}
      />
    );

  return (
    <NumberInput
      label={label}
      disabled={disabled}
      invalid={invalid}
      value={value}
      onNumber={onChange}
    />
  );
}

/** The two ends of a `BETWEEN`, either of which may still be blank. */
function NumberRangeValue({
  value,
  onChange,
  label,
  disabled,
  invalid,
}: ValueProps) {
  const messages = useViewMessages();
  const parts = Array.isArray(value) ? value : [];
  const ends = [numberOrBlank(parts[0]), numberOrBlank(parts[1])];
  const end = (index: 0 | 1) => (
    <NumberInput
      label={messages.label(
        index === 0 ? 'label.filter.range-from' : 'label.filter.range-to',
        { field: label },
      )}
      disabled={disabled}
      invalid={invalid}
      value={ends[index]}
      // Neither end is the one that gives: they share the pill's room and
      // each shrinks to nothing rather than pushing the row wider.
      className="min-w-0 flex-1"
      onNumber={next => {
        const written = index === 0 ? [next, ends[1]] : [ends[0], next];
        // Two empty ends are the kind's blank value, not a range between
        // nothing and nothing.
        onChange(written.every(end => end === null) ? null : written);
      }}
    />
  );

  return <RangeRow from={end(0)} to={end(1)} />;
}

/** The values of an `IN` / `NOT_IN`: a list of numbers that grows. */
function NumberListValue({
  value,
  onChange,
  label,
  disabled,
  invalid,
}: ValueProps) {
  const messages = useViewMessages();
  return (
    <ValueChips
      value={value}
      values={(Array.isArray(value) ? value : []).filter(isFiniteNumber)}
      onChange={onChange}
      label={label}
      disabled={disabled}
      invalid={invalid}
      parse={parseNumber}
      unparsable={messages.label('label.filter.not-a-number')}
      inputMode="decimal"
    />
  );
}

/** The text as a number, or `null` while it is empty or not one yet. */
function parseNumber(text: string): number | null {
  if (text.trim().length === 0) return null;
  const parsed = Number(text);
  return Number.isFinite(parsed) ? parsed : null;
}

/** One end of a range, as a number or as nothing. */
function numberOrBlank(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}
