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
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  FilterOperator,
  SortDirection,
} from '@ahoo-wang/fetcher-wow';
import { describe, expect, it } from 'vitest';
import {
  analysisScope,
  builtinFieldKinds,
  compileAnalysis,
  compileAnalysisTotals,
  defaultAnalysisConfig,
  projectAnalysis,
  qualify,
  resultSchema,
  validateAnalysis,
  type AnalysisCapability,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type Issue,
} from '../src/index.js';

const context = { now: new Date('2026-09-16T10:30:00.000Z'), timeZone: 'UTC' };

const capability: AnalysisCapability = {
  count: true,
  fields: [
    {
      field: 'warehouse',
      groups: [AggregationGroupType.TERMS],
      functions: [],
    },
    {
      field: 'createdAt',
      groups: [AggregationGroupType.DATE_HISTOGRAM],
      functions: [],
      dateUnits: [AggregationDateUnit.MONTH],
    },
    {
      field: 'amount',
      groups: [AggregationGroupType.HISTOGRAM],
      functions: [AggregationFunction.SUM, AggregationFunction.AVG],
      distinctCount: true,
      percentile: true,
      any: true,
    },
  ],
  expressions: true,
  having: true,
};

function definition(
  overrides: Partial<DataViewDefinition> = {},
): DataViewDefinition {
  return {
    id: 'orders',
    title: 'Orders',
    kind: 'data',
    source: 'orders',
    fields: [
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
      { name: 'createdAt', label: 'Created', kind: 'datetime' },
      {
        name: 'amount',
        label: 'Amount',
        kind: 'number',
        numberFormat: { style: 'currency', currency: 'CNY' },
      },
    ],
    analysis: capability,
    ...overrides,
  };
}

function config(
  overrides: Partial<AnalysisViewConfig> = {},
): AnalysisViewConfig {
  return {
    filter: { op: 'and', children: [] },
    filterMode: 'simple',
    refresh: { interval: null },
    kind: 'analysis',
    groups: [{ type: 'TERMS', field: 'warehouse', alias: 'wh' }],
    metrics: [{ type: 'COUNT', alias: 'orders' }],
    sort: [{ alias: 'orders', direction: 'DESC' }],
    limit: 100,
    layout: 'chart',
    table: { columns: [] },
    chart: {
      type: 'bar',
      cartesian: { x: 'wh', series: [{ metric: 'orders' }] },
    },
    ...overrides,
  };
}

const codes = (issues: Issue[]) =>
  issues.filter(i => i.severity === 'error').map(i => i.code);
const check = (overrides: Partial<AnalysisViewConfig>) =>
  codes(validateAnalysis(definition(), config(overrides), builtinFieldKinds));

describe('defaultAnalysisConfig', () => {
  it('starts from COUNT and the first groupable field', () => {
    const built = defaultAnalysisConfig(definition());
    expect(built.metrics[0]).toEqual({ type: 'COUNT', alias: 'count' });
    expect(built.groups[0]).toMatchObject({
      type: 'TERMS',
      field: 'warehouse',
    });
    expect(validateAnalysis(definition(), built, builtinFieldKinds)).toEqual(
      [],
    );
  });

  it('falls back through the capability when COUNT is not declared', () => {
    const numeric = defaultAnalysisConfig(
      definition({ analysis: { ...capability, count: false } }),
    );
    expect(numeric.metrics[0]).toMatchObject({
      type: 'NUMERIC',
      function: AggregationFunction.SUM,
    });

    const distinct = defaultAnalysisConfig(
      definition({
        analysis: {
          count: false,
          fields: [
            { field: 'amount', groups: [], functions: [], distinctCount: true },
          ],
        },
      }),
    );
    expect(distinct.metrics[0].type).toBe('DISTINCT_COUNT');
    // No groupable field: an ungrouped aggregation shown as a metric card.
    expect(distinct.groups).toEqual([]);
    expect(distinct.layout).toBe('table');
    expect(distinct.chart.type).toBe('metric');

    const percentile = defaultAnalysisConfig(
      definition({
        analysis: {
          count: false,
          fields: [
            { field: 'amount', groups: [], functions: [], percentile: true },
          ],
        },
      }),
    );
    expect(percentile.metrics[0]).toMatchObject({
      type: 'PERCENTILE',
      percentile: 95,
    });

    const any = defaultAnalysisConfig(
      definition({
        analysis: {
          count: false,
          fields: [{ field: 'amount', groups: [], functions: [], any: true }],
        },
      }),
    );
    expect(any.metrics[0].type).toBe('ANY');
  });

  it('keeps the limit inside both ceilings', () => {
    const built = defaultAnalysisConfig(
      definition({
        analysis: {
          ...capability,
          limits: { defaultLimit: 5000, maxLimit: 500 },
        },
      }),
    );
    expect(built.limit).toBe(500);
  });

  it('refuses a definition it cannot build from', () => {
    expect(() =>
      defaultAnalysisConfig(definition({ analysis: undefined })),
    ).toThrow(/no analysis capability/);
    expect(() =>
      defaultAnalysisConfig(
        definition({ analysis: { count: false, fields: [] } }),
      ),
    ).toThrow(/no usable metric/);
  });
});

