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

import { canAddAnalysisSort } from '../src/analysis/analysisSort.js';
import { expect, it, vi } from 'vitest';
import {
  aggregation,
  AggregationFunction as Fn,
  AggregationGroupType as Group,
  AggregationDateUnit as Unit,
  FilterOperator,
  SortDirection,
} from '@ahoo-wang/fetcher-wow';
import { createFilterConfiguration } from '../src/filter/filterConfiguration.js';
import { validateAnalysisResult } from '../src/analysis/analysisResult.js';
import { compileAnalysis } from '../src/analysis/analysisCompiler.js';
import type {
  AnalysisViewConfig,
  AnalysisCompileContext,
} from '../src/analysis/analysisModel.js';
export const context: AnalysisCompileContext = {
  fields: [
    { field: 'state', label: 'State', type: 'string' },
    { field: 'amount', label: 'Amount', type: 'number' },
    { field: 'created', label: 'Created', type: 'datetime' },
  ],
  capability: {
    count: true,
    fields: [
      { field: 'state', groups: [Group.TERMS], functions: [] },
      { field: 'amount', groups: [Group.HISTOGRAM], functions: [Fn.SUM] },
      {
        field: 'created',
        groups: [Group.DATE_HISTOGRAM],
        functions: [],
        dateUnits: [Unit.MONTH],
      },
    ],
  },
  timeZone: 'Asia/Shanghai',
};
export const config: AnalysisViewConfig = {
  filters: createFilterConfiguration({
    id: 'all',
    component: { name: 'builtin' },
    operator: FilterOperator.MATCH_ALL,
    props: {},
  }),
  dimensions: [],
  metrics: [
    {
      id: 'count',
      component: { name: 'count' },
      alias: 'orders',
      title: 'Orders',
      props: {},
    },
    {
      id: 'sum',
      component: { name: 'numeric' },
      field: 'amount',
      alias: 'total',
      title: 'Total',
      props: { function: Fn.SUM },
    },
  ],
  sort: [],
  limit: 100,
  presentation: { layout: 'table', columns: [] },
};
it('compiles count and sum with real builders and preserves alias on rename', () => {
  const result = compileAnalysis(config, context);
  expect(result.errors).toEqual([]);
  expect(result.plan?.query.metrics).toEqual([
    aggregation.count('orders'),
    aggregation.sum(aggregation.field('amount'), 'total'),
  ]);
  expect(
    compileAnalysis(
      {
        ...config,
        metrics: config.metrics.map(x => ({ ...x, title: 'Changed' })),
      },
      context,
    ).plan?.query,
  ).toEqual(result.plan?.query);
});
it('groups state and month with consistent timezone and stable sort', () => {
  const result = compileAnalysis(
    {
      ...config,
      dimensions: [
        {
          id: 'state',
          component: { name: 'terms' },
          field: 'state',
          alias: 'state_key',
          title: 'State',
          props: {},
        },
        {
          id: 'month',
          component: { name: 'date-histogram' },
          field: 'created',
          alias: 'month',
          title: 'Month',
          props: { unit: Unit.MONTH },
        },
      ],
      sort: [{ alias: 'total', direction: SortDirection.DESC }],
    },
    context,
  );
  expect(result.errors).toEqual([]);
  expect(result.plan?.query.groupBy).toEqual([
    aggregation.terms('state', 'state_key'),
    aggregation.dateHistogram('created', {
      alias: 'month',
      unit: Unit.MONTH,
      timeZone: 'Asia/Shanghai',
    }),
  ]);
  expect(result.plan?.query.sort?.map(x => x.field)).toEqual([
    'total',
    'state_key',
    'month',
  ]);
});
it.each(['missing', 'numeric'])(
  'rejects unknown components and incomplete props: %s',
  name => {
    expect(
      compileAnalysis(
        {
          ...config,
          metrics: [{ ...config.metrics[1], component: { name }, props: {} }],
        },
        context,
      ).plan,
    ).toBeUndefined();
  },
);
it('rejects capability bypass, duplicate aliases, missing timezone and excessive limits', () => {
  for (const bad of [
    {
      ...config,
      metrics: [{ ...config.metrics[1], props: { function: Fn.AVG } }],
    },
    {
      ...config,
      metrics: [config.metrics[0], { ...config.metrics[1], alias: 'orders' }],
    },
    { ...config, limit: 10001 },
  ])
    expect(compileAnalysis(bad, context).plan).toBeUndefined();
  expect(
    compileAnalysis(
      {
        ...config,
        dimensions: [
          {
            id: 'd',
            component: { name: 'date-histogram' },
            field: 'created',
            alias: 'month',
            title: 'Month',
            props: { unit: Unit.MONTH },
          },
        ],
      },
      { ...context, timeZone: undefined },
    ).plan,
  ).toBeUndefined();
});
it('bounds custom contributions by capability and binding', () => {
  const customConfig = {
    ...config,
    metrics: [{ ...config.metrics[1], component: { name: 'custom' } }],
  };
  expect(
    compileAnalysis(customConfig, {
      ...context,
      compilers: {
        custom: {
          roles: ['metric'],
          compile: () => aggregation.sum(aggregation.field('amount'), 'total'),
        },
      },
    }).errors,
  ).toEqual([]);
  for (const output of [
    aggregation.avg(aggregation.field('amount'), 'total'),
    aggregation.sum(aggregation.field('other'), 'total'),
    aggregation.count('other'),
    aggregation.any('amount', 'total'),
  ])
    expect(
      compileAnalysis(customConfig, {
        ...context,
        compilers: { custom: { roles: ['metric'], compile: () => output } },
      }).plan,
    ).toBeUndefined();
});
it('rejects invalid histogram and combined sort limit', () => {
  const dimension = {
    id: 'bucket',
    component: { name: 'histogram' },
    field: 'amount',
    alias: 'bucket',
    title: 'Bucket',
    props: { interval: 0 },
  };
  expect(
    compileAnalysis({ ...config, dimensions: [dimension] }, context).plan,
  ).toBeUndefined();
  expect(
    compileAnalysis(
      {
        ...config,
        dimensions: [{ ...dimension, props: { interval: 10 } }],
        sort: [{ alias: 'total', direction: SortDirection.ASC }],
      },
      {
        ...context,
        capability: { ...context.capability, limits: { maxSort: 1 } },
      },
    ).plan,
  ).toBeUndefined();
  expect(
    compileAnalysis(
      { ...config, sort: [{ alias: 'total', direction: SortDirection.ASC }] },
      context,
    ).plan,
  ).toBeUndefined();
});
it('keeps query schema independent from persisted column order and width', () => {
  const result = compileAnalysis(
    {
      ...config,
      presentation: {
        layout: 'table',
        columns: [{ alias: 'total', width: 240 }],
      },
    },
    context,
  );
  expect(result.plan?.schema.map(column => column.alias)).toEqual([
    'orders',
    'total',
  ]);
  expect(result.plan?.schema[0].width).toBeUndefined();
  expect(result.plan?.query.metrics.map(metric => metric.alias)).toEqual([
    'orders',
    'total',
  ]);
});
it('rejects an unknown numeric function even when external capability and custom output agree', () => {
  const unknown = 'MEDIAN' as Fn;
  const custom = {
    ...config,
    metrics: [{ ...config.metrics[1], component: { name: 'custom' } }],
  };
  const external = {
    ...context,
    capability: {
      ...context.capability,
      fields: context.capability.fields.map(field =>
        field.field === 'amount' ? { ...field, functions: [unknown] } : field,
      ),
    },
    compilers: {
      custom: {
        roles: ['metric'] as const,
        compile: () => ({
          ...aggregation.sum(aggregation.field('amount'), 'total'),
          function: unknown,
        }),
      },
    },
  };
  expect(compileAnalysis(custom, external).plan).toBeUndefined();
});
it('keeps presentation errors outside query compilation and returns semantic metadata', () => {
  const result = compileAnalysis(
    { ...config, presentation: { layout: 'bar', columns: [] } },
    context,
  );
  expect(result.errors).toEqual([]);
  expect(result.plan?.schema[0].aggregation).toBe('COUNT');
  expect(result.plan?.schema[1].aggregation).toBe('SUM');
});
it('authorizes numeric expressions and scalar representatives independently', () => {
  const ctx = {
    ...context,
    capability: {
      ...context.capability,
      expressions: true,
      fields: context.capability.fields.map(f => ({
        ...f,
        any: true,
        unit: f.field === 'amount' ? 'CNY' : undefined,
      })),
    },
  };
  const expression = aggregation.multiply(
    aggregation.field('amount'),
    aggregation.constant(2),
  );
  const metric = { ...config.metrics[1], field: undefined, expression };
  const result = compileAnalysis(
    {
      ...config,
      metrics: [
        metric,
        { ...config.metrics[0], component: { name: 'any' }, field: 'state' },
      ],
    },
    ctx,
  );
  expect(result.errors).toEqual([]);
  expect(result.plan?.query.metrics[0]).toEqual(
    aggregation.sum(expression, 'total'),
  );
  expect(result.plan?.schema[0].unit).toBe('CNY');
  expect(result.plan?.schema[1]).toMatchObject({
    aggregation: 'ANY',
    valueType: 'string',
  });
  expect(
    compileAnalysis({ ...config, metrics: [metric] }, context).plan,
  ).toBeUndefined();
  for (const value of ['', '-', '1e', 'Infinity'])
    expect(
      compileAnalysis(
        {
          ...config,
          metrics: [{ ...metric, expression: { type: 'CONSTANT', value } }],
        } as AnalysisViewConfig,
        ctx,
      ).plan,
    ).toBeUndefined();
});
it('compiles declared element chains with root and relative filter fields', () => {
  const ctx: AnalysisCompileContext = {
    ...context,
    capability: {
      ...context.capability,
      scopes: [
        {
          id: 'items',
          label: 'Items',
          elements: [{ path: 'items', fields: context.fields }],
          fields: context.fields,
          capability: { fields: context.capability.fields, count: true },
        },
      ],
    },
  };
  const result = compileAnalysis(
    { ...config, scope: { id: 'items', filters: [config.filters] } },
    ctx,
  );
  expect(result.errors).toEqual([]);
  expect(result.plan?.query.elements).toEqual([
    aggregation.element('items', { op: FilterOperator.MATCH_ALL }),
  ]);
  expect(
    compileAnalysis({ ...config, scope: { id: 'unknown', filters: [] } }, ctx)
      .plan,
  ).toBeUndefined();
});
it('bounds expression depth and size, rejecting scope escapes and retaining valid raw numeric text', () => {
  const ctx = {
    ...context,
    capability: { ...context.capability, expressions: true },
  };
  const compile = (
    expression: AnalysisViewConfig['metrics'][number]['expression'],
  ) =>
    compileAnalysis(
      {
        ...config,
        metrics: [{ ...config.metrics[1], field: undefined, expression }],
      },
      ctx,
    );
  expect(
    compile({ ...aggregation.constant(0), value: '-2.5e2' }).plan?.query
      .metrics[0],
  ).toEqual(aggregation.sum(aggregation.constant(-250), 'total'));
  expect(compile(aggregation.field('secret')).plan).toBeUndefined();
  let deep = aggregation.field('amount') as NonNullable<
    AnalysisViewConfig['metrics'][number]['expression']
  >;
  for (let i = 0; i < 8; i++)
    deep = {
      ...aggregation.add(aggregation.constant(1), aggregation.constant(1)),
      left: deep,
    };
  expect(compile(deep).plan).toBeUndefined();
  const wide = aggregation.field('amount');
  let tree: NonNullable<AnalysisViewConfig['metrics'][number]['expression']> =
    wide;
  for (let i = 0; i < 6; i++)
    tree = { ...aggregation.add(wide, wide), left: tree, right: tree };
  expect(compile(tree).errors).toEqual([]);
});
it('keeps parent filters at root and forbids element filters or groups from escaping relative capabilities', () => {
  const rootFilter = createFilterConfiguration({
    id: 'root',
    field: 'state',
    component: { name: 'builtin' },
    operator: FilterOperator.EQ,
    props: { value: 'OPEN' },
  });
  const relativeFilter = createFilterConfiguration({
    id: 'relative',
    field: 'sku',
    component: { name: 'builtin' },
    operator: FilterOperator.EQ,
    props: { value: 'A' },
  });
  const scoped: AnalysisCompileContext = {
    ...context,
    capability: {
      ...context.capability,
      scopes: [
        {
          id: 'items',
          label: 'Items',
          elements: [
            {
              path: 'items',
              fields: [{ field: 'sku', label: 'SKU', type: 'string' }],
            },
          ],
          fields: [{ field: 'sku', label: 'SKU', type: 'string' }],
          capability: {
            count: true,
            fields: [{ field: 'sku', groups: [Group.TERMS], functions: [] }],
          },
        },
      ],
    },
  };
  const selection = {
    ...config,
    filters: rootFilter,
    metrics: [config.metrics[0]],
    scope: { id: 'items', filters: [relativeFilter] },
  };
  const result = compileAnalysis(selection, scoped);
  expect(result.errors).toEqual([]);
  expect(result.plan?.query.filter).toMatchObject({ field: 'state' });
  expect(result.plan?.query.elements?.[0].filter).toMatchObject({
    field: 'sku',
  });
  expect(
    compileAnalysis(
      { ...selection, scope: { id: 'items', filters: [rootFilter] } },
      scoped,
    ).plan,
  ).toBeUndefined();
  expect(
    compileAnalysis(
      { ...selection, scope: { id: 'items', filters: [] } },
      scoped,
    ).plan,
  ).toBeUndefined();
  expect(
    compileAnalysis({ ...selection, metrics: config.metrics }, scoped).plan,
  ).toBeUndefined();
});
it('copies display options and Intl number format into schema without modifying the query', () => {
  const styled: AnalysisCompileContext = {
    ...context,
    fields: context.fields.map(field =>
      field.field === 'state'
        ? { ...field, options: [{ value: 'FAILED', label: '失败' }] }
        : field,
    ),
    capability: {
      ...context.capability,
      fields: context.capability.fields.map(field => ({
        ...field,
        any: field.field === 'state',
        numberFormat:
          field.field === 'amount' ? { maximumFractionDigits: 2 } : undefined,
      })),
    },
  };
  const result = compileAnalysis(
    {
      ...config,
      metrics: [
        ...config.metrics,
        {
          ...config.metrics[0],
          id: 'sample',
          alias: 'sample',
          component: { name: 'any' },
          field: 'state',
        },
      ],
    },
    styled,
  );
  expect(result.plan?.schema[1].numberFormat).toEqual({
    maximumFractionDigits: 2,
  });
  expect(result.plan?.schema[2].options).toEqual([
    { value: 'FAILED', label: '失败' },
  ]);
  expect(result.plan?.query.metrics.slice(0, 2)).toEqual(
    compileAnalysis(config, context).plan?.query.metrics,
  );
});

