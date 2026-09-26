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
 * The opening and the closing value (N1, Wow's `FIRST` / `LAST`) as
 * ordinary metrics, and the candlestick family drawn from a field's four
 * numbers: admission, the descriptor, compilation, the tray's builders, the
 * set a candle is recognised as, its fit and its shaping.
 */

import {
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
  AggregationMetricType,
  type QueryModelDescriptor,
} from '@ahoo-wang/wow-client';
import { describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  compileAnalysis,
  firstMetric,
  fitCharts,
  fitChartSlots,
  isValueMetric,
  metricOfSummary,
  readsAsItsField,
  shapeChart,
  summaryChoices,
  summaryOf,
  validateAnalysis,
  validateDefinition,
  type AnalysisCapability,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type DataViewDefinition,
} from '../src/index.js';
import {
  isOhlcSet,
  ohlcMetrics,
  ohlcSet,
  ohlcSets,
} from '../src/analysis/candlestick.js';
import { narrowDefinition } from '../src/capabilities/index.js';
import {
  analysisContext as context,
  analysisKernelConfig as config,
  errorCodes as codes,
} from './fixtures/analysis.js';
import { describedField, ordersDescriptor } from './fixtures/descriptor.js';

const capability: AnalysisCapability = {
  count: true,
  fields: [
    {
      field: 'placedAt',
      groups: [AggregationGroupType.DATE_HISTOGRAM],
      functions: [AggregationFunction.MIN, AggregationFunction.MAX],
      dateUnits: [AggregationDateUnit.DAY],
    },
    {
      field: 'price',
      groups: [],
      functions: [AggregationFunction.MIN, AggregationFunction.MAX],
      firstLast: true,
    },
    {
      field: 'qty',
      groups: [AggregationGroupType.TERMS],
      functions: [AggregationFunction.SUM],
    },
  ],
  elements: [
    {
      path: 'lines',
      aggregations: [
        { field: 'price', groups: [], functions: [], firstLast: true },
        {
          field: 'at',
          groups: [AggregationGroupType.DATE_HISTOGRAM],
          functions: [],
          dateUnits: [AggregationDateUnit.DAY],
        },
      ],
    },
  ],
  expressions: true,
  having: true,
};

function definition(
  overrides: Partial<DataViewDefinition> = {},
): DataViewDefinition {
  return {
    id: 'trades',
    title: 'Trades',
    kind: 'data',
    source: 'trades',
    fields: [
      { name: 'placedAt', label: 'Placed', kind: 'datetime' },
      {
        name: 'price',
        label: 'Price',
        kind: 'number',
        numberFormat: { style: 'currency', currency: 'CNY' },
      },
      { name: 'qty', label: 'Qty', kind: 'number' },
      {
        name: 'lines',
        label: 'Lines',
        kind: 'array',
        elements: [
          { name: 'price', label: 'Line price', kind: 'number' },
          { name: 'at', label: 'Line time', kind: 'datetime' },
        ],
      },
    ],
    analysis: capability,
    ...overrides,
  };
}

const DAY = {
  type: 'DATE_HISTOGRAM',
  field: 'placedAt',
  alias: 'day',
  unit: 'DAY',
} as const;
const open: AnalysisMetric = { type: 'FIRST', alias: 'open', field: 'price' };
const high: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'high',
  function: 'MAX',
  expression: { type: 'FIELD', field: 'price' },
};
const low: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'low',
  function: 'MIN',
  expression: { type: 'FIELD', field: 'price' },
};
const close: AnalysisMetric = { type: 'LAST', alias: 'close', field: 'price' };
const OHLC = [open, high, low, close] as [AnalysisMetric, ...AnalysisMetric[]];

const candleConfig = (
  overrides: Partial<AnalysisViewConfig> = {},
): AnalysisViewConfig =>
  config({
    groups: [DAY],
    metrics: OHLC,
    sort: [],
    chart: {
      type: 'candlestick',
      candlestick: {
        x: 'day',
        open: 'open',
        high: 'high',
        low: 'low',
        close: 'close',
      },
    },
    ...overrides,
  });

const check = (overrides: Partial<AnalysisViewConfig>) =>
  codes(
    validateAnalysis(definition(), candleConfig(overrides), builtinFieldKinds),
  );

