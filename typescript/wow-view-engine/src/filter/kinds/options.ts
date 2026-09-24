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

import type { FieldOption, FieldDefinition, Issue } from '../../model/index.js';
import { issue } from '../fieldKind.js';
import { isFiniteNumber } from '../values.js';

/**
 * What `enum` and `array` share.
 *
 * Their values are different things — one picks from a closed set, the other
 * holds several entries at once — but "a list of strings or numbers, and a
 * declared `options` closes it" is one rule, and it was written twice. A
 * change to what a candidate may be, or to how an unknown one is reported,
 * belongs in one place or the two kinds drift.
 */

/**
 * A candidate's label, strictly: `undefined` when the definition named none.
 *
 * The difference from `labelOf` matters wherever the answer travels rather
 * than being printed. A summary item carries labels so the bar can show what
 * the definition calls a value; handing it a stringified value as if it were
 * a label makes the bar prefer it over the field's own formatting, and a
 * currency entry shows as a bare number beside a column showing ¥.
 */
export function optionLabelOf(
  options: FieldOption[] | undefined,
  value: string | number,
): string | undefined {
  return options?.find(option => option.value === value)?.label;
}

/** A candidate's label, or the raw value when the definition declares none. */
export function labelOf(
  options: FieldOption[] | undefined,
  value: string | number,
): string {
  return optionLabelOf(options, value) ?? String(value);
}

/**
 * The values of one condition as the English line reads them: the label the
 * definition gave each, or the value itself where it gave none.
 */
export function shownEntries(
  values: readonly (string | number)[],
  labels: readonly (string | undefined)[],
): string {
  return values
    .map((value, index) => labels[index] ?? String(value))
    .join(', ');
}

/**
 * The `labels` a summary item carries, or nothing at all when the definition
 * named none of them — an absent `labels` is what tells the bar to fall back
 * on the field's own formatting rather than on a stringified value.
 */
export function namedLabels(labels: readonly (string | undefined)[]): {
  labels?: readonly (string | undefined)[];
} {
  return labels.some(label => label !== undefined) ? { labels } : {};
}

/** Whether a value is something `options` could list. */
export function isOptionValue(value: unknown): value is string | number {
  return typeof value === 'string' || isFiniteNumber(value);
}

/**
 * Admits a list of candidates against the field's declared set.
 *
 * `expected` is the kind's own code for "this is not a list of candidates at
 * all", because a user choosing options and a user typing entries are being
 * asked different questions and should read different sentences.
 */
export function validateOptionValues(
  value: unknown,
  field: FieldDefinition,
  path: Issue['path'],
  expected: string,
): Issue[] {
  if (!Array.isArray(value) || !value.every(isOptionValue))
    return [issue(expected, path)];
  if (value.length === 0) return [issue('filter.value.required', path)];

  const declared = field.options;
  if (!declared) return [];
  const allowed = new Set(declared.map(option => option.value));
  const unknown = value.filter(item => !allowed.has(item));
  return unknown.length === 0
    ? []
    : [
        issue('filter.value.unknown-option', path, {
          values: unknown.join(', '),
        }),
      ];
}
