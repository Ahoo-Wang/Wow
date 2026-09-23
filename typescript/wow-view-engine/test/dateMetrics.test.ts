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
} from '@ahoo-wang/fetcher-wow';
import { describe, expect, it } from 'vitest';
import {
  analysisScope,
  builtinFieldKinds,
  defaultAnalysisConfig,
  fitChartSlots,
  fitCharts,
  momentColumns,
  momentMetrics,
  projectAnalysis,
  readsAsItsField,
  splitBy,
  validateAnalysis,
  validateChart,
  validateDefinition,
} from '../src/index.js';
import {
  aggregationFunctionsOf,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type DataViewDefinition,
} from '../src/model/index.js';
import { INSTANT, analysisConfig, ordersDefinition } from './fixtures.js';

const { SUM, AVG, MIN, MAX } = AggregationFunction;
const { TERMS } = AggregationGroupType;

/**
 * Orders with two moments beside the amount: a creation time whose
 * capability declares the sum and the average as well — a mistake in code
 * the scope refuses rather than obeys — and a delivery day read as a date.
 */
function dated(
  overrides: Partial<DataViewDefinition['analysis'] & object> = {},
): DataViewDefinition {
  const base = ordersDefinition();
  return ordersDefinition({
    fields: [
      ...base.fields,
      { name: 'createdAt', label: 'Created', kind: 'datetime' },
      { name: 'deliveredOn', label: 'Delivered', kind: 'string', cell: 'date' },
    ],
    analysis: {
      count: true,
      expressions: true,
      having: true,
      fields: [
        { field: 'warehouse', groups: [TERMS], functions: [] },
        { field: 'amount', groups: [], functions: [SUM, MIN, MAX] },
        {
          field: 'createdAt',
          groups: [],
          functions: [SUM, AVG, MIN, MAX],
          any: true,
          percentile: true,
        },
        { field: 'deliveredOn', groups: [], functions: [MIN, MAX] },
      ],
      ...overrides,
    },
  });
}

const warehouse = { type: 'TERMS', field: 'warehouse', alias: 'wh' } as const;
const count: AnalysisMetric = { type: 'COUNT', alias: 'orders' };
const total: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'total',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'amount' },
};
const latest: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'latest',
  function: 'MAX',
  expression: { type: 'FIELD', field: 'createdAt' },
};
const earliest: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'earliest',
  function: 'MIN',
  expression: { type: 'FIELD', field: 'createdAt' },
};

function config(
  metrics: AnalysisMetric[],
  overrides: Partial<AnalysisViewConfig> = {},
): AnalysisViewConfig {
  const groups = overrides.groups ?? [warehouse];
  const definition = dated();
  const moments = momentMetrics(
    metrics,
    analysisScope(definition, definition.analysis!).fields,
  );
  return analysisConfig({
    groups,
    metrics: metrics as AnalysisViewConfig['metrics'],
    chart: fitChartSlots({ type: 'bar' }, groups, metrics, moments),
    ...overrides,
  });
}

const errors = (target: AnalysisViewConfig, definition = dated()) =>
  validateAnalysis(definition, target, builtinFieldKinds)
    .filter(found => found.severity === 'error')
    .map(found => found.code);

describe('a date is summarised by its earliest and its latest', () => {
  it('offers MIN and MAX of a date and nothing else, whatever is declared', () => {
    const definition = dated();
    const scope = analysisScope(definition, definition.analysis!);
    expect(scope.aggregations.get('createdAt')?.functions).toEqual([MIN, MAX]);
    // A reading of `date` over a string is a date as much as the kind is.
    expect(scope.aggregations.get('deliveredOn')?.functions).toEqual([
      MIN,
      MAX,
    ]);
    expect(scope.aggregations.get('amount')?.functions).toEqual([
      SUM,
      MIN,
      MAX,
    ]);
    expect(
      aggregationFunctionsOf({ kind: 'date' }, ['SUM', 'AVG', 'MIN', 'MAX']),
    ).toEqual(['MIN', 'MAX']);
    expect(aggregationFunctionsOf(undefined, ['SUM'])).toEqual(['SUM']);
  });

  it('refuses the sum and the average of a date, as a record column does', () => {
    for (const fn of ['SUM', 'AVG', 'STDDEV', 'VARIANCE'] as const)
      expect(
        errors(
          config([
            {
              type: 'NUMERIC',
              alias: 'bad',
              function: fn,
              expression: { type: 'FIELD', field: 'createdAt' },
            },
          ]),
        ),
      ).toEqual(['analysis.function.unsupported']);
    expect(errors(config([latest, earliest, count]))).toEqual([]);
  });

  it('never starts a view on a date’s sum, and admits no capability made only of one', () => {
    const onlyDates = dated({
      count: false,
      fields: [
        { field: 'warehouse', groups: [TERMS], functions: [] },
        { field: 'createdAt', groups: [], functions: [SUM, MAX] },
      ],
    });
    const start = defaultAnalysisConfig(onlyDates);
    expect(start.metrics).toEqual([
      {
        type: 'NUMERIC',
        alias: 'createdAt_max',
        function: 'MAX',
        expression: { type: 'FIELD', field: 'createdAt' },
      },
    ]);
    // The latest is read, not drawn: the view starts as its table, and the
    // chart it would switch to is valid and measures nothing.
    expect(start.layout).toBe('table');
    expect(start.chart.cartesian?.series).toEqual([]);
    expect(errors(start, onlyDates)).toEqual([]);

    const sumOnly = dated({
      count: false,
      fields: [{ field: 'createdAt', groups: [], functions: [SUM] }],
    });
    expect(
      validateDefinition(sumOnly, builtinFieldKinds).map(found => found.code),
    ).toContain('definition.analysis.no-metric');
  });
});

