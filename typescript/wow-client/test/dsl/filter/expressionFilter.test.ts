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

/** `filter`'s expression filter: a computed value compared with a number. */

import {
  aggregation,
  ComparisonOperator,
  DateDiffUnit,
  filter,
  FilterOperator,
  type ElementFilterExpression,
} from '../../../src/dsl.js';
import { describe, expect, expectTypeOf, it } from 'vitest';

describe('filter', () => {
  it('compares a computed expression, but not inside ELEMENT_MATCH', () => {
    const hours = aggregation.dateDiff(
      'paidAt',
      'shippedAt',
      DateDiffUnit.HOUR,
    );
    const late = filter.expression(hours, ComparisonOperator.GT, 48);
    expect(late).toEqual({
      op: FilterOperator.EXPRESSION,
      expression: hours,
      comparison: 'gt',
      value: 48,
    });
    expect(() =>
      filter.expression(hours, ComparisonOperator.GT, Infinity),
    ).toThrow('EXPRESSION value must be finite.');
    expect(() =>
      filter.expression(aggregation.constant(1), ComparisonOperator.EQ, 1),
    ).toThrow('EXPRESSION must read at least one field.');
    expect(() => filter.expression(hours, 'LIKE' as never, 1)).toThrow(
      'EXPRESSION comparison is invalid: [LIKE].',
    );

    const insideElement = () => {
      // @ts-expect-error EXPRESSION cannot be scoped to an array element.
      filter.elementMatch('state.items', late);
    };
    expectTypeOf(insideElement).toBeFunction();
    expect(() =>
      filter.elementMatch(
        'state.items',
        filter.and([late as unknown as ElementFilterExpression]),
      ),
    ).toThrow('ELEMENT_MATCH predicate cannot contain root filters.');
    // An aggregation element's filter takes it.
    expect(aggregation.element('state.items', late)).toEqual({
      path: 'state.items',
      filter: late,
    });
  });
});
