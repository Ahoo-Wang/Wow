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

import {
  filter,
  FilterOperator as Op,
  type FilterExpression,
} from '@ahoo-wang/fetcher-wow';
import { expect, it } from 'vitest';
import { compileFilterConfiguration } from '../src/filter/filterCore.js';
import {
  createFilterConfiguration,
  validateFilterConfiguration,
} from '../src/filter/filterConfiguration.js';
import { transitionFilterOperator } from '../src/filter/filterDraftTransitions.js';
import { dateTimeValue } from '../src/filter/filterDateTimeValue.js';
import {
  readFilterOptions,
  readResolvedFilterOptions,
} from '../src/filter/filterOptionSource.js';
import type { FilterComponentConfig } from '../src/filter/filterModel.js';
import { fields } from './fixtures/filterCore.js';

it('validates builtin tree scope without widening malformed or unauthorized conditions', () => {
  const invalid: FilterComponentConfig[] = [
    {
      id: 'missing-field',
      operator: Op.EQ,
      field: 'missing',
      component: { name: 'builtin', options: { showTime: true } },
      props: { value: 1 },
    },
    {
      id: 'unsupported',
      operator: Op.CONTAINS,
      field: 'amount',
      component: { name: 'builtin', options: { showTime: true } },
      props: { value: '1' },
    },
    {
      id: 'empty-group',
      operator: Op.AND,
      operands: [],
      component: { name: 'builtin', options: { showTime: true } },
      props: {},
    },
    {
      id: 'missing-predicate',
      operator: Op.ELEMENT_MATCH,
      field: 'items',
      component: { name: 'builtin', options: { showTime: true } },
      props: {},
    },
    {
      id: 'element',
      operator: Op.ELEMENT_MATCH,
      field: 'items',
      predicate: {
        id: 'wrong-scope',
        operator: Op.OWNER_ID,
        component: { name: 'builtin', options: { showTime: true } },
        props: { value: 'alice' },
      },
      component: { name: 'builtin', options: { showTime: true } },
      props: {},
    },
  ];
  for (const draft of invalid) {
    const result = compileFilterConfiguration(
      { mode: 'advanced', root: draft },
      fields,
    );
    expect(result.expression).toBeUndefined();
    expect(result.errors).toHaveLength(1);
    expect(result.errors[0].id).toBe(draft.predicate?.id ?? draft.id);
  }
  expect(
    compileFilterConfiguration(
      {
        mode: 'advanced',
        root: {
          id: 'forbidden',
          operator: Op.GT,
          field: 'amount',
          component: { name: 'builtin', options: { showTime: true } },
          props: { value: 1 },
        },
      },
      fields,
      [Op.EQ],
    ).errors[0].message,
  ).toContain('不允许');
  const element: FilterComponentConfig = {
    id: 'element',
    operator: Op.ELEMENT_MATCH,
    field: 'items',
    predicate: {
      id: 'quantity',
      operator: Op.GTE,
      field: 'quantity',
      component: { name: 'builtin', options: { showTime: true } },
      props: { value: 2 },
    },
    component: { name: 'builtin', options: { showTime: true } },
    props: {},
  };
  expect(
    compileFilterConfiguration({ mode: 'advanced', root: element }, fields),
  ).toEqual({
    expression: filter.elementMatch('items', filter.gte('quantity', 2)),
    errors: [],
  });
  delete element.predicate!.props.value;
  expect(
    compileFilterConfiguration({ mode: 'advanced', root: element }, fields),
  ).toEqual({
    expression: filter.matchAll(),
    errors: [],
  });
  expect(
    compileFilterConfiguration(
      {
        mode: 'advanced',
        root: {
          id: 'unset',
          operator: Op.AND,
          operands: [
            {
              id: 'value',
              operator: Op.EQ,
              field: 'amount',
              component: { name: 'builtin', options: { showTime: true } },
              props: {},
            },
          ],
          component: { name: 'builtin', options: { showTime: true } },
          props: {},
        },
      },
      fields,
    ),
  ).toEqual({ expression: filter.matchAll(), errors: [] });
});

it.each([
  { op: Op.AND, operands: 'wrong' },
  { op: Op.AND },
  { op: Op.IN, field: 'amount' },
  { op: Op.SEARCH, query: 'order', fields: 'amount' },
])(
  'rejects malformed remote expressions before constructing an editor: %j',
  expression => {
    expect(() =>
      parseFilterOutput(expression as unknown as FilterExpression),
    ).toThrow();
  },
);

