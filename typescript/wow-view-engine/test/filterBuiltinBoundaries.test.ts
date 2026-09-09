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

import { FilterOperator as Op, filter } from '@ahoo-wang/fetcher-wow';
import { expect, it } from 'vitest';
import { compileBuiltinFilter } from '../src/filter/filterBuiltinCompiler.js';
import { compileFilterConfiguration } from '../src/filter/filterCore.js';
import type {
  FilterComponentProperties,
  FilterCompilerContext,
} from '../src/filter/filterModel.js';
import { fields, node } from './fixtures/filterCore.js';

const dateField = {
  field: 'created',
  label: '时间',
  type: 'datetime' as const,
};
const dateContext: FilterCompilerContext = {
  field: dateField,
  fields: [dateField],
  operator: Op.EQ,
  timeZone: 'UTC',
};

it('rejects malformed saved collection values rather than querying all records', () => {
  for (const values of [1, '1,2', {}, [undefined]]) {
    const result = compileFilterConfiguration(
      {
        mode: 'advanced',
        root: node(Op.IN, 'amount', { values } as FilterComponentProperties),
      },
      fields,
    );
    expect(result.expression).toBeUndefined();
    expect(result.errors).toEqual([
      { id: expect.any(String), message: expect.any(String) },
    ]);
  }
});

it('rejects invalid datetime configuration and out-of-range timestamps at compilation', () => {
  for (const [props, context] of [
    [{ value: 0 }, { ...dateContext, options: { showTime: 'yes' } }],
    [
      { value: 8_640_000_000_001_000 },
      { ...dateContext, options: { showTime: true } },
    ],
    [{ value: true }, dateContext],
    [{ value: { date: '2026-09-09', unknown: 'extra' } }, dateContext],
    [
      { value: '2026-09-09' },
      { ...dateContext, field: { ...dateField, operators: [Op.BETWEEN] } },
    ],
  ] as [FilterComponentProperties, FilterCompilerContext][]) {
    expect(() => compileBuiltinFilter(props, context)).toThrow();
  }
});

it('rejects the skipped Apia calendar day without shifting the selected date', () => {
  const context = { ...dateContext, timeZone: 'Pacific/Apia' };
  expect(() =>
    compileBuiltinFilter({ value: { date: '2011-12-30' } }, context),
  ).toThrow('日期在指定时区不存在');
  expect(
    compileBuiltinFilter({ value: { date: '2011-12-31' } }, context),
  ).toEqual(
    filter.between(
      'created',
      Date.parse('2011-12-30T10:00:00Z'),
      Date.parse('2011-12-31T09:59:59.999Z'),
    ),
  );
});

it('keeps unset calendar-day controls distinct from incomplete ranges', () => {
  expect(compileBuiltinFilter({}, dateContext)).toBeUndefined();
  expect(compileBuiltinFilter({ value: null }, dateContext)).toEqual(
    filter.eq('created', null),
  );
  expect(
    compileBuiltinFilter({ values: [] }, { ...dateContext, operator: Op.IN }),
  ).toBeUndefined();
  expect(() =>
    compileBuiltinFilter(
      { lowerBound: { date: '2026-09-09' } },
      { ...dateContext, operator: Op.BETWEEN },
    ),
  ).toThrow('请补全范围上下界');
});
