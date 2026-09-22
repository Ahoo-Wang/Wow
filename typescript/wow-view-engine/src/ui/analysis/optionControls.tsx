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

import { useId, type ReactNode } from 'react';
import type { ChartSpec, RecordData } from '../../model/index.js';
import { Checkbox } from '../components/checkbox.js';
import { Field, FieldDescription, FieldLabel } from '../components/field.js';
import { ToggleGroup, ToggleGroupItem } from '../components/toggle-group.js';
import { NumberInput } from '../FilterValueEditor.js';
import { cn } from 'cn';
import { PillInput } from '../variants.js';
import { CompactSelect } from './CompactSelect.js';
import type { ValueLabel } from '../charts/family.js';

/** An alias offered for a slot, named as its column is titled. */
export interface Choice {
  value: string;
  label: string;
}

/** The result on hand, as the options page needs it. */
export interface OptionsShape {
  groups: Choice[];
  metrics: Choice[];
  /** Metrics that add up across rows: the ones a pie may merge a tail of. */
  additive: ReadonlySet<string>;
}

/** What every options page receives. */
export interface OptionsPageProps {
  chart: ChartSpec;
  shape: OptionsShape;
  /** The rows that ran, for the choices only they can name (funnel stages). */
  rows: readonly RecordData[];
  /** A group value as its field shows it. */
  label: ValueLabel;
  onChange(chart: ChartSpec): void;
}

/** A slot of the chart, filled from one list of aliases. */
export function SlotSelect<V extends string>({
  label,
  items,
  value,
  onChange,
}: {
  label: string;
  items: readonly Choice[];
  value: V;
  onChange(value: V): void;
}) {
  return (
    <Field>
      <FieldLabel>{label}</FieldLabel>
      <CompactSelect
        label={label}
        items={items as readonly { value: V; label: string }[]}
        value={value}
        onChange={onChange}
      />
    </Field>
  );
}

/**
 * A slot that may stay empty. The empty choice is a word rather than a
 * blank, because a select with nothing in it reads as broken.
 */
export function OptionalSlotSelect({
  label,
  none,
  items,
  value,
  onChange,
}: {
  label: string;
  none: string;
  items: readonly Choice[];
  value: string | undefined;
  onChange(value: string | undefined): void;
}) {
  return (
    <SlotSelect
      label={label}
      items={[{ value: '', label: none }, ...items]}
      value={value ?? ''}
      onChange={next => onChange(next === '' ? undefined : next)}
    />
  );
}

/** One yes-or-no setting, named after itself. */
export function CheckField({
  label,
  checked,
  disabled,
  onChange,
  'data-slot': slot,
}: {
  label: string;
  checked: boolean;
  disabled?: boolean;
  onChange(checked: boolean): void;
  'data-slot'?: string;
}) {
  const id = useId();
  return (
    <Field orientation="horizontal" data-slot={slot}>
      <Checkbox
        id={id}
        checked={checked}
        disabled={disabled}
        onCheckedChange={next => onChange(next === true)}
      />
      <FieldLabel htmlFor={id}>{label}</FieldLabel>
    </Field>
  );
}

/** One choice among a few words, all of them on screen at once. */
export function ChoiceField<V extends string>({
  label,
  items,
  value,
  onChange,
}: {
  label: string;
  items: readonly { value: V; label: string }[];
  value: V;
  onChange(value: V): void;
}) {
  return (
    <Field>
      <FieldLabel>{label}</FieldLabel>
      <ToggleGroup
        value={[value]}
        onValueChange={next => {
          const chosen = next[0];
          if (items.some(item => item.value === chosen)) onChange(chosen as V);
        }}
        variant="outline"
        size="sm"
        spacing={0}
        aria-label={label}
      >
        {items.map(item => (
          <ToggleGroupItem key={item.value} value={item.value}>
            {item.label}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
    </Field>
  );
}

/** A number that may be left unset, in the registry's box. */
export function NumberField({
  label,
  hint,
  value,
  min,
  onChange,
}: {
  label: string;
  hint?: string;
  value: number | undefined;
  /** Below this the number is refused rather than written. */
  min?: number;
  onChange(value: number | undefined): void;
}) {
  return (
    <Field>
      <FieldLabel>{label}</FieldLabel>
      <NumberInput
        label={label}
        chrome="box"
        className="w-28"
        value={value}
        onNumber={next => {
          if (next === null) onChange(undefined);
          else if (min === undefined || next >= min) onChange(next);
        }}
      />
      {hint && <FieldDescription>{hint}</FieldDescription>}
    </Field>
  );
}

/** A few words the analyst types, such as an axis title. */
export function TextField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string | undefined;
  onChange(value: string | undefined): void;
}) {
  return (
    <Field>
      <FieldLabel>{label}</FieldLabel>
      <PillInput
        aria-label={label}
        chrome="box"
        value={value ?? ''}
        onChange={event =>
          onChange(event.target.value === '' ? undefined : event.target.value)
        }
      />
    </Field>
  );
}

/**
 * A name typed where the name is drawn: a box with no label above it,
 * wearing what the thing would be called as its placeholder.
 *
 * {@link TextField} is the shape a setting of its own takes — a label over a
 * box, on its own line. A name is not a setting beside the thing it names;
 * it *is* the thing's first line, and a label over every stage of a funnel
 * would repeat one word down the whole list. So the label is the box's
 * accessible name and nothing on screen, and the default is the placeholder,
 * which is also what an emptied box goes back to saying.
 */
export function NameField({
  label,
  placeholder,
  value,
  onChange,
}: {
  label: string;
  /** What it is called when nothing has been typed. */
  placeholder: string;
  value: string | undefined;
  onChange(value: string | undefined): void;
}) {
  return (
    <PillInput
      aria-label={label}
      chrome="box"
      className="h-7 min-w-0 flex-1"
      placeholder={placeholder}
      value={value ?? ''}
      onChange={event =>
        onChange(event.target.value === '' ? undefined : event.target.value)
      }
    />
  );
}

/** A titled run of settings on one page. */
export function OptionsSection({
  name,
  title,
  className,
  children,
}: {
  name: string;
  title?: string;
  className?: string;
  children: ReactNode;
}) {
  return (
    <section
      data-slot={`chart-options-${name}`}
      aria-label={title}
      className={cn('flex flex-col gap-3', className)}
    >
      {/* **`h3`, and not grey.** The panel's own title is the `h2` above
          (`ChartOptions`), so a section under it is the next level down —
          `h5` skipped two, which axe's `heading-order` reads as a level
          gone missing and a reader hears as a section that belongs to
          nothing. And `text-muted-foreground` lands at 4.34:1 on this
          panel's ground — under the 4.5:1 axe asks of 13px text, the same
          call `SortSettings` records for the same grey. It stays secondary
          by being a short bold line over the controls it names, not by
          being paler. */}
      {title && <h3 className="font-semibold">{title}</h3>}
      {children}
    </section>
  );
}
