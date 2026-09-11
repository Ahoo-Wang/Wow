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
  DeletionState,
  filter,
  FilterOperator as Op,
} from '@ahoo-wang/fetcher-wow';
import { expect, it, vi } from 'vitest';
import {
  compileFilterConfiguration,
  validateFilterConfiguration,
} from '../src/filter/filterConfiguration.js';
import type {
  FilterComponentConfig,
  FilterConfiguration,
} from '../src/filter/filterModel.js';
import { validateViewInstance } from '../src/record/recordValidation.js';
import type { ViewDefinition } from '../src/contracts/viewModel.js';
import { definition, instance, setup } from './engine/fixtures.js';

const view: ViewDefinition = {
  ...definition,
  fields: [
    ...definition.fields,
    {
      field: 'items',
      label: 'Items',
      type: 'array',
      fields: [
        { field: 'qty', label: 'Quantity', type: 'number' },
        {
          field: 'created',
          label: 'Created',
          type: 'datetime',
          operators: [Op.EQ],
        },
      ],
    },
  ],
};
function element(predicate: FilterComponentConfig): FilterConfiguration {
  return {
    mode: 'advanced',
    root: {
      id: 'items',
      component: { name: 'builtin' },
      operator: Op.ELEMENT_MATCH,
      field: 'items',
      props: {},
      predicate,
    },
  };
}
const deletion: FilterComponentConfig = {
  id: 'deletion',
  component: { name: 'builtin' },
  operator: Op.DELETION,
  props: { state: DeletionState.ALL },
};

it.each([undefined, Op.AND, Op.OR, Op.NOR])(
  'rejects a saved root-only predicate through %s before publishing a ready session',
  async logical => {
    const config = element(
      logical
        ? {
            id: 'outer',
            component: { name: 'builtin' },
            operator: logical,
            props: {},
            operands: [
              {
                id: 'inner',
                component: { name: 'builtin' },
                operator: Op.AND,
                props: {},
                operands: [deletion],
              },
            ],
          }
        : deletion,
    );
    const saved = instance();
    saved.config.filters = JSON.parse(JSON.stringify(config));
    const compiled = compileFilterConfiguration(config, view.fields);
    expect(compiled.expression).toBeUndefined();
    expect(compiled.errors[0].message).toBe('元素条件不能使用根级操作');
    expect(() => validateViewInstance(saved, view)).toThrow(
      '元素条件不能使用根级操作',
    );
    const { engine, host, paged } = setup({
      definition: view,
      instances: { instances: [saved], defaultInstanceId: saved.id },
    });
    const statuses: string[] = [];
    engine.subscribe(() => statuses.push(engine.getSnapshot().status));
    try {
      await engine.load();
      expect(engine.getSnapshot().status).toBe('ready');
      expect(
        engine.getSnapshot().sessions[saved.id].validation.length,
      ).toBeGreaterThan(0);
      expect(statuses).toContain('ready');
      expect(host.resolveSource).not.toHaveBeenCalled();
      expect(paged).not.toHaveBeenCalled();
    } finally {
      engine.dispose();
    }
  },
);

it.each([
  Op.ID,
  Op.IDS,
  Op.AGGREGATE_ID,
  Op.AGGREGATE_IDS,
  Op.TENANT_ID,
  Op.OWNER_ID,
  Op.SPACE_ID,
  Op.SEARCH,
])(
  'rejects unset root-only %s predicates even without field metadata',
  operator => {
    const config = element({ ...deletion, operator, props: {} });
    expect(() => validateFilterConfiguration(config)).toThrow(
      '元素条件不能使用根级操作',
    );
  },
);

it.each(['global policy', 'incompatible field', 'missing child schema'])(
  'uses the same %s boundary for saved and compiled nested conditions',
  restriction => {
    const config = element({
      id: 'group',
      operator: Op.OR,
      component: { name: 'builtin' },
      props: {},
      operands: [
        {
          id: 'qty',
          operator: Op.GT,
          field: 'qty',
          component: { name: 'builtin' },
          props: { value: 1 },
        },
      ],
    });
    const fields = view.fields.map(field =>
      field.field !== 'items'
        ? field
        : {
            ...field,
            fields:
              restriction === 'missing child schema'
                ? undefined
                : field.fields?.map(child => ({
                    ...child,
                    operators:
                      restriction === 'incompatible field'
                        ? [Op.EQ]
                        : child.operators,
                  })),
          },
    );
    const allowed =
      restriction === 'global policy'
        ? [Op.ELEMENT_MATCH, Op.OR, Op.EQ]
        : undefined;
    expect(
      compileFilterConfiguration(config, fields, allowed).errors,
    ).not.toEqual([]);
    expect(() =>
      validateFilterConfiguration(config, fields, allowed),
    ).toThrow();
  },
);

it.each([Op.MATCH_ALL, Op.MATCH_NONE])(
  'keeps %s valid through logical element branches',
  operator => {
    const config = element({
      id: 'group',
      operator: Op.OR,
      component: { name: 'builtin' },
      props: {},
      operands: [
        { id: 'constant', operator, component: { name: 'builtin' }, props: {} },
      ],
    });
    const fields = view.fields.map(field =>
      field.field === 'items' ? { ...field, fields: undefined } : field,
    );
    const allowed = [Op.ELEMENT_MATCH, Op.OR, operator];
    expect(() =>
      validateFilterConfiguration(config, fields, allowed),
    ).not.toThrow();
    expect(compileFilterConfiguration(config, fields, allowed)).toEqual({
      expression: filter.elementMatch(
        'items',
        filter.or([
          operator === Op.MATCH_ALL ? filter.matchAll() : filter.matchNone(),
        ]),
      ),
      errors: [],
    });
  },
);

it('preserves unset controls and opaque props without compiling at admission', () => {
  const config = element({
    id: 'group',
    operator: Op.AND,
    component: { name: 'builtin' },
    props: {},
    operands: [
      {
        id: 'unset',
        operator: Op.EQ,
        field: 'qty',
        component: { name: 'builtin' },
        props: {},
      },
      {
        id: 'custom',
        operator: Op.EQ,
        field: 'qty',
        component: { name: 'custom' },
        props: { selected: 0, label: 'Zero', nullable: null },
      },
    ],
  });
  const before = structuredClone(config);
  const compile = vi.fn(() => filter.eq('qty', 0));
  const saved = instance();
  saved.config.filters = config;
  expect(() => validateViewInstance(saved, view)).not.toThrow();
  expect(compile).not.toHaveBeenCalled();
  expect(
    compileFilterConfiguration(config, view.fields, undefined, {
      custom: { compile },
    }),
  ).toEqual({
    expression: filter.elementMatch('items', filter.and([filter.eq('qty', 0)])),
    errors: [],
  });
  expect(compile).toHaveBeenCalledOnce();
  expect(config).toEqual(before);
});

it('allows calendar-day lowering after admitting the selected operator in an element scope', () => {
  const config = element({
    id: 'created',
    operator: Op.EQ,
    field: 'created',
    component: { name: 'builtin' },
    props: { value: { date: '2026-09-09' } },
  });
  const allowed = [Op.ELEMENT_MATCH, Op.EQ];
  expect(() =>
    validateFilterConfiguration(config, view.fields, allowed),
  ).not.toThrow();
  expect(
    compileFilterConfiguration(config, view.fields, allowed, undefined, 'UTC'),
  ).toEqual({
    expression: filter.elementMatch(
      'items',
      filter.between(
        'created',
        Date.parse('2026-09-09T00:00:00Z'),
        Date.parse('2026-09-09T23:59:59.999Z'),
      ),
    ),
    errors: [],
  });
});