describe('a metric that is one of its field’s values reads as the field', () => {
  it('carries the field’s reading on MIN, MAX, a percentile and ANY', () => {
    const metrics: AnalysisMetric[] = [
      latest,
      earliest,
      {
        type: 'PERCENTILE',
        alias: 'p50',
        percentile: 50,
        expression: { type: 'FIELD', field: 'createdAt' },
      },
      { type: 'ANY', alias: 'some', field: 'createdAt' },
      {
        type: 'NUMERIC',
        alias: 'delivered',
        function: 'MAX',
        expression: { type: 'FIELD', field: 'deliveredOn' },
      },
      total,
      count,
    ];
    const view = projectAnalysis(dated(), config(metrics), [
      { wh: 'CN', latest: INSTANT, earliest: INSTANT, total: 3, orders: 1 },
    ]);
    const column = (alias: string) =>
      view.columns.find(found => found.alias === alias);
    for (const alias of ['latest', 'earliest', 'p50', 'some'])
      expect(column(alias)).toMatchObject({
        kind: 'datetime',
        cell: 'datetime',
      });
    expect(column('delivered')).toMatchObject({ kind: 'string', cell: 'date' });
    // A sum and a count are numbers the query computed, whatever from.
    expect(column('total')?.cell).toBeUndefined();
    expect(column('orders')?.cell).toBeUndefined();
    expect(view.rows[0]?.latest).toBe(INSTANT);
    // The result says which are moments without the definition being asked.
    expect([...momentColumns(view.schema ?? [])]).toEqual([
      'latest',
      'earliest',
      'p50',
      'some',
      'delivered',
    ]);
  });

  it('names the metrics that are moments, and only those', () => {
    expect(readsAsItsField(latest)).toBe(true);
    expect(readsAsItsField(total)).toBe(false);
    expect(readsAsItsField(count)).toBe(false);
    const definition = dated();
    const fields = analysisScope(definition, definition.analysis!).fields;
    const moments = momentMetrics(
      [
        latest,
        total,
        count,
        {
          type: 'NUMERIC',
          alias: 'least',
          function: 'MIN',
          expression: { type: 'FIELD', field: 'amount' },
        },
        { type: 'ANY', alias: 'some', field: 'createdAt' },
      ],
      fields,
    );
    expect([...moments]).toEqual(['latest', 'some']);
  });
});