describe('the opening and the closing value as metrics', () => {
  it('are offered as a field’s summaries where it declares them', () => {
    expect(
      summaryChoices({ ...capability.fields[1]!, field: 'price' }),
    ).toEqual(['MIN', 'MAX', 'FIRST', 'LAST']);
    expect(
      summaryChoices({ ...capability.fields[2]!, field: 'qty' }),
    ).not.toContain('FIRST');
    expect(metricOfSummary({ field: 'price' }, 'LAST', 'p')).toEqual({
      type: 'LAST',
      alias: 'p',
      field: 'price',
    });
    expect(summaryOf(open)).toBe('FIRST');
    expect(isValueMetric(close)).toBe(true);
    expect(readsAsItsField(open)).toBe(true);
  });

  it('are admitted where declared, and refused where not', () => {
    expect(check({})).toEqual([]);
    expect(
      check({
        metrics: [{ type: 'FIRST', alias: 'open', field: 'qty' }],
        chart: { type: 'bar', cartesian: { x: 'day', series: [] } },
      }),
    ).toEqual(['analysis.first-last.undeclared']);
    expect(
      check({
        metrics: [
          { type: 'FIRST', alias: 'open', field: 'price', orderBy: 'gone' },
        ],
        chart: { type: 'bar', cartesian: { x: 'day', series: [] } },
      }),
    ).toEqual(['analysis.field.unknown']);
  });

  it('must name a time inside expanded entries, which have no event time', () => {
    const inLines = (orderBy?: string) =>
      codes(
        validateAnalysis(
          definition(),
          config({
            elements: [{ path: 'lines' }],
            groups: [],
            metrics: [
              {
                type: 'LAST',
                alias: 'close',
                field: 'lines.price',
                ...(orderBy ? { orderBy } : {}),
              },
            ],
            sort: [],
            chart: { type: 'metric', metric: { metric: 'close' } },
          }),
          builtinFieldKinds,
        ),
      );
    expect(inLines()).toEqual(['analysis.first-last.order-by-required']);
    expect(inLines('lines.at')).toEqual([]);
  });

  it('are no operand of a derived metric and nothing 「只保留」 compares', () => {
    expect(
      check({
        metrics: [
          ...OHLC,
          {
            type: 'DERIVED',
            alias: 'range',
            expression: {
              type: 'BINARY',
              operator: 'SUBTRACT',
              left: { type: 'METRIC_REF', metric: 'close' },
              right: { type: 'METRIC_REF', metric: 'open' },
            },
          },
        ],
      }),
    ).toContain('analysis.derived.unknown-metric');
    expect(
      check({
        having: {
          type: 'CONDITION',
          metric: 'close',
          operator: 'GT',
          value: 1,
        },
      }),
    ).toContain('analysis.having.unknown-metric');
  });

  it('compile with their order, relative to the element they read', () => {
    const query = compileAnalysis(
      definition(),
      candleConfig({
        metrics: [
          { ...open, orderBy: 'placedAt' },
          high,
          low,
          { ...close, filter: { op: 'and', children: [] } },
        ],
      }),
      builtinFieldKinds,
      context,
    );
    expect(query.metrics[0]).toEqual({
      type: AggregationMetricType.FIRST,
      field: 'price',
      orderBy: 'placedAt',
      alias: 'open',
    });
    expect(query.metrics[3]).toMatchObject({
      type: AggregationMetricType.LAST,
      field: 'price',
      alias: 'close',
    });
    expect(query.metrics[3]).not.toHaveProperty('orderBy');

    const inLines = compileAnalysis(
      definition(),
      config({
        elements: [{ path: 'lines' }],
        groups: [],
        metrics: [
          {
            type: 'FIRST',
            alias: 'open',
            field: 'lines.price',
            orderBy: 'lines.at',
          },
        ],
        sort: [],
        chart: { type: 'metric', metric: { metric: 'open' } },
      }),
      builtinFieldKinds,
      context,
    );
    expect(inLines.metrics[0]).toMatchObject({ field: 'price', orderBy: 'at' });
  });

  it('start an analysis only when nothing else can', () => {
    expect(
      firstMetric(false, [
        { field: 'price', groups: [], functions: [], firstLast: true },
      ]),
    ).toEqual({ type: 'LAST', alias: 'price_last', field: 'price' });
    expect(
      validateDefinition(
        definition({
          analysis: {
            count: false,
            fields: [
              { field: 'price', groups: [], functions: [], firstLast: true },
            ],
          },
        }),
        builtinFieldKinds,
      ).filter(found => found.code === 'definition.analysis.no-metric'),
    ).toEqual([]);
  });
});

