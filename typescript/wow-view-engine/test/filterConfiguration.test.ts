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
import { FilterOperator as Op } from '@ahoo-wang/fetcher-wow';
import { expect, it, vi } from 'vitest';
import {
  createFilterConfiguration,
  compileFilterConfiguration,
  validateFilterConfiguration,
  clearFilterValues,
} from '../src/filter/filterConfiguration.js';
import type {
  FilterConfiguration,
  FilterComponentConfig,
} from '../src/filter/filterModel.js';
import { transitionFilterOperator } from '../src/filter/filterDraftTransitions.js';
import { fields, node } from './fixtures/filterCore.js';

it('round trips stable identities, unset controls, typed buffers and date/time attributes through JSON', () => {
  const draft: FilterComponentConfig = {
    id: 'group',
    operator: Op.AND,
    operands: [
      {
        id: 'unset',
        operator: Op.EQ,
        field: 'amount',
        component: { name: 'builtin', options: { showTime: true } },
        props: {},
      },
      {
        id: 'date',
        operator: Op.GTE,
        field: 'created',
        component: { name: 'builtin', options: { showTime: true } },
        props: {
          value: { date: '2026-09-08', time: '09:00', offsetMinutes: -480 },
        },
      },
      {
        id: 'typed',
        operator: Op.EQ,
        field: 'items',
        component: { name: 'builtin', options: { showTime: true } },
        props: { value: { type: 'number', value: undefined } },
      },
    ],
    component: { name: 'builtin', options: { showTime: true } },
    props: {},
  };
  const config = createFilterConfiguration(draft, 'advanced');
  const reloaded = JSON.parse(JSON.stringify(config)) as FilterConfiguration;
  expect(reloaded.root.operands?.map(node => node.id)).toEqual([
    'unset',
    'date',
    'typed',
  ]);
  expect(reloaded.root.operands?.[0].props).toEqual({});
  expect(reloaded.root.operands?.[1].props.value).toEqual(
    draft.operands?.[1].props.value,
  );
  expect(reloaded.root.operands?.[2].props.value).toEqual({
    type: 'number',
  });
  expect(compileFilterConfiguration(reloaded, fields).errors).toEqual([]);
});

it('serializes the chosen component and opaque props without deriving them from its expression', () => {
  const draft = {
    ...node(Op.EQ, 'amount', { value: 0 }),
    component: { name: 'custom', options: { compact: false } },
    props: { selected: 0, displayLabel: '', nullable: null, visible: false },
  };
  const config = createFilterConfiguration(draft, 'simple');
  const result = JSON.parse(JSON.stringify(config)).root;
  expect(result.component).toEqual(draft.component);
  expect(result.props).toEqual(draft.props);
  expect(result.props.value).toBeUndefined();
  expect(compileFilterConfiguration(config, fields).expression).toBeUndefined();
  expect(() => validateFilterConfiguration(config, fields)).not.toThrow();
});

it('rejects values that JSON drops or changes, including sparse arrays and cyclic props', () => {
  const cycle: Record<string, unknown> = {};
  cycle.self = cycle;
  for (const value of [
    () => 1,
    new Date(),
    new Map(),
    NaN,
    Infinity,
    Symbol('value'),
    BigInt(1),
    [undefined],
    new Array(1),
    cycle,
  ]) {
    expect(() =>
      createFilterConfiguration({
        id: 'bad',
        operator: Op.EQ,
        field: 'amount',
        component: { name: 'custom' },
        props: { value },
      } as FilterComponentConfig),
    ).toThrow();
  }
});

it('rejects malformed component references, field bindings and container shapes', () => {
  const base = createFilterConfiguration(node(Op.EQ, 'amount', { value: 1 }));
  for (const patch of [
    { component: { name: '' } },
    { component: 'custom' },
    { props: [] },
    { operator: 'BOGUS' },
    { operands: [] },
    { predicate: base.root },
    { field: undefined },
    { unexpected: true },
  ])
    expect(() =>
      validateFilterConfiguration({
        ...base,
        root: { ...base.root, ...patch },
      }),
    ).toThrow();
});

