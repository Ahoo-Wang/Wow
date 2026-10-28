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
  AggregationMetricType,
  SortDirection,
  AggregationExpressionType,
  AggregationExpressionOperator,
  DerivedExpressionType,
  HavingExpressionType,
  ComparisonOperator,
} from '@ahoo-wang/wow-client/dsl';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  compileAnalysis,
  DEFAULT_MISSING_KEY,
  defaultAnalysisConfig,
  validateAnalysis,
  type AnalysisMetric,
  type AnalysisViewConfig,
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
    expect(built.metrics[0]).toEqual({
      type: AggregationMetricType.COUNT,
      alias: 'count',
    });
    expect(built.groups[0]).toMatchObject({
      type: AggregationGroupType.TERMS,
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
      type: AggregationGroupType.TERMS,
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
      type: AggregationGroupType.TERMS,
      field: 'amount',
      alias: 'amount_group',
    });
  });

  it('falls back through the capability when COUNT is not declared', () => {
    const numeric = defaultAnalysisConfig(
      definition({ analysis: { ...capability, count: false } }),
    );
    expect(numeric.metrics[0]).toMatchObject({
      type: AggregationMetricType.NUMERIC,
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
    expect(distinct.metrics[0].type).toBe(AggregationMetricType.DISTINCT_COUNT);
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
      type: AggregationMetricType.PERCENTILE,
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
    expect(any.metrics[0].type).toBe(AggregationMetricType.ANY);
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
    expect(grouped.sort).toEqual([
      { alias: 'count', direction: SortDirection.DESC },
    ]);
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
        groups: [
          {
            type: AggregationGroupType.TERMS,
            field: 'warehouse',
            alias: 'orders',
          },
        ],
      }),
    ).toContain('analysis.alias.duplicate');
    expect(
      check({
        groups: [
          {
            type: AggregationGroupType.TERMS,
            field: 'warehouse',
            alias: 'a.b',
          },
        ],
        chart: {
          type: 'bar',
          cartesian: { x: 'a.b', series: [{ metric: 'orders' }] },
        },
      }),
    ).toEqual(['analysis.alias.not-a-segment']);
    expect(
      check({
        groups: [
          {
            type: AggregationGroupType.TERMS,
            field: 'warehouse',
            alias: '__wow_x',
          },
        ],
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
        groups: [
          {
            type: AggregationGroupType.TERMS,
            field: 'warehouse',
            alias: '2024 wh',
          },
        ],
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
          { alias: 'orders', direction: SortDirection.DESC },
          { alias: 'orders', direction: SortDirection.ASC },
        ],
      }),
    ).toEqual(['analysis.sort.duplicate']);
  });

  it('refuses a gap-filling date grouping next to another grouping', () => {
    const dense = {
      type: AggregationGroupType.DATE_HISTOGRAM,
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
        groups: [
          { type: AggregationGroupType.TERMS, field: 'warehouse', alias: 'wh' },
          dense,
        ],
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
      (_, index) => ({ type: AggregationMetricType.COUNT, alias: `m${index}` }),
    ) as [AnalysisMetric, ...AnalysisMetric[]];
    const sort = metrics
      .slice(0, AGGREGATION_LIMITS.MAX_SORT_FIELDS + 1)
      .map(metric => ({
        alias: metric.alias,
        direction: SortDirection.DESC as const,
      }));
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
        groups: [
          { type: AggregationGroupType.TERMS, field: 'gone', alias: 'wh' },
        ],
      }),
    ).toEqual(['analysis.field.unknown']);
    expect(
      check({
        groups: [
          {
            type: AggregationGroupType.HISTOGRAM,
            field: 'warehouse',
            alias: 'wh',
            interval: 1,
          },
        ],
      }),
    ).toEqual(['analysis.group.unsupported']);
    expect(
      check({
        groups: [
          {
            type: AggregationGroupType.HISTOGRAM,
            field: 'amount',
            alias: 'wh',
            interval: 0,
          },
        ],
      }),
    ).toEqual(['analysis.group.interval-not-positive']);
    expect(
      check({
        groups: [
          {
            type: AggregationGroupType.DATE_HISTOGRAM,
            field: 'createdAt',
            alias: 'wh',
            unit: AggregationDateUnit.DAY,
          },
        ],
      }),
    ).toEqual(['analysis.group.unit-unsupported']);
    expect(
      check({
        groups: [
          {
            type: AggregationGroupType.DATE_HISTOGRAM,
            field: 'createdAt',
            alias: 'wh',
            unit: AggregationDateUnit.MONTH,
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
              type: AggregationGroupType.DATE_HISTOGRAM,
              field: 'createdAt',
              alias: 'wh',
              unit: AggregationDateUnit.MONTH,
              timeZone,
            },
          ],
        }),
      ).toEqual([]);
    expect(
      check({
        groups: [
          {
            type: AggregationGroupType.TERMS,
            field: 'warehouse',
            alias: 'wh',
            missingKey: '',
          },
        ],
      }),
    ).toEqual(['analysis.group.blank-missing-key']);
    // A stored value of the wrong type is malformed, not blank, and must not
    // reach `trim`.
    expect(
      check({
        groups: [
          {
            type: AggregationGroupType.DATE_HISTOGRAM,
            field: 'createdAt',
            alias: 'wh',
            unit: AggregationDateUnit.MONTH,
            timeZone: 123 as never,
          },
        ],
      }),
    ).toEqual(['analysis.config.malformed']);
    expect(
      check({
        groups: [
          {
            type: AggregationGroupType.TERMS,
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
              {
                type: AggregationGroupType.TERMS,
                field,
                alias: 'wh',
                missingKey: '(empty)',
              },
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
                type: AggregationGroupType.HISTOGRAM,
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
            type: AggregationMetricType.NUMERIC,
            alias: 'orders',
            function: AggregationFunction.MIN,
            expression: {
              type: AggregationExpressionType.FIELD,
              field: 'amount',
            },
          },
        ],
      }),
    ).toEqual(['analysis.function.unsupported']);

    expect(
      check({
        metrics: [
          {
            type: AggregationMetricType.ANY,
            alias: 'orders',
            field: 'warehouse',
          },
        ],
      }),
    ).toEqual(['analysis.any.undeclared']);

    expect(
      check({
        metrics: [
          {
            type: AggregationMetricType.PERCENTILE,
            alias: 'orders',
            expression: {
              type: AggregationExpressionType.FIELD,
              field: 'amount',
            },
            percentile: 100,
          },
        ],
      }),
    ).toEqual(['analysis.percentile.out-of-range']);

    expect(
      check({
        metrics: [{ type: AggregationMetricType.COUNT, alias: 'orders' }],
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

  it('refuses a summary the field itself does not declare', () => {
    // The capability names the summaries a field can carry, one member per
    // kind (D4): `amount` declares the distinct count, the percentile and
    // any-value, so taking one away is what a definition that never offered
    // it looks like. The kernel refuses on the declaration alone and never
    // probes the backend — a query it sent would come back an error the
    // analyst could do nothing about.
    const without = (member: 'distinctCount' | 'percentile' | 'any') =>
      definition({
        analysis: {
          ...capability,
          fields: capability.fields.map(entry =>
            entry.field === 'amount' ? { ...entry, [member]: false } : entry,
          ),
        },
      });
    const refused = (
      member: 'distinctCount' | 'percentile' | 'any',
      metric: AnalysisMetric,
    ) =>
      codes(
        validateAnalysis(
          without(member),
          config({ metrics: [metric] }),
          builtinFieldKinds,
        ),
      );

    expect(
      refused('distinctCount', {
        type: AggregationMetricType.DISTINCT_COUNT,
        alias: 'orders',
        expression: { type: AggregationExpressionType.FIELD, field: 'amount' },
      }),
    ).toEqual(['analysis.distinctCount.undeclared']);

    expect(
      refused('percentile', {
        type: AggregationMetricType.PERCENTILE,
        alias: 'orders',
        expression: { type: AggregationExpressionType.FIELD, field: 'amount' },
        percentile: 95,
      }),
    ).toEqual(['analysis.percentile.undeclared']);

    expect(
      refused('any', {
        type: AggregationMetricType.ANY,
        alias: 'orders',
        field: 'amount',
      }),
    ).toEqual(['analysis.any.undeclared']);

    // Declared, and the same three metrics are admitted as they stand.
    for (const metric of [
      {
        type: AggregationMetricType.DISTINCT_COUNT,
        alias: 'orders',
        expression: { type: AggregationExpressionType.FIELD, field: 'amount' },
      },
      {
        type: AggregationMetricType.PERCENTILE,
        alias: 'orders',
        expression: { type: AggregationExpressionType.FIELD, field: 'amount' },
        percentile: 95,
      },
      { type: AggregationMetricType.ANY, alias: 'orders', field: 'amount' },
    ] as AnalysisMetric[])
      expect(check({ metrics: [metric] })).toEqual([]);
  });

  it('refuses a derived metric where the capability declares no expressions', () => {
    // A derived metric is an expression over other metrics, so it needs the
    // same declaration a formula does — and it is refused for itself, not
    // for what its operands happen to be: this one reads a plain count.
    expect(
      codes(
        validateAnalysis(
          definition({ analysis: { ...capability, expressions: false } }),
          config({
            metrics: [
              { type: AggregationMetricType.COUNT, alias: 'orders' },
              {
                type: AggregationMetricType.DERIVED,
                alias: 'half',
                expression: {
                  type: DerivedExpressionType.BINARY,
                  operator: AggregationExpressionOperator.DIVIDE,
                  left: {
                    type: DerivedExpressionType.METRIC_REF,
                    metric: 'orders',
                  },
                  right: { type: DerivedExpressionType.CONSTANT, value: 2 },
                },
              },
            ],
          }),
          builtinFieldKinds,
        ),
      ),
    ).toEqual(['analysis.expressions.undeclared']);
  });

  it('checks expressions recursively', () => {
    expect(
      check({
        metrics: [
          {
            type: AggregationMetricType.NUMERIC,
            alias: 'orders',
            function: AggregationFunction.SUM,
            expression: {
              type: AggregationExpressionType.BINARY,
              operator: AggregationExpressionOperator.DIVIDE,
              left: { type: AggregationExpressionType.FIELD, field: 'amount' },
              right: { type: AggregationExpressionType.CONSTANT, value: 0 },
            },
          },
        ],
      }),
    ).toEqual(['analysis.expression.divide-by-zero']);

    expect(
      check({
        metrics: [
          {
            type: AggregationMetricType.NUMERIC,
            alias: 'orders',
            function: AggregationFunction.SUM,
            expression: {
              type: AggregationExpressionType.BINARY,
              operator: AggregationExpressionOperator.ADD,
              left: { type: AggregationExpressionType.FIELD, field: 'amount' },
              right: {
                type: AggregationExpressionType.CONSTANT,
                value: Number.POSITIVE_INFINITY,
              },
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
          {
            type: AggregationMetricType.NUMERIC,
            alias: 'orders',
            function: AggregationFunction.SUM,
          } as never,
        ],
      }),
    ).toEqual(['analysis.expression.malformed']);
    expect(
      check({
        metrics: [
          {
            type: AggregationMetricType.NUMERIC,
            alias: 'orders',
            function: AggregationFunction.SUM,
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
            type: AggregationMetricType.NUMERIC,
            alias: 'orders',
            function: AggregationFunction.SUM,
            expression: {
              type: AggregationExpressionType.BINARY,
              operator: AggregationExpressionOperator.DIVIDE,
              left: { type: AggregationExpressionType.FIELD, field: 'amount' },
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
                type: AggregationMetricType.NUMERIC,
                alias: 'orders',
                function: AggregationFunction.SUM,
                expression: {
                  type: AggregationExpressionType.BINARY,
                  operator: AggregationExpressionOperator.ADD,
                  left: {
                    type: AggregationExpressionType.FIELD,
                    field: 'amount',
                  },
                  right: { type: AggregationExpressionType.CONSTANT, value: 1 },
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
          type: AggregationMetricType.DERIVED,
          alias: 'ratio',
          expression: {
            type: DerivedExpressionType.METRIC_REF,
            metric: 'orders',
          },
        },
        { type: AggregationMetricType.COUNT, alias: 'orders' },
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
        { type: AggregationMetricType.COUNT, alias: 'orders' },
        {
          type: AggregationMetricType.DERIVED,
          alias: 'ratio',
          expression: {
            type: DerivedExpressionType.BINARY,
            operator: AggregationExpressionOperator.DIVIDE,
            left: { type: DerivedExpressionType.METRIC_REF, metric: 'orders' },
            right: { type: DerivedExpressionType.CONSTANT, value: 2 },
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
          { type: AggregationMetricType.COUNT, alias: 'orders' },
          { type: AggregationMetricType.DERIVED, alias: 'broken' } as never,
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
          { type: AggregationMetricType.COUNT, alias: 'orders' },
          {
            type: AggregationMetricType.DERIVED,
            alias: 'broken',
            expression: {
              type: DerivedExpressionType.BINARY,
              operator: AggregationExpressionOperator.ADD,
              left: {
                type: DerivedExpressionType.METRIC_REF,
                metric: 'orders',
              },
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
        having: {
          type: HavingExpressionType.CONDITION,
          metric: 'gone',
          operator: ComparisonOperator.GT,
          value: 1,
        },
      }),
    ).toEqual(['analysis.having.unknown-metric']);

    expect(
      check({
        metrics: [
          { type: AggregationMetricType.COUNT, alias: 'orders' },
          { type: AggregationMetricType.ANY, alias: 'first', field: 'amount' },
        ],
        having: {
          type: HavingExpressionType.CONDITION,
          metric: 'first',
          operator: ComparisonOperator.GT,
          value: 1,
        },
      }),
    ).toEqual(['analysis.having.unknown-metric']);

    expect(
      check({
        having: {
          type: HavingExpressionType.AND,
          operands: [
            {
              type: HavingExpressionType.CONDITION,
              metric: 'orders',
              operator: ComparisonOperator.GT,
              value: 1,
            },
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
              type: HavingExpressionType.CONDITION,
              metric: 'orders',
              operator: ComparisonOperator.GT,
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
        having: {
          type: HavingExpressionType.AND,
          operands: undefined,
        } as never,
      }),
    ).toEqual(['analysis.having.malformed']);

    // Not even a plain number reaches the walk, and a null inside a group's
    // operands is a finding at that depth rather than a crash.
    expect(check({ having: 42 as never })).toEqual([
      'analysis.having.malformed',
    ]);
    expect(
      check({
        having: { type: HavingExpressionType.AND, operands: [null] } as never,
      }),
    ).toEqual(['analysis.having.malformed']);
  });

  it('bounds the limit and the counts', () => {
    // One finding for every way out of the range, since the sentence it
    // reads as is the range itself: a fraction is not told it "must be
    // positive", which it is.
    for (const limit of [0, -3, 2.5, 999_999])
      expect(check({ limit })).toEqual(['analysis.limit.out-of-range']);
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
    expect(
      check({ sort: [{ alias: 'gone', direction: SortDirection.ASC }] }),
    ).toEqual(['analysis.sort.unknown-alias']);
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
      config({
        ...ungrouped,
        sort: [{ alias: 'orders', direction: SortDirection.DESC }],
      }),
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
      check({
        ...ungrouped,
        sort: [{ alias: 'gone', direction: SortDirection.ASC }],
      }),
    ).toEqual(['analysis.sort.requires-group', 'analysis.sort.unknown-alias']);

    const having = validateAnalysis(
      definition(),
      config({
        ...ungrouped,
        sort: [],
        having: {
          type: HavingExpressionType.CONDITION,
          metric: 'orders',
          operator: ComparisonOperator.GT,
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
