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

import { filter, type FilterExpression } from '@ahoo-wang/wow-client/dsl';
import { issue, readValue, type FieldKind } from '../fieldKind.js';
import { OPERATOR_TEXT } from '../operatorWords.js';
import {
  compilePresence,
  describePresenceParts,
  isPresenceOperator,
  PRESENCE_OPERATORS,
} from './presence.js';
import { FilterOperators } from '../../model/index.js';

export const booleanFieldKind: FieldKind = {
  id: 'boolean',
  operators: [FilterOperators.EQ, FilterOperators.NE, ...PRESENCE_OPERATORS],
  defaultOperator: FilterOperators.EQ,

  emptyValue() {
    // Not `true`: a row that appears already answering its own question
    // would filter the list before the user chose anything.
    return null;
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
    return leaf.operator === FilterOperators.NE
      ? filter.ne(field.name, value)
      : filter.eq(field.name, value);
  },

  editor(operator, field) {
    if (isPresenceOperator(operator)) return { input: 'none' };
    // The field's own words for its two answers, where it has them.
    return field.options?.length
      ? { input: 'boolean', options: field.options }
      : { input: 'boolean' };
  },

  describe({ leaf, field }) {
    const presence = describePresenceParts(leaf.operator, field);
    if (presence) return presence;
    if (typeof leaf.value !== 'boolean')
      return { text: field.label, value: { kind: 'blank' } };
    const value = readValue<boolean>(leaf.value);
    // The answer as the field names it (「老客」), where it does.
    const label = field.options?.find(
      option => option.value === String(value),
    )?.label;
    return {
      text: `${field.label} ${OPERATOR_TEXT[leaf.operator]} ${label ?? value}`,
      value: { kind: 'text', value, ...(label === undefined ? {} : { label }) },
    };
  },
};