describe('validateAnalysis', () => {
  it('admits a config that matches its capability', () => {
    expect(validateAnalysis(definition(), config(), builtinFieldKinds)).toEqual(
      [],
    );
  });

  it('keeps aliases unique, single-segment and unreserved', () => {
    // The chart also breaks, because its x alias no longer names a group.
    expect(
      check({
        groups: [{ type: 'TERMS', field: 'warehouse', alias: 'orders' }],
      }),
    ).toContain('analysis.alias.duplicate');
    expect(
      check({
        groups: [{ type: 'TERMS', field: 'warehouse', alias: 'a.b' }],
        chart: {
          type: 'bar',
          cartesian: { x: 'a.b', series: [{ metric: 'orders' }] },
        },
      }),
    ).toEqual(['analysis.alias.not-a-segment']);
    expect(
      check({
        groups: [{ type: 'TERMS', field: 'warehouse', alias: '__wow_x' }],
        chart: {
          type: 'bar',
          cartesian: { x: '__wow_x', series: [{ metric: 'orders' }] },
        },
      }),
    ).toEqual(['analysis.alias.reserved']);
  });

  it('checks each group against the field capability', () => {
    expect(
      check({
        groups: [{ type: 'TERMS', field: 'gone', alias: 'wh' }],
      }),
    ).toEqual(['analysis.field.unknown']);
    expect(
      check({
        groups: [
          { type: 'HISTOGRAM', field: 'warehouse', alias: 'wh', interval: 1 },
        ],
      }),
    ).toEqual(['analysis.group.unsupported']);
    expect(
      check({
        groups: [
          { type: 'HISTOGRAM', field: 'amount', alias: 'wh', interval: 0 },
        ],
      }),
    ).toEqual(['analysis.group.interval-not-positive']);
    expect(
      check({
        groups: [
          {
            type: 'DATE_HISTOGRAM',
            field: 'createdAt',
            alias: 'wh',
            unit: 'DAY',
          },
        ],
      }),
    ).toEqual(['analysis.group.unit-unsupported']);
    expect(
      check({
        groups: [
          {
            type: 'DATE_HISTOGRAM',
            field: 'createdAt',
            alias: 'wh',
            unit: 'MONTH',
            timeZone: '  ',
          },
        ],
      }),
    ).toEqual(['analysis.group.blank-time-zone']);
    expect(
      check({
        groups: [
          { type: 'TERMS', field: 'warehouse', alias: 'wh', missingKey: '' },
        ],
      }),
    ).toEqual(['analysis.group.blank-missing-key']);
  });

  it('checks each metric against the field capability', () => {
    expect(
      check({
        metrics: [
          {
            type: 'NUMERIC',
            alias: 'orders',
            function: 'MIN',
            expression: { type: 'FIELD', field: 'amount' },
          },
        ],
      }),
    ).toEqual(['analysis.function.unsupported']);

    expect(
      check({
        metrics: [{ type: 'ANY', alias: 'orders', field: 'warehouse' }],
      }),
    ).toEqual(['analysis.any.undeclared']);

    expect(
      check({
        metrics: [
          {
            type: 'PERCENTILE',
            alias: 'orders',
            expression: { type: 'FIELD', field: 'amount' },
            percentile: 100,
          },
        ],
      }),
    ).toEqual(['analysis.percentile.out-of-range']);

    expect(
      check({
        metrics: [{ type: 'COUNT', alias: 'orders' }],
      }),
    ).toEqual([]);
    expect(
      codes(
        validateAnalysis(
          definition({ analysis: { ...capability, count: false } }),
          config(),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['analysis.count.undeclared']);
  });

  it('checks expressions recursively', () => {
    expect(
      check({
        metrics: [
          {
            type: 'NUMERIC',
            alias: 'orders',
            function: 'SUM',
            expression: {
              type: 'BINARY',
              operator: 'DIVIDE',
              left: { type: 'FIELD', field: 'amount' },
              right: { type: 'CONSTANT', value: 0 },
            },
          },
        ],
      }),
    ).toEqual(['analysis.expression.divide-by-zero']);

    expect(
      check({
        metrics: [
          {
            type: 'NUMERIC',
            alias: 'orders',
            function: 'SUM',
            expression: {
              type: 'BINARY',
              operator: 'ADD',
              left: { type: 'FIELD', field: 'amount' },
              right: { type: 'CONSTANT', value: Number.POSITIVE_INFINITY },
            },
          },
        ],
      }),
    ).toEqual(['analysis.constant.not-finite']);

    // A config arrives from a store: a metric with no expression, or one of a
    // shape this version does not know, is a finding rather than a crash.
    expect(
      check({
        metrics: [
          { type: 'NUMERIC', alias: 'orders', function: 'SUM' } as never,
        ],
      }),
    ).toEqual(['analysis.expression.malformed']);
    expect(
      check({
        metrics: [
          {
            type: 'NUMERIC',
            alias: 'orders',
            function: 'SUM',
            expression: { type: 'LAMBDA' },
          } as never,
        ],
      }),
    ).toEqual(['analysis.expression.malformed']);

    // The same contract covers a BINARY whose sides went missing: the DIVIDE
    // check must not dereference a right side that is not there.
    expect(
      check({
        metrics: [
          {
            type: 'NUMERIC',
            alias: 'orders',
            function: 'SUM',
            expression: {
              type: 'BINARY',
              operator: 'DIVIDE',
              left: { type: 'FIELD', field: 'amount' },
            } as never,
          },
        ],
      }),
    ).toEqual(['analysis.expression.malformed']);

    expect(
      codes(
        validateAnalysis(
          definition({ analysis: { ...capability, expressions: false } }),
          config({
            metrics: [
              {
                type: 'NUMERIC',
                alias: 'orders',
                function: 'SUM',
                expression: {
                  type: 'BINARY',
                  operator: 'ADD',
                  left: { type: 'FIELD', field: 'amount' },
                  right: { type: 'CONSTANT', value: 1 },
                },
              },
            ],
          }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['analysis.expressions.undeclared']);
  });

  it('lets DERIVED reach only earlier non-ANY metrics', () => {
    const forward = check({
      metrics: [
        {
          type: 'DERIVED',
          alias: 'ratio',
          expression: { type: 'METRIC_REF', metric: 'orders' },
        },
        { type: 'COUNT', alias: 'orders' },
      ],
      sort: [],
      chart: {
        type: 'bar',
        cartesian: { x: 'wh', series: [{ metric: 'ratio' }] },
      },
    });
    expect(forward).toEqual(['analysis.derived.unknown-metric']);

    const backward = check({
      metrics: [
        { type: 'COUNT', alias: 'orders' },
        {
          type: 'DERIVED',
          alias: 'ratio',
          expression: {
            type: 'BINARY',
            operator: 'DIVIDE',
            left: { type: 'METRIC_REF', metric: 'orders' },
            right: { type: 'CONSTANT', value: 2 },
          },
        },
      ],
    });
    expect(backward).toEqual([]);
  });

  it('reports a DERIVED expression a config from a store may hold', () => {
    // No expression at all, or one missing a side, is a finding rather than a
    // crash: `derivedIssues` walks whatever the store returned.
    expect(
      check({
        metrics: [
          { type: 'COUNT', alias: 'orders' },
          { type: 'DERIVED', alias: 'broken' } as never,
        ],
        sort: [],
        chart: {
          type: 'bar',
          cartesian: { x: 'wh', series: [{ metric: 'broken' }] },
        },
      }),
    ).toEqual(['analysis.expression.malformed']);

    expect(
      check({
        metrics: [
          { type: 'COUNT', alias: 'orders' },
          {
            type: 'DERIVED',
            alias: 'broken',
            expression: {
              type: 'BINARY',
              operator: 'ADD',
              left: { type: 'METRIC_REF', metric: 'orders' },
            } as never,
          },
        ],
        sort: [],
        chart: {
          type: 'bar',
          cartesian: { x: 'wh', series: [{ metric: 'broken' }] },
        },
      }),
    ).toEqual(['analysis.expression.malformed']);
  });

  it('lets having reach only non-ANY metrics', () => {
    expect(
      check({
        having: { type: 'CONDITION', metric: 'gone', operator: 'GT', value: 1 },
      }),
    ).toEqual(['analysis.having.unknown-metric']);

    expect(
      check({
        metrics: [
          { type: 'COUNT', alias: 'orders' },
          { type: 'ANY', alias: 'first', field: 'amount' },
        ],
        having: {
          type: 'CONDITION',
          metric: 'first',
          operator: 'GT',
          value: 1,
        },
      }),
    ).toEqual(['analysis.having.unknown-metric']);

    expect(
      check({
        having: {
          type: 'AND',
          operands: [
            { type: 'CONDITION', metric: 'orders', operator: 'GT', value: 1 },
          ],
        },
      }),
    ).toEqual([]);
  });

  it('refuses a having the capability never declared', () => {
    expect(
      codes(
        validateAnalysis(
          definition({ analysis: { ...capability, having: false } }),
          config({
            having: {
              type: 'CONDITION',
              metric: 'orders',
              operator: 'GT',
              value: 1,
            },
          }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['analysis.having.undeclared']);
  });

  it('reports a having of a shape this version does not know', () => {
    expect(
      check({
        having: { type: 'AND', operands: undefined } as never,
      }),
    ).toEqual(['analysis.having.malformed']);

    // Not even a plain number reaches the walk, and a null inside a group's
    // operands is a finding at that depth rather than a crash.
    expect(check({ having: 42 as never })).toEqual([
      'analysis.having.malformed',
    ]);
    expect(
      check({
        having: { type: 'AND', operands: [null] } as never,
      }),
    ).toEqual(['analysis.having.malformed']);
  });

  it('bounds the limit and the counts', () => {
    expect(check({ limit: 0 })).toEqual(['analysis.limit.not-positive']);
    expect(check({ limit: 999_999 })).toEqual(['analysis.limit.too-large']);
    expect(
      codes(
        validateAnalysis(
          definition({
            analysis: {
              ...capability,
              limits: { maxGroups: 0, maxMetrics: 0 },
            },
          }),
          config(),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['analysis.groups.too-many', 'analysis.metrics.too-many']);
  });

  it('checks sort and table columns against the aliases in play', () => {
    expect(check({ sort: [{ alias: 'gone', direction: 'ASC' }] })).toEqual([
      'analysis.sort.unknown-alias',
    ]);
    expect(check({ table: { columns: [{ alias: 'gone' }] } })).toEqual([
      'analysis.column.unknown-alias',
    ]);
    expect(
      check({ table: { columns: [{ alias: 'wh' }, { alias: 'wh' }] } }),
    ).toEqual(['analysis.column.duplicate']);
  });

  it('reports an element path the capability never declared', () => {
    expect(check({ elements: [{ path: 'items' }] })).toEqual([
      'analysis.element.undeclared',
    ]);
  });

  it('reports a definition without the analysis capability', () => {
    expect(
      codes(
        validateAnalysis(
          definition({ analysis: undefined }),
          config(),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['analysis.capability.missing']);
  });
});

describe('element scope', () => {
  const withElements = definition({
    analysis: {
      ...capability,
      elements: [
        {
          path: 'items',
          fields: [{ name: 'sku', label: 'SKU', kind: 'string' }],
          aggregations: [
            {
              field: 'sku',
              groups: [AggregationGroupType.TERMS],
              functions: [],
            },
          ],
        },
      ],
    },
  });

  it('qualifies element fields with their path', () => {
    expect(qualify('items', 'sku')).toBe('items.sku');
    expect(qualify('items', 'items.sku')).toBe('items.sku');
  });

  it('exposes element fields only once the element is configured', () => {
    const capability_ = withElements.analysis;
    if (!capability_) throw new Error('unreachable');

    const without = analysisScope(withElements, capability_, { elements: [] });
    expect(without.aggregations.has('items.sku')).toBe(false);
    expect(without.declaredPaths.has('items')).toBe(true);

    const scope = analysisScope(withElements, capability_, {
      elements: [{ path: 'items' }],
    });
    expect(scope.fields.get('items.sku')?.label).toBe('SKU');
    expect(scope.aggregations.has('items.sku')).toBe(true);
  });

  it('groups by an element field once the element is expanded', () => {
    const built = config({
      elements: [{ path: 'items' }],
      groups: [{ type: 'TERMS', field: 'items.sku', alias: 'sku' }],
      chart: {
        type: 'bar',
        cartesian: { x: 'sku', series: [{ metric: 'orders' }] },
      },
    });
    expect(
      codes(validateAnalysis(withElements, built, builtinFieldKinds)),
    ).toEqual([]);

    const query = compileAnalysis(
      withElements,
      built,
      builtinFieldKinds,
      context,
    );
    expect(query.elements).toEqual([{ path: 'items' }]);
    expect(query.groupBy?.[0]).toMatchObject({ field: 'items.sku' });
  });
});

describe('compileAnalysis', () => {
  it('maps the config onto the aggregation protocol', () => {
    const query = compileAnalysis(
      definition(),
      config({
        filter: {
          op: 'and',
          children: [{ field: 'warehouse', operator: 'EQ', value: 'SH' }],
        },
        having: {
          type: 'CONDITION',
          metric: 'orders',
          operator: 'GT',
          value: 10,
        },
      }),
      builtinFieldKinds,
      context,
    );

    expect(query).toMatchObject({
      filter: { op: FilterOperator.EQ, field: 'warehouse' },
      groupBy: [
        {
          type: AggregationGroupType.TERMS,
          field: 'warehouse',
          alias: 'wh',
        },
      ],
      metrics: [{ type: AggregationMetricType.COUNT, alias: 'orders' }],
      sort: [{ field: 'orders', direction: SortDirection.DESC }],
      limit: 100,
      having: { metric: 'orders', value: 10 },
    });
  });

  it('carries a metric-level filter, which is what a funnel needs', () => {
    const query = compileAnalysis(
      definition(),
      config({
        metrics: [
          {
            type: 'COUNT',
            alias: 'orders',
            filter: {
              op: 'and',
              children: [{ field: 'warehouse', operator: 'EQ', value: 'SH' }],
            },
          },
        ],
      }),
      builtinFieldKinds,
      context,
    );
    expect(query.metrics[0]).toMatchObject({
      filter: { op: FilterOperator.EQ, field: 'warehouse' },
    });
  });

  it('maps every metric and group shape', () => {
    const query = compileAnalysis(
      definition(),
      config({
        groups: [
          {
            type: 'DATE_HISTOGRAM',
            field: 'createdAt',
            alias: 'month',
            unit: 'MONTH',
            timeZone: 'UTC',
            dense: true,
          },
          { type: 'HISTOGRAM', field: 'amount', alias: 'bucket', interval: 10 },
        ],
        metrics: [
          {
            type: 'NUMERIC',
            alias: 'total',
            function: 'SUM',
            expression: {
              type: 'BINARY',
              operator: 'MULTIPLY',
              left: { type: 'FIELD', field: 'amount' },
              right: { type: 'CONSTANT', value: 2 },
            },
          },
          { type: 'ANY', alias: 'first', field: 'amount' },
          {
            type: 'DISTINCT_COUNT',
            alias: 'skus',
            expression: { type: 'FIELD', field: 'amount' },
          },
          {
            type: 'PERCENTILE',
            alias: 'p95',
            expression: { type: 'FIELD', field: 'amount' },
            percentile: 95,
          },
          {
            type: 'DERIVED',
            alias: 'ratio',
            expression: {
              type: 'BINARY',
              operator: 'DIVIDE',
              left: { type: 'METRIC_REF', metric: 'total' },
              right: { type: 'CONSTANT', value: 2 },
            },
          },
        ],
        sort: [],
        layout: 'table',
        table: { columns: [] },
        chart: {
          type: 'heatmap',
          heatmap: { x: 'month', y: 'bucket', value: 'total' },
        },
      }),
      builtinFieldKinds,
      context,
    );

    expect(query.groupBy).toHaveLength(2);
    expect(query.metrics.map(metric => metric.type)).toEqual([
      AggregationMetricType.NUMERIC,
      AggregationMetricType.ANY,
      AggregationMetricType.DISTINCT_COUNT,
      AggregationMetricType.PERCENTILE,
      AggregationMetricType.DERIVED,
    ]);
  });

  it('runs totals as their own ungrouped query, or not at all', () => {
    expect(
      compileAnalysisTotals(definition(), config(), builtinFieldKinds, context),
    ).toBeNull();

    const totals = compileAnalysisTotals(
      definition(),
      config({ table: { columns: [], totals: true } }),
      builtinFieldKinds,
      context,
    );
    expect(totals?.groupBy).toBeUndefined();
    expect(totals?.metrics).toHaveLength(1);
  });

  it('refuses a definition without the analysis capability', () => {
    expect(() =>
      compileAnalysis(
        definition({ analysis: undefined }),
        config(),
        builtinFieldKinds,
        context,
      ),
    ).toThrow(/no analysis capability/);
  });
});

describe('projectAnalysis', () => {
  const rows = [
    { wh: 'SH', orders: 30 },
    { wh: 'BJ', orders: 10 },
  ];

  it('lists group columns before metric columns', () => {
    expect(resultSchema(config())).toEqual(['wh', 'orders']);
    const view = projectAnalysis(
      definition(),
      config({ layout: 'table' }),
      rows,
    );
    expect(view.columns).toEqual([
      {
        alias: 'wh',
        label: 'Warehouse',
        role: 'group',
        width: undefined,
        pinned: undefined,
        numberFormat: undefined,
      },
      {
        alias: 'orders',
        label: 'orders',
        role: 'metric',
        width: undefined,
        pinned: undefined,
        numberFormat: undefined,
      },
    ]);
    expect(view.rows).toEqual(rows);
    expect(view.chart).toBeUndefined();
  });

  it('honours declared table columns and reports totals separately', () => {
    const view = projectAnalysis(
      definition(),
      config({
        layout: 'table',
        table: { columns: [{ alias: 'orders', width: 90 }], totals: true },
      }),
      rows,
      [{ orders: 40 }],
    );
    expect(view.columns.map(column => column.alias)).toEqual(['orders']);
    expect(view.columns[0].width).toBe(90);
    expect(view.totals).toEqual({ orders: 40 });
  });

  it('labels a numeric metric with its source field', () => {
    const view = projectAnalysis(
      definition(),
      config({
        layout: 'table',
        metrics: [
          {
            type: 'NUMERIC',
            alias: 'total',
            function: 'SUM',
            expression: { type: 'FIELD', field: 'amount' },
          },
        ],
        sort: [],
        chart: {
          type: 'bar',
          cartesian: { x: 'wh', series: [{ metric: 'total' }] },
        },
      }),
      [],
    );
    expect(view.columns[1]).toMatchObject({
      label: 'Amount',
      numberFormat: { style: 'currency', currency: 'CNY' },
    });
  });
});
