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
 * `filter`'s logical filters, `and`, `or`, `nor`, and `elementMatch` with the
 * element scope it sets.
 */

import {
  DeletionState,
  filter,
  FilterOperator,
  type ElementFilterExpression,
} from '../../../src/dsl.js';
import { describe, expect, expectTypeOf, it } from 'vitest';

describe('filter', () => {
  it('accepts readonly arrays for logical builders', () => {
    const operands = [
      filter.eq('status', 'PAID'),
      filter.isNotNull('paidAt'),
    ] as const;

    expect([
      filter.and(operands),
      filter.or(operands),
      filter.nor(operands),
    ]).toEqual([
      { op: 'and', operands },
      { op: 'or', operands },
      { op: 'nor', operands },
    ]);
    expect(filter.and(operands).operands).not.toBe(operands);
  });

  it.each([
    ['AND', () => filter.and([]), 'AND operands cannot be empty.'],
    ['OR', () => filter.or([]), 'OR operands cannot be empty.'],
    ['NOR', () => filter.nor([]), 'NOR operands cannot be empty.'],
  ])('rejects an empty %s array', (_name, create, message) => {
    expect(create).toThrow(message);
  });

  it.each<{
    name: string;
    create: () => unknown;
    expected: unknown;
  }>([
    {
      name: 'or',
      create: () => filter.or([filter.eq('status', 'PAID')]),
      expected: {
        op: FilterOperator.OR,
        operands: [{ op: FilterOperator.EQ, field: 'status', value: 'PAID' }],
      },
    },
    {
      name: 'nor',
      create: () => filter.nor([filter.ne('status', null)]),
      expected: {
        op: FilterOperator.NOR,
        operands: [{ op: FilterOperator.NE, field: 'status', value: null }],
      },
    },
    {
      name: 'nested element match',
      create: () =>
        filter.elementMatch(
          'groups',
          filter.elementMatch('items', filter.eq('sku', 'product-1')),
        ),
      expected: {
        op: FilterOperator.ELEMENT_MATCH,
        field: 'groups',
        predicate: {
          op: FilterOperator.ELEMENT_MATCH,
          field: 'items',
          predicate: {
            op: FilterOperator.EQ,
            field: 'sku',
            value: 'product-1',
          },
        },
      },
    },
  ])('builds the $name wire shape', ({ create, expected }) => {
    expect(create()).toEqual(expected);
  });

  it('restricts element predicates recursively', () => {
    const predicate = filter.and([
      filter.eq('sku', 'product-1'),
      filter.gt('quantity', 0),
    ]);

    expectTypeOf(predicate).toMatchTypeOf<
      ElementFilterExpression<'sku' | 'quantity'>
    >();
    expect(filter.elementMatch('state.items', predicate)).toEqual({
      op: FilterOperator.ELEMENT_MATCH,
      field: 'state.items',
      predicate,
    });

    const invalidElementPredicates = () => {
      // @ts-expect-error DELETION cannot be scoped to an array element.
      filter.elementMatch('state.items', filter.deletion(DeletionState.ACTIVE));
      // @ts-expect-error SEARCH cannot be scoped to an array element.
      filter.elementMatch('state.items', filter.search('wow'));
      // @ts-expect-error Metadata filters cannot be scoped to an array element.
      filter.elementMatch('state.items', filter.id('snapshot-1'));
      filter.elementMatch(
        'state.items',
        // @ts-expect-error Unsupported filters remain invalid inside logical predicates.
        filter.and([filter.eq('sku', 'product-1'), filter.search('wow')]),
      );
    };
    expectTypeOf(invalidElementPredicates).toBeFunction();
  });

  it('rejects unsupported element predicates at runtime', () => {
    const deletion = filter.deletion(DeletionState.ACTIVE);
    const nestedSearch = filter.and([
      filter.eq('sku', 'product-1'),
      filter.search('wow'),
    ]);
    const metadata = filter.id('snapshot-1');

    expect(() =>
      filter.elementMatch(
        'state.items',
        deletion as unknown as ElementFilterExpression,
      ),
    ).toThrow();
    expect(() =>
      filter.elementMatch(
        'state.items',
        nestedSearch as unknown as ElementFilterExpression,
      ),
    ).toThrow();
    expect(() =>
      filter.elementMatch(
        'state.items',
        metadata as unknown as ElementFilterExpression,
      ),
    ).toThrow();
  });

  it.each([
    ['empty logical operands', () => filter.and([])],
    [
      'undefined AND operand',
      () => Reflect.apply(filter.and, null, [[undefined]]),
    ],
    ['null OR operand', () => Reflect.apply(filter.or, null, [[null]])],
    [
      'undefined NOR operand',
      () => Reflect.apply(filter.nor, null, [[undefined]]),
    ],
  ])('rejects %s', (_name, create) => {
    expect(create).toThrow();
  });
});

describe('element scope', () => {
  it('refuses an empty or null operand list inside an element predicate', () => {
    expect(() =>
      filter.elementMatch('items', {
        op: FilterOperator.AND,
        operands: [],
      } as never),
    ).toThrow('AND operands cannot be empty.');
    expect(() =>
      filter.elementMatch('items', {
        op: FilterOperator.OR,
        operands: [null],
      } as never),
    ).toThrow('OR operands cannot contain null.');
  });
});