describe('a moment is read, never measured by a mark', () => {
  const moments = new Set(['latest', 'earliest']);

  it('greys every family that measures, and keeps the card', () => {
    const byWarehouse = fitCharts({
      groups: [warehouse],
      metrics: [latest, earliest],
      moments,
    });
    for (const type of ['bar', 'line', 'area', 'combo', 'pie'] as const)
      expect(byWarehouse[type]).toEqual({
        available: false,
        reason: 'chart.fit.needs-quantity',
      });
    expect(
      Object.values(byWarehouse).some(fit => fit.recommended === true),
    ).toBe(false);

    const withCount = fitCharts({
      groups: [warehouse],
      metrics: [count, latest],
      moments,
    });
    expect(withCount.bar).toEqual({ available: true, recommended: true });
    expect(withCount.scatter.reason).toBe('chart.fit.needs-quantity');

    const headline = fitCharts({ groups: [], metrics: [latest], moments });
    expect(headline.metric).toEqual({ available: true, recommended: true });
  });

  it('fills the slots a mark measures from the quantities alone', () => {
    const groups = [warehouse];
    const metrics = [count, latest, total];
    const bars = fitChartSlots({ type: 'bar' }, groups, metrics, moments);
    expect(bars.cartesian?.series.map(series => series.metric)).toEqual([
      'orders',
      'total',
    ]);
    const pie = fitChartSlots(
      { type: 'pie', pie: { category: 'wh', value: 'latest' } },
      groups,
      metrics,
      moments,
    );
    expect(pie.pie?.value).toBe('orders');
    // Nothing left to measure: the valid, empty bars, whatever was picked.
    const empty = fitChartSlots({ type: 'heatmap' }, groups, [latest], moments);
    expect(empty.type).toBe('bar');
    expect(empty.cartesian?.series).toEqual([]);
    expect(validateChart(config([latest], { chart: empty }), moments)).toEqual(
      [],
    );
  });

  it('keeps a card over a moment to the moment itself', () => {
    const card = fitChartSlots(
      {
        type: 'metric',
        metric: {
          metric: 'latest',
          compare: { metric: 'orders', mode: 'delta' },
          target: 10,
          format: 'compact',
        },
      },
      [],
      [latest, count],
      moments,
    );
    expect(card.metric).toEqual({ metric: 'latest' });
  });

  it('refuses a stored chart that measures a moment', () => {
    const codes = (chart: AnalysisViewConfig['chart'], groups = [warehouse]) =>
      validateChart(config([count, latest], { groups, chart }), moments).map(
        found => `${found.code} ${found.path.join('.')}`,
      );
    expect(
      codes({
        type: 'bar',
        cartesian: { x: 'wh', series: [{ metric: 'latest' }] },
      }),
    ).toEqual(['chart.metric.moment chart.cartesian.series.0.metric']);
    expect(
      codes({ type: 'pie', pie: { category: 'wh', value: 'latest' } }),
    ).toEqual(['chart.metric.moment chart.pie.value']);
    expect(
      codes(
        {
          type: 'metric',
          metric: {
            metric: 'latest',
            compare: { metric: 'orders', mode: 'delta' },
            target: 1,
            format: 'compact',
          },
        },
        [],
      ),
    ).toEqual([
      'chart.metric.moment chart.metric.compare',
      'chart.metric.moment chart.metric.target',
      'chart.metric.moment chart.metric.format',
    ]);
    // Admission reads the moments off the definition itself.
    expect(
      errors(
        config([count, latest], {
          chart: {
            type: 'bar',
            cartesian: { x: 'wh', series: [{ metric: 'latest' }] },
          },
        }),
      ),
    ).toEqual(['chart.metric.moment']);
  });

  it('splits by another dimension without drawing the moment', () => {
    const ran = config([count, latest]);
    const split = splitBy(
      ran,
      [],
      { type: 'TERMS', field: 'status', alias: 'st' },
      moments,
    );
    expect(split.chart.cartesian?.series).toEqual([{ metric: 'orders' }]);
  });
});

describe('a moment is no operand', () => {
  it('refuses a date in a formula, where MIN of one is admitted', () => {
    expect(
      errors(
        config([
          {
            type: 'NUMERIC',
            alias: 'span',
            function: 'AVG',
            expression: {
              type: 'BINARY',
              operator: 'SUBTRACT',
              left: { type: 'FIELD', field: 'createdAt' },
              right: { type: 'FIELD', field: 'amount' },
            },
          },
        ]),
      ),
    ).toEqual(['analysis.expression.date-operand']);
  });

  it('refuses a moment in a derived metric and in 「只保留」', () => {
    expect(
      errors(
        config([
          latest,
          earliest,
          {
            type: 'DERIVED',
            alias: 'span',
            expression: {
              type: 'BINARY',
              operator: 'SUBTRACT',
              left: { type: 'METRIC_REF', metric: 'latest' },
              right: { type: 'METRIC_REF', metric: 'earliest' },
            },
          },
        ]),
      ),
    ).toEqual([
      'analysis.derived.moment-operand',
      'analysis.derived.moment-operand',
    ]);
    expect(
      errors(
        config([count, latest], {
          having: {
            type: 'CONDITION',
            metric: 'latest',
            operator: 'GT',
            value: 0,
          },
        }),
      ),
    ).toEqual(['analysis.having.unknown-metric']);
  });
});
