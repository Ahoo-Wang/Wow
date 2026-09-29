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
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/wow-client';
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { withoutDangling } from '../src/analysis/dangling.js';
import { withElements, withLevel } from '../src/analysis/index.js';
import {
  MemoryViewStore,
  ViewEngine,
  type AnalysisHavingExpression,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type DataViewDefinition,
} from '../src/index.js';
import { useAnalysisEditor, useOpenView } from '../src/react/index.js';
import {
  analysisConfig,
  ordersDefinition,
  resourcesOf,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

/**
 * A metric leaving the question takes with it what reads it: the derived
 * metrics over it — and those over them — and the having rules on it, the
 * way the chart, the sort and the table columns already followed an alias.
 * Left behind, they stopped the next run at admission
 * (`analysis.derived.unknown-metric`, `analysis.having.unknown-metric`,
 * `analysis.having.requires-group`) instead of the question following.
 */

const amount: AnalysisMetric = {
  alias: 'amount',
  type: 'NUMERIC',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'amount' },
};
const cost: AnalysisMetric = {
  alias: 'cost',
  type: 'NUMERIC',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'cost' },
};
const count: AnalysisMetric = { alias: 'orders', type: 'COUNT' };

function derived(alias: string, left: string, right: string): AnalysisMetric {
  return {
    alias,
    type: 'DERIVED',
    expression: {
      type: 'BINARY',
      operator: 'DIVIDE' as never,
      left: { type: 'METRIC_REF', metric: left },
      right: { type: 'METRIC_REF', metric: right },
    },
  };
}

function over(metric: string, value = 10): AnalysisHavingExpression {
  return { type: 'CONDITION', metric, operator: 'GT', value };
}

function and(
  ...operands: AnalysisHavingExpression[]
): AnalysisHavingExpression {
  return {
    type: 'AND',
    operands: operands as [
      AnalysisHavingExpression,
      ...AnalysisHavingExpression[],
    ],
  };
}

function or(...operands: AnalysisHavingExpression[]): AnalysisHavingExpression {
  return {
    type: 'OR',
    operands: operands as [
      AnalysisHavingExpression,
      ...AnalysisHavingExpression[],
    ],
  };
}

const grouped = [
  { alias: 'warehouse', field: 'warehouse', type: 'TERMS' as const },
];

describe('withoutDangling', () => {
  it('takes a derived metric with what it read, in cascade', () => {
    const followed = withoutDangling({
      groups: grouped,
      metrics: [
        count,
        derived('perOrder', 'amount', 'orders'),
        derived('perOrderShare', 'perOrder', 'orders'),
        derived('ordersSquared', 'orders', 'orders'),
      ] as AnalysisViewConfig['metrics'],
    });
    expect(followed.metrics.map(metric => metric.alias)).toEqual([
      'orders',
      'ordersSquared',
    ]);
    expect(followed.removed).toEqual(['perOrder', 'perOrderShare']);
  });

  it('reads no operand in a sample value or a moment', () => {
    const metrics = [
      { alias: 'sample', type: 'ANY', field: 'amount' },
      {
        alias: 'latest',
        type: 'NUMERIC',
        function: 'MAX',
        expression: { type: 'FIELD', field: 'createdAt' },
      },
      count,
      derived('a', 'sample', 'orders'),
      derived('b', 'latest', 'orders'),
      derived('c', 'orders', 'orders'),
    ] as AnalysisViewConfig['metrics'];
    const followed = withoutDangling(
      { groups: grouped, metrics },
      { moments: new Set(['latest']) },
    );
    expect(followed.removed).toEqual(['a', 'b']);
  });

  it('keeps the metrics and the having as they were when nothing moved', () => {
    const shape = {
      groups: grouped,
      metrics: [count, amount] as AnalysisViewConfig['metrics'],
      having: and(over('orders'), over('amount')),
    };
    const followed = withoutDangling(shape);
    expect(followed.metrics).toBe(shape.metrics);
    expect(followed.having).toBe(shape.having);
    expect(followed.removed).toEqual([]);
  });

  it('takes a having rule on a metric gone out of an AND, and the OR it sits in', () => {
    const metrics = [count] as AnalysisViewConfig['metrics'];
    const follow = (having: AnalysisHavingExpression) =>
      withoutDangling({ groups: grouped, metrics, having }).having;

    expect(follow(over('amount'))).toBeUndefined();
    expect(follow(and(over('orders'), over('amount')))).toEqual(
      and(over('orders')),
    );
    expect(follow(and(over('amount'), over('amount', 5)))).toBeUndefined();
    // A rule that no longer constrains holds for every group, and so does
    // an OR with it on one side.
    expect(follow(or(over('orders'), over('amount')))).toBeUndefined();
    expect(
      follow(or(and(over('orders'), over('amount')), over('orders', 99))),
    ).toEqual(or(and(over('orders')), over('orders', 99)));
    expect(
      follow({ type: 'IS_NULL', metric: 'amount' } as never),
    ).toBeUndefined();
  });

  it('takes a having rule on what it may not compare', () => {
    const metrics = [
      count,
      amount,
      { alias: 'sample', type: 'ANY', field: 'amount' },
    ] as AnalysisViewConfig['metrics'];
    const having = and(over('orders'), over('amount'), over('sample'));
    expect(
      withoutDangling(
        { groups: grouped, metrics, having },
        { havingMetrics: ['NUMERIC'] },
      ).having,
    ).toEqual(and(over('amount')));
    expect(
      withoutDangling(
        { groups: grouped, metrics, having },
        { moments: new Set(['amount']) },
      ).having,
    ).toEqual(and(over('orders')));
  });

  it('takes the having with the last dimension', () => {
    expect(
      withoutDangling({
        groups: [],
        metrics: [count] as AnalysisViewConfig['metrics'],
        having: over('orders'),
      }).having,
    ).toBeUndefined();
  });
});

