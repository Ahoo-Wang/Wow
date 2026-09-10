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
import { filter, FilterOperator as Op } from '@ahoo-wang/fetcher-wow';
import { expect, it } from 'vitest';
import {
  compileFilterConfiguration,
  getFieldOperators,
  isSimpleFilter,
  newFilterNode,
} from '../src/filter/filterCore';
import type { FilterComponentConfig } from '../src/filter/filterModel';
import { compile, fields, node } from './fixtures/filterCore.js';

it('compiles repeated AND fields but keeps them out of simple mode, including unset values', () => {
  for (const value of [undefined, 2]) {
    const draft: FilterComponentConfig = {
      id: 'and',
      operator: Op.AND,
      operands: [
        {
          id: 'first',
          operator: Op.GTE,
          field: 'amount',
          component: { name: 'builtin', options: { showTime: true } },
          props: { value: 1 },
        },
        {
          id: 'second',
          operator: Op.LTE,
          field: 'amount',
          component: { name: 'builtin', options: { showTime: true } },
          props: { value },
        },
      ],
      component: { name: 'builtin', options: { showTime: true } },
      props: {},
    };
    expect(compile(draft)).toEqual({
      expression: filter.and([
        filter.gte('amount', 1),
        ...(value === undefined ? [] : [filter.lte('amount', value)]),
      ]),
      errors: [],
    });
    expect(isSimpleFilter(draft)).toBe(false);
  }
});

it('keeps OR/NOR branches and separate nested groups independent', () => {
  for (const [draft, expression] of [
    [
      node(
        Op.OR,
        undefined,
        {},
        {
          operands: [
            node(Op.EQ, 'amount', { value: 1 }),
            node(Op.EQ, 'amount', { value: 2 }),
          ],
        },
      ),
      filter.or([filter.eq('amount', 1), filter.eq('amount', 2)]),
    ] as const,
    [
      node(
        Op.NOR,
        undefined,
        {},
        {
          operands: [
            node(Op.EQ, 'amount', { value: 1 }),
            node(Op.EQ, 'amount', { value: 2 }),
          ],
        },
      ),
      filter.nor([filter.eq('amount', 1), filter.eq('amount', 2)]),
    ] as const,
    [
      node(
        Op.AND,
        undefined,
        {},
        {
          operands: [
            node(
              Op.AND,
              undefined,
              {},
              { operands: [node(Op.GTE, 'amount', { value: 1 })] },
            ),
            node(
              Op.AND,
              undefined,
              {},
              { operands: [node(Op.LTE, 'amount', { value: 2 })] },
            ),
          ],
        },
      ),
      filter.and([
        filter.and([filter.gte('amount', 1)]),
        filter.and([filter.lte('amount', 2)]),
      ]),
    ] as const,
    [
      node(
        Op.AND,
        undefined,
        {},
        {
          operands: [
            node(Op.EQ, 'amount', { value: 1 }),
            node(
              Op.ELEMENT_MATCH,
              'items',
              {},
              { predicate: node(Op.EQ, 'quantity', { value: 1 }) },
            ),
          ],
        },
      ),
      filter.and([
        filter.eq('amount', 1),
        filter.elementMatch('items', filter.eq('quantity', 1)),
      ]),
    ] as const,
  ])
    expect(compile(draft)).toEqual({
      expression,
      errors: [],
    });
  const element = filter.elementMatch(
    'items',
    filter.and([filter.eq('quantity', 1), filter.eq('quantity', 2)]),
  );
  expect(
    compile(
      node(
        Op.ELEMENT_MATCH,
        'items',
        {},
        {
          predicate: node(
            Op.AND,
            undefined,
            {},
            {
              operands: [
                node(Op.EQ, 'quantity', { value: 1 }),
                node(Op.EQ, 'quantity', { value: 2 }),
              ],
            },
          ),
        },
      ),
    ),
  ).toEqual({
    expression: element,
    errors: [],
  });
});

it('creates unset values and explicitly empty groups', () => {
  expect(compile(newFilterNode(Op.EQ, 'amount')).expression).toEqual(
    filter.matchAll(),
  );
  expect(newFilterNode(Op.AND).operands).toEqual([]);
  expect(newFilterNode(Op.ELEMENT_MATCH, 'items').predicate?.operands).toEqual(
    [],
  );
  expect(compile(newFilterNode(Op.AND)).errors).not.toEqual([]);
  expect(compile(newFilterNode(Op.ELEMENT_MATCH, 'items')).errors).not.toEqual(
    [],
  );
});

