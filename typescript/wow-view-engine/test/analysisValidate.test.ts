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
 * Admission: the config a definition starts from, the rules it is held to,
 * the budgets an expression may not spend, and what a config that arrived
 * malformed comes back as. Element scope, compilation and projection have
 * their own files beside this one.
 */

import {
  AGGREGATION_LIMITS,
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
  aggregation,
} from '@ahoo-wang/fetcher-wow';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  compileAnalysis,
  DEFAULT_MISSING_KEY,
  defaultAnalysisConfig,
  validateAnalysis,
  type AnalysisDerivedExpression,
  type AnalysisExpression,
  type AnalysisHavingExpression,
  type AnalysisMetric,
  type AnalysisViewConfig,
  DEFAULT_RUNTIME_LIMITS,
} from '../src/index.js';
import {
  analysisCapability as capability,
  analysisContext as context,
  analysisDefinition as definition,
  analysisKernelConfig as config,
  errorCodes as codes,
} from './fixtures/analysis.js';

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

  it('gives a text dimension a bucket for the records with no value', () => {
    // Without a `missingKey` Wow drops those records from the result
    // entirely, so a first config that left it out would answer a question
    // nobody asked — and say nothing about the rows it left out.
    expect(defaultAnalysisConfig(definition()).groups[0]).toEqual({
      type: 'TERMS',
      field: 'warehouse',
      alias: 'warehouse_group',
      missingKey: DEFAULT_MISSING_KEY,
    });

    // A dimension that cannot carry one starts without it; admission would
    // refuse it there.
    const numeric = definition({
      fields: [{ name: 'amount', label: 'Amount', kind: 'number' }],
      analysis: {
        count: true,
        fields: [
          {
            field: 'amount',
            groups: [AggregationGroupType.TERMS],
            functions: [],
          },
        ],
      },
    });
    expect(defaultAnalysisConfig(numeric).groups[0]).toEqual({
      type: 'TERMS',
      field: 'amount',
      alias: 'amount_group',
    });
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

  it('allows a missing-value bucket on single-valued text only', () => {
    // Without a `missingKey`, Wow drops the records that have no value of the
    // dimension from the whole result rather than leaving them ungrouped, so
    // a string dimension carries one; Wow refuses one on every other shape at
    // schema validation, and a config that asks for it is refused here first.
    const typed = definition({
      fields: [
        ...definition().fields,
        {
          name: 'status',
          label: 'Status',
          kind: 'enum',
          options: [{ value: 'PAID', label: 'Paid' }],
        },
        {
          name: 'code',
          label: 'Code',
          kind: 'enum',
          options: [{ value: 1, label: 'One' }],
        },
        { name: 'tags', label: 'Tags', kind: 'array' },
      ],
      analysis: {
        ...capability,
        fields: [
          ...capability.fields,
          {
            field: 'status',
            groups: [AggregationGroupType.TERMS],
            functions: [],
          },
          {
            field: 'code',
            groups: [AggregationGroupType.TERMS],
            functions: [],
          },
          {
            field: 'tags',
            groups: [AggregationGroupType.TERMS],
            functions: [],
          },
        ],
      },
    });
    const onField = (field: string) =>
      codes(
        validateAnalysis(
          typed,
          config({
            groups: [
              { type: 'TERMS', field, alias: 'wh', missingKey: '(empty)' },
            ],
          }),
          builtinFieldKinds,
        ),
      );

    expect(onField('warehouse')).toEqual([]);
    // A closed set of names is text; a closed set of numeric codes is not,
    // whatever the kind is called.
    expect(onField('status')).toEqual([]);
    expect(onField('code')).toEqual(['analysis.group.missing-key-unsupported']);
    expect(onField('tags')).toEqual(['analysis.group.missing-key-unsupported']);
    expect(
      codes(
        validateAnalysis(
          typed,
          config({
            groups: [
              {
                type: 'HISTOGRAM',
                field: 'amount',
                alias: 'wh',
                interval: 10,
                missingKey: '(empty)',
              } as never,
            ],
          }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual([]);
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

describe('a display name', () => {
  /**
   * D20 显示名: a dimension or a metric may be given a name for the screen.
   * Given, it is a word — a blank one would head a column with nothing.
   */
  it('is admitted when given, refused when blank, and never sent to Wow', () => {
    const base = config();
    expect(
      check({
        groups: [{ ...base.groups[0]!, label: '仓库' }],
        metrics: [{ ...base.metrics[0], label: '单数' }],
      }),
    ).not.toContain('analysis.label.blank');
    expect(
      check({
        groups: [
          { type: 'TERMS', field: 'warehouse', alias: 'wh', label: '  ' },
        ],
      }),
    ).toContain('analysis.label.blank');
    expect(
      check({ metrics: [{ type: 'COUNT', alias: 'n', label: '' }] }),
    ).toContain('analysis.label.blank');
    expect(
      check({
        groups: [
          {
            type: 'TERMS',
            field: 'warehouse',
            alias: 'wh',
            label: 7 as unknown as string,
          },
        ],
      }),
    ).toContain('analysis.config.malformed');
  });
});
