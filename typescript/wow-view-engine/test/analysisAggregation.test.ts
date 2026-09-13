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
import type { AnalysisViewInstance } from '../src/contracts/viewModel.js';
import type { AggregationQuery } from '@ahoo-wang/fetcher-wow';
import {
  aggregation,
  AggregationExpressionType as E,
  AggregationExpressionOperator as O,
  DerivedExpressionType as D,
  HavingExpressionType as H,
  ComparisonOperator as C,
  AggregationFunction as F,
  AggregationGroupType as G,
  AggregationDateUnit as U,
  FilterOperator as Op,
} from '@ahoo-wang/fetcher-wow';
import {
  compileAnalysis,
  analysisScopeContext,
} from '../src/analysis/analysisCompiler.js';
import { validateAnalysisResult } from '../src/analysis/analysisResult.js';
import { projectAnalysis } from '../src/analysis/analysisProjection.js';
import { adaptWowAnalysisSchema } from '../src/analysis/wowAnalysis.js';
import type {
  AnalysisViewConfig,
  AnalysisCompileContext,
  AnalysisComponentConfig,
  AnalysisNumericExpression,
} from '../src/analysis/analysisModel.js';
const context: AnalysisCompileContext = {
  fields: [
    { field: 'customer', label: '客户', type: 'string' },
    { field: 'amount', label: '金额', type: 'number' },
    { field: 'date', label: '日期', type: 'datetime' },
  ],
  capability: {
    count: true,
    expressions: true,
    features: {
      distinctCount: true,
      percentile: true,
      derived: true,
      having: true,
      metricFilters: true,
      missingKey: true,
      dense: true,
    },
    fields: [
      {
        field: 'customer',
        groups: [G.TERMS],
        functions: [],
        distinctCount: true,
      },
      {
        field: 'amount',
        groups: [G.HISTOGRAM],
        functions: Object.values(F),
        distinctCount: true,
        percentile: true,
        unit: 'CNY',
        numberFormat: { style: 'currency', currency: 'CNY' },
      },
      {
        field: 'date',
        groups: [G.DATE_HISTOGRAM],
        functions: [],
        dateUnits: [U.DAY],
      },
    ],
  },
  timeZone: 'Asia/Shanghai',
};
const count: AnalysisComponentConfig = {
  id: 'count',
  component: { name: 'count' },
  alias: 'orders',
  title: '订单数',
  props: {},
};
const config: AnalysisViewConfig = {
  filters: {
    mode: 'simple',
    root: {
      id: 'all',
      component: { name: 'builtin' },
      operator: Op.MATCH_ALL,
      props: {},
    },
  },
  dimensions: [],
  metrics: [count],
  sort: [],
  limit: 100,
  presentation: { layout: 'table', columns: [] },
};
const group: AnalysisComponentConfig = {
  id: 'g',
  component: { name: 'terms' },
  field: 'customer',
  alias: 'customer',
  title: '客户',
  props: {},
};
const derived: AnalysisComponentConfig = {
  id: 'ratio',
  component: { name: 'derived' },
  alias: 'ratio',
  title: '比例',
  props: {},
  derivedExpression: {
    type: D.BINARY,
    operator: O.DIVIDE,
    left: { type: D.METRIC_REF, metricId: 'count' },
    right: { type: D.CONSTANT, value: 2 },
  },
};
function compile(
  metrics: AnalysisComponentConfig[],
  patch: Partial<AnalysisViewConfig> = {},
) {
  return compileAnalysis({ ...config, metrics, ...patch }, context);
}
it('keeps service capabilities in element scope', () => {
  const ctx = {
    ...context,
    capability: {
      ...context.capability,
      scopes: [
        {
          id: 'items',
          label: '明细',
          elements: [{ path: 'items', fields: [] }],
          fields: [],
          capability: { fields: [], count: true },
        },
      ],
    },
  };
  expect(
    analysisScopeContext(
      { ...config, scope: { id: 'items', filters: [config.filters] } },
      ctx,
    ).capability.features,
  ).toEqual(context.capability.features);
});
it('maps features explicitly without enabling them from schema', () => {
  const schema = {
    model: 'orders',
    root: {
      kind: 'OBJECT',
      masked: false,
      properties: {
        customer: {
          kind: 'SCALAR',
          masked: false,
          valueTypes: ['STRING'],
          capabilities: ['AGGREGATE_TERMS'],
        },
      },
    },
  };
  expect(adaptWowAnalysisSchema(schema).capability.features).toBeUndefined();
  const mapped = adaptWowAnalysisSchema(schema, {
    features: { distinctCount: true },
  });
  expect(mapped.capability.features).toEqual({ distinctCount: true });
  expect(mapped.capability.fields[0].distinctCount).toBe(true);
});
it('compiles string distinct and numeric percentiles', () => {
  const result = compile([
    { ...count, component: { name: 'distinct-count' }, field: 'customer' },
    {
      ...count,
      id: 'p',
      alias: 'p95',
      component: { name: 'percentile' },
      field: 'amount',
      props: { percentile: '95' },
    },
  ]);
  expect(result.errors).toEqual([]);
  expect(result.plan?.query.metrics).toEqual([
    aggregation.distinctCount(aggregation.field('customer'), 'orders'),
    aggregation.percentile(aggregation.field('amount'), 95, 'p95'),
  ]);
});
it.each([0, 100, '', ' ', '95x', true])('rejects invalid percentile %s', p => {
  expect(
    compile([
      {
        ...count,
        component: { name: 'percentile' },
        field: 'amount',
        props: { percentile: p },
      },
    ]).plan,
  ).toBeUndefined();
});
it('does not admit string arithmetic or percentile', () => {
  for (const name of ['percentile', 'distinct-count'])
    expect(
      compile([
        {
          ...count,
          component: { name },
          props: { percentile: 95 },
          expression: {
            type: E.BINARY,
            operator: O.ADD,
            left: { type: E.FIELD, field: 'customer' },
            right: { type: E.CONSTANT, value: 1 },
          },
        },
      ]).plan,
    ).toBeUndefined();
});
it('resolves derived and having IDs after renaming aliases', () => {
  const result = compile([{ ...count, alias: 'renamed' }, derived], {
    dimensions: [group],
    having: {
      id: 'h',
      type: H.CONDITION,
      metricId: 'ratio',
      operator: C.GTE,
      value: '1',
    },
  });
  expect(result.errors).toEqual([]);
  expect(result.plan?.query.metrics[1]).toEqual(
    aggregation.derived(
      {
        type: D.BINARY,
        operator: O.DIVIDE,
        left: { type: D.METRIC_REF, metric: 'renamed' },
        right: { type: D.CONSTANT, value: 2 },
      },
      'ratio',
    ),
  );
  expect(result.plan?.query.having).toEqual({
    type: H.CONDITION,
    metric: 'ratio',
    operator: C.GTE,
    value: 1,
  });
});
it('rejects forward and deleted IDs instead of rebinding aliases', () => {
  expect(compile([derived, count]).plan).toBeUndefined();
  expect(
    compile([{ ...count, id: 'replacement' }, derived]).plan,
  ).toBeUndefined();
  expect(
    compile([count], {
      dimensions: [group],
      having: { id: 'h', type: H.IS_NULL, metricId: 'missing' },
    }).plan,
  ).toBeUndefined();
});
it('compiles metric filters separately from root filters', () => {
  const filters = {
    ...config.filters,
    root: {
      id: 'paid',
      component: { name: 'builtin' },
      operator: Op.EQ,
      field: 'customer',
      props: { value: 'A' },
    },
  };
  const result = compile([{ ...count, filters }]);
  expect(result.errors).toEqual([]);
  expect(result.plan?.query.metrics[0]).toHaveProperty('filter', {
    op: 'EQ',
    field: 'customer',
    value: 'A',
  });
  expect(result.plan?.query.filter).toEqual({ op: 'MATCH_ALL' });
});
it('rejects disabled service features', () => {
  const result = compileAnalysis(
    {
      ...config,
      metrics: [
        { ...count, component: { name: 'distinct-count' }, field: 'customer' },
      ],
    },
    { ...context, capability: { ...context.capability, features: undefined } },
  );
  expect(result.plan).toBeUndefined();
  expect(result.errors[0].message).toMatch(/授权|能力/);
});
it('preserves missing-key and dense options with combination checks', () => {
  const a = compile([count], {
    dimensions: [{ ...group, props: { missingKey: '未知' } }],
  });
  expect(a.errors).toEqual([]);
  expect(a.plan?.query.groupBy?.[0]).toHaveProperty('missingKey', '未知');
  const date = {
    ...group,
    field: 'date',
    component: { name: 'date-histogram' },
    props: { unit: U.DAY, dense: true },
  };
  const b = compile([count], { dimensions: [date] });
  expect(b.errors).toEqual([]);
  expect(b.plan?.query.groupBy?.[0]).toHaveProperty('dense', true);
  expect(
    compile([count], {
      dimensions: [date, { ...group, id: 'other', alias: 'other' }],
    }).plan,
  ).toBeUndefined();
});
function tree(depth: number): AnalysisNumericExpression {
  return depth === 1
    ? { type: E.CONSTANT, value: 1 }
    : {
        type: E.BINARY,
        operator: O.ADD,
        left: tree(depth - 1),
        right: tree(depth - 1),
      };
}
it('counts expression nodes across all metrics in a query', () => {
  const metric = {
    ...count,
    component: { name: 'numeric' },
    props: { function: F.SUM },
    expression: tree(8),
  };
  expect(compile([metric]).errors).toEqual([]);
  expect(
    compile([metric, { ...metric, id: 'second', alias: 'second' }]).plan,
  ).toBeUndefined();
});
it('does not interpret integer formatting as statistical additivity', () => {
  const result = compile(
    [{ ...count, component: { name: 'distinct-count' }, field: 'customer' }],
    { dimensions: [group] },
  );
  expect(result.errors).toEqual([]);
  const plan = result.plan!;
  expect(
    validateAnalysisResult([{ customer: 'A', orders: 0 }], plan).errors,
  ).toEqual([]);
  expect(
    validateAnalysisResult([{ customer: 'A', orders: 1.5 }], plan).rows,
  ).toBeUndefined();
  expect(
    projectAnalysis(plan, [{ customer: 'A', orders: 1 }], {
      layout: 'pie',
      columns: [],
    }).issues.length,
  ).toBeGreaterThan(0);
});
it('keeps variance units and derived display formats separate from money', () => {
  const result = compile([
    {
      ...count,
      component: { name: 'numeric' },
      field: 'amount',
      props: { function: F.VARIANCE },
    },
    derived,
  ]);
  expect(result.errors).toEqual([]);
  expect(result.plan?.schema[0].unit).toBe('CNY·CNY');
  expect(result.plan?.schema[0].numberFormat?.style).not.toBe('currency');
  const percent = compile([
    count,
    { ...derived, props: { displayFormat: 'percent' } },
  ]);
  expect(percent.plan?.schema[1].numberFormat?.style).toBe('percent');
});

