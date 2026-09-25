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

// Internal: not re-exported by filter/index.ts. `filter.elementMatch` and an
// aggregation element's own filter both scope a filter to an element; this is
// the one rule they share.
import { FilterOperator } from './operator.js';
import type { FilterExpression } from './types.js';

/**
 * Refuses the filters that only a whole record can answer.
 *
 * The metadata filters, `DELETION` and `SEARCH` ask about the record — its id,
 * its owner, whether it is deleted, its text — and an element is not a record.
 * Wow calls them root filters and refuses them in both places a filter is
 * scoped to an element: an `ELEMENT_MATCH` predicate and an aggregation
 * element's own filter. `subject` names which one, so the complaint points at
 * the call that made it.
 */
export function requireElementScopedFilter(
  expression: FilterExpression,
  subject: string,
): void {
  switch (expression.op) {
    case FilterOperator.ID:
    case FilterOperator.IDS:
    case FilterOperator.AGGREGATE_ID:
    case FilterOperator.AGGREGATE_IDS:
    case FilterOperator.TENANT_ID:
    case FilterOperator.OWNER_ID:
    case FilterOperator.SPACE_ID:
    case FilterOperator.DELETION:
    case FilterOperator.SEARCH:
      throw new TypeError(`${subject} cannot contain root filters.`);
    case FilterOperator.AND:
    case FilterOperator.OR:
    case FilterOperator.NOR:
      if (expression.operands.length === 0) {
        throw new TypeError(`${expression.op} operands cannot be empty.`);
      }
      if (expression.operands.some(operand => operand == null)) {
        throw new TypeError(`${expression.op} operands cannot contain null.`);
      }
      expression.operands.forEach(operand =>
        requireElementScopedFilter(operand, subject),
      );
      break;
    case FilterOperator.ELEMENT_MATCH:
      requireElementScopedFilter(expression.predicate, subject);
      break;
  }
}