it('owns a display field on its dimension and compiles authorized ANY without adding a user measure', () => {
  const ctx = {
    ...context,
    capability: {
      ...context.capability,
      fields: context.capability.fields.map(f => ({ ...f, any: true })),
    },
  };
  const view: AnalysisViewConfig = {
    ...config,
    dimensions: [
      {
        id: 'state',
        component: { name: 'terms' },
        field: 'state',
        alias: 'state_key',
        title: 'State',
        props: {},
        label: { field: 'state', alias: 'state_label', title: 'State name' },
      },
    ],
  };
  const result = compileAnalysis(view, ctx);
  expect(result.errors).toEqual([]);
  expect(result.plan?.query.metrics.at(-1)).toEqual(
    aggregation.any('state', 'state_label'),
  );
  expect(result.plan?.schema.at(-1)?.labelFor).toBe('state_key');
  expect(view.metrics).toHaveLength(2);
  expect(compileAnalysis(view, context).errors.length).toBeGreaterThan(0);
  expect(
    compileAnalysis(
      {
        ...view,
        dimensions: [
          {
            ...view.dimensions[0],
            label: { ...view.dimensions[0].label!, alias: 'orders' },
          },
        ],
      },
      ctx,
    ).errors.length,
  ).toBeGreaterThan(0);
  expect(
    compileAnalysis(view, {
      ...ctx,
      capability: { ...ctx.capability, limits: { maxMetrics: 2 } },
    }).errors.length,
  ).toBeGreaterThan(0);
});