it('rejects malformed saved expression trees but retains incomplete numbers', async () => {
  const { validateAnalysisConfiguration } =
    await import('../src/analysis/analysisConfigurationValidation.js');
  expect(() =>
    validateAnalysisConfiguration({
      ...config,
      metrics: [
        {
          ...derived,
          derivedExpression: {
            type: D.BINARY,
            operator: O.ADD,
            left: null,
            right: { type: D.CONSTANT, value: '' },
          },
        },
      ],
    }),
  ).toThrow();
  expect(() =>
    validateAnalysisConfiguration({
      ...config,
      metrics: [
        { ...derived, derivedExpression: { type: D.CONSTANT, value: '' } },
      ],
    }),
  ).not.toThrow();
  expect(() =>
    validateAnalysisConfiguration({
      ...config,
      having: { id: 'h', type: H.IN, metricId: 'count', values: 'invalid' },
    }),
  ).toThrow();
});
it('does not drop filters emitted by extensions and rejects forbidden output', () => {
  const compiled = compileAnalysis(
    { ...config, metrics: [{ ...count, component: { name: 'custom' } }] },
    {
      ...context,
      compilers: {
        custom: {
          roles: ['metric'],
          compile: () =>
            aggregation.count('orders', {
              op: Op.EQ,
              field: 'customer',
              value: 'A',
            }),
        },
      },
    },
  );
  expect(compiled.errors).toEqual([]);
  expect(compiled.plan?.query.metrics[0]).toHaveProperty('filter', {
    op: Op.EQ,
    field: 'customer',
    value: 'A',
  });
  for (const field of ['missing', 'items']) {
    const result = compileAnalysis(
      { ...config, metrics: [{ ...count, component: { name: 'custom' } }] },
      {
        ...context,
        fields: [
          ...context.fields,
          { field: 'items', label: '列表', type: 'array' },
        ],
        compilers: {
          custom: {
            roles: ['metric'],
            compile: () =>
              aggregation.count('orders', { op: Op.EXISTS, field }),
          },
        },
      },
    );
    expect(result.plan).toBeUndefined();
  }
});
it('rejects metric filter conflicts instead of replacing a contributed predicate', () => {
  const result = compileAnalysis(
    {
      ...config,
      metrics: [
        { ...count, component: { name: 'custom' }, filters: config.filters },
      ],
    },
    {
      ...context,
      compilers: {
        custom: {
          roles: ['metric'],
          compile: () =>
            aggregation.count('orders', {
              op: Op.EQ,
              field: 'customer',
              value: 'A',
            }),
        },
      },
    },
  );
  expect(result.plan).toBeUndefined();
  expect(result.errors[0].message).toMatch(/冲突/);
});
it('supports having groups, intervals, sets and null without returning UI IDs', () => {
  const result = compile([count], {
    dimensions: [group],
    having: {
      id: 'and',
      type: H.AND,
      operands: [
        {
          id: 'bt',
          type: H.BETWEEN,
          metricId: 'count',
          lower: '1',
          upper: '5',
        },
        {
          id: 'or',
          type: H.OR,
          operands: [
            { id: 'in', type: H.IN, metricId: 'count', values: ['2', 3] },
            { id: 'null', type: H.IS_NULL, metricId: 'count', negated: true },
          ],
        },
      ],
    },
  });
  expect(result.errors).toEqual([]);
  expect(result.plan?.query.having).toEqual({
    type: H.AND,
    operands: [
      { type: H.BETWEEN, metric: 'orders', lower: 1, upper: 5 },
      {
        type: H.OR,
        operands: [
          { type: H.IN, metric: 'orders', values: [2, 3] },
          { type: H.IS_NULL, metric: 'orders', negated: true },
        ],
      },
    ],
  });
});
it.each([
  { id: 'h', type: H.BETWEEN, metricId: 'count', lower: 5, upper: 1 },
  { id: 'h', type: H.IN, metricId: 'count', values: [] },
  { id: 'h', type: H.AND, operands: [] },
  { id: 'h', type: H.CONDITION, metricId: 'count', operator: 'wat', value: 1 },
  { id: 'h', type: H.IS_NULL, metricId: 'count', negated: 'false' },
  { id: 'h', type: 'INVALID', metricId: 'count' },
])('rejects malformed having %j', having => {
  expect(
    compile([count], { dimensions: [group], having: having as never }).plan,
  ).toBeUndefined();
});
it('requires grouping for having and budgets its nodes and IDs', () => {
  expect(
    compile([count], {
      having: { id: 'h', type: H.IS_NULL, metricId: 'count' },
    }).plan,
  ).toBeUndefined();
  const leaf = { id: 'h', type: H.IS_NULL, metricId: 'count' } as const;
  expect(
    compile([count], {
      dimensions: [group],
      having: { id: 'g', type: H.AND, operands: [leaf, leaf] },
    }).plan,
  ).toBeUndefined();
  expect(
    compile([count], {
      dimensions: [group],
      having: {
        id: 'g',
        type: H.AND,
        operands: Array.from({ length: 256 }, (_, i) => ({
          ...leaf,
          id: String(i),
        })),
      },
    }).plan,
  ).toBeUndefined();
});
it('supports derived chains, scalar constants and arithmetic while preserving null', () => {
  const result = compile([
    count,
    derived,
    {
      ...derived,
      id: 'next',
      alias: 'next',
      derivedExpression: {
        type: D.BINARY,
        operator: O.ADD,
        left: { type: D.METRIC_REF, metricId: 'ratio' },
        right: { type: D.CONSTANT, value: '1' },
      },
    },
  ]);
  expect(result.errors).toEqual([]);
  expect(
    validateAnalysisResult(
      [{ orders: 0, ratio: null, next: null }],
      result.plan!,
    ).errors,
  ).toEqual([]);
  expect(
    compile([count, { ...derived, filters: config.filters }]).plan,
  ).toBeUndefined();
  expect(
    compile([
      { ...count, component: { name: 'any' }, field: 'customer' },
      derived,
    ]).plan,
  ).toBeUndefined();
});
it('does not charge value and derived budgets twice', () => {
  const numeric = {
    ...count,
    component: { name: 'numeric' },
    props: { function: F.SUM },
    expression: tree(8),
  };
  const constant = {
    ...derived,
    derivedExpression: { type: D.CONSTANT, value: 2 } as const,
  };
  expect(
    compile([
      numeric,
      { ...numeric, id: 'one', alias: 'one', expression: tree(1) },
      constant,
    ]).errors,
  ).toEqual([]);
  expect(
    compile([
      numeric,
      { ...numeric, id: 'one', alias: 'one', expression: tree(1) },
      { ...numeric, id: 'two', alias: 'two', expression: tree(1) },
    ]).plan,
  ).toBeUndefined();
});
it.each([true, 1, ' ', null])('rejects missing bucket key %j', missingKey => {
  expect(
    compile([count], { dimensions: [{ ...group, props: { missingKey } }] })
      .plan,
  ).toBeUndefined();
});
it('rejects missing keys on numeric terms and invalid dense values', () => {
  expect(
    compile([count], {
      dimensions: [
        { ...group, field: 'amount', props: { missingKey: 'Unknown' } },
      ],
    }).plan,
  ).toBeUndefined();
  const date = {
    ...group,
    field: 'date',
    component: { name: 'date-histogram' },
    props: { unit: U.DAY, dense: 'true' },
  };
  expect(compile([count], { dimensions: [date] }).plan).toBeUndefined();
});
it('keeps value units on percentile and refuses percent formatting on dimensional formulas', () => {
  const result = compile([
    {
      ...count,
      component: { name: 'percentile' },
      field: 'amount',
      props: { percentile: 50 },
    },
  ]);
  expect(result.plan?.schema[0].unit).toBe('CNY');
  const money = {
    ...count,
    component: { name: 'numeric' },
    field: 'amount',
    props: { function: F.SUM },
  };
  expect(
    compile([money, { ...derived, props: { displayFormat: 'percent' } }]).plan,
  ).toBeUndefined();
  expect(
    compile([count, { ...derived, props: { displayFormat: 'invalid' } }]).plan,
  ).toBeUndefined();
});

