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

/**
 * `filter` as a whole: the field path it takes, the wire shape of an
 * expression its groups build together, and the value-list builders of more
 * than one group. Each group's own builders are tested beside it, in
 * `test/dsl/filter/`.
 */

import {
  DeletionState,
  filter,
  FilterOperator,
  SearchMode,
  TimeUnit,
  type QueryField,
} from '../../src/dsl.js';
import { describe, expect, expectTypeOf, it } from 'vitest';

describe('filter', () => {
  it('exposes QueryField as the field-path type', () => {
    expectTypeOf<QueryField<'state.status'>>().toEqualTypeOf<'state.status'>();
  });

  it('builds the Wow FilterExpression wire shape', () => {
    expect(
      filter.and([
        filter.deletion(DeletionState.ACTIVE),
        filter.eq('state.status', 'PAID'),
        filter.elementMatch('state.items', filter.gt('quantity', 0)),
        filter.search('wow', { fields: ['state.name'] }),
        filter.today('state.createdAt', { zoneId: 'UTC' }),
      ]),
    ).toEqual({
      op: FilterOperator.AND,
      operands: [
        { op: FilterOperator.DELETION, state: DeletionState.ACTIVE },
        { op: FilterOperator.EQ, field: 'state.status', value: 'PAID' },
        {
          op: FilterOperator.ELEMENT_MATCH,
          field: 'state.items',
          predicate: { op: FilterOperator.GT, field: 'quantity', value: 0 },
        },
        {
          op: FilterOperator.SEARCH,
          query: 'wow',
          mode: SearchMode.TERMS,
          fields: ['state.name'],
        },
        {
          op: FilterOperator.TODAY,
          field: 'state.createdAt',
          timeUnit: TimeUnit.MILLISECONDS,
          zoneId: 'UTC',
        },
      ],
    });
  });

  it('accepts readonly arrays for value-list builders', () => {
    const ids = ['snapshot-1', 'snapshot-2'] as const;
    const aggregateIds = ['order-1', 'order-2'] as const;
    const statuses = ['PAID', 'SHIPPED'] as const;
    const tags = ['wow', 'cqrs'] as const;

    expect([
      filter.ids(ids),
      filter.aggregateIds(aggregateIds),
      filter.isIn('status', statuses),
      filter.notIn('status', statuses),
      filter.containsAll('tags', tags),
    ]).toEqual([
      { op: 'ids', values: ['snapshot-1', 'snapshot-2'] },
      { op: 'aggregate-ids', values: ['order-1', 'order-2'] },
      { op: 'in', field: 'status', values: ['PAID', 'SHIPPED'] },
      { op: 'not-in', field: 'status', values: ['PAID', 'SHIPPED'] },
      { op: 'contains-all', field: 'tags', values: ['wow', 'cqrs'] },
    ]);
    expect(filter.aggregateIds(aggregateIds).values).not.toBe(aggregateIds);
  });
});
