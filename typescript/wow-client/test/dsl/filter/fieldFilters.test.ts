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
 * `filter`'s field filters: equality, order, text, collections, ranges and
 * presence.
 */

import { filter, FilterOperator, StringComparison } from '../../../src/dsl.js';
import { describe, expect, expectTypeOf, it } from 'vitest';

describe('filter', () => {
  it('types equality filters with scalar values only', () => {
    const assertCanonicalEqualityValues = () => {
      // @ts-expect-error Canonical Wow equality filters do not accept arrays.
      filter.eq('status', ['PAID']);
      // @ts-expect-error Canonical Wow equality filters do not accept arrays.
      filter.ne('status', ['PAID']);
    };
    expectTypeOf(assertCanonicalEqualityValues).toBeFunction();
  });

  it('uses QueryField terminology in validation errors', () => {
    expect(() => filter.eq('bad field', 'value')).toThrow(
      'Query field is invalid: [bad field].',
    );
  });

  it('uses explicit string comparison semantics', () => {
    expect(
      filter.contains('state.name', 'wow', StringComparison.CASE_INSENSITIVE),
    ).toEqual({
      op: FilterOperator.CONTAINS,
      field: 'state.name',
      value: 'wow',
      stringComparison: StringComparison.CASE_INSENSITIVE,
    });
  });

  it('builds operand-free empty string filters', () => {
    expect([
      filter.isEmptyString('state.name'),
      filter.isNotEmptyString('state.name'),
    ]).toEqual([
      { op: 'is-empty-string', field: 'state.name' },
      { op: 'is-not-empty-string', field: 'state.name' },
    ]);
  });

  it.each([
    ['IN', () => filter.isIn('status', []), 'IN values cannot be empty.'],
    [
      'NOT_IN',
      () => filter.notIn('status', []),
      'NOT_IN values cannot be empty.',
    ],
    [
      'CONTAINS_ALL',
      () => filter.containsAll('tags', []),
      'CONTAINS_ALL values cannot be empty.',
    ],
  ])('rejects an empty %s array', (_name, create, message) => {
    expect(create).toThrow(message);
  });

  it('supports scalar equality values and query fields', () => {
    expect(filter.eq('@metadata.tags', 'wow')).toEqual({
      op: FilterOperator.EQ,
      field: '@metadata.tags',
      value: 'wow',
    });
    expect(filter.eq('state.@metadata.tags', 'wow')).toEqual({
      op: FilterOperator.EQ,
      field: 'state.@metadata.tags',
      value: 'wow',
    });
    expect(filter.ne('state.status', 'CANCELLED')).toEqual({
      op: FilterOperator.NE,
      field: 'state.status',
      value: 'CANCELLED',
    });
  });

  it('rejects array equality values unsupported by canonical Wow REST', () => {
    expect(() => Reflect.apply(filter.eq, null, ['status', ['PAID']])).toThrow(
      'Filter value must be a JSON scalar.',
    );
    expect(() => Reflect.apply(filter.ne, null, ['status', ['PAID']])).toThrow(
      'Filter value must be a JSON scalar.',
    );
  });

  it.each<{
    name: string;
    create: () => unknown;
    expected: unknown;
  }>([
    {
      name: 'greater than or equal',
      create: () => filter.gte('score', 80),
      expected: { op: FilterOperator.GTE, field: 'score', value: 80 },
    },
    {
      name: 'less than',
      create: () => filter.lt('score', 100),
      expected: { op: FilterOperator.LT, field: 'score', value: 100 },
    },
    {
      name: 'less than or equal',
      create: () => filter.lte('score', 100),
      expected: { op: FilterOperator.LTE, field: 'score', value: 100 },
    },
    {
      name: 'starts with',
      create: () => filter.startsWith('name', 'Wow'),
      expected: {
        op: FilterOperator.STARTS_WITH,
        field: 'name',
        value: 'Wow',
        stringComparison: StringComparison.CASE_SENSITIVE,
      },
    },
    {
      name: 'ends with',
      create: () =>
        filter.endsWith('name', 'Wow', StringComparison.CASE_INSENSITIVE),
      expected: {
        op: FilterOperator.ENDS_WITH,
        field: 'name',
        value: 'Wow',
        stringComparison: StringComparison.CASE_INSENSITIVE,
      },
    },
    {
      name: 'in',
      create: () => filter.isIn('status', ['PAID', 'SHIPPED']),
      expected: {
        op: FilterOperator.IN,
        field: 'status',
        values: ['PAID', 'SHIPPED'],
      },
    },
    {
      name: 'not in',
      create: () => filter.notIn('status', ['CANCELLED']),
      expected: {
        op: FilterOperator.NOT_IN,
        field: 'status',
        values: ['CANCELLED'],
      },
    },
    {
      name: 'between',
      create: () => filter.between('score', 60, 100),
      expected: {
        op: FilterOperator.BETWEEN,
        field: 'score',
        lowerBound: 60,
        upperBound: 100,
      },
    },
    {
      name: 'contains all',
      create: () => filter.containsAll('tags', ['wow', 'cqrs']),
      expected: {
        op: FilterOperator.CONTAINS_ALL,
        field: 'tags',
        values: ['wow', 'cqrs'],
      },
    },
    {
      name: 'is empty',
      create: () => filter.isEmpty('tags'),
      expected: { op: FilterOperator.IS_EMPTY, field: 'tags' },
    },
    {
      name: 'is null',
      create: () => filter.isNull('deletedAt'),
      expected: { op: FilterOperator.IS_NULL, field: 'deletedAt' },
    },
    {
      name: 'is not null',
      create: () => filter.isNotNull('createdAt'),
      expected: { op: FilterOperator.IS_NOT_NULL, field: 'createdAt' },
    },
    {
      name: 'exists',
      create: () => filter.exists('metadata.owner'),
      expected: { op: FilterOperator.EXISTS, field: 'metadata.owner' },
    },
    {
      name: 'not exists',
      create: () => filter.notExists('metadata.owner'),
      expected: { op: FilterOperator.NOT_EXISTS, field: 'metadata.owner' },
    },
  ])('builds the $name wire shape', ({ create, expected }) => {
    expect(create()).toEqual(expected);
  });

  it.each([
    ['empty collection values', () => filter.isIn('status', [])],
    ['invalid query field', () => filter.eq('bad field', 'value')],
    [
      'non-string query field',
      () => Reflect.apply(filter.eq, null, [1, 'value']),
    ],
    [
      'object equality value',
      () => Reflect.apply(filter.eq, null, ['status', {}]),
    ],
    [
      'object equality array value',
      () => Reflect.apply(filter.eq, null, ['status', ['PAID', {}]]),
    ],
    [
      'null comparison value',
      () => Reflect.apply(filter.gt, null, ['score', null]),
    ],
    ['non-finite value', () => filter.gt('score', Number.POSITIVE_INFINITY)],
    [
      'null collection value',
      () => Reflect.apply(filter.isIn, null, ['status', [null]]),
    ],
    [
      'non-string string operand',
      () => Reflect.apply(filter.contains, null, ['name', 1]),
    ],
    [
      'invalid string comparison',
      () => Reflect.apply(filter.contains, null, ['name', 'wow', 'INVALID']),
    ],
  ])('rejects %s', (_name, create) => {
    expect(create).toThrow();
  });
});