it('persists IDs, executes aliases and retains the executed plan after a failed edit', async () => {
  const { ViewEngine } = await import('../src/engine/ViewEngine.js');
  const { vi } = await import('vitest');
  const instance: AnalysisViewInstance = {
    id: 'analysis',
    definitionId: 'orders',
    title: '渠道分析',
    kind: 'analysis',
    revision: '1',
    scope: { type: 'personal' },
    config: {
      ...config,
      dimensions: [group],
      metrics: [count, derived],
      having: {
        id: 'h',
        type: H.CONDITION,
        metricId: 'count',
        operator: C.GTE,
        value: 1,
      },
    },
  };
  let saved = instance;
  const aggregate = vi.fn(async (query: AggregationQuery) => {
    expect(query.metrics.length).toBeGreaterThan(0);
    return [{ customer: 'A', orders: 2, ratio: 1 }];
  });
  const make = () =>
    new ViewEngine({
      definitionId: 'orders',
      definition: {
        id: 'orders',
        title: '订单',
        sourceId: 'orders',
        fields: context.fields,
        analysis: context.capability,
        timeZone: context.timeZone,
      },
      instances: { instances: [saved], defaultInstanceId: 'analysis' },
      host: {
        resolveSource: () => ({ aggregate }),
        instance: {
          save: async value => {
            if (value.kind !== 'analysis') throw new Error('expected analysis');
            saved = { ...value, revision: '2' };
            return saved;
          },
        },
        permission: {
          getInstance: () => ({
            save: true,
            saveAsPersonal: true,
            saveAsShared: false,
          }),
        },
      },
    });
  const first = make();
  await first.load();
  first.analysis('analysis').edit(value => ({ ...value, limit: 50 }));
  await first.save('analysis');
  first.dispose();
  const second = make();
  await second.load();
  expect(second.getSnapshot().sessions.analysis.instance.config).toEqual(
    saved.config,
  );
  expect(saved.config.having).toHaveProperty('metricId', 'count');
  expect(aggregate.mock.calls.at(-1)![0].having).toHaveProperty(
    'metric',
    'orders',
  );
  const before = second.getSnapshot().sessions.analysis;
  if (before.kind !== 'analysis') throw new Error('expected analysis');
  second.analysis('analysis').edit(value => ({
    ...value,
    metrics: value.metrics.map(m =>
      m.id === 'ratio' ? { ...m, props: { displayFormat: 'percent' } } : m,
    ),
  }));
  aggregate.mockRejectedValueOnce(new Error('network'));
  await expect(second.analysis('analysis').run()).rejects.toThrow('network');
  const after = second.getSnapshot().sessions.analysis;
  if (after.kind !== 'analysis') throw new Error('expected analysis');
  expect(after.result?.plan).toEqual(before.result?.plan);
  expect(after.result?.config).toEqual(before.result?.config);
  expect(after.instance.config.metrics[1].props.displayFormat).toBe('percent');
  second.dispose();
});