it('rejects unsupported custom roles before invoking the compiler', () => {
  const compile = vi.fn(value => aggregation.count(value.alias));
  const result = compileAnalysis(
    {
      ...config,
      metrics: [{ ...config.metrics[0], component: { name: 'custom' } }],
    },
    {
      ...context,
      compilers: { custom: { roles: ['dimension'], compile } },
    },
  );
  expect(result.plan).toBeUndefined();
  expect(result.errors[0].message).toContain('角色');
  expect(compile).not.toHaveBeenCalled();
});
it('passes the actual role to a declared dual-role compiler', () => {
  const roles: string[] = [];
  const result = compileAnalysis(
    {
      ...config,
      dimensions: [
        {
          ...config.metrics[0],
          id: 'state',
          alias: 'state',
          field: 'state',
          component: { name: 'custom' },
        },
      ],
      metrics: [{ ...config.metrics[0], component: { name: 'custom' } }],
    },
    {
      ...context,
      compilers: {
        custom: {
          roles: ['dimension', 'metric'],
          compile(value, context) {
            roles.push(context.role);
            return context.role === 'dimension'
              ? aggregation.terms(value.field!, value.alias)
              : aggregation.count(value.alias);
          },
        },
      },
    },
  );
  expect(result.errors).toEqual([]);
  expect(roles).toEqual(['dimension', 'metric']);
});

