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
 * The running lines Wow computes over every group (D87): a source whose
 * descriptor lists `RUNNING` (and `TOTAL` for a share) is asked for each
 * line as a hidden derived metric, and the line drawn from it is not greyed
 * out for a cut or for 「只保留」 — Wow computed it before `having` and
 * `limit`. Without the window, nothing changes (Q53).
 */

import {
  AGGREGATION_LIMITS,
  DerivedExpressionType,
  RunningFunction,
  SortDirection,
  WindowFunction,
  type DerivedAggregationMetric,
  AggregationGroupType,
  AggregationDateUnit,
  AggregationExpressionOperator,
  AggregationMetricType,
  AggregationFunction,
  AggregationExpressionType,
  HavingExpressionType,
  ComparisonOperator,
} from '@ahoo-wang/wow-client/dsl';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  compileAnalysis,
  type AnalysisViewConfig,
  type CartesianData,
  type DerivedSeries,
  type RecordData,
} from '../src/index.js';
import { shapeChart } from '../src/analysis/chart.js';
import { narrowDefinition } from '../src/capabilities/index.js';
import { ordersDescriptor } from './fixtures/descriptor.js';
import {
  analysisCapability,
  analysisContext as context,
  analysisDefinition,
  analysisKernelConfig,
} from './fixtures/analysis.js';

const windowed = (
  windows = [WindowFunction.RUNNING, WindowFunction.TOTAL] as const,
) =>
  analysisDefinition({
    analysis: { ...analysisCapability, windows: [...windows] },
  });

function daily(
  derived: DerivedSeries[],
  overrides: Partial<AnalysisViewConfig> = {},
): AnalysisViewConfig {
  return analysisKernelConfig({
    groups: [
      {
        type: AggregationGroupType.DATE_HISTOGRAM,
        field: 'createdAt',
        alias: 'day',
        unit: AggregationDateUnit.DAY,
      },
    ],
    sort: [],
    chart: {
      type: 'line',
      cartesian: { x: 'day', series: [{ metric: 'orders' }], derived },
    },
    ...overrides,
  });
}

const hidden = (definition = windowed(), config = daily([])) =>
  compileAnalysis(definition, config, builtinFieldKinds, context)
    .metrics.slice(config.metrics.length)
    .map(metric => metric as DerivedAggregationMetric);

const byDay = { field: 'day', direction: SortDirection.ASC };

describe('the hidden metrics of the lines Wow computes', () => {
  it('asks for a running total, a running share and a moving average', () => {
    const metrics = hidden(
      windowed(),
      daily([
        { kind: 'cumulative', metric: 'orders' },
        { kind: 'cumulative-share', metric: 'orders' },
        { kind: 'moving-average', metric: 'orders', window: 3 },
        { kind: 'trend', metric: 'orders' },
      ]),
    );
    expect(metrics.map(metric => metric.alias)).toEqual([
      'orders__cumulative',
      'orders__share',
      'orders__moving3',
    ]);
    expect(metrics.map(metric => metric.expression)).toEqual([
      { type: DerivedExpressionType.RUNNING, metric: 'orders', order: byDay },
      {
        type: DerivedExpressionType.BINARY,
        operator: AggregationExpressionOperator.DIVIDE,
        left: {
          type: DerivedExpressionType.RUNNING,
          metric: 'orders',
          order: byDay,
        },
        right: { type: DerivedExpressionType.TOTAL, metric: 'orders' },
      },
      {
        type: DerivedExpressionType.RUNNING,
        metric: 'orders',
        order: byDay,
        frame: 3,
        function: RunningFunction.AVG,
      },
    ]);
  });

  it('runs a Pareto along the order the result is sorted in', () => {
    const config = analysisKernelConfig({
      sort: [{ alias: 'orders', direction: SortDirection.ASC }],
      chart: {
        type: 'bar',
        cartesian: {
          x: 'wh',
          series: [{ metric: 'orders' }],
          derived: [
            { kind: 'cumulative', metric: 'orders' },
            { kind: 'moving-average', metric: 'orders' },
          ],
        },
      },
    });
    expect(hidden(windowed(), config).map(metric => metric.expression)).toEqual(
      [
        {
          type: DerivedExpressionType.RUNNING,
          metric: 'orders',
          order: { field: 'orders', direction: SortDirection.ASC },
        },
      ],
    );
    expect(hidden(windowed(), { ...config, sort: [] })).toEqual([]);
  });

  it.each<[string, () => DerivedAggregationMetric[]]>([
    [
      'the source lists no window',
      () =>
        hidden(
          analysisDefinition(),
          daily([{ kind: 'cumulative', metric: 'orders' }]),
        ),
    ],
    [
      'a share without TOTAL',
      () =>
        hidden(
          windowed([WindowFunction.RUNNING] as never),
          daily([{ kind: 'cumulative-share', metric: 'orders' }]),
        ),
    ],
    [
      'a split chart',
      () =>
        hidden(
          windowed(),
          daily([{ kind: 'cumulative', metric: 'orders' }], {
            groups: [
              {
                type: AggregationGroupType.DATE_HISTOGRAM,
                field: 'createdAt',
                alias: 'day',
                unit: AggregationDateUnit.DAY,
              },
              {
                type: AggregationGroupType.TERMS,
                field: 'warehouse',
                alias: 'wh',
              },
            ],
            chart: {
              type: 'line',
              cartesian: {
                x: 'day',
                splitBy: 'wh',
                series: [{ metric: 'orders' }],
                derived: [{ kind: 'cumulative', metric: 'orders' }],
              },
            },
          }),
        ),
    ],
    [
      'a running total of an average',
      () =>
        hidden(
          windowed(),
          daily([{ kind: 'cumulative', metric: 'avg' }], {
            metrics: [
              { type: AggregationMetricType.COUNT, alias: 'orders' },
              {
                type: AggregationMetricType.NUMERIC,
                alias: 'avg',
                function: AggregationFunction.AVG,
                expression: {
                  type: AggregationExpressionType.FIELD,
                  field: 'amount',
                },
              },
            ],
          }),
        ),
    ],
    [
      'an alias already taken',
      () =>
        hidden(
          windowed(),
          daily([{ kind: 'cumulative', metric: 'orders' }], {
            metrics: [
              { type: AggregationMetricType.COUNT, alias: 'orders' },
              {
                type: AggregationMetricType.COUNT,
                alias: 'orders__cumulative',
              },
            ],
          }),
        ),
    ],
  ])('asks for none when %s', (_why, metrics) => {
    expect(metrics()).toEqual([]);
  });

  it('stays within the windows Wow computes for one query', () => {
    const lines = Array.from(
      { length: AGGREGATION_LIMITS.MAX_WINDOWS },
      (_, index): DerivedSeries => ({
        kind: 'moving-average',
        metric: 'orders',
        window: index + 2,
      }),
    );
    const metrics = hidden(
      windowed(),
      daily([{ kind: 'cumulative-share', metric: 'orders' }, ...lines]),
    );
    expect(metrics).toHaveLength(AGGREGATION_LIMITS.MAX_WINDOWS - 1);
    expect(metrics[0]?.alias).toBe('orders__share');
  });
});