it('rejects arithmetic on numeric identifiers with only terms capability', () => {
  const schema = {
    model: 'orders',
    root: {
      kind: 'OBJECT',
      masked: false,
      properties: {
        customer: {
          kind: 'SCALAR',
          masked: false,
          valueTypes: ['INTEGER'],
          capabilities: ['AGGREGATE_TERMS'],
        },
      },
    },
  };
  const mapped = adaptWowAnalysisSchema(schema, {
    features: { distinctCount: true },
  });
  const ctx = {
    ...context,
    fields: mapped.fields,
    capability: { ...mapped.capability, expressions: true },
  };
  expect(
    compileAnalysis(
      {
        ...config,
        metrics: [
          {
            ...count,
            component: { name: 'distinct-count' },
            field: 'customer',
          },
        ],
      },
      ctx,
    ).errors,
  ).toEqual([]);
  expect(
    compileAnalysis(
      {
        ...config,
        metrics: [
          {
            ...count,
            component: { name: 'distinct-count' },
            expression: {
              type: E.BINARY,
              operator: O.ADD,
              left: { type: E.FIELD, field: 'customer' },
              right: { type: E.CONSTANT, value: 1 },
            },
          },
        ],
      },
      ctx,
    ).plan,
  ).toBeUndefined();
});
it('locates a failed having predicate instead of marking its parent group', () => {
  const result = compile([count], {
    dimensions: [group],
    having: {
      id: 'parent',
      type: H.AND,
      operands: [
        {
          id: 'invalid',
          type: H.CONDITION,
          metricId: 'count',
          operator: C.GTE,
          value: '',
        },
      ],
    },
  });
  expect(result.errors[0].id).toBe('invalid');
});

