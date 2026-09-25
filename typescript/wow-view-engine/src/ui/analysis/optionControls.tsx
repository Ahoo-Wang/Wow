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
import type { FiveNumbers } from '../../analysis/boxplot.js';
import type { ChartData, DerivedGap } from '../../analysis/index.js';
import type {
  ChartSpec,
  DerivedSeries,
  RecordData,
} from '../../model/index.js';
import { Checkbox } from '../components/checkbox.js';
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldLabel,
} from '../components/field.js';
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
  /** Every metric: what a card's headline may be. */
  metrics: Choice[];
  /**
   * The metrics a mark can measure: every one but a moment (`momentMetrics`
   * — the earliest or the latest of a date), which is written out, not drawn.
   */
  quantities: Choice[];
  /** The moments among `metrics`, by alias. */
  moments: ReadonlySet<string>;
  /** Metrics that add up across rows: the ones a pie may merge a tail of. */
  additive: ReadonlySet<string>;
  /** What each metric is a quantity of (`metricMeasure`), by alias. */
  measures: ReadonlyMap<string, string>;
  /**
   * The dimensions that are date buckets, by alias: an axis of dates never
   * lies on its side by itself (`drawsHorizontal`).
   */
  dated?: ReadonlySet<string>;
  /**
   * Each field's five numbers the quantities hold (`fiveNumberSets`): what a
   * boxplot can draw.
   */
  fiveNumbers?: readonly FiveNumbers[];
}

/**
 * What a hint line under a control wears on this panel.
 *
 * `FieldDescription` ships `text-muted-foreground`, which lands at 4.34:1 on
 * the panel's sidebar ground — under the 4.5:1 axe asks of a 14px line, the
 * same measurement {@link OptionsSection} records for the same grey. A hint
 * is the sentence that says why a control is the way it is, so it is toned
 * rather than faded: quieter than the label above it, and still legible.
 */
export const HINT = 'text-foreground/70';

/** What every options page receives. */
export interface OptionsPageProps {
  chart: ChartSpec;
  shape: OptionsShape;
  /** The rows that ran, for the choices only they can name (funnel stages). */
  rows: readonly RecordData[];
  /** A group value as its field shows it. */
  label: ValueLabel;
  onChange(chart: ChartSpec): void;
  /**
   * The chart as drawn over the rows on screen, for the choices only its
   * numbers can judge — a log scale over values that hold 0 (D33 batch E),
   * a split past the palette (Q56). Left out before any rows.
   */
  data?: ChartData;
  /**
   * Why a derived line could not be drawn over the result on screen
   * (`derivedGap`, Q53), with the groups it was cut at; `undefined` when it
   * can, and left out altogether before any rows — nothing to judge yet.
   */
  gapOf?: (
    derived: Pick<DerivedSeries, 'kind' | 'metric' | 'window'>,
  ) => { gap: DerivedGap; limit?: number } | undefined;
  /** The window a moving average runs over when none is typed. */
  defaultWindow?: number;
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

/**
 * One yes-or-no setting, named after itself.
 *
 * A `hint` is the line under the label that says why the box is the way it
 * is — above all why it cannot be ticked. A disabled control that says
 * nothing is the defect: the analyst sees a setting that refuses the press
 * and is told neither what it would do nor what is missing. The hint is tied
 * to the box with `aria-describedby`, so a reader hears the reason with the
 * name rather than having to go looking for the text beside it.
 */
export function CheckField({
  label,
  hint,
  checked,
  disabled,
  onChange,
  'data-slot': slot,
}: {
  label: string;
  /** Why the box reads as it does; shown under the label. */
  hint?: string;
  checked: boolean;
  disabled?: boolean;
  onChange(checked: boolean): void;
  'data-slot'?: string;
}) {
  const id = useId();
  const hintId = `${id}-hint`;
  const box = (
    <Checkbox
      id={id}
      checked={checked}
      disabled={disabled}
      aria-describedby={hint ? hintId : undefined}
      onCheckedChange={next => onChange(next === true)}
    />
  );
  return (
    <Field orientation="horizontal" data-slot={slot}>
      {box}
      {hint ? (
        <FieldContent>
          <FieldLabel htmlFor={id}>{label}</FieldLabel>
          <FieldDescription id={hintId} className={HINT}>
            {hint}
          </FieldDescription>
        </FieldContent>
      ) : (
        <FieldLabel htmlFor={id}>{label}</FieldLabel>
      )}
    </Field>
  );
}

/** One choice among a few words, all of them on screen at once. */
export function ChoiceField<V extends string>({
  label,
  hint,
  items,
  value,
  onChange,
  'data-slot': slot,
}: {
  label: string;
  /** What the current choice means; shown under the choices. */
  hint?: string;
  /** A choice `disabled` stays on screen, greyed; the hint says why. */
  items: readonly { value: V; label: string; disabled?: boolean }[];
  value: V;
  onChange(value: V): void;
  'data-slot'?: string;
}) {
  const hintId = `${useId()}-hint`;
  return (
    <Field {...(slot === undefined ? {} : { 'data-slot': slot })}>
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
        aria-describedby={hint ? hintId : undefined}
      >
        {items.map(item => (
          <ToggleGroupItem
            key={item.value}
            value={item.value}
            disabled={item.disabled === true}
          >
            {item.label}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
      {hint && (
        <FieldDescription id={hintId} className={HINT}>
          {hint}
        </FieldDescription>
      )}
    </Field>
  );
}

/** A number that may be left unset, in the registry's box. */
export function NumberField({
  label,
  hint,
  value,
  min,
  max,
  onChange,
}: {
  label: string;
  hint?: string;
  value: number | undefined;
  /** Below this the number is refused rather than written. */
  min?: number;
  /** Above this, likewise. */
  max?: number;
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
          else if (
            (min === undefined || next >= min) &&
            (max === undefined || next <= max)
          )
            onChange(next);
        }}
      />
      {hint && <FieldDescription className={HINT}>{hint}</FieldDescription>}
    </Field>
  );
}

/** A few words the analyst types, such as an axis title. */
export function TextField({
  label,
  value,
  placeholder,
  onChange,
}: {
  label: string;
  value: string | undefined;
  /**
   * What stands when nothing is typed — the default the chart draws, not a
   * hint — so it is toned as `NameField`'s is: quieter than a typed value,
   * still readable. An empty box that hides its default reads as "nothing",
   * when the axis is in fact titled (2026-09-23 audit).
   */
  placeholder?: string;
  onChange(value: string | undefined): void;
}) {
  return (
    <Field>
      <FieldLabel>{label}</FieldLabel>
      <PillInput
        aria-label={label}
        chrome="box"
        className="placeholder:text-foreground/70"
        placeholder={placeholder}
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
 *
 * **The placeholder here is not a hint, it is the name.** Until the analyst
 * renames a stage, the placeholder *is* what that stage is called on screen,
 * so the registry's `placeholder:text-muted-foreground` — 4.34:1 on this
 * panel's ground, under the 4.5:1 axe asks — would grey out the only word
 * naming the row. It is overridden to `text-foreground/70`: still quieter
 * than a typed name, and still readable. The same call `OptionsSection`
 * records below.
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
      className="placeholder:text-foreground/70 h-7 min-w-0 flex-1"
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