it.each(['', '   '])('rejects blank output titles: %j', title => {
  for (const role of ['dimension', 'metric', 'label']) {
    const scoped = {
      ...context,
      capability: {
        ...context.capability,
        fields: context.capability.fields.map(field => ({
          ...field,
          any: true,
        })),
      },
    };
    const value = structuredClone(config);
    value.dimensions = [
      {
        id: 'state',
        alias: 'state',
        title: 'State',
        field: 'state',
        component: { name: 'terms' },
        props: {},
      },
    ];
    if (role === 'dimension') value.dimensions[0].title = title;
    else if (role === 'metric') value.metrics[0].title = title;
    else value.dimensions[0].label = { field: 'state', alias: 'label', title };
    const result = compileAnalysis(value, scoped);
    expect(result.plan).toBeUndefined();
    expect(
      result.errors.some(error => error.message.includes('输出名称')),
    ).toBe(true);
  }
});

it.each([1, 2, 3])(
  'keeps editor/table sort capacity consistent with compilation at limit %i',
  maxSort => {
    const dimensions = [
      {
        id: 'state',
        alias: 'state',
        title: 'State',
        field: 'state',
        component: { name: 'terms' },
        props: {},
      },
    ];
    for (const alias of ['state', 'orders', 'total']) {
      const proposed = {
        ...config,
        dimensions,
        sort: [{ alias, direction: SortDirection.ASC }],
      };
      const compiled = compileAnalysis(proposed, {
        ...context,
        capability: { ...context.capability, limits: { maxSort } },
      });
      expect(canAddAnalysisSort(dimensions, [], alias, maxSort)).toBe(
        !!compiled.plan,
      );
    }
  },
);