it.each([Op.OR, Op.NOR, Op.AND])(
  'prunes inactive %s children before composing, retaining wrappers',
  op => {
    const draft = node(
      op,
      undefined,
      {},
      {
        operands: [
          node(Op.EQ, 'amount'),
          node(Op.EQ, 'enabled', { value: false }),
        ],
      },
    );
    expect(compile(draft).expression).toEqual({
      op,
      operands: [filter.eq('enabled', false)],
    });
    draft.operands!.pop();
    expect(compile(draft).expression).toEqual(filter.matchAll());
  },
);

it('prunes wholly inactive nested groups and element predicates', () => {
  const draft = node(
    Op.OR,
    undefined,
    {},
    {
      operands: [
        node(
          Op.ELEMENT_MATCH,
          'items',
          {},
          {
            predicate: node(
              Op.AND,
              undefined,
              {},
              { operands: [node(Op.EQ, 'quantity')] },
            ),
          },
        ),
        node(Op.NOR, undefined, {}, { operands: [node(Op.EQ, 'amount')] }),
        node(Op.EQ, 'name', { value: 'a' }),
      ],
    },
  );
  expect(compile(draft).expression).toEqual(
    filter.or([filter.eq('name', 'a')]),
  );
});

it('validates fields, capabilities and element relative scope even for unset values', () => {
  for (const draft of [
    node(Op.EQ, 'missing'),
    node(Op.EQ),
    node('BOGUS' as Op),
    node(Op.CONTAINS, 'amount', { value: 'x' }),
    node(
      Op.ELEMENT_MATCH,
      'items',
      {},
      { predicate: node(Op.EQ, 'amount', { value: 1 }) },
    ),
    node(
      Op.ELEMENT_MATCH,
      'items',
      {},
      { predicate: node(Op.ID, undefined, { value: 'x' }) },
    ),
  ])
    expect(compile(draft).errors).not.toEqual([]);
  expect(
    compileFilterConfiguration(
      { mode: 'advanced', root: node(Op.EQ, 'amount') },
      fields,
      [Op.NE],
    ).errors,
  ).not.toEqual([]);
  expect(
    compile(node(Op.EQ, 'amount'), [
      { field: 'amount', label: '金额', type: 'number', operators: [Op.GT] },
    ]).errors,
  ).not.toEqual([]);
  expect(
    compile(node(Op.SEARCH, undefined, { query: 'x', fields: ['missing'] }))
      .errors,
  ).not.toEqual([]);
  expect(getFieldOperators(fields[1])).not.toContain(Op.CONTAINS);
  expect(getFieldOperators({ ...fields[1], operators: [Op.EQ] })).toEqual([
    Op.EQ,
  ]);
});

it('recognizes root match-all, field predicates and flat AND as simple', () => {
  expect(isSimpleFilter(newFilterNode(Op.MATCH_ALL))).toBe(true);
  expect(isSimpleFilter(node(Op.EQ, 'amount'))).toBe(true);
  expect(
    isSimpleFilter(
      node(
        Op.AND,
        undefined,
        {},
        { operands: [node(Op.EQ, 'amount'), node(Op.EQ, 'name')] },
      ),
    ),
  ).toBe(true);
  for (const draft of [
    node(Op.MATCH_NONE),
    node(Op.ID),
    node(Op.OR, undefined, {}, { operands: [node(Op.EQ, 'amount')] }),
    node(
      Op.AND,
      undefined,
      {},
      {
        operands: [
          node(Op.AND, undefined, {}, { operands: [node(Op.EQ, 'amount')] }),
        ],
      },
    ),
    node(Op.EQ),
  ])
    expect(isSimpleFilter(draft)).toBe(false);
});

it('recognizes recursive element scopes and enforces field uniqueness within each scope', () => {
  const element = (predicate: FilterComponentConfig) => ({
    ...newFilterNode(Op.ELEMENT_MATCH, 'items'),
    predicate,
  });
  const and = (...operands: FilterComponentConfig[]) => ({
    ...newFilterNode(Op.AND),
    operands,
  });
  const nested = element(
    and(node(Op.EQ, 'quantity'), element(node(Op.EQ, 'quantity'))),
  );
  expect(isSimpleFilter(and(node(Op.EQ, 'quantity'), nested))).toBe(true);
  expect(isSimpleFilter(newFilterNode(Op.ELEMENT_MATCH, 'items'))).toBe(true);
  expect(isSimpleFilter(newFilterNode(Op.AND))).toBe(false);
  for (const predicate of [
    and(node(Op.GTE, 'quantity'), node(Op.LTE, 'quantity')),
    { ...and(node(Op.EQ, 'quantity')), operator: Op.OR },
    { ...and(node(Op.EQ, 'quantity')), operator: Op.NOR },
    and(and(node(Op.EQ, 'quantity'))),
    node(Op.MATCH_ALL),
  ])
    expect(isSimpleFilter(element(predicate))).toBe(false);
  expect(isSimpleFilter(and(nested, element(node(Op.EQ, 'quantity'))))).toBe(
    false,
  );
});