describe('narrowing by the descriptor', () => {
  function described(
    edit: (descriptor: QueryModelDescriptor) => void,
  ): QueryModelDescriptor {
    const base = ordersDescriptor();
    const descriptor: QueryModelDescriptor = {
      ...base,
      fields: [
        describedField('placedAt'),
        describedField('price'),
        describedField('qty'),
      ],
      elements: [],
      analysis: {
        ...base.analysis,
        dateUnits: [AggregationDateUnit.DAY],
        firstLastOrderBy: 'eventTime',
      },
    };
    edit(descriptor);
    return descriptor;
  }
  const noElements = definition({
    analysis: { ...capability, elements: undefined },
  });

  it('keeps them where the model reads both ends, and says its default order', () => {
    const { definition: narrowed } = narrowDefinition(
      noElements,
      described(() => {}),
      builtinFieldKinds,
    );
    expect(narrowed.analysis?.fields[1]).toMatchObject({ firstLast: true });
    expect(narrowed.analysis?.firstLastOrderBy).toBe('eventTime');
  });

  it('takes them away where the model or the field reads no end', () => {
    const noMetric = narrowDefinition(
      noElements,
      described(descriptor => {
        descriptor.analysis.metrics = descriptor.analysis.metrics.filter(
          type => type !== 'LAST',
        );
        delete descriptor.analysis.firstLastOrderBy;
      }),
      builtinFieldKinds,
    );
    expect(noMetric.definition.analysis?.fields[1]).toMatchObject({
      firstLast: false,
    });
    expect(noMetric.definition.analysis).not.toHaveProperty('firstLastOrderBy');
    expect(noMetric.findings).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          code: 'capability.analysis.field-narrowed',
          params: { field: 'price', dropped: 'FIRST_LAST' },
        }),
      ]),
    );

    const noField = narrowDefinition(
      noElements,
      described(descriptor => {
        descriptor.fields[1] = describedField('price', {
          aggregate: {
            ...describedField('price').aggregate!,
            firstLast: false,
          },
        });
      }),
      builtinFieldKinds,
    );
    expect(noField.definition.analysis?.fields[1]).toMatchObject({
      firstLast: false,
    });
  });
});

describe('a candle’s four numbers', () => {
  it('are recognised as one field’s open, high, low and close', () => {
    expect(ohlcSets(OHLC)).toEqual([
      { open: 'open', high: 'high', low: 'low', close: 'close' },
    ]);
    expect(
      isOhlcSet(ohlcSets(OHLC)[0]!, alias =>
        OHLC.find(metric => metric.alias === alias),
      ),
    ).toBe(true);
    // A close ordered by another time closes another period.
    expect(
      ohlcSets([open, high, low, { ...close, orderBy: 'placedAt' }]),
    ).toEqual([]);
    // A moment is no price.
    expect(ohlcSets(OHLC, new Set(['open', 'high', 'low']))).toEqual([]);
    // Two fields are no candle.
    expect(
      isOhlcSet(
        { open: 'open', high: 'high', low: 'low', close: 'other' },
        alias =>
          alias === 'other'
            ? { type: 'LAST', alias, field: 'qty' }
            : OHLC.find(metric => metric.alias === alias),
      ),
    ).toBe(false);
    const other = { open: 'o2', high: 'h2', low: 'l2', close: 'c2' };
    const first = ohlcSets(OHLC)[0]!;
    // A set the spec names is kept; one the metrics no longer hold is not.
    expect(ohlcSet(other, [first, other])).toBe(other);
    expect(ohlcSet(other, [first])).toBe(first);
    expect(ohlcSet(undefined, [])).toEqual({
      open: '',
      high: '',
      low: '',
      close: '',
    });
  });

  it('are completed from any one of them, under its condition and order', () => {
    const filter = { op: 'and' as const, children: [] };
    const added = ohlcMetrics({ ...close, orderBy: 'placedAt', filter }, [
      'close',
    ]);
    expect(added).toEqual([
      expect.objectContaining({
        type: 'FIRST',
        field: 'price',
        orderBy: 'placedAt',
        filter,
      }),
      expect.objectContaining({ type: 'NUMERIC', function: 'MAX', filter }),
      expect.objectContaining({ type: 'NUMERIC', function: 'MIN', filter }),
    ]);
    expect(added?.map(metric => metric.alias)).toEqual([
      expect.stringMatching(/^price_open/),
      expect.stringMatching(/^price_high/),
      expect.stringMatching(/^price_low/),
    ]);
    expect(ohlcMetrics(high, [])?.map(metric => metric.type)).toEqual([
      'FIRST',
      'NUMERIC',
      'LAST',
    ]);
    expect(ohlcMetrics({ type: 'COUNT', alias: 'n' }, [])).toBeUndefined();
    expect(
      ohlcMetrics(
        {
          type: 'NUMERIC',
          alias: 'total',
          function: 'SUM',
          expression: { type: 'FIELD', field: 'price' },
        },
        [],
      ),
    ).toBeUndefined();
  });
});

