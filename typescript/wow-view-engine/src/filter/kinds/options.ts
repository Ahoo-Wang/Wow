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

/** A candidate's label, or the raw value when the definition declares none. */
export function labelOf(
  options: FieldOption[] | undefined,
  value: string | number,
): string {
  return (
    options?.find(option => option.value === value)?.label ?? String(value)
  );
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
