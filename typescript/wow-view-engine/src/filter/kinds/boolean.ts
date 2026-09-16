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
import { issue, readValue, type FieldKind } from '../fieldKind.js';
import {
  compilePresence,
  describePresence,
  isPresenceOperator,
  PRESENCE_OPERATORS,
} from './presence.js';

export const booleanFieldKind: FieldKind = {
  id: 'boolean',
  operators: ['EQ', 'NE', ...PRESENCE_OPERATORS],
  defaultOperator: 'EQ',

  emptyValue() {
    return true;
  },

  validate({ value, operator, path }) {
    if (isPresenceOperator(operator)) return [];
    if (typeof value !== 'boolean')
      return [issue('filter.value.expected-boolean', path)];
    return [];
  },

  compile({ leaf, field }): FilterExpression {
    const presence = compilePresence(field.name, leaf.operator);
    if (presence) return presence;
    const value = leaf.value as boolean;
    return leaf.operator === 'NE'
      ? filter.ne(field.name, value)
      : filter.eq(field.name, value);
  },

  editor(operator) {
    return isPresenceOperator(operator)
      ? { input: 'none' }
      : { input: 'boolean' };
  },

  describe({ leaf, field }) {
    const presence = describePresence(leaf.operator);
    if (presence) return `${field.label} ${presence}`;
    return `${field.label} ${leaf.operator} ${readValue<boolean>(leaf.value)}`;
  },
};