it('revalidates and normalizes scalar values supplied by analysis compilers', () => {
  const run = (value: string | number, local = context) =>
    compileAnalysis(
      { ...config, metrics: [{ ...count, component: { name: 'custom' } }] },
      {
        ...local,
        compilers: {
          custom: {
            roles: ['metric'],
            compile: () =>
              aggregation.count('orders', {
                op: Op.EQ,
                field: 'amount',
                value,
              }),
          },
        },
      },
    );
  expect(run('oops').plan).toBeUndefined();
  expect(run('10').plan).toBeUndefined();
  expect(run(10).plan?.query.metrics[0]).toHaveProperty('filter', {
    op: Op.EQ,
    field: 'amount',
    value: 10,
  });
  expect(
    run(20, {
      ...context,
      fields: context.fields.map(f =>
        f.field === 'amount'
          ? { ...f, options: [{ value: 10, label: '十' }] }
          : f,
      ),
    }).plan,
  ).toBeUndefined();
});

it('removes root metric operations from an element editing context', async () => {
  const { analysisMetricFilterContext } =
    await import('../src/analysis/analysisMetricFilter.js');
  const element = analysisMetricFilterContext(context, true);
  expect(element.allowedOperators).not.toContain(Op.ID);
  expect(element.allowedOperators).not.toContain(Op.TENANT_ID);
  expect(element.allowedOperators).not.toContain(Op.DELETION);
  expect(element.allowedOperators).toContain(Op.MATCH_ALL);
  expect(element.allowedOperators).toContain(Op.MATCH_NONE);
  expect(analysisMetricFilterContext(context).allowedOperators).toContain(
    Op.ID,
  );
});