describe('withElements', () => {
  it('takes a derived metric over a derived metric that left', () => {
    const chained = ordersDefinition({
      fields: [
        ...ordersDefinition().fields,
        {
          name: 'items',
          label: 'Items',
          kind: 'array',
          elements: [{ name: 'qty', label: 'Qty', kind: 'number' }],
        },
      ],
      analysis: {
        ...ordersDefinition().analysis!,
        expressions: true,
        elements: [
          {
            path: 'items',
            aggregations: [
              { field: 'qty', groups: [], functions: ['SUM' as never] },
            ],
          },
        ],
      },
    });
    const rescoped = withElements(
      analysisConfig({
        metrics: [
          count,
          amount,
          derived('perOrder', 'amount', 'orders'),
          derived('perOrderShare', 'perOrder', 'orders'),
        ] as AnalysisViewConfig['metrics'],
      }),
      withLevel([], 'items'),
      chained,
      chained.analysis!,
    );
    expect(rescoped.metrics).toEqual([count]);
  });
});

describe('an edit to the question', () => {
  /** Two summable fields and a sample value, with having and derived metrics. */
  function definition(): DataViewDefinition {
    return ordersDefinition({
      fields: [
        { name: 'id', label: 'Order', kind: 'string', sortable: true },
        { name: 'warehouse', label: 'Warehouse', kind: 'string' },
        { name: 'amount', label: 'Amount', kind: 'number', sortable: true },
        { name: 'cost', label: 'Cost', kind: 'number', sortable: true },
        {
          name: 'items',
          label: 'Items',
          kind: 'array',
          elements: [
            { name: 'sku', label: 'SKU', kind: 'string' },
            { name: 'qty', label: 'Qty', kind: 'number' },
          ],
        },
      ],
      analysis: {
        count: true,
        expressions: true,
        having: true,
        fields: [
          {
            field: 'warehouse',
            groups: [AggregationGroupType.TERMS],
            functions: [],
          },
          {
            field: 'amount',
            groups: [],
            functions: [AggregationFunction.SUM],
            any: true,
          },
          { field: 'cost', groups: [], functions: [AggregationFunction.SUM] },
        ],
        elements: [
          {
            path: 'items',
            aggregations: [
              {
                field: 'sku',
                groups: [AggregationGroupType.TERMS],
                functions: [],
              },
              {
                field: 'qty',
                groups: [],
                functions: [AggregationFunction.SUM],
              },
            ],
          },
        ],
      },
    });
  }

  async function editor(config: Partial<AnalysisViewConfig>) {
    const store = new MemoryViewStore({
      instances: [
        {
          id: 'orders-1',
          definitionId: 'orders',
          title: 'By warehouse',
          scope: 'personal',
          revision: '1',
          config: analysisConfig(config),
        },
      ],
    });
    const engine = new ViewEngine({
      resources: resourcesOf([definition()], () => testSource()),
      store,
    });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return { opened, analysis: useAnalysisEditor(opened.runtime) };
    });
    await waitFor(() => expect(result.current.opened.runtime).not.toBeNull());
    const draft = () => {
      const current = result.current.opened.runtime!.getSnapshot().draft;
      return current as AnalysisViewConfig;
    };
    return { analysis: () => result.current.analysis, draft };
  }

  it('starts from a config that admits', async () => {
    const { analysis } = await editor({
      metrics: [
        count,
        amount,
        derived('perOrder', 'amount', 'orders'),
      ] as AnalysisViewConfig['metrics'],
      having: and(over('orders'), over('amount')),
    });
    expect(analysis().issues).toEqual([]);
  });

  it('takes the having rules and the derived metrics on a metric removed', async () => {
    const { analysis, draft } = await editor({
      metrics: [
        count,
        amount,
        cost,
        derived('perOrder', 'amount', 'orders'),
        derived('perOrderShare', 'perOrder', 'orders'),
        derived('costPerOrder', 'cost', 'orders'),
      ] as AnalysisViewConfig['metrics'],
      having: and(over('orders'), over('amount'), over('perOrder')),
      sort: [{ alias: 'perOrderShare', direction: 'DESC' as never }],
    });

    act(() => analysis().removeMetric(1));

    expect(draft().metrics.map(metric => metric.alias)).toEqual([
      'orders',
      'cost',
      'costPerOrder',
    ]);
    expect(draft().having).toEqual(and(over('orders')));
    expect(draft().sort).toEqual([]);
    expect(analysis().issues).toEqual([]);
  });

  it('takes a having that names only the metric removed', async () => {
    const { analysis, draft } = await editor({
      metrics: [count, amount] as AnalysisViewConfig['metrics'],
      having: over('amount'),
    });

    act(() => analysis().removeMetric(1));

    expect('having' in draft()).toBe(false);
    expect(analysis().issues).toEqual([]);
  });

  it('follows a change of summary that makes a metric one nothing reads', async () => {
    const { analysis, draft } = await editor({
      metrics: [
        count,
        amount,
        derived('perOrder', 'amount', 'orders'),
      ] as AnalysisViewConfig['metrics'],
      having: and(over('orders'), over('amount')),
    });

    // The alias stays: the card swaps the summary under it.
    act(() =>
      analysis().replaceMetric(1, {
        alias: 'amount',
        type: 'ANY',
        field: 'amount',
      }),
    );

    expect(draft().metrics.map(metric => metric.alias)).toEqual([
      'orders',
      'amount',
    ]);
    expect(draft().having).toEqual(and(over('orders')));
    expect(analysis().issues).toEqual([]);
  });

  it('keeps what reads a metric given a display name', async () => {
    const having = and(over('orders'), over('amount'));
    const { analysis, draft } = await editor({
      metrics: [
        count,
        amount,
        derived('perOrder', 'amount', 'orders'),
      ] as AnalysisViewConfig['metrics'],
      having,
    });

    act(() => analysis().renameMetric(1, 'Revenue'));

    expect(draft().metrics).toHaveLength(3);
    expect(draft().having).toEqual(having);
    expect(analysis().issues).toEqual([]);
  });

  it('takes the having with the last dimension', async () => {
    const { analysis, draft } = await editor({
      having: over('orders'),
    });

    act(() => analysis().removeGroup(0));

    expect('having' in draft()).toBe(false);
    expect(analysis().issues).toEqual([]);
  });

  it('takes the having with what an expansion leaves behind', async () => {
    const { analysis, draft } = await editor({
      metrics: [count, amount] as AnalysisViewConfig['metrics'],
      having: over('amount'),
    });

    act(() => analysis().expand('items'));

    expect(draft().elements).toEqual([{ path: 'items' }]);
    expect(draft().metrics).toEqual([count]);
    expect('having' in draft()).toBe(false);
    expect(analysis().issues).toEqual([]);
  });

  it('is not made when it would leave nothing to measure', async () => {
    const metrics = [
      amount,
      derived('ratio', 'amount', 'amount'),
    ] as AnalysisViewConfig['metrics'];
    const { analysis, draft } = await editor({ metrics });

    act(() => analysis().removeMetric(0));

    expect(draft().metrics).toEqual(metrics);
  });
});
