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
  AGGREGATION_LIMITS,
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  DerivedExpressionType,
  FilterOperator,
  SortDirection,
  aggregation,
} from '@ahoo-wang/fetcher-wow';
import { describe, expect, it } from 'vitest';
import {
  analysisScope,
  builtinFieldKinds,
  compileAnalysis,
  emptyFilter,
  compileAnalysisTotals,
  defaultAnalysisConfig,
  projectAnalysis,
  qualify,
  resultSchema,
  validateAnalysis,
  type AnalysisCapability,
  type AnalysisDerivedExpression,
  type AnalysisExpression,
  type AnalysisHavingExpression,
  type AnalysisMetric,
  type AnalysisViewConfig,
  withFieldKinds,
  DEFAULT_RUNTIME_LIMITS,
  type DataViewDefinition,
  type FilterOperatorName,
  type FilterTree,
  type FilterValue,
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

  it('sorts nothing when there is nothing to group by', () => {
    // An ungrouped aggregation is one row, and Wow refuses to order it:
    // `aggregation.query` throws `sort requires at least one groupBy`. The
    // default is the first thing a user sees, so it must pass that gate.
    const ungrouped = definition({
      analysis: {
        count: true,
        fields: [{ field: 'amount', groups: [], functions: [] }],
      },
    });
    const built = defaultAnalysisConfig(ungrouped);
    expect(built.groups).toEqual([]);
    expect(built.sort).toEqual([]);
    expect(validateAnalysis(ungrouped, built, builtinFieldKinds)).toEqual([]);
    expect(() =>
      aggregation.query(
        compileAnalysis(ungrouped, built, builtinFieldKinds, context),
      ),
    ).not.toThrow();

    // With a group the rows are ordered by the metric, which Wow accepts.
    const grouped = defaultAnalysisConfig(definition());
    expect(grouped.sort).toEqual([{ alias: 'count', direction: 'DESC' }]);
    expect(() =>
      aggregation.query(
        compileAnalysis(definition(), grouped, builtinFieldKinds, context),
      ),
    ).not.toThrow();
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

  it('refuses an alias Wow would not take as a field name', () => {
    // `aggregationAlias` runs the alias through the same admission a field
    // path gets, so a space or a leading digit is a `TypeError` at the
    // factory rather than an issue on the config.
    expect(
      check({
        groups: [{ type: 'TERMS', field: 'warehouse', alias: '2024 wh' }],
        chart: {
          type: 'bar',
          cartesian: { x: '2024 wh', series: [{ metric: 'orders' }] },
        },
      }),
    ).toEqual(['analysis.alias.invalid']);
  });

  it('refuses the same alias sorted twice', () => {
    expect(
      check({
        sort: [
          { alias: 'orders', direction: 'DESC' },
          { alias: 'orders', direction: 'ASC' },
        ],
      }),
    ).toEqual(['analysis.sort.duplicate']);
  });

  it('refuses a gap-filling date grouping next to another grouping', () => {
    const dense = {
      type: 'DATE_HISTOGRAM',
      field: 'createdAt',
      alias: 'month',
      unit: AggregationDateUnit.MONTH,
      dense: true,
    } as const;
    expect(
      check({
        groups: [dense],
        chart: {
          type: 'bar',
          cartesian: { x: 'month', series: [{ metric: 'orders' }] },
        },
      }),
    ).toEqual([]);
    expect(
      check({
        groups: [{ type: 'TERMS', field: 'warehouse', alias: 'wh' }, dense],
        chart: {
          type: 'heatmap',
          heatmap: { x: 'month', y: 'wh', value: 'orders' },
        },
      }),
    ).toEqual(['analysis.group.dense-not-alone']);
  });

  it('applies Wow own maximums when the capability declares no limits', () => {
    const metrics: [AnalysisMetric, ...AnalysisMetric[]] = Array.from(
      { length: AGGREGATION_LIMITS.MAX_METRICS + 1 },
      (_, index) => ({ type: 'COUNT', alias: `m${index}` }),
    ) as [AnalysisMetric, ...AnalysisMetric[]];
    const sort = metrics
      .slice(0, AGGREGATION_LIMITS.MAX_SORT_FIELDS + 1)
      .map(metric => ({ alias: metric.alias, direction: 'DESC' as const }));
    const issues = validateAnalysis(
      definition(),
      config({
        metrics,
        sort,
        chart: {
          type: 'bar',
          cartesian: { x: 'wh', series: [{ metric: 'm0' }] },
        },
      }),
      builtinFieldKinds,
    );
    expect(codes(issues)).toEqual([
      'analysis.metrics.too-many',
      'analysis.sort.too-many',
    ]);
    expect(issues[0].params?.max).toBe(AGGREGATION_LIMITS.MAX_METRICS);
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
    // Anything else is the server's call. This zone is passed straight
    // through to Wow and never resolved here, so gating it on the browser's
    // zone table would refuse names a backend knows — and refuse them only on
    // the clients whose ICU happens to be trimmed or out of date.
    for (const timeZone of ['+08:00', 'Etc/GMT-8', 'Asia/Rangoon', 'Mars/Base'])
      expect(
        check({
          groups: [
            {
              type: 'DATE_HISTOGRAM',
              field: 'createdAt',
              alias: 'wh',
              unit: 'MONTH',
              timeZone,
            },
          ],
        }),
      ).toEqual([]);
    expect(
      check({
        groups: [
          { type: 'TERMS', field: 'warehouse', alias: 'wh', missingKey: '' },
        ],
      }),
    ).toEqual(['analysis.group.blank-missing-key']);
    // A stored value of the wrong type is malformed, not blank, and must not
    // reach `trim`.
    expect(
      check({
        groups: [
          {
            type: 'DATE_HISTOGRAM',
            field: 'createdAt',
            alias: 'wh',
            unit: 'MONTH',
            timeZone: 123 as never,
          },
        ],
      }),
    ).toEqual(['analysis.config.malformed']);
    expect(
      check({
        groups: [
          {
            type: 'TERMS',
            field: 'warehouse',
            alias: 'wh',
            missingKey: 123 as never,
          },
        ],
      }),
    ).toEqual(['analysis.config.malformed']);
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

  it('refuses a sort or a having without a group', () => {
    // An ungrouped aggregation is one row. Wow throws on a sort or a having
    // over it, so a stored config carrying either must fail admission here
    // rather than at the server.
    const ungrouped: Partial<AnalysisViewConfig> = {
      groups: [],
      layout: 'table',
      chart: { type: 'metric', metric: { metric: 'orders' } },
    };
    expect(check({ ...ungrouped, sort: [] })).toEqual([]);

    const sorted = validateAnalysis(
      definition(),
      config({ ...ungrouped, sort: [{ alias: 'orders', direction: 'DESC' }] }),
      builtinFieldKinds,
    );
    expect(sorted).toEqual([
      {
        code: 'analysis.sort.requires-group',
        severity: 'error',
        path: ['sort'],
      },
    ]);
    // The alias is still checked, so both findings reach the user at once.
    expect(
      check({ ...ungrouped, sort: [{ alias: 'gone', direction: 'ASC' }] }),
    ).toEqual(['analysis.sort.requires-group', 'analysis.sort.unknown-alias']);

    const having = validateAnalysis(
      definition(),
      config({
        ...ungrouped,
        sort: [],
        having: {
          type: 'CONDITION',
          metric: 'orders',
          operator: 'GT',
          value: 1,
        },
      }),
      builtinFieldKinds,
    );
    expect(having).toEqual([
      {
        code: 'analysis.having.requires-group',
        severity: 'error',
        path: ['having'],
      },
    ]);
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
  // What an element holds is the definition's to say; the capability names
  // which of those paths this analysis may expand, and how they aggregate.
  const withElements = definition({
    // The array declares what it holds; the capability only names which
    // arrays this analysis may expand, and how their fields aggregate.
    fields: [
      ...definition().fields,
      {
        name: 'items',
        label: 'Items',
        kind: 'array',
        elements: [{ name: 'sku', label: 'SKU', kind: 'string' }],
      },
    ],
    analysis: {
      ...capability,
      elements: [
        {
          path: 'items',
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

  // An element filter gates which entries the expansion lets through. It has
  // no editor either, so the same rule as a metric's filter applies: having
  // written one, it must actually narrow something.
  it('refuses an element filter with no conditions', () => {
    expect(
      codes(
        validateAnalysis(
          withElements,
          config({ elements: [{ path: 'items', filter: emptyFilter() }] }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['analysis.elementFilter.empty']);
  });

  it('refuses an element condition with no value', () => {
    expect(
      codes(
        validateAnalysis(
          withElements,
          config({
            elements: [
              {
                path: 'items',
                filter: {
                  op: 'and',
                  children: [{ field: 'items.sku', operator: 'EQ', value: '' }],
                },
              },
            ],
          }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['analysis.elementFilter.incomplete']);
  });

  it('paths an empty element filter at the filter itself', () => {
    expect(
      validateAnalysis(
        withElements,
        config({ elements: [{ path: 'items', filter: emptyFilter() }] }),
        builtinFieldKinds,
      ).map(found => found.path),
    ).toContainEqual(['elements', 0, 'filter']);
  });

  it('admits an element filter that names a value', () => {
    expect(
      codes(
        validateAnalysis(
          withElements,
          config({
            elements: [
              {
                path: 'items',
                filter: {
                  op: 'and',
                  children: [
                    { field: 'items.sku', operator: 'EQ', value: 'A-1' },
                  ],
                },
              },
            ],
          }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual([]);
  });

  it('leaves an element without a filter alone', () => {
    // No filter at all is how "expand every entry" is said; only a filter
    // that was written and says nothing is wrong.
    expect(
      codes(
        validateAnalysis(
          withElements,
          config({ elements: [{ path: 'items' }] }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual([]);
  });

  it('admits a multi-valued element field, which a metric filter refuses', () => {
    // The scalar rule belongs to metric position, where the filter has one
    // record's value to test. An element filter is an ordinary filter over
    // the element's own fields and carries no such restriction.
    const nested = definition({
      fields: [
        ...definition().fields,
        {
          name: 'items',
          label: 'Items',
          kind: 'array',
          elements: [
            { name: 'sku', label: 'SKU', kind: 'string' },
            {
              name: 'tags',
              label: 'Tags',
              kind: 'array',
              elements: [{ name: 'name', label: 'Name', kind: 'string' }],
            },
          ],
        },
      ],
      analysis: {
        ...capability,
        elements: [
          {
            path: 'items',
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
    const onTags: FilterTree = {
      op: 'and',
      children: [{ field: 'items.tags', operator: 'IN', value: ['red'] }],
    };

    expect(
      codes(
        validateAnalysis(
          nested,
          config({ elements: [{ path: 'items', filter: onTags }] }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual([]);
    expect(
      codes(
        validateAnalysis(
          nested,
          config({
            elements: [{ path: 'items' }],
            metrics: [{ type: 'COUNT', alias: 'orders', filter: onTags }],
          }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['analysis.metricFilter.not-scalar']);
  });

  it('honours a caller that widened the tree limits', () => {
    // An element filter is a filter like any other, so it spends the budget
    // the caller set rather than the default one.
    const deep = (depth: number): FilterTree =>
      depth <= 1
        ? {
            op: 'and',
            children: [{ field: 'items.sku', operator: 'EQ', value: 'x' }],
          }
        : { op: 'and', children: [deep(depth - 1)] };
    const overrides = { elements: [{ path: 'items', filter: deep(12) }] };

    expect(
      codes(
        validateAnalysis(withElements, config(overrides), builtinFieldKinds),
      ),
    ).toEqual(['filter.tree.too-deep']);
    expect(
      codes(
        validateAnalysis(withElements, config(overrides), builtinFieldKinds, {
          limits: { ...DEFAULT_RUNTIME_LIMITS, maxFilterDepth: 16 },
        }),
      ),
    ).toEqual([]);
  });

  it('qualifies element fields with their path, always', () => {
    expect(qualify('items', 'sku')).toBe('items.sku');
    // A declaration names what it holds relative to itself. Leaving a name
    // that already begins with the path alone accepted two spellings of one
    // reference, and made `items.sku` mean one thing at the root of an
    // element and another inside a nested object sharing the array's name.
    expect(qualify('items', 'items.sku')).toBe('items.items.sku');
    expect(qualify('items', 'address.city')).toBe('items.address.city');
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

describe('display metadata', () => {
  const warehouses = [{ value: 'SH', label: 'Shanghai' }];
  const withEnum = () =>
    definition({
      fields: definition().fields.map(field =>
        field.name === 'warehouse'
          ? { ...field, kind: 'enum', options: warehouses }
          : field,
      ),
    });

  // Left to the backend, a day ran midnight to midnight UTC: mid-morning to
  // mid-morning in Shanghai, and no longer the day "today" means.
  it('cuts a date histogram in the engine zone unless the group names one', () => {
    const day = {
      type: 'DATE_HISTOGRAM',
      field: 'createdAt',
      alias: 'day',
      unit: 'DAY',
    } as const;
    const shanghai = { ...context, timeZone: 'Asia/Shanghai' };

    const unnamed = compileAnalysis(
      definition(),
      config({ groups: [day] }),
      builtinFieldKinds,
      shanghai,
    );
    const named = compileAnalysis(
      definition(),
      config({ groups: [{ ...day, timeZone: 'UTC' }] }),
      builtinFieldKinds,
      shanghai,
    );

    expect(unnamed.groupBy?.[0]).toMatchObject({ timeZone: 'Asia/Shanghai' });
    expect(named.groupBy?.[0]).toMatchObject({ timeZone: 'UTC' });
  });

  it('tells each column how its values show', () => {
    const view = projectAnalysis(
      withEnum(),
      config({
        layout: 'table',
        groups: [
          { type: 'TERMS', field: 'warehouse', alias: 'wh' },
          {
            type: 'DATE_HISTOGRAM',
            field: 'createdAt',
            alias: 'day',
            unit: 'DAY',
            timeZone: 'UTC',
          },
          {
            type: 'DATE_HISTOGRAM',
            field: 'createdAt',
            alias: 'month',
            unit: 'MONTH',
          },
        ],
        metrics: [
          { type: 'COUNT', alias: 'orders' },
          { type: 'ANY', alias: 'some', field: 'warehouse' },
          {
            type: 'NUMERIC',
            alias: 'latest',
            function: 'MAX',
            expression: { type: 'FIELD', field: 'createdAt' },
          },
        ],
      }),
      [],
    );
    const column = (alias: string) =>
      view.columns.find(found => found.alias === alias);

    expect(column('wh')).toMatchObject({
      kind: 'enum',
      cell: 'enum',
      options: warehouses,
    });
    expect(column('day')).toMatchObject({
      kind: 'datetime',
      dateUnit: 'DAY',
      timeZone: 'UTC',
    });
    // Cut in the engine's zone, which is the zone it is shown in anyway.
    expect(column('month')).toMatchObject({ dateUnit: 'MONTH' });
    expect(column('month')?.timeZone).toBeUndefined();
    // ANY returns one of the field's values; any other metric is a number,
    // whatever it was computed from.
    expect(column('some')).toMatchObject({ kind: 'enum', options: warehouses });
    expect(column('latest')?.kind).toBeUndefined();
    expect(column('orders')?.kind).toBeUndefined();
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
        // A group column holds the field's values, so it says how they show.
        kind: 'string',
        cell: 'string',
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

  it('refuses a definition without the analysis capability', () => {
    expect(() =>
      projectAnalysis(
        definition({ analysis: undefined }),
        config({ layout: 'table' }),
        rows,
      ),
    ).toThrow(/no analysis capability/);
  });

  it('labels every metric that reads a field, and element fields too', () => {
    // Only NUMERIC used to be looked up, and only among the root fields, so a
    // p95 read as `p95` and a grouping of `items.sku` read as its alias.
    const withElements = definition({
      fields: [
        ...definition().fields,
        {
          name: 'items',
          label: 'Items',
          kind: 'array',
          elements: [{ name: 'sku', label: 'SKU', kind: 'string' }],
        },
      ],
      analysis: {
        ...capability,
        elements: [
          {
            path: 'items',
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
    const view = projectAnalysis(
      withElements,
      config({
        layout: 'table',
        elements: [{ path: 'items' }],
        groups: [{ type: 'TERMS', field: 'items.sku', alias: 'sku' }],
        metrics: [
          {
            type: 'PERCENTILE',
            alias: 'p95',
            expression: { type: 'FIELD', field: 'amount' },
            percentile: 95,
          },
          {
            type: 'DISTINCT_COUNT',
            alias: 'buyers',
            expression: { type: 'FIELD', field: 'amount' },
          },
          { type: 'ANY', alias: 'sample', field: 'amount' },
        ],
        sort: [],
        chart: {
          type: 'bar',
          cartesian: { x: 'sku', series: [{ metric: 'p95' }] },
        },
      }),
      [],
    );
    expect(view.columns.map(column => column.label)).toEqual([
      'SKU',
      'Amount',
      'Amount',
      'Amount',
    ]);
    expect(view.columns[1].numberFormat).toEqual({
      style: 'currency',
      currency: 'CNY',
    });
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

describe('expression budgets', () => {
  // Every tree here is built iteratively: the point is that validation must
  // survive a tree the recursive walks could not.
  const chain = (depth: number): AnalysisExpression => {
    let node: AnalysisExpression = { type: 'FIELD', field: 'amount' };
    for (let level = 1; level < depth; level += 1)
      node = {
        type: 'BINARY',
        operator: 'ADD',
        left: node,
        right: { type: 'CONSTANT', value: 1 },
      };
    return node;
  };

  const derivedChain = (depth: number): AnalysisDerivedExpression => {
    let node: AnalysisDerivedExpression = {
      type: 'METRIC_REF',
      metric: 'orders',
    };
    for (let level = 1; level < depth; level += 1)
      node = {
        type: 'BINARY',
        operator: 'ADD',
        left: node,
        right: { type: 'CONSTANT', value: 1 },
      };
    return node;
  };

  const condition: AnalysisHavingExpression = {
    type: 'CONDITION',
    metric: 'orders',
    operator: 'GT',
    value: 1,
  };
  const havingChain = (depth: number): AnalysisHavingExpression => {
    let node: AnalysisHavingExpression = condition;
    for (let level = 1; level < depth; level += 1)
      node = { type: 'AND', operands: [node] };
    return node;
  };

  /** A complete binary tree of the given depth, 2^depth - 1 nodes. */
  const full = (depth: number): AnalysisExpression => {
    let level: AnalysisExpression[] = Array.from(
      { length: 2 ** (depth - 1) },
      () => ({ type: 'CONSTANT', value: 1 }),
    );
    while (level.length > 1) {
      const next: AnalysisExpression[] = [];
      for (let index = 0; index < level.length; index += 2)
        next.push({
          type: 'BINARY',
          operator: 'ADD',
          left: level[index],
          right: level[index + 1],
        });
      level = next;
    }
    return level[0];
  };

  const orders: AnalysisMetric = { type: 'COUNT', alias: 'orders' };
  const sum = (
    alias: string,
    expression: AnalysisExpression,
  ): AnalysisMetric => ({
    type: 'NUMERIC',
    alias,
    function: 'SUM',
    expression,
  });
  const found = (
    overrides: Partial<AnalysisViewConfig>,
    limits = DEFAULT_RUNTIME_LIMITS,
  ) =>
    validateAnalysis(definition(), config(overrides), builtinFieldKinds, {
      limits,
    });

  it('refuses a chain nested far beyond the budget without overflowing', () => {
    expect(found({ metrics: [orders, sum('deep', chain(100_000))] })).toEqual([
      {
        code: 'analysis.expression.too-deep',
        severity: 'error',
        path: ['metrics', 1, 'expression'],
        params: { max: 8 },
      },
    ]);
  });

  it('draws the depth line where Wow does', () => {
    expect(codes(found({ metrics: [orders, sum('edge', chain(8))] }))).toEqual(
      [],
    );
    expect(codes(found({ metrics: [orders, sum('over', chain(9))] }))).toEqual([
      'analysis.expression.too-deep',
    ]);
  });

  it('honours a caller that widened the limits', () => {
    expect(
      codes(
        found(
          { metrics: [orders, sum('over', chain(9))] },
          {
            ...DEFAULT_RUNTIME_LIMITS,
            maxFilterDepth: 16,
          },
        ),
      ),
    ).toEqual([]);
  });

  it('counts the nodes of every expression against one budget', () => {
    // A tree within the depth limit holds at most 255 nodes, so the node
    // budget only bites across metrics — which is how Wow counts it.
    expect(codes(found({ metrics: [orders, sum('a', full(8))] }))).toEqual([]);
    expect(
      found({ metrics: [orders, sum('a', full(8)), sum('b', full(8))] }),
    ).toEqual([
      {
        code: 'analysis.expression.too-many-nodes',
        severity: 'error',
        path: ['metrics', 2, 'expression'],
        params: { max: 256 },
      },
    ]);
  });

  it('budgets a derived expression the same way', () => {
    const derived = (depth: number): AnalysisMetric => ({
      type: 'DERIVED',
      alias: 'share',
      expression: derivedChain(depth),
    });
    expect(codes(found({ metrics: [orders, derived(100_000)] }))).toEqual([
      'analysis.expression.too-deep',
    ]);
    expect(codes(found({ metrics: [orders, derived(8)] }))).toEqual([]);
    expect(codes(found({ metrics: [orders, derived(9)] }))).toEqual([
      'analysis.expression.too-deep',
    ]);
  });

  it('budgets a having tree the same way', () => {
    expect(codes(found({ having: havingChain(100_000) }))).toEqual([
      'analysis.having.too-deep',
    ]);
    expect(codes(found({ having: havingChain(8) }))).toEqual([]);
    expect(codes(found({ having: havingChain(9) }))).toEqual([
      'analysis.having.too-deep',
    ]);
    const wide: AnalysisHavingExpression = {
      type: 'AND',
      operands: [condition, ...Array.from({ length: 299 }, () => condition)],
    };
    expect(codes(found({ having: wide }))).toEqual([
      'analysis.having.too-many-nodes',
    ]);
  });
});

describe('a malformed skeleton', () => {
  // A config arrives from a store, so every wrong piece must come back as an
  // Issue with a path, never as a TypeError from the rule that tripped on it.
  const broken = (overrides: Record<string, unknown>) =>
    validateAnalysis(
      definition(),
      { ...config(), ...overrides } as unknown as AnalysisViewConfig,
      builtinFieldKinds,
    );

  it.each([
    ['groups', { groups: 'wh' }, ['groups']],
    ['metrics', { metrics: { type: 'COUNT' } }, ['metrics']],
    ['sort', { sort: null }, ['sort']],
    ['elements', { elements: 'items' }, ['elements']],
    ['table', { table: 'columns' }, ['table']],
    ['table.columns', { table: { columns: 5 } }, ['table', 'columns']],
    ['chart', { chart: undefined }, ['chart']],
    [
      'a group alias',
      { groups: [{ type: 'TERMS', field: 'warehouse', alias: 42 }] },
      ['groups', 0, 'alias'],
    ],
    ['a metric entry', { metrics: [null] }, ['metrics', 0]],
  ])('reports %s of the wrong shape and stops there', (_, overrides, path) => {
    expect(broken(overrides)).toEqual([
      { code: 'analysis.config.malformed', severity: 'error', path },
    ]);
  });

  it('reports a limit that is not a number', () => {
    expect(codes(broken({ limit: 'ten' }))).toContain(
      'analysis.limit.not-positive',
    );
  });

  it('reports a metric type this version does not know', () => {
    const median = { type: 'MEDIAN', alias: 'orders' };
    expect(broken({ metrics: [median] })).toEqual([
      {
        code: 'analysis.metric.type-unknown',
        severity: 'error',
        path: ['metrics', 0, 'type'],
        params: { type: 'MEDIAN' },
      },
    ]);
    // Compilation has no mapping for it either; admission is what keeps it
    // out, and reaching it anyway is a programming error rather than a query
    // with a hole in `metrics`.
    expect(() =>
      compileAnalysis(
        definition(),
        { ...config(), metrics: [median] } as unknown as AnalysisViewConfig,
        builtinFieldKinds,
        context,
      ),
    ).toThrow('MEDIAN');
  });

  it('refuses an aggregation with no metric, as Wow does', () => {
    expect(codes(broken({ metrics: [] }))).toContain('analysis.metrics.empty');
  });
});

describe('metric card headline', () => {
  it('hands the totals row to the chart', () => {
    // The totals query is the ungrouped aggregation, which is the headline a
    // trend card shows; the grouped rows only draw the sparkline.
    const view = projectAnalysis(
      definition(),
      config({
        groups: [
          {
            type: 'DATE_HISTOGRAM',
            field: 'createdAt',
            alias: 'month',
            unit: 'MONTH',
          },
        ],
        table: { columns: [], totals: true },
        chart: {
          type: 'metric',
          metric: { metric: 'orders', trend: { x: 'month' } },
        },
      }),
      [
        { month: '2026-08', orders: 40 },
        { month: '2026-09', orders: 20 },
      ],
      [{ orders: 55 }],
    );
    expect(view.totals).toEqual({ orders: 55 });
    expect(view.chart).toMatchObject({ type: 'metric', value: 55 });
  });
});
