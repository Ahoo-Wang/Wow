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

import { filter, type FilterExpression } from '@ahoo-wang/fetcher-wow';
import type { FieldOption } from '../../model/index.js';
import { issue, readValue, type FieldKind } from '../fieldKind.js';
import { isFiniteNumber } from '../values.js';
import {
  compilePresence,
  describePresence,
  isPresenceOperator,
  PRESENCE_OPERATORS,
} from './presence.js';

/** What one entry of an array field may be. */
export type ArrayFilterValue = (string | number)[];

function labelOf(options: FieldOption[] | undefined, value: string | number) {
  return (
    options?.find(option => option.value === value)?.label ?? String(value)
  );
}

/**
 * A field holding several values at once: tags, categories, labels.
 *
 * It is a kind of its own rather than a flag on `string`, because its
 * operators mean something else. On a scalar field `IN` asks whether the one
 * value is among those listed; on this one it asks whether the field's entries
 * include any of them, and that difference is the whole reason the kind
 * exists. `CONTAINS_ALL` asks for all of them, and `IS_EMPTY` asks whether
 * there are any entries at all — which `IS_NULL` cannot answer, because a
 * field can hold an empty list without being absent.
 */
export const arrayFieldKind: FieldKind = {
  id: 'array',
  operators: [
    'IN',
    'NOT_IN',
    'CONTAINS_ALL',
    'IS_EMPTY',
    ...PRESENCE_OPERATORS,
  ],
  defaultOperator: 'IN',

  emptyValue() {
    return [] satisfies ArrayFilterValue;
  },

  validate({ value, operator, field, path }) {
    if (isPresenceOperator(operator) || operator === 'IS_EMPTY') return [];

    if (
      !Array.isArray(value) ||
      !value.every(item => typeof item === 'string' || isFiniteNumber(item))
    )
      return [issue('filter.value.expected-entry-list', path)];
    if (value.length === 0) return [issue('filter.value.required', path)];

    // A declared candidate set is closed, exactly as it is for `enum`.
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
  },

  compile({ leaf, field }): FilterExpression {
    const presence = compilePresence(field.name, leaf.operator);
    if (presence) return presence;

    const name = field.name;
    if (leaf.operator === 'IS_EMPTY') return filter.isEmpty(name);

    const entries = readValue<ArrayFilterValue>(leaf.value);
    switch (leaf.operator) {
      case 'CONTAINS_ALL':
        return filter.containsAll(name, entries);
      case 'NOT_IN':
        return filter.notIn(name, entries);
      default:
        return filter.isIn(name, entries);
    }
  },

  editor(operator, field) {
    if (isPresenceOperator(operator) || operator === 'IS_EMPTY')
      return { input: 'none' };
    // Entries are picked when the definition says what they can be, typed
    // when they are open-ended, and looked up when they live elsewhere.
    if (field.remote)
      return { input: 'remote', multiple: true, remote: field.remote };
    if (field.options)
      return { input: 'select', multiple: true, options: field.options };
    return { input: 'text', multiple: true };
  },

  describe({ leaf, field }) {
    const presence = describePresence(leaf.operator);
    if (presence) return `${field.label} ${presence}`;
    if (leaf.operator === 'IS_EMPTY') return `${field.label} has no entries`;

    const entries = readValue<ArrayFilterValue>(leaf.value).map(entry =>
      labelOf(field.options, entry),
    );
    const relation =
      leaf.operator === 'CONTAINS_ALL'
        ? 'has all of'
        : leaf.operator === 'NOT_IN'
          ? 'has none of'
          : 'has any of';
    return `${field.label} ${relation} ${entries.join(', ')}`;
  },
};
