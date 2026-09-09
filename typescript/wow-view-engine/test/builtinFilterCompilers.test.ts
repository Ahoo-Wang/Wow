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

import { expect, it } from 'vitest';
import { FilterOperator as Op } from '@ahoo-wang/fetcher-wow';
import { getBuiltinFilterCompiler } from '../src/filter/builtinFilterCompilers.js';
import { compileFilterConfiguration } from '../src/filter/filterConfigurationCompiler.js';
import {
  readFilterOptionPage,
  readResolvedFilterOptions,
} from '../src/filter/filterOptionSource.js';
const fields = [{ field: 'id', label: 'ID' }];
const context = { operator: Op.IN, field: fields[0], fields };
it('compiles typed IDs while labels remain presentation-only', () => {
  const compiler = getBuiltinFilterCompiler('multi-select')!;
  const props = {
    values: [1, '1', 1],
    selectedOptions: [
      { value: 1, label: '数字' },
      { value: '1', label: '文本' },
    ],
  };
  expect(compiler.compile(props, context)).toEqual({
    op: Op.IN,
    field: 'id',
    values: [1, '1'],
  });
  expect(compiler.compile({ values: [] }, context)).toBeUndefined();
  expect(compiler.clear!(props, context)).toEqual({});
  expect(() => compiler.compile({ values: [NaN] }, context)).toThrow();
  expect(getBuiltinFilterCompiler('toString')).toBeUndefined();
});
it('validates remote pages and distinguishes missing IDs from incomplete responses', () => {
  expect(
    readFilterOptionPage({
      list: [{ value: 0, label: '零' }],
      nextCursor: null,
    }).list[0].value,
  ).toBe(0);
  expect(() => readFilterOptionPage({ list: [], nextCursor: 2 })).toThrow();
  expect(() =>
    readResolvedFilterOptions({ list: [], missing: [] }, [1]),
  ).toThrow();
  expect(() =>
    readResolvedFilterOptions(
      { list: [{ value: 1, label: 'a' }], missing: [1] },
      [1],
    ),
  ).toThrow();
  expect(readResolvedFilterOptions({ list: [], missing: [1] }, [1])).toEqual({
    list: [],
    missing: [1],
  });
});
it('rejects partial, reversed and nonexistent date ranges', () => {
  const compiler = getBuiltinFilterCompiler('datetime-range')!;
  const fields = [{ field: 'date', label: '日期', type: 'date' as const }];
  const context = { operator: Op.BETWEEN, field: fields[0], fields };
  expect(compiler.compile({}, context)).toBeUndefined();
  expect(() =>
    compiler.compile({ lowerBound: '2026-01-01' }, context),
  ).toThrow();
  expect(() =>
    compiler.compile(
      { lowerBound: '2026-02-01', upperBound: '2026-01-01' },
      context,
    ),
  ).toThrow();
  expect(
    compiler.compile(
      { lowerBound: '2026-01-01', upperBound: '2026-02-01' },
      context,
    ),
  ).toMatchObject({ op: Op.BETWEEN });
});

it.each([
  { date: false, time: 0 },
  { date: 0, time: false },
  { date: null, time: null },
  { date: '', time: false },
  { date: false, time: '' },
])('rejects malformed empty datetime range endpoints: %j', lowerBound => {
  const result = compileFilterConfiguration(
    {
      mode: 'simple',
      root: {
        id: 'created',
        field: 'created',
        operator: Op.BETWEEN,
        component: { name: 'datetime-range', options: { showTime: true } },
        props: { lowerBound, upperBound: {} },
      },
    },
    [{ field: 'created', label: '创建', type: 'datetime' }],
  );
  expect(result.errors).toEqual([
    { id: 'created', message: expect.any(String) },
  ]);
  expect(result.expression).toBeUndefined();
});

it('accepts cleared datetime range endpoints and preserves zero timestamps', () => {
  const compiler = getBuiltinFilterCompiler('datetime-range')!;
  const fields = [
    { field: 'created', label: '创建', type: 'datetime' as const },
  ];
  const context = {
    operator: Op.BETWEEN,
    field: fields[0],
    fields,
    timeZone: 'Asia/Shanghai',
    options: { showTime: true },
  };
  expect(compiler.compile({}, context)).toBeUndefined();
  expect(
    compiler.compile({ lowerBound: '', upperBound: {} }, context),
  ).toBeUndefined();
  expect(
    compiler.compile(
      { lowerBound: { date: '', time: '' }, upperBound: {} },
      context,
    ),
  ).toBeUndefined();
  expect(
    compiler.compile({ lowerBound: 0, upperBound: 1000 }, context),
  ).toEqual({
    op: Op.BETWEEN,
    field: 'created',
    lowerBound: 0,
    upperBound: 1000,
  });
});

it('separates named component choices from field capabilities', async () => {
  const { getFieldOperators, getNamedFilterOperators } =
    await import('../src/filter/filterOperators.js');
  expect(getNamedFilterOperators('multi-select')).toEqual([Op.IN, Op.NOT_IN]);
  expect(getNamedFilterOperators('datetime-range')).toEqual([Op.BETWEEN]);
  const field = { field: 'id', label: 'ID', type: 'string' as const };
  expect(
    getFieldOperators({ ...field, editor: { name: 'multi-select' } }),
  ).toEqual(getFieldOperators(field));
  expect(
    getFieldOperators({
      ...field,
      editor: { name: 'multi-select' },
      operators: [Op.EQ],
    }),
  ).toEqual([Op.EQ]);
});
