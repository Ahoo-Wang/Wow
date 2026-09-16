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
import { issue, type FieldKind } from '../fieldKind.js';
import { isFiniteNumber, type EnumFilterValue } from '../values.js';
import {
  compilePresence,
  describePresence,
  isPresenceOperator,
  PRESENCE_OPERATORS,
} from './presence.js';

function labelOf(options: FieldOption[] | undefined, value: string | number) {
  return (
    options?.find(option => option.value === value)?.label ?? String(value)
  );
}

/**
 * A closed set of values declared by the definition. Both `IN` and `NOT_IN`
 * store a list, so switching between them keeps the selection.
 */
export const enumFieldKind: FieldKind = {
  id: 'enum',
  operators: ['IN', 'NOT_IN', ...PRESENCE_OPERATORS],
  defaultOperator: 'IN',

  emptyValue() {
    return [] satisfies EnumFilterValue;
  },

  validate({ value, operator, field, path }) {
    if (isPresenceOperator(operator)) return [];

    if (
      !Array.isArray(value) ||
      !value.every(item => typeof item === 'string' || isFiniteNumber(item))
    )
      return [issue('filter.value.expected-option-list', path)];
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
  },

  compile({ leaf, field }): FilterExpression {
    const presence = compilePresence(field.name, leaf.operator);
    if (presence) return presence;
    const values = leaf.value as EnumFilterValue;
    return leaf.operator === 'NOT_IN'
      ? filter.notIn(field.name, values)
      : filter.isIn(field.name, values);
  },

  editor(operator, field) {
    return isPresenceOperator(operator)
      ? { input: 'none' }
      : { input: 'select', multiple: true, options: field.options };
  },

  describe({ leaf, field }) {
    const presence = describePresence(leaf.operator);
    if (presence) return `${field.label} ${presence}`;
    const labels = (leaf.value as EnumFilterValue).map(value =>
      labelOf(field.options, value),
    );
    return `${field.label} ${leaf.operator} ${labels.join(', ')}`;
  },
};