it('clears builtin and custom values while retaining components, IDs and non-query properties', () => {
  const draft: FilterComponentConfig = {
    id: 'group',
    operator: Op.AND,
    operands: [
      {
        id: 'builtin',
        operator: Op.EQ,
        field: 'amount',
        component: { name: 'builtin', options: { showTime: true } },
        props: { value: 1 },
      },
      {
        id: 'custom',
        operator: Op.EQ,
        field: 'name',
        component: { name: 'custom' },
        props: { selected: 'a', displayLabel: '姓名' },
      },
    ],
    component: { name: 'builtin', options: { showTime: true } },
    props: {},
  };
  const cleared = clearFilterValues(draft, fields, {
    custom: {
      compile: () => undefined,
      clear: props => ({ ...props, selected: undefined }),
    },
  });
  expect(cleared.id).toBe('group');
  expect(cleared.operands?.map(node => node.id)).toEqual(['builtin', 'custom']);
  expect(cleared.operands?.[0].props.value).toBeUndefined();
  expect(cleared.operands?.[1].props).toEqual({
    displayLabel: '姓名',
    selected: undefined,
  });
  expect(draft.operands?.[0].props.value).toBe(1);
});

it('rejects duplicate stable IDs and incompatible saved simple mode without changing the configuration', () => {
  const config = createFilterConfiguration(
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
    'advanced',
  );
  expect(() =>
    validateFilterConfiguration({ ...config, mode: 'simple' }),
  ).toThrow();
  config.root.operands![1].id = config.root.operands![0].id;
  expect(() => validateFilterConfiguration(config)).toThrow();
});

it('rejects structural attributes hidden inside builtin props and extra array properties', () => {
  const config = createFilterConfiguration(node(Op.EQ, 'amount', { value: 1 }));
  for (const props of [
    { id: 'hidden' },
    { op: Op.NE },
    { field: 'name' },
    { operands: [] },
  ])
    expect(() =>
      validateFilterConfiguration({
        ...config,
        root: { ...config.root, props },
      }),
    ).toThrow();
  const values = Object.assign([1], { label: 'would be lost' });
  expect(() =>
    createFilterConfiguration({
      id: 'array',
      operator: Op.IN,
      field: 'amount',
      component: { name: 'builtin', options: { showTime: true } },
      props: { values },
    }),
  ).toThrow();
});

it('keeps element and logical containers builtin when a field has a custom leaf default', () => {
  const draft = node(
    Op.ELEMENT_MATCH,
    'items',
    {},
    { predicate: node(Op.EQ, 'quantity', { value: 1 }) },
  );
  const config = createFilterConfiguration(draft, 'advanced');
  expect(config.root.component.name).toBe('builtin');
  expect(compileFilterConfiguration(config, fields).errors).toEqual([]);
});

it('round trips and compiles simple element predicates without flattening their scope', () => {
  const root = {
    ...node(Op.ELEMENT_MATCH, 'items'),
    predicate: {
      ...node(Op.AND),
      operands: [node(Op.EQ, 'quantity', { value: 2 })],
    },
  };
  const config = createFilterConfiguration(root, 'simple');
  const reloaded = JSON.parse(JSON.stringify(config));
  expect(reloaded).toEqual(config);
  expect(() => validateFilterConfiguration(reloaded, fields)).not.toThrow();
  expect(compileFilterConfiguration(reloaded, fields)).toEqual(
    compileFilterConfiguration({ ...config, mode: 'advanced' }, fields),
  );
  expect(compileFilterConfiguration(reloaded, fields).errors).toEqual([]);
});

it('switches a custom array editor to a builtin element container', () => {
  const source = {
    ...node(Op.EQ, 'items'),
    component: { name: 'custom' },
    props: { value: ['a'] },
  };
  const result = transitionFilterOperator(source, Op.ELEMENT_MATCH);
  expect(result.component).toEqual({ name: 'builtin' });
  expect(result.props).toEqual({});
  expect(() => createFilterConfiguration(result, 'simple')).not.toThrow();
});

it('avoids cloning the field catalog for builtins while isolating extension context', () => {
  const config = createFilterConfiguration({
    id: 'amount',
    operator: Op.GTE,
    field: 'amount',
    component: { name: 'builtin' },
    props: { value: 1 },
  });
  const clone = vi.spyOn(globalThis, 'structuredClone');
  try {
    expect(compileFilterConfiguration(config, fields).errors).toEqual([]);
    expect(
      clone.mock.calls.filter(
        ([value]) => value && typeof value === 'object' && 'fields' in value,
      ),
    ).toHaveLength(0);
    const custom = {
      ...config,
      root: { ...config.root, component: { name: 'custom' } },
    };
    let received: unknown;
    compileFilterConfiguration(custom, fields, undefined, {
      custom: {
        compile(_props, context) {
          received = context.fields;
          expect(context.fields).not.toBe(fields);
          expect(Object.isFrozen(context.fields)).toBe(true);
          return undefined;
        },
      },
    });
    expect(received).toEqual(fields);
  } finally {
    clone.mockRestore();
  }
});
