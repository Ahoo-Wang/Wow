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
import type { FieldDefinition, FilterOperatorName } from '../../model/index.js';
import type { FieldKindDescription } from '../describe.js';
import { FilterOperators } from '../../model/index.js';
import { OPERATOR_TEXT } from '../operatorWords.js';

/**
 * Operators that ask about presence rather than about a value. Every kind
 * offers them and none of them reads the leaf's value.
 */
export const PRESENCE_OPERATORS: readonly FilterOperatorName[] = [
  FilterOperators.IS_NULL,
  FilterOperators.IS_NOT_NULL,
  FilterOperators.EXISTS,
  FilterOperators.NOT_EXISTS,
];

export function isPresenceOperator(operator: FilterOperatorName): boolean {
  return PRESENCE_OPERATORS.includes(operator);
}

export function compilePresence(
  field: string,
  operator: FilterOperatorName,
): FilterExpression | null {
  switch (operator) {
    case FilterOperators.IS_NULL:
      return filter.isNull(field);
    case FilterOperators.IS_NOT_NULL:
      return filter.isNotNull(field);
    case FilterOperators.EXISTS:
      return filter.exists(field);
    case FilterOperators.NOT_EXISTS:
      return filter.notExists(field);
    default:
      return null;
  }
}

/**
 * A presence question as one summary item: the operator is the whole
 * condition, so the value says there is nothing beside it to read. `null`
 * when the operator asks about a value after all.
 */
export function describePresenceParts(
  operator: FilterOperatorName,
  field: FieldDefinition,
): FieldKindDescription | null {
  const presence = describePresence(operator);
  return presence === null
    ? null
    : { text: `${field.label} ${presence}`, value: { kind: 'none' } };
}

/** A presence operator's English words; `null` for any other operator. */
export function describePresence(operator: FilterOperatorName): string | null {
  return isPresenceOperator(operator) ? OPERATOR_TEXT[operator] : null;
}
