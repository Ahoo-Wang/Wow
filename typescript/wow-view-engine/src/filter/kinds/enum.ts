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

import { filter, type FilterExpression } from '@ahoo-wang/wow-client';
import { type FieldKind } from '../fieldKind.js';
import { type EnumFilterValue } from '../values.js';
import {
  namedLabels,
  optionLabelOf,
  shownEntries,
  validateOptionValues,
} from './options.js';
import {
  compilePresence,
  describePresenceParts,
  isPresenceOperator,
  PRESENCE_OPERATORS,
} from './presence.js';

/**
 * A closed set of values declared by the definition. Both `IN` and `NOT_IN`
 * store a list, so switching between them keeps the selection.
 */
export const enumFieldKind: FieldKind = {
  id: 'enum',
  operators: ['IN', 'NOT_IN', ...PRESENCE_OPERATORS],
  defaultOperator: 'IN',
  // One of the declared candidates, which are strings unless the definition
  // wrote numeric codes — `isSingleStringField` reads those and answers no.
  singleString: true,

  emptyValue() {
    return [] satisfies EnumFilterValue;
  },

  validate({ value, operator, field, path }) {
    if (isPresenceOperator(operator)) return [];
    return validateOptionValues(
      value,
      field,
      path,
      'filter.value.expected-option-list',
    );
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
    const presence = describePresenceParts(leaf.operator, field);
    if (presence) return presence;
    // A value that is not a list of candidates says nothing about the field,
    // so the summary says only which field it was written against.
    if (!Array.isArray(leaf.value))
      return { text: field.label, value: { kind: 'blank' } };
    const values = leaf.value as EnumFilterValue;
    // A code the definition no longer lists has no label, and stringifying
    // it into one would put it ahead of the field's own formatting.
    const labels = values.map(value => optionLabelOf(field.options, value));
    return {
      text: `${field.label} ${leaf.operator} ${shownEntries(values, labels)}`,
      value: { kind: 'list', values, ...namedLabels(labels) },
    };
  },
};
