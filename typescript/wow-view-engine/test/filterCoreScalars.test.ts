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
import type { FilterFieldDefinition } from '../src/filter/filterModel';
import { compile, node } from './fixtures/filterCore.js';

it.each([
  ['amount', null],
  ['amount', 0],
  ['enabled', false],
  ['name', ''],
  ['status', 0],
  ['status', false],
  ['status', ''],
] as const)('preserves explicit %s value %s', (field, value) => {
  expect(compile(node(Op.EQ, field, { value })).expression).toEqual({
    op: Op.EQ,
    field,
    value,
  });
});

it('coerces only explicit numeric editor drafts and leaves enum values typed', () => {
  expect(
    compile(
      node(Op.EQ, 'amount', { value: { type: 'number', value: '-1.25e2' } }),
    ).expression,
  ).toEqual(filter.eq('amount', -125));
  expect(
    compile(node(Op.IN, 'status', { values: [0, false, ''] })).expression,
  ).toEqual(filter.isIn('status', [0, false, '']));
  expect(compile(node(Op.EQ, 'status', { value: '0' })).errors).not.toEqual([]);
});

it.each([
  '',
  ' ',
  '-',
  '1.',
  '1e',
  '12abc',
  '0x10',
  'Infinity',
  Infinity,
  NaN,
  {},
  true,
])('rejects invalid number %s', value => {
  expect(
    compile(node(Op.EQ, 'amount', { value: { type: 'number', value } })).errors,
  ).not.toEqual([]);
});

it.each([
  [
    node(Op.EQ, 'amount', { value: '001' }, {}),
    { op: Op.EQ, field: 'amount', value: '001' },
  ] as const,
  [
    node(Op.IN, 'amount', { values: ['001', '002'] }, {}),
    { op: Op.IN, field: 'amount', values: ['001', '002'] },
  ] as const,
  [
    node(Op.BETWEEN, 'amount', { lowerBound: '001', upperBound: '002' }, {}),
    { op: Op.BETWEEN, field: 'amount', lowerBound: '001', upperBound: '002' },
  ] as const,
])(
  'does not reinterpret loaded strings as numbers for %j',
  (draft, expression) => {
    const before = structuredClone(draft);
    const result = compile(draft);
    expect(result.errors).not.toEqual([]);
    expect(result.expression).toBeUndefined();
    expect(draft).toEqual(before);
    expect(
      compile(draft, [{ field: 'amount', label: '编码', type: 'string' }])
        .expression,
    ).toStrictEqual(expression);
  },
);

it('converts explicit number wrappers for numeric collection entries', () => {
  expect(
    compile(
      node(Op.IN, 'amount', {
        values: [
          { type: 'number', value: '001' },
          { type: 'number', value: '0' },
        ],
      }),
    ).expression,
  ).toEqual({ op: Op.IN, field: 'amount', values: [1, 0] });
});

it('rejects partial, reversed, mixed-type and invalid ranges', () => {
  expect(compile(node(Op.BETWEEN, 'amount')).expression).toEqual(
    filter.matchAll(),
  );
  for (const bounds of [
    { lowerBound: 0 },
    { upperBound: 0 },
    { lowerBound: 2, upperBound: 1 },
    { lowerBound: '', upperBound: 2 },
    { lowerBound: null, upperBound: 2 },
  ]) {
    expect(compile(node(Op.BETWEEN, 'amount', bounds)).errors).not.toEqual([]);
  }
  expect(
    compile(
      node(Op.BETWEEN, 'amount', {
        lowerBound: { type: 'number', value: '0' },
        upperBound: { type: 'number', value: '2' },
      }),
    ).expression,
  ).toEqual(filter.between('amount', 0, 2));
});

it('treats cleared draft collections as unset but rejects incomplete entries and empty wire collections', () => {
  expect(compile(node(Op.IN, 'amount')).expression).toEqual(filter.matchAll());
  expect(compile(node(Op.IN, 'amount', { values: [] })).expression).toEqual(
    filter.matchAll(),
  );
  expect(() =>
    parseFilterOutput({ op: Op.IN, field: 'amount', values: [] }),
  ).toThrow();
  for (const values of [[1, undefined], [1, null], ['']])
    expect(compile(node(Op.IN, 'amount', { values })).errors).not.toEqual([]);
});

it('compiles typed scalar editor drafts while preserving their actual scalar types', () => {
  expect(
    compile(
      node(Op.CONTAINS_ALL, 'items', {
        values: [
          { type: 'number', value: '0' },
          { type: 'string', value: '0' },
          { type: 'boolean', value: false },
        ],
      }),
    ).expression,
  ).toEqual(filter.containsAll('items', [0, '0', false]));
  for (const value of [
    { type: 'number', value: '1e' },
    { type: 'number', value: '' },
    { type: 'boolean', value: 'false' },
    { type: 'object', value: '{}' },
  ])
    expect(
      compile(node(Op.CONTAINS_ALL, 'items', { values: [value] })).errors,
    ).not.toEqual([]);
});

it('keeps typed scalar selection when its value is cleared, but blocks partial collection entries', () => {
  for (const type of ['number', 'string', 'boolean']) {
    expect(
      compile(node(Op.EQ, 'items', { value: { type, value: undefined } }))
        .expression,
    ).toEqual(filter.matchAll());
    expect(
      compile(
        node(Op.CONTAINS_ALL, 'items', {
          values: [{ type, value: undefined }],
        }),
      ).errors,
    ).not.toEqual([]);
  }
  expect(
    compile(
      node(Op.EQ, 'items', { value: { type: 'unknown', value: undefined } }),
    ).errors,
  ).not.toEqual([]);
});

it('allows partial string matching on enum fields without allowing unknown equality values', () => {
  const enumFields: FilterFieldDefinition[] = [
    {
      field: 'name',
      label: '名称',
      type: 'string',
      options: [{ value: 'pending', label: '待处理' }],
    },
  ];
  expect(
    compile(node(Op.STARTS_WITH, 'name', { value: 'pend' }), enumFields)
      .expression,
  ).toEqual({ op: Op.STARTS_WITH, field: 'name', value: 'pend' });
  expect(
    compile(node(Op.EQ, 'name', { value: 'pend' }), enumFields).errors,
  ).not.toEqual([]);
});

import { parseFilterOutput } from '../src/filter/filterProtocol.js';
