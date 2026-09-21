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

/**
 * What a validated analysis becomes: the aggregation query Wow executes, the
 * ungrouped totals beside it, and the per-metric filters that decide, record
 * by record, which rows a metric counts.
 */

import {
  AggregationGroupType,
  AggregationMetricType,
  DerivedExpressionType,
  FilterOperator,
  SortDirection,
} from '@ahoo-wang/fetcher-wow';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  compileAnalysis,
  compileAnalysisTotals,
  validateAnalysis,
  type AnalysisViewConfig,
  withFieldKinds,
  DEFAULT_RUNTIME_LIMITS,
  type FilterOperatorName,
  type FilterTree,
  type FilterValue,
} from '../src/index.js';
import {
  analysisContext as context,
  analysisDefinition as definition,
  analysisKernelConfig as config,
  errorCodes as codes,
} from './fixtures/analysis.js';

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

/**
 * A metric's own filter reaches `compileAnalysis` either way, and nothing was
 * admitting it: an unknown field went all the way to `compileFilter`, which
 * answers that by throwing. Wow also allows less here than at the root,
 * because a metric filter decides per record whether that record counts, so
 * it has one record's value to work with.
 */
describe('metric filters', () => {
  const wide = () =>
    definition({
      fields: [
        ...definition().fields,
        {
          name: 'items',
          label: 'Items',
          kind: 'array',
          elements: [{ name: 'sku', label: 'SKU', kind: 'string' }],
        },
        {
          name: 'lines',
          label: 'Lines',
          kind: 'elementMatch',
          elements: [{ name: 'status', label: 'Status', kind: 'string' }],
        },
        { name: '@search', label: 'Search', kind: 'search' },
        { name: '@ownerId', label: 'Created by', kind: 'ownerId' },
      ],
    });

  const withFilter = (filter: FilterTree) =>
    validateAnalysis(
      wide(),
      config({ metrics: [{ type: 'COUNT', alias: 'orders', filter }] }),
      builtinFieldKinds,
    );

  const leaf = (
    field: string,
    operator: FilterOperatorName,
    value: FilterValue,
  ): FilterTree => ({ op: 'and', children: [{ field, operator, value }] });

  it('admits a filter that names a real field', () => {
    expect(codes(withFilter(leaf('warehouse', 'EQ', 'WH-1')))).toEqual([]);
  });

  it('reports a field that does not exist', () => {
    // This used to reach compileFilter, which answers an unknown field by
    // throwing rather than by reporting it.
    expect(codes(withFilter(leaf('ghost', 'EQ', 'x')))).toEqual([
      'filter.field.unknown',
    ]);
  });

  it('reports a value the field cannot take', () => {
    expect(codes(withFilter(leaf('amount', 'EQ', 'not-a-number')))).toEqual([
      'filter.value.expected-number',
    ]);
  });

  it('paths an issue under the metric that carries the filter', () => {
    const issues = validateAnalysis(
      wide(),
      config({
        metrics: [
          { type: 'COUNT', alias: 'orders' },
          {
            type: 'COUNT',
            alias: 'other',
            filter: leaf('ghost', 'EQ', 'x'),
          },
        ],
        sort: [],
        chart: {
          type: 'bar',
          cartesian: { x: 'wh', series: [{ metric: 'orders' }] },
        },
      }),
      builtinFieldKinds,
    );

    expect(issues.map(found => found.path)).toContainEqual([
      'metrics',
      1,
      'filter',
      'children',
      0,
    ]);
  });

  it.each([
    ['items', 'IN', ['a'] as FilterValue],
    ['lines', 'ELEMENT_MATCH', { op: 'and', children: [] } as FilterValue],
  ])('refuses %s, which holds several values', (field, operator, value) => {
    // No single value to test, so the question is "does some entry match",
    // which is an element question rather than a whole-record one.
    expect(
      codes(withFilter(leaf(field, operator as FilterOperatorName, value))),
    ).toEqual(['analysis.metricFilter.not-scalar']);
  });

  it('refuses a search, which matches text rather than a value', () => {
    expect(codes(withFilter(leaf('@search', 'SEARCH', 'premium')))).toEqual([
      'analysis.metricFilter.not-scalar',
    ]);
  });

  it('asks the registry rather than the kind id', () => {
    // `withFieldKinds` lets an app replace a built-in kind, so a replacement
    // that does test one value must be usable here.
    const scalarSearch = withFieldKinds(builtinFieldKinds, [
      { ...builtinFieldKinds.get('search')!, scalar: true },
    ]);

    expect(
      codes(
        validateAnalysis(
          wide(),
          config({
            metrics: [
              {
                type: 'COUNT',
                alias: 'orders',
                filter: leaf('@search', 'SEARCH', 'premium'),
              },
            ],
          }),
          scalarSearch,
        ),
      ),
    ).toEqual([]);
  });

  it('refuses a custom kind that says it tests no single value', () => {
    const custom = withFieldKinds(builtinFieldKinds, [
      { ...builtinFieldKinds.get('string')!, scalar: false },
    ]);

    expect(
      codes(
        validateAnalysis(
          wide(),
          config({
            metrics: [
              {
                type: 'COUNT',
                alias: 'orders',
                filter: leaf('warehouse', 'EQ', 'WH-1'),
              },
            ],
          }),
          custom,
        ),
      ),
    ).toEqual(['analysis.metricFilter.not-scalar']);
  });

  it('honours a caller that widened the tree limits', () => {
    // The root filter and a metric filter must agree on the budget.
    const deep = (depth: number): FilterTree =>
      depth <= 1
        ? {
            op: 'and',
            children: [{ field: 'warehouse', operator: 'EQ', value: 'x' }],
          }
        : { op: 'and', children: [deep(depth - 1)] };

    const overrides = {
      metrics: [
        { type: 'COUNT' as const, alias: 'orders', filter: deep(12) },
      ] as AnalysisViewConfig['metrics'],
    };

    expect(
      codes(validateAnalysis(wide(), config(overrides), builtinFieldKinds)),
    ).toEqual(['filter.tree.too-deep']);
    expect(
      codes(
        validateAnalysis(wide(), config(overrides), builtinFieldKinds, {
          limits: { ...DEFAULT_RUNTIME_LIMITS, maxFilterDepth: 16 },
        }),
      ),
    ).toEqual([]);
  });

  it('admits a metadata filter, unlike an element predicate', () => {
    // An element has no owner, but a metric filter is looking at a whole
    // record and that is exactly what OWNER_ID asks about.
    expect(codes(withFilter(leaf('@ownerId', 'OWNER_ID', 'u-1')))).toEqual([]);
  });

  it('refuses a condition with no value', () => {
    // Not the rule the filter panel follows. An empty condition is dropped at
    // compile, so the metric would silently count every record instead of the
    // subset the filter was meant to name.
    expect(codes(withFilter(leaf('warehouse', 'EQ', '')))).toEqual([
      'analysis.metricFilter.incomplete',
    ]);
  });

  it.each([
    ['no children at all', { op: 'and', children: [] } as FilterTree],
    [
      'nothing but empty groups',
      {
        op: 'and',
        children: [{ op: 'or', children: [] }],
      } as FilterTree,
    ],
  ])('refuses a filter with %s', (_name, tree) => {
    // It compiles to MATCH_ALL, so the metric covers every record — the same
    // silent widening as an empty condition, through another door.
    expect(codes(withFilter(tree))).toEqual(['analysis.metricFilter.empty']);
  });

  it('paths an empty filter at the filter itself', () => {
    const issues = validateAnalysis(
      wide(),
      config({
        metrics: [
          {
            type: 'COUNT',
            alias: 'orders',
            filter: { op: 'and', children: [] },
          },
        ],
      }),
      builtinFieldKinds,
    );

    expect(issues.map(found => found.path)).toContainEqual([
      'metrics',
      0,
      'filter',
    ]);
  });

  it('says nothing about a value the operator does not take', () => {
    // IS_NULL carries no value, so there is nothing to fill in.
    expect(codes(withFilter(leaf('warehouse', 'IS_NULL', null)))).toEqual([]);
  });

  it('reports the kind before the empty value, never both', () => {
    expect(codes(withFilter(leaf('items', 'IN', [])))).toEqual([
      'analysis.metricFilter.not-scalar',
    ]);
  });

  it('ignores a stale filter left on a DERIVED metric', () => {
    // `compileMetric` never emits one, so refusing the config would block it
    // over a property that changes nothing.
    const stale = {
      type: 'DERIVED',
      alias: 'share',
      expression: { type: 'METRIC_REF', metric: 'orders' },
      filter: leaf('ghost', 'EQ', 'x'),
    } as unknown as AnalysisViewConfig['metrics'][number];

    expect(
      codes(
        validateAnalysis(
          wide(),
          config({ metrics: [{ type: 'COUNT', alias: 'orders' }, stale] }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual([]);
  });

  it('compiles a DERIVED metric without its stale filter', () => {
    // Validation lets the filter through because it changes nothing, so
    // compilation must not turn around and compile it: the field it names is
    // gone, and `compileFilter` answers that by throwing.
    const stale = {
      type: 'DERIVED',
      alias: 'share',
      expression: { type: 'METRIC_REF', metric: 'orders' },
      filter: leaf('ghost', 'EQ', 'x'),
    } as unknown as AnalysisViewConfig['metrics'][number];

    const query = compileAnalysis(
      wide(),
      config({ metrics: [{ type: 'COUNT', alias: 'orders' }, stale] }),
      builtinFieldKinds,
      context,
    );
    expect(query.metrics[1]).toEqual({
      type: AggregationMetricType.DERIVED,
      alias: 'share',
      expression: { type: DerivedExpressionType.METRIC_REF, metric: 'orders' },
    });
  });

  it('stops at the budget rather than walking the tree again', () => {
    // The budget exists so a tree from a store cannot cost unbounded work; a
    // second walk would spend exactly what it refused.
    const wide_ = (leaves: number): FilterTree => ({
      op: 'and',
      children: Array.from({ length: leaves }, () => ({
        field: 'items',
        operator: 'IN' as FilterOperatorName,
        value: [] as FilterValue,
      })),
    });

    expect(codes(withFilter(wide_(400)))).toEqual([
      'filter.tree.too-many-nodes',
    ]);
  });
});
