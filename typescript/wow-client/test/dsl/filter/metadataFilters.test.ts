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
 * `filter`'s metadata filters: all or none, ids, tenant, owner, space and
 * deletion state.
 */

import {
  filter,
  FilterOperator,
  type MetadataFilter,
} from '../../../src/dsl.js';
import { describe, expect, it } from 'vitest';

describe('filter', () => {
  it('builds metadata filters', () => {
    const expressions: MetadataFilter[] = [
      filter.id('snapshot-1'),
      filter.ids(['snapshot-1', 'snapshot-2']),
      filter.aggregateId('order-1'),
      filter.aggregateIds(['order-1', 'order-2']),
      filter.tenantId('tenant-1'),
      filter.ownerId('owner-1'),
      filter.spaceId('space-1'),
    ];

    expect(expressions).toEqual([
      { op: FilterOperator.ID, value: 'snapshot-1' },
      { op: FilterOperator.IDS, values: ['snapshot-1', 'snapshot-2'] },
      { op: FilterOperator.AGGREGATE_ID, value: 'order-1' },
      {
        op: FilterOperator.AGGREGATE_IDS,
        values: ['order-1', 'order-2'],
      },
      { op: FilterOperator.TENANT_ID, value: 'tenant-1' },
      { op: FilterOperator.OWNER_ID, value: 'owner-1' },
      { op: FilterOperator.SPACE_ID, value: 'space-1' },
    ]);
  });

  it.each([
    ['IDS', () => filter.ids([]), 'IDS values cannot be empty.'],
    [
      'AGGREGATE_IDS',
      () => filter.aggregateIds([]),
      'AGGREGATE_IDS values cannot be empty.',
    ],
  ])('rejects an empty %s array', (_name, create, message) => {
    expect(create).toThrow(message);
  });

  it.each<{
    name: string;
    create: () => unknown;
    expected: unknown;
  }>([
    {
      name: 'match all',
      create: () => filter.matchAll(),
      expected: { op: FilterOperator.MATCH_ALL },
    },
    {
      name: 'match none',
      create: () => filter.matchNone(),
      expected: { op: FilterOperator.MATCH_NONE },
    },
  ])('builds the $name wire shape', ({ create, expected }) => {
    expect(create()).toEqual(expected);
  });

  it.each([
    ['empty ids', () => filter.ids([])],
    [
      'non-string aggregate ID',
      () => Reflect.apply(filter.aggregateId, null, [1]),
    ],
    [
      'non-string aggregate IDs value',
      () => Reflect.apply(filter.aggregateIds, null, [['order-1', 2]]),
    ],
    [
      'invalid deletion state',
      () => Reflect.apply(filter.deletion, null, ['INVALID']),
    ],
  ])('rejects %s', (_name, create) => {
    expect(create).toThrow();
  });
});