const DAY = 86_400_000;

describe('a line Wow computed', () => {
  const shaped = (
    config: AnalysisViewConfig,
    rows: RecordData[],
    cutShort = false,
  ) =>
    shapeChart(config, rows, undefined, {
      timeZone: 'UTC',
      cutShort,
    }) as CartesianData;

  it('is read off its hidden metric, cut short or narrowed', () => {
    const config = daily([{ kind: 'cumulative', metric: 'orders' }], {
      having: {
        type: HavingExpressionType.CONDITION,
        metric: 'orders',
        operator: ComparisonOperator.GT,
        value: 0,
      },
    });
    const rows = [
      { day: 0, orders: 2, orders__cumulative: 12 },
      { day: DAY, orders: 3, orders__cumulative: 15 },
    ];
    const data = shaped(config, rows, true);
    expect(data.gaps).toBeUndefined();
    expect(data.derived?.[0]?.values).toEqual([12, 15]);
    // The same rows without the hidden metric: the kernel's rule (Q53).
    expect(
      shaped(
        config,
        rows.map(({ day, orders }) => ({ day, orders })),
        true,
      ).gaps?.[0]?.gap,
    ).toBe('narrowed');
  });

  it('holds a running value over a day without records', () => {
    const data = shaped(daily([{ kind: 'cumulative', metric: 'orders' }]), [
      { day: 0, orders: 2, orders__cumulative: 2 },
      { day: 2 * DAY, orders: 3, orders__cumulative: 5 },
    ]);
    expect(data.points.map(point => point.x)).toEqual([0, DAY, 2 * DAY]);
    expect(data.derived?.[0]?.values).toEqual([2, 2, 5]);
  });

  it('has no moving average before its window fills, nor over a day no row stands for', () => {
    const config = daily([
      { kind: 'moving-average', metric: 'orders', window: 2 },
    ]);
    const full = shaped(
      config,
      [
        { day: 0, orders: 2, orders__moving2: 2 },
        { day: DAY, orders: 4, orders__moving2: 3 },
        { day: 2 * DAY, orders: 6, orders__moving2: 5 },
      ],
      true,
    );
    expect(full.gaps).toBeUndefined();
    expect(full.derived?.[0]?.values).toEqual([null, 3, 5]);
    const holed = shaped(config, [
      { day: 0, orders: 2, orders__moving2: 2 },
      { day: 2 * DAY, orders: 6, orders__moving2: 4 },
    ]);
    expect(holed.gaps?.[0]?.gap).toBe('holes');
  });
});

describe('the windows a source computes', () => {
  it('are the descriptor’s to say, and none from a server that says nothing', () => {
    const base = ordersDescriptor();
    const narrow = (windows?: WindowFunction[]) =>
      narrowDefinition(
        analysisDefinition({
          analysis: { ...analysisCapability, windows: [WindowFunction.RANK] },
        }),
        {
          ...base,
          analysis: {
            ...base.analysis,
            ...(windows === undefined ? {} : { windows }),
          },
        },
        builtinFieldKinds,
      ).definition.analysis;
    expect(
      narrow([WindowFunction.RUNNING, WindowFunction.TOTAL])?.windows,
    ).toEqual([WindowFunction.RUNNING, WindowFunction.TOTAL]);
    expect(narrow([])).not.toHaveProperty('windows');
    expect(narrow()).not.toHaveProperty('windows');
  });
});
