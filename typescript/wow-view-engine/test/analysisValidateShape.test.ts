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
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
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
  validateAnalysis,
  type AnalysisDerivedExpression,
  type AnalysisExpression,
  type AnalysisHavingExpression,
  type AnalysisMetric,
  type AnalysisViewConfig,
  DEFAULT_RUNTIME_LIMITS,
} from '../src/index.js';
import {
  analysisContext as context,
  analysisDefinition as definition,
  analysisKernelConfig as config,
  errorCodes as codes,
} from './fixtures/analysis.js';

const check = (overrides: Partial<AnalysisViewConfig>) =>
  codes(validateAnalysis(definition(), config(overrides), builtinFieldKinds));

describe('expression budgets', () => {
  // Every tree here is built iteratively: the point is that validation must
  // survive a tree the recursive walks could not.
  const chain = (depth: number): AnalysisExpression => {
    let node: AnalysisExpression = {
      type: AggregationExpressionType.FIELD,
      field: 'amount',
    };
    for (let level = 1; level < depth; level += 1)
      node = {
        type: AggregationExpressionType.BINARY,
        operator: AggregationExpressionOperator.ADD,
        left: node,
        right: { type: AggregationExpressionType.CONSTANT, value: 1 },
      };
    return node;
  };

  const derivedChain = (depth: number): AnalysisDerivedExpression => {
    let node: AnalysisDerivedExpression = {
      type: DerivedExpressionType.METRIC_REF,
      metric: 'orders',
    };
    for (let level = 1; level < depth; level += 1)
      node = {
        type: AggregationExpressionType.BINARY,
        operator: AggregationExpressionOperator.ADD,
        left: node,
        right: { type: AggregationExpressionType.CONSTANT, value: 1 },
      };
    return node;
  };

  const condition: AnalysisHavingExpression = {
    type: HavingExpressionType.CONDITION,
    metric: 'orders',
    operator: ComparisonOperator.GT,
    value: 1,
  };
  const havingChain = (depth: number): AnalysisHavingExpression => {
    let node: AnalysisHavingExpression = condition;
    for (let level = 1; level < depth; level += 1)
      node = { type: HavingExpressionType.AND, operands: [node] };
    return node;
  };

  /** A complete binary tree of the given depth, 2^depth - 1 nodes. */
  const full = (depth: number): AnalysisExpression => {
    let level: AnalysisExpression[] = Array.from(
      { length: 2 ** (depth - 1) },
      () => ({ type: AggregationExpressionType.CONSTANT, value: 1 }),
    );
    while (level.length > 1) {
      const next: AnalysisExpression[] = [];
      for (let index = 0; index < level.length; index += 2)
        next.push({
          type: AggregationExpressionType.BINARY,
          operator: AggregationExpressionOperator.ADD,
          left: level[index],
          right: level[index + 1],
        });
      level = next;
    }
    return level[0];
  };

  const orders: AnalysisMetric = {
    type: AggregationMetricType.COUNT,
    alias: 'orders',
  };
  const sum = (
    alias: string,
    expression: AnalysisExpression,
  ): AnalysisMetric => ({
    type: AggregationMetricType.NUMERIC,
    alias,
    function: AggregationFunction.SUM,
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
      type: AggregationMetricType.DERIVED,
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
      type: HavingExpressionType.AND,
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
    [
      'metrics',
      { metrics: { type: AggregationMetricType.COUNT } },
      ['metrics'],
    ],
    ['sort', { sort: null }, ['sort']],
    ['elements', { elements: 'items' }, ['elements']],
    ['table', { table: 'columns' }, ['table']],
    ['table.columns', { table: { columns: 5 } }, ['table', 'columns']],
    ['chart', { chart: undefined }, ['chart']],
    [
      'a group alias',
      {
        groups: [
          { type: AggregationGroupType.TERMS, field: 'warehouse', alias: 42 },
        ],
      },
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
      'analysis.limit.out-of-range',
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
          {
            type: AggregationGroupType.TERMS,
            field: 'warehouse',
            alias: 'wh',
            label: '  ',
          },
        ],
      }),
    ).toContain('analysis.label.blank');
    expect(
      check({
        metrics: [{ type: AggregationMetricType.COUNT, alias: 'n', label: '' }],
      }),
    ).toContain('analysis.label.blank');
    expect(
      check({
        groups: [
          {
            type: AggregationGroupType.TERMS,
            field: 'warehouse',
            alias: 'wh',
            label: 7 as unknown as string,
          },
        ],
      }),
    ).toContain('analysis.config.malformed');
  });
});