describe('the candlestick family', () => {
  it('fits one date dimension holding a field’s four numbers', () => {
    const fits = fitCharts({ groups: [DAY], metrics: OHLC, rows: [] });
    expect(fits.candlestick).toMatchObject({ available: true });
    expect(
      fitCharts({
        groups: [{ type: 'TERMS', field: 'qty', alias: 'q' }],
        metrics: OHLC,
        rows: [],
      }).candlestick,
    ).toMatchObject({ available: false, reason: 'chart.fit.needs-date' });
    expect(
      fitCharts({ groups: [DAY], metrics: [open, high], rows: [] }).candlestick,
    ).toMatchObject({ available: false, reason: 'chart.fit.needs-ohlc' });
    expect(
      fitCharts({ groups: [], metrics: OHLC, rows: [] }).candlestick,
    ).toMatchObject({ available: false, reason: 'chart.fit.needs-dimension' });
  });

  it('fills its slots from the set the metrics hold', () => {
    expect(
      fitChartSlots({ type: 'candlestick' }, [DAY], OHLC).candlestick,
    ).toEqual({
      x: 'day',
      open: 'open',
      high: 'high',
      low: 'low',
      close: 'close',
    });
  });

  it('refuses a non-date axis and four numbers that are no set', () => {
    // Unfit for its shape, the chart is drawn as the table (D20) and says so.
    expect(
      validateAnalysis(
        definition(),
        candleConfig({
          groups: [{ type: 'TERMS', field: 'qty', alias: 'day' }],
        }),
        builtinFieldKinds,
      ),
    ).toEqual([
      expect.objectContaining({
        code: 'chart.as-table',
        params: expect.objectContaining({ reason: 'chart.fit.needs-date' }),
      }),
    ]);
    // The result holds a candle, but the spec draws another field's
    // highest in it: four numbers that are no set.
    expect(
      check({
        metrics: [
          ...OHLC,
          {
            type: 'NUMERIC',
            alias: 'most',
            function: 'SUM',
            expression: { type: 'FIELD', field: 'qty' },
          },
        ],
        chart: {
          type: 'candlestick',
          candlestick: {
            x: 'day',
            open: 'open',
            high: 'most',
            low: 'low',
            close: 'close',
          },
        },
      }),
    ).toContain('chart.candlestick.not-ohlc');
  });

  it('shapes a candle per period, earliest first, and counts the incomplete', () => {
    const data = shapeChart(candleConfig(), [
      { day: Date.UTC(2026, 8, 3), open: 10, high: 12, low: 9, close: 11 },
      { day: Date.UTC(2026, 8, 1), open: 10, high: 10, low: 8, close: 8 },
      { day: Date.UTC(2026, 8, 2), open: 9, high: 9, low: 9, close: 9 },
      {
        day: Date.UTC(2026, 8, 4),
        open: null,
        high: null,
        low: null,
        close: null,
      },
    ]);
    expect(data).toMatchObject({ type: 'candlestick', omitted: 1 });
    if (data?.type !== 'candlestick') return;
    expect(data.candles.map(candle => candle.direction)).toEqual([
      'fall',
      'flat',
      'rise',
    ]);
    expect(data.candles[2]).toMatchObject({
      open: 10,
      high: 12,
      low: 9,
      close: 11,
    });
  });
});