it('rejects malformed saved component structure and validates the supplied field scope', () => {
  const valid = createFilterConfiguration({
    id: 'amount',
    operator: Op.EQ,
    field: 'amount',
    component: { name: 'builtin', options: { showTime: true } },
    props: { value: 1 },
  });
  for (const patch of [
    { unexpected: true },
    { id: '' },
    { component: { name: ' ' } },
    { field: 'missing' },
  ]) {
    expect(() =>
      validateFilterConfiguration(
        { ...valid, root: { ...valid.root, ...patch } },
        fields,
      ),
    ).toThrow();
  }
  expect(() =>
    createFilterConfiguration({
      id: 'items',
      operator: Op.ELEMENT_MATCH,
      field: 'items',
      component: { name: 'builtin', options: { showTime: true } },
      props: {},
    }),
  ).toThrow('请补全元素条件');
  expect(() =>
    validateFilterConfiguration({ ...valid, mode: 'unsupported' }, fields),
  ).toThrow('筛选模式无效');
  expect(() =>
    validateFilterConfiguration(
      { ...valid, root: { ...valid.root, operator: Op.MATCH_ALL, props: {} } },
      fields,
    ),
  ).toThrow('根级操作不能绑定字段');
  expect(() =>
    validateFilterConfiguration(
      {
        mode: 'advanced',
        root: {
          id: 'group',
          operator: Op.AND,
          operands: [],
          component: { name: 'custom' },
          props: {},
        },
      },
      fields,
    ),
  ).toThrow('条件容器必须使用 builtin 组件');
  expect(() => validateFilterConfiguration(valid, fields)).not.toThrow();
});

it.each([
  {
    id: 'select',
    operator: Op.BETWEEN,
    field: 'amount',
    component: { name: 'select' },
    props: { lowerBound: 1, upperBound: 2 },
  },
  {
    id: 'range',
    operator: Op.BETWEEN,
    field: 'name',
    component: { name: 'datetime-range' },
    props: { lowerBound: 'a', upperBound: 'z' },
  },
  {
    id: 'remote',
    operator: Op.EQ,
    field: 'name',
    component: { name: 'remote-select' },
    props: { value: 'a' },
  },
])(
  'fails closed when a saved builtin component is incompatible: $id',
  draft => {
    const result = compileFilterConfiguration(
      { mode: 'advanced', root: draft },
      fields,
    );
    expect(result.expression).toBeUndefined();
    expect(result.errors).toEqual([
      { id: draft.id, message: expect.any(String) },
    ]);
  },
);

it.each([
  {
    id: 'typed',
    operator: Op.EQ,
    field: 'items',
    component: { name: 'builtin', options: { showTime: true } },
    props: { value: { type: 'number', value: 1, extra: true } },
  },
  {
    id: 'object',
    operator: Op.EQ,
    field: 'items',
    component: { name: 'builtin', options: { showTime: true } },
    props: { value: { raw: 'unsupported' } },
  },
  {
    id: 'datetime',
    operator: Op.EQ,
    field: 'created',
    component: { name: 'builtin', options: { showTime: true } },
    props: { value: '2026-09-09T12:00:00Z' },
  },
])(
  'keeps malformed scalar buffers invalid rather than interpreting them differently: $id',
  draft => {
    expect(
      compileFilterConfiguration({ mode: 'advanced', root: draft }, fields)
        .expression,
    ).toBeUndefined();
    expect(
      compileFilterConfiguration({ mode: 'advanced', root: draft }, fields)
        .errors,
    ).toHaveLength(1);
  },
);

it('preserves range/time buffers and component metadata on a same-operator transition', () => {
  for (const draft of [
    {
      id: 'range',
      operator: Op.BETWEEN,
      field: 'amount',
      component: { name: 'builtin', options: { showTime: true } },
      props: { lowerBound: 0, upperBound: 2 },
    },
    {
      id: 'time',
      operator: Op.BEFORE_TODAY,
      field: 'created',
      component: { name: 'builtin', options: { showTime: true } },
      props: { time: '09:12:34', zoneId: 'UTC' },
    },
  ]) {
    const configured = {
      ...draft,
      editor: { name: 'builtin' },
      props: { label: 'retained' },
    };
    expect(transitionFilterOperator(configured, draft.operator)).toEqual(
      configured,
    );
  }
});

it('rejects malformed option metadata and resolve envelopes without losing valid typed IDs', () => {
  for (const item of [
    null,
    { value: true, label: 'yes' },
    { value: 1, label: 2 },
    { value: 1, label: 'one', group: 1 },
    { value: 1, label: 'one', disabled: 'false' },
  ]) {
    expect(() => readFilterOptions([item])).toThrow();
  }
  for (const response of [
    null,
    { list: [] },
    { list: [], missing: '1' },
    { list: [], missing: [true] },
  ]) {
    expect(() => readResolvedFilterOptions(response, [1])).toThrow();
  }
  expect(
    readResolvedFilterOptions(
      { list: [{ value: 1, label: 'one' }], missing: ['1'] },
      [1, '1'],
    ),
  ).toEqual({ list: [{ value: 1, label: 'one' }], missing: ['1'] });
});

it('preserves an invalid zoned timestamp for correction and renders local timestamps in local time', () => {
  expect(dateTimeValue(0, 'Invalid/Zone')).toEqual({ date: '0' });
  const value = new Date(2026, 8, 9, 12, 34, 56).getTime();
  expect(dateTimeValue(value)).toEqual({
    date: '2026-09-09',
    time: '12:34:56',
    offsetMinutes: new Date(value).getTimezoneOffset(),
  });
});

import { parseFilterOutput } from '../src/filter/filterProtocol.js';
