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

/**
 * What a value an option names wears: its label, said in the surface's
 * words (`DisplayContext.say`), and its tone — the badges a cell draws, and
 * the text an enum reads as.
 */

import type { FieldOption, FieldTone } from '../../model/index.js';
import type { DisplayField } from './display.js';

/** One badge: the value the record holds, the label and tone it wears. */
export interface BadgeEntry {
  value: unknown;
  label: string;
  /** The matching option's tone; absent when no option names this value. */
  tone?: FieldTone;
}

/**
 * The badges a cell wears, or `undefined` when it wears none.
 *
 * Three readings land here and they differ in what they require, not in what
 * they produce. `enum` is inferred: the renderer is the kind's own, so a
 * badge is only justified when the definition declares the choices *and*
 * names at least one of the values — a pill around a code nobody named only
 * makes the code look deliberate. `status` and `tags` were asked for by name,
 * so the definition has already answered that question and a value no option
 * names still wears its pill, showing the code it came as.
 *
 * An array gets one badge per entry whichever reading it is: joined into a
 * single pill they would read as one status with a comma in its name.
 *
 * Each entry carries the raw value beside its label, because labels are not
 * identities: `FieldOption.label` is free text a definition may repeat, and a
 * list of values may repeat too, so the caller needs something better than
 * the label to tell two badges apart. The tone rides along from the matching
 * option, since the caller holding a label no longer has the option it came
 * from.
 */
export function badgeEntries(
  value: unknown,
  field: DisplayField,
  say: (value: string) => string = SAID,
): BadgeEntry[] | undefined {
  const cell = field.cell ?? field.kind;
  if (cell !== 'enum' && cell !== 'status' && cell !== 'tags') return undefined;
  if (value === null || value === undefined) return undefined;
  const options = field.options ?? [];
  const items = Array.isArray(value) ? value : [value];
  if (cell === 'enum') {
    if (options.length === 0) return undefined;
    const labels = optionLabels(items, options, say);
    return labels?.map((label, index) => badge(items[index], label, options));
  }
  return items.map(item => {
    const label = optionOf(item, options)?.label;
    return badge(
      item,
      label === undefined ? String(item) : say(label),
      options,
    );
  });
}

function badge(
  value: unknown,
  label: string,
  options: readonly FieldOption[],
): BadgeEntry {
  const tone = optionOf(value, options)?.tone;
  return { value, label, ...(tone ? { tone } : {}) };
}

function optionOf(
  value: unknown,
  options: readonly FieldOption[],
): FieldOption | undefined {
  return options.find(option => option.value === value);
}

/** The label of each value an enum holds; `undefined` when none is known. */
export function optionLabel(
  value: unknown,
  options: readonly FieldOption[],
  say: (value: string) => string = SAID,
): string | undefined {
  return optionLabels(value, options, say)?.join(', ');
}

/** A label as it is written: where no surface says how it is shown. */
const SAID = (value: string) => value;

/**
 * One label per value, in order, or `undefined` when the options name none of
 * them — a code the definition no longer lists is shown as it came, but a
 * value nothing at all is known about is left to the caller's own rendering.
 */
function optionLabels(
  value: unknown,
  options: readonly FieldOption[],
  say: (value: string) => string,
): string[] | undefined {
  const labelOf = (item: unknown) => {
    const label = optionOf(item, options)?.label;
    return label === undefined ? undefined : say(label);
  };
  const items = Array.isArray(value) ? value : [value];
  return items.some(item => labelOf(item) !== undefined)
    ? items.map(item => labelOf(item) ?? String(item))
    : undefined;
}
