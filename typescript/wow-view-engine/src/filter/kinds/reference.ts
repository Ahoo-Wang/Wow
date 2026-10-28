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
  isReferenceFilterValue,
  type ReferenceFilterValue,
} from '../values.js';
import {
  compilePresence,
  describePresenceParts,
  isPresenceOperator,
  PRESENCE_OPERATORS,
} from './presence.js';
import { FilterOperators, type FilterOperatorName } from '../../model/index.js';

/**
 * Points at rows of another dataset. The value keeps each label next to its
 * id, so reopening a saved view shows what was chosen without asking the
 * remote source again; only editing needs `resolveOptions`.
 *
 * `IS_EMPTY` — 「没有条目」 — asks a list of ids for none; the descriptor
 * admits it only on an array, so a single id never offers it. "One of
 * these, or none" is two conditions on one field, written as a nested `OR`
 * group: a group names a field once (`filter.field.duplicate-in-group`).
 */
export const referenceFieldKind: FieldKind = {
  id: 'reference',
  operators: [
    FilterOperators.IN,
    FilterOperators.NOT_IN,
    FilterOperators.IS_EMPTY,
    ...PRESENCE_OPERATORS,
  ],
  defaultOperator: FilterOperators.IN,
  compiledOperators: operator =>
    operator === FilterOperators.IS_EMPTY
      ? [FilterOperators.IS_EMPTY, FilterOperators.IS_NULL]
      : [operator],

  emptyValue() {
    return { items: [] } satisfies ReferenceFilterValue;
  },

  /** Its empty shape is an object, which no general rule would recognise. */
  isBlank({ value }) {
    return isReferenceFilterValue(value) && value.items.length === 0;
  },

  validate({ value, operator, field, path }) {
    if (asksNoValue(operator)) return [];
    if (!isReferenceFilterValue(value))
      return [issue('filter.value.expected-reference-list', path)];
    if (value.items.length === 0) return [issue('filter.value.required', path)];
    if (!field.remote)
      return [
        issue('filter.field.reference-without-source', path, {
          field: field.name,
        }),
      ];
    return [];
  },

  compile({ leaf, field }): FilterExpression {
    const presence = compilePresence(field.name, leaf.operator);
    if (presence) return presence;
    // As the array kind reads it: an empty list, or none at all, which a
    // store's empty-list test alone misses.
    if (leaf.operator === FilterOperators.IS_EMPTY)
      return filter.or([filter.isEmpty(field.name), filter.isNull(field.name)]);
    const ids = readValue<ReferenceFilterValue>(leaf.value).items.map(
      item => item.id,
    );
    return leaf.operator === FilterOperators.NOT_IN
      ? filter.notIn(field.name, ids)
      : filter.isIn(field.name, ids);
  },

  editor(operator, field) {
    return asksNoValue(operator)
      ? { input: 'none' }
      : { input: 'remote', multiple: true, remote: field.remote };
  },

  describe({ leaf, field }) {
    const presence = describePresenceParts(leaf.operator, field);
    if (presence) return presence;
    if (leaf.operator === FilterOperators.IS_EMPTY)
      return {
        text: `${field.label} ${OPERATOR_TEXT[FilterOperators.IS_EMPTY]}`,
        relation: 'has-no-entries',
        value: { kind: 'none' },
      };
    if (!isReferenceFilterValue(leaf.value))
      return { text: field.label, value: { kind: 'blank' } };
    const items = readValue<ReferenceFilterValue>(leaf.value).items;
    const labels = items.map(item => item.label);
    return {
      text: `${field.label} ${OPERATOR_TEXT[leaf.operator]} ${labels.join(', ')}`,
      value: { kind: 'list', values: items.map(item => item.id), labels },
    };
  },
};

/** An operator that is the whole condition, so the leaf holds no ids. */
function asksNoValue(operator: FilterOperatorName): boolean {
  return isPresenceOperator(operator) || operator === FilterOperators.IS_EMPTY;
}
