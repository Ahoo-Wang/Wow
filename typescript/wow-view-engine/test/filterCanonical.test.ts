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
import { filter, FilterOperator as Op } from '@ahoo-wang/fetcher-wow';
import {
  newFilterNode,
  getFieldOperators,
  validateFilterConfiguration,
  createFilterConfiguration,
  compileFilterConfiguration,
  clearFilterValues,
} from '../src/filter/filterCore.js';
import { transitionFilterOperator } from '../src/filter/filterDraftTransitions.js';

it('uses only explicit components and props across compilation, clearing and transitions', () => {
  const root = { ...newFilterNode(Op.EQ, 'amount'), props: { value: 0 } };
  const fields = [
    {
      field: 'amount',
      label: 'Amount',
      type: 'number' as const,
      editor: { name: 'unused-default' },
    },
  ];
  const config = createFilterConfiguration(root);
  expect(compileFilterConfiguration(config, fields)).toEqual({
    expression: filter.eq('amount', 0),
    errors: [],
  });
  const cleared = clearFilterValues(root, fields);
  expect(cleared).toEqual({ ...root, props: {} });
  expect(root.props.value).toBe(0);
  expect(transitionFilterOperator(root, Op.NE)).toEqual({
    ...root,
    operator: Op.NE,
  });
  expect(transitionFilterOperator(root, Op.IN).props).toEqual({});
  expect(
    compileFilterConfiguration(createFilterConfiguration(cleared), fields)
      .expression,
  ).toEqual(filter.matchAll());
});

it('resolves defaults only at construction and preserves restored custom properties', () => {
  const field = {
    field: 'amount',
    label: 'Amount',
    type: 'number' as const,
    editor: { name: 'custom', options: { compact: false } },
  };
  const root = {
    ...newFilterNode(Op.EQ, field.field, resolveFilterComponent(Op.EQ, field)),
    props: { selected: 0, label: 'Zero' },
  };
  const configuration = createFilterConfiguration(root);
  const restored = createFilterConfiguration(
    JSON.parse(JSON.stringify(configuration)).root,
  );
  const compilers = {
    custom: {
      compile: (props: Readonly<FilterComponentProperties>) =>
        props.selected === undefined
          ? undefined
          : filter.eq('amount', props.selected as number),
      clear: () => ({ label: 'Zero' }),
    },
  };
  expect(
    compileFilterConfiguration(
      restored,
      [{ ...field, editor: { name: 'changed' } }],
      undefined,
      compilers,
    ).expression,
  ).toEqual(filter.eq('amount', 0));
  expect(restored).toEqual(configuration);
  expect(clearFilterValues(restored.root, [field], compilers)).toEqual({
    ...root,
    props: { label: 'Zero' },
  });
  expect(
    resolveFilterComponent(Op.ELEMENT_MATCH, { ...field, type: 'array' }),
  ).toEqual({ name: 'builtin' });
  expect(
    resolveFilterComponent(Op.EQ, undefined, {
      [Op.EQ]: { name: 'operator-default' },
    }),
  ).toEqual({ name: 'operator-default' });
});

it('clears element props in their own scope while retaining inactive control identities', () => {
  const child = {
    ...newFilterNode(Op.EQ, 'quantity', { name: 'custom' }),
    props: { selected: 1 },
  };
  const root = {
    ...newFilterNode(Op.ELEMENT_MATCH, 'items'),
    predicate: child,
  };
  const fields = [
    {
      field: 'items',
      label: 'Items',
      type: 'array' as const,
      fields: [
        { field: 'quantity', label: 'Quantity', type: 'number' as const },
      ],
    },
  ];
  const cleared = clearFilterValues(root, fields, {
    custom: {
      compile: () => undefined,
      clear: (_, context) => {
        expect(context.field?.field).toBe('quantity');
        expect(context.fields).toEqual(fields[0].fields);
        return {};
      },
    },
  });
  expect(cleared).toEqual({ ...root, predicate: { ...child, props: {} } });
  expect(root.predicate.props).toEqual({ selected: 1 });
});

import { resolveFilterComponent } from '../src/filter/filterConfiguration.js';
import type { FilterComponentProperties } from '../src/filter/filterModel.js';

it('keeps field capabilities independent of changed component defaults', () => {
  const field = {
    field: 'amount',
    label: 'Amount',
    type: 'number' as const,
    editor: { name: 'select' },
  };
  const root = { ...newFilterNode(Op.GT, field.field), props: { value: 1 } };
  const configuration = createFilterConfiguration(root);
  expect(() =>
    validateFilterConfiguration(configuration, [field]),
  ).not.toThrow();
  expect(getFieldOperators(field)).toContain(Op.GT);
  expect(compileFilterConfiguration(configuration, [field])).toEqual({
    expression: filter.gt('amount', 1),
    errors: [],
  });
  expect(
    compileFilterConfiguration(configuration, [
      { ...field, operators: [Op.EQ] },
    ]).errors,
  ).toHaveLength(1);
  expect(
    compileFilterConfiguration(
      createFilterConfiguration({ ...root, component: { name: 'select' } }),
      [field],
    ).errors[0].message,
  ).toBe('选择器不支持当前操作');
});

it('keeps saved calendar-day and custom output operators independent of new editor defaults', () => {
  const date = {
    field: 'created',
    label: 'Created',
    type: 'datetime' as const,
    editor: { name: 'datetime-range' },
  };
  const root = {
    ...newFilterNode(Op.GT, 'created'),
    props: { value: { date: '2026-09-09' } },
  };
  expect(
    compileFilterConfiguration(
      createFilterConfiguration(root),
      [date],
      undefined,
      undefined,
      'UTC',
    ),
  ).toEqual({
    expression: filter.gt('created', Date.parse('2026-09-09T23:59:59.999Z')),
    errors: [],
  });
  const amount = {
    field: 'amount',
    label: 'Amount',
    type: 'number' as const,
    editor: { name: 'select' },
  };
  const custom = {
    ...newFilterNode(Op.EQ, 'amount', { name: 'custom' }),
    props: { minimum: 1 },
  };
  expect(
    compileFilterConfiguration(
      createFilterConfiguration(custom),
      [amount],
      undefined,
      { custom: { compile: () => filter.gt('amount', 1) } },
    ),
  ).toEqual({ expression: filter.gt('amount', 1), errors: [] });
  expect(
    compileFilterConfiguration(
      createFilterConfiguration(custom),
      [{ ...amount, operators: [Op.EQ] }],
      undefined,
      { custom: { compile: () => filter.gt('amount', 1) } },
    ).errors,
  ).toHaveLength(1);
});