it('uses default analysis limits for explicit undefined overrides', () => {
  expect(
    compileAnalysis(config, {
      ...context,
      capability: { ...context.capability, limits: { maxGroups: undefined } },
    }),
  ).toEqual(compileAnalysis(config, context));
});

it.each([false, true])(
  'accepts date-only ANY output as a string (dimension label: %s)',
  label => {
    const date = {
      id: 'date',
      component: { name: 'any' },
      field: 'day',
      alias: 'day',
      title: 'Day',
      props: {},
    };
    const result = compileAnalysis(
      {
        ...config,
        dimensions: label
          ? [
              {
                id: 'state',
                component: { name: 'terms' },
                field: 'state',
                alias: 'state',
                title: 'State',
                props: {},
                label: { field: 'day', alias: 'day', title: 'Day' },
              },
            ]
          : [],
        metrics: label ? config.metrics : [...config.metrics, date],
      },
      {
        ...context,
        fields: [
          ...context.fields,
          { field: 'day', label: 'Day', type: 'date' },
        ],
        capability: {
          ...context.capability,
          fields: [...context.capability.fields, { field: 'day', any: true }],
        },
      },
    );
    expect(result.errors).toEqual([]);
    expect(result.plan!.schema.find(c => c.alias === 'day')?.valueType).toBe(
      'string',
    );
    expect(
      validateAnalysisResult(
        [
          {
            ...(label ? { state: 'ok' } : {}),
            orders: 1,
            total: 2,
            day: '2026-09-12',
          },
        ],
        result.plan!,
      ).errors,
    ).toEqual([]);
  },
);

it('allocates dimension label IDs without colliding with real component IDs', () => {
  const result = compileAnalysis(
    {
      ...config,
      dimensions: [
        {
          id: 'state',
          component: { name: 'terms' },
          field: 'state',
          alias: 'state',
          title: 'State',
          props: {},
          label: { field: 'state', alias: 'name', title: 'Name' },
        },
      ],
      metrics: config.metrics.map((m, i) => ({
        ...m,
        id: i ? 'state:label:label' : 'state:label',
      })),
    },
    {
      ...context,
      capability: {
        ...context.capability,
        fields: context.capability.fields.map(f => ({ ...f, any: true })),
      },
    },
  );
  expect(result.errors).toEqual([]);
  expect(new Set(result.plan!.schema.map(c => c.id)).size).toBe(
    result.plan!.schema.length,
  );
});
