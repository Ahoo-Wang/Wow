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
import type { FilterOperatorName } from '../../model/index.js';
import { issue, readValue, type FieldKind } from '../fieldKind.js';
import {
  isFiniteNumber,
  isNumberRange,
  isOrderedRange,
  type NumberRange,
} from '../values.js';
import {
  compilePresence,
  describePresence,
  isPresenceOperator,
  PRESENCE_OPERATORS,
} from './presence.js';

const MULTI_VALUE: readonly FilterOperatorName[] = ['IN', 'NOT_IN'];

export const numberFieldKind: FieldKind = {
  id: 'number',
  operators: [
    'EQ',
    'NE',
    'GT',
    'GTE',
    'LT',
    'LTE',
    'BETWEEN',
    'IN',
    'NOT_IN',
    ...PRESENCE_OPERATORS,
  ],
  defaultOperator: 'EQ',

  emptyValue(operator) {
    if (MULTI_VALUE.includes(operator)) return [];
    // `0` and `[0, 0]` are real conditions, and a row the user has only just
    // added has not asked for either.
    return null;
  },

  validate({ value, operator, path }) {
    if (isPresenceOperator(operator)) return [];

    if (operator === 'BETWEEN') {
      if (!isNumberRange(value))
        return [issue('filter.value.expected-number-range', path)];
      // Two numbers the wrong way round is the same mistake a date range
      // makes, and it reads as the same sentence.
      if (!isOrderedRange(value))
        return [issue('filter.value.inverted-range', path)];
      return [];
    }

    if (MULTI_VALUE.includes(operator)) {
      if (!Array.isArray(value) || !value.every(isFiniteNumber))
        return [issue('filter.value.expected-number-list', path)];
      if (value.length === 0) return [issue('filter.value.required', path)];
      return [];
    }

    if (!isFiniteNumber(value))
      return [issue('filter.value.expected-number', path)];
    return [];
  },

  compile({ leaf, field }): FilterExpression {
    const presence = compilePresence(field.name, leaf.operator);
    if (presence) return presence;

    const name = field.name;
    switch (leaf.operator) {
      case 'BETWEEN': {
        const [lower, upper] = leaf.value as NumberRange;
        return filter.between(name, lower, upper);
      }
      case 'IN':
        return filter.isIn(name, leaf.value as number[]);
      case 'NOT_IN':
        return filter.notIn(name, leaf.value as number[]);
      case 'NE':
        return filter.ne(name, leaf.value as number);
      case 'GT':
        return filter.gt(name, leaf.value as number);
      case 'GTE':
        return filter.gte(name, leaf.value as number);
      case 'LT':
        return filter.lt(name, leaf.value as number);
      case 'LTE':
        return filter.lte(name, leaf.value as number);
      default:
        return filter.eq(name, leaf.value as number);
    }
  },

  editor(operator) {
    if (isPresenceOperator(operator)) return { input: 'none' };
    if (operator === 'BETWEEN') return { input: 'number', range: true };
    return { input: 'number', multiple: MULTI_VALUE.includes(operator) };
  },

  // A summary line describes what is in force, and a value this kind cannot
  // read is not in force. Saying only the field's name is honest; "Qty o ~ o"
  // — which is what a string spread into two bounds reads as — is not.
  describe({ leaf, field }) {
    const presence = describePresence(leaf.operator);
    if (presence) return `${field.label} ${presence}`;
    if (leaf.operator === 'BETWEEN') {
      if (!isNumberRange(leaf.value)) return field.label;
      const [lower, upper] = leaf.value;
      return `${field.label} ${lower} ~ ${upper}`;
    }
    if (Array.isArray(leaf.value))
      return `${field.label} ${leaf.operator} ${readValue<number[]>(leaf.value).join(', ')}`;
    if (!isFiniteNumber(leaf.value)) return field.label;
    return `${field.label} ${leaf.operator} ${leaf.value}`;
  },
};