it('allows percentages only when units are proven dimensionless', () => {
  const money = {
    ...count,
    component: { name: 'numeric' },
    field: 'amount',
    props: { function: F.SUM },
  };
  const formula = (
    operator: O,
    right: typeof derived.derivedExpression,
  ): AnalysisComponentConfig => ({
    ...derived,
    props: { displayFormat: 'percent' },
    derivedExpression: {
      type: D.BINARY,
      operator,
      left: { type: D.METRIC_REF, metricId: 'count' },
      right: right!,
    },
  });
  expect(
    compile([money, formula(O.ADD, { type: D.CONSTANT, value: 1 })]).plan,
  ).toBeUndefined();
  expect(
    compile([money, formula(O.SUBTRACT, { type: D.CONSTANT, value: 1 })]).plan,
  ).toBeUndefined();
  expect(
    compile([
      money,
      formula(O.DIVIDE, { type: D.METRIC_REF, metricId: 'count' }),
    ]).errors,
  ).toEqual([]);
  const unknown = {
    ...context,
    capability: {
      ...context.capability,
      fields: context.capability.fields.map(f => ({ ...f, unit: undefined })),
    },
  };
  expect(
    compileAnalysis(
      {
        ...config,
        metrics: [money, formula(O.DIVIDE, { type: D.CONSTANT, value: 2 })],
      },
      unknown,
    ).plan,
  ).toBeUndefined();
  expect(
    compile([count, formula(O.DIVIDE, { type: D.CONSTANT, value: 2 })]).errors,
  ).toEqual([]);
});

