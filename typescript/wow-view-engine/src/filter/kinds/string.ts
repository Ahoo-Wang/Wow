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

import {
  filter,
  type FilterExpression,
  type StringComparison,
} from '@ahoo-wang/wow-client';
import {
  DEFAULT_STRING_COMPARISON,
  type FilterOperatorName,
} from '../../model/index.js';
import { issue, readValue, type FieldKind } from '../fieldKind.js';
import { isNonEmptyString } from '../values.js';
import {
  compilePresence,
  describePresenceParts,
  isPresenceOperator,
  PRESENCE_OPERATORS,
} from './presence.js';

const MULTI_VALUE: readonly FilterOperatorName[] = ['IN', 'NOT_IN'];

export const stringFieldKind: FieldKind = {
  id: 'string',
  operators: [
    'EQ',
    'NE',
    'CONTAINS',
    'STARTS_WITH',
    'ENDS_WITH',
    'IN',
    'NOT_IN',
    'IS_EMPTY_STRING',
    'IS_NOT_EMPTY_STRING',
    ...PRESENCE_OPERATORS,
  ],
  defaultOperator: 'EQ',
  singleString: true,

  emptyValue(operator) {
    if (MULTI_VALUE.includes(operator)) return [];
    return '';
  },

  validate({ value, operator, path }) {
    if (isPresenceOperator(operator)) return [];
    if (operator === 'IS_EMPTY_STRING' || operator === 'IS_NOT_EMPTY_STRING')
      return [];

    if (MULTI_VALUE.includes(operator)) {
      if (!Array.isArray(value) || !value.every(isNonEmptyString))
        return [issue('filter.value.expected-string-list', path)];
      if (value.length === 0) return [issue('filter.value.required', path)];
      return [];
    }

    if (!isNonEmptyString(value))
      return [issue('filter.value.expected-string', path)];
    return [];
  },

  compile({ leaf, field }): FilterExpression {
    const presence = compilePresence(field.name, leaf.operator);
    if (presence) return presence;

    const name = field.name;
    // Text matching is a search, so it ignores case unless the definition
    // says the field's case carries meaning.
    const comparison = (field.stringComparison ??
      DEFAULT_STRING_COMPARISON) as StringComparison;
    switch (leaf.operator) {
      case 'IS_EMPTY_STRING':
        return filter.isEmptyString(name);
      case 'IS_NOT_EMPTY_STRING':
        return filter.isNotEmptyString(name);
      case 'IN':
        return filter.isIn(name, leaf.value as string[]);
      case 'NOT_IN':
        return filter.notIn(name, leaf.value as string[]);
      case 'NE':
        return filter.ne(name, leaf.value as string);
      case 'CONTAINS':
        return filter.contains(name, leaf.value as string, comparison);
      case 'STARTS_WITH':
        return filter.startsWith(name, leaf.value as string, comparison);
      case 'ENDS_WITH':
        return filter.endsWith(name, leaf.value as string, comparison);
      default:
        return filter.eq(name, leaf.value as string);
    }
  },

  editor(operator) {
    if (isPresenceOperator(operator)) return { input: 'none' };
    if (operator === 'IS_EMPTY_STRING' || operator === 'IS_NOT_EMPTY_STRING')
      return { input: 'none' };
    return { input: 'text', multiple: MULTI_VALUE.includes(operator) };
  },

  describe({ leaf, field }) {
    const presence = describePresenceParts(leaf.operator, field);
    if (presence) return presence;
    if (Array.isArray(leaf.value)) {
      const values = readValue<string[]>(leaf.value);
      return {
        text: `${field.label} ${leaf.operator} ${values.join(', ')}`,
        value: { kind: 'list', values },
      };
    }
    // Not text: no condition this kind can read, so name only the field.
    if (typeof leaf.value !== 'string')
      return { text: field.label, value: { kind: 'blank' } };
    return {
      text: `${field.label} ${leaf.operator} ${leaf.value}`,
      value: { kind: 'text', value: leaf.value },
    };
  },
};
