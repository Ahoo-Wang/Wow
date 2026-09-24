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
import type { FilterSummaryRelation } from '../describe.js';
import { readValue, type FieldKind } from '../fieldKind.js';
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
 * The English `text` has always read this way. The bar words the relation
 * through the catalogue instead, so both say the same thing in whichever
 * language is in force.
 */
const RELATION_TEXT: Record<FilterSummaryRelation, string> = {
  'has-all': 'has all of',
  'has-none': 'has none of',
  'has-any': 'has any of',
};

/** What one entry of an array field may be. */
export type ArrayFilterValue = (string | number)[];

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
  scalar: false,

  emptyValue() {
    return [] satisfies ArrayFilterValue;
  },

  validate({ value, operator, field, path }) {
    if (isPresenceOperator(operator) || operator === 'IS_EMPTY') return [];
    // A declared candidate set is closed, exactly as it is for `enum`.
    return validateOptionValues(
      value,
      field,
      path,
      'filter.value.expected-entry-list',
    );
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
    const presence = describePresenceParts(leaf.operator, field);
    if (presence) return presence;
    if (leaf.operator === 'IS_EMPTY')
      return {
        text: `${field.label} has no entries`,
        value: { kind: 'none' },
      };
    // Entries this kind cannot read are no condition to report.
    if (!Array.isArray(leaf.value))
      return { text: field.label, value: { kind: 'blank' } };

    const values = readValue<ArrayFilterValue>(leaf.value);
    // Only what the definition actually named. An open-ended array has no
    // candidates at all, so every "label" would be the entry stringified —
    // and the bar prefers a label to the field's own formatting, which is
    // how a currency entry ended up a bare number beside a column of ¥.
    const labels = values.map(entry => optionLabelOf(field.options, entry));
    const relation =
      leaf.operator === 'CONTAINS_ALL'
        ? 'has-all'
        : leaf.operator === 'NOT_IN'
          ? 'has-none'
          : 'has-any';
    return {
      text: `${field.label} ${RELATION_TEXT[relation]} ${shownEntries(values, labels)}`,
      relation,
      value: { kind: 'list', values, ...namedLabels(labels) },
    };
  },
};