it('keeps custom numeric references and excludes unknown or invalid contributions', async () => {
  const { referenceableAnalysisMetrics } =
    await import('../src/analysis/analysisEditorLabels.js');
  const metric = (name: string) => ({
    ...count,
    id: name,
    alias: name,
    component: { name },
  });
  const result = referenceableAnalysisMetrics(
    [
      'customCount',
      'customAny',
      'broken',
      'group',
      'missing',
      'wrongAlias',
      'wrongRole',
    ].map(metric),
    {
      ...context,
      compilers: {
        customCount: {
          roles: ['metric'],
          compile: item => aggregation.count(item.alias),
        },
        customAny: {
          roles: ['metric'],
          compile: item => aggregation.any('customer', item.alias),
        },
        broken: {
          roles: ['metric'],
          compile: () => {
            throw new Error('invalid draft');
          },
        },
        group: {
          roles: ['metric'],
          compile: item => aggregation.terms('customer', item.alias),
        },
        wrongAlias: {
          roles: ['metric'],
          compile: () => aggregation.count('other'),
        },
        wrongRole: {
          roles: ['dimension'],
          compile: item => aggregation.count(item.alias),
        },
      },
    },
  );
  expect(result.map(metric => metric.id)).toEqual(['customCount']);
});

it('does not cancel different compound units with the same unparenthesized spelling', () => {
  const ctx = {
    ...context,
    fields: [
      ...context.fields,
      { field: 'duration', label: '时长', type: 'number' as const },
    ],
    capability: {
      ...context.capability,
      fields: [
        ...context.capability.fields,
        { field: 'duration', groups: [], functions: [F.SUM], unit: 's' },
      ],
    },
  };
  const ref = (metricId: string) => ({ type: D.METRIC_REF as const, metricId });
  const divide = (
    left: ReturnType<typeof ref> | AnalysisComponentConfig['derivedExpression'],
    right:
      ReturnType<typeof ref> | AnalysisComponentConfig['derivedExpression'],
  ) => ({
    type: D.BINARY as const,
    operator: O.DIVIDE,
    left: left!,
    right: right!,
  });
  const money = {
    ...count,
    component: { name: 'numeric' },
    field: 'amount',
    props: { function: F.SUM },
  };
  const time = { ...money, id: 'time', alias: 'time', field: 'duration' };
  const a = {
    ...derived,
    id: 'a',
    alias: 'a',
    derivedExpression: divide(ref('count'), divide(ref('time'), ref('count'))),
  };
  const b = {
    ...derived,
    id: 'b',
    alias: 'b',
    derivedExpression: divide(divide(ref('count'), ref('time')), ref('count')),
  };
  const percent = {
    ...derived,
    props: { displayFormat: 'percent' },
    derivedExpression: divide(ref('a'), ref('b')),
  };
  const result = compileAnalysis(
    { ...config, metrics: [money, time, a, b, percent] },
    ctx,
  );
  expect(result.plan).toBeUndefined();
  expect(result.errors[0].id).toBe('ratio');
});
