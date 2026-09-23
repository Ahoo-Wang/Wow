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

import { describe, expect, it } from 'vitest';
import {
  comboAxis,
  fitChartSlots,
  measureColumns,
  metricMeasure,
  metricMeasures,
  switchChartType,
  type AnalysisColumnView,
  type AnalysisGroup,
  type AnalysisMetric,
  type CartesianData,
  type ChartSpec,
  type FieldDefinition,
} from '../src/index.js';
import {
  cartesianOption,
  type CartesianContext,
} from '../src/ui/charts/cartesianOption.js';
import type { ChartTheme } from '../src/ui/charts/theme.js';

/**
 * A combo's right axis (todo 「组合图的右轴」, audit P2-4): the slot layer
 * sees no rows, so a combo's second metric sat on the left axis whatever
 * it measured, and a count beside money lay flat along zero. The axis is
 * now decided by what the metrics measure, and the two axes rule one set of
 * gridlines.
 */

const WAREHOUSE: AnalysisGroup = {
  type: 'TERMS',
  field: 'warehouse',
  alias: 'wh',
};
const COUNT: AnalysisMetric = { type: 'COUNT', alias: 'orders' };
const numeric = (
  alias: string,
  fn: 'SUM' | 'AVG' | 'MAX' | 'VARIANCE',
  field: string,
): AnalysisMetric => ({
  type: 'NUMERIC',
  alias,
  function: fn,
  expression: { type: 'FIELD', field },
});
const TOTAL = numeric('total', 'SUM', 'amount');
const AVERAGE = numeric('average', 'AVG', 'amount');
const REFUNDS = numeric('refunds', 'SUM', 'refund');
const DISCOUNT = numeric('discount', 'AVG', 'rate');

const FIELDS = new Map<string, Pick<FieldDefinition, 'numberFormat'>>([
  ['amount', { numberFormat: { style: 'currency', currency: 'cny' } }],
  ['refund', { numberFormat: { style: 'currency', currency: 'CNY' } }],
  ['rate', { numberFormat: { style: 'percent' } }],
  ['weight', { numberFormat: { style: 'unit', unit: 'kilogram' } }],
  ['items', {}],
]);

describe('metricMeasure: what a metric is a quantity of', () => {
  it('reads the function and the declared unit, never the values', () => {
    expect(metricMeasure('COUNT')).toBe('count');
    expect(metricMeasure('DISTINCT_COUNT')).toBe('count');
    expect(metricMeasure('DERIVED')).toBe('derived');
    expect(metricMeasure('SUM', { style: 'currency', currency: 'cny' })).toBe(
      'total:currency:CNY',
    );
    // An average of money is still money, on the scale of one value.
    expect(metricMeasure('AVG', { style: 'currency', currency: 'CNY' })).toBe(
      'value:currency:CNY',
    );
    expect(metricMeasure('MAX', { style: 'percent' })).toBe('value:percent');
    expect(metricMeasure('VARIANCE', undefined)).toBe('square:number');
    expect(metricMeasure('SUM', { style: 'unit', unit: 'kilogram' })).toBe(
      'total:unit:kilogram',
    );
    expect(metricMeasure('SUM', {})).toBe('total:number');
    expect(metricMeasure(undefined)).toBe('value:number');
  });

  it('reads a metric off its field, and a column off its format', () => {
    const measures = metricMeasures(
      [COUNT, TOTAL, AVERAGE, REFUNDS, DISCOUNT, numeric('w', 'SUM', 'items')],
      FIELDS,
    );
    expect(Object.fromEntries(measures)).toEqual({
      orders: 'count',
      total: 'total:currency:CNY',
      average: 'value:currency:CNY',
      refunds: 'total:currency:CNY',
      discount: 'value:percent',
      w: 'total:number',
    });
    const column = (
      alias: string,
      fn: AnalysisColumnView['fn'],
      numberFormat?: AnalysisColumnView['numberFormat'],
    ): AnalysisColumnView => ({
      alias,
      label: alias,
      role: 'metric',
      fn,
      numberFormat,
    });
    expect(
      Object.fromEntries(
        measureColumns([
          { alias: 'wh', label: 'Warehouse', role: 'group' },
          column('orders', 'COUNT', { maximumFractionDigits: 0 }),
          column('total', 'SUM', { style: 'currency', currency: 'CNY' }),
        ]),
      ),
    ).toEqual({ orders: 'count', total: 'total:currency:CNY' });
  });
});

describe('comboAxis', () => {
  const measures = metricMeasures(
    [COUNT, TOTAL, AVERAGE, REFUNDS, DISCOUNT],
    FIELDS,
  );

  it('puts on the other axis what measures something else than the first', () => {
    // A count and an amount; an amount and an average of it; a percent and
    // a count.
    expect(comboAxis(measures, 'orders', 'total')).toBe('right');
    expect(comboAxis(measures, 'total', 'average')).toBe('right');
    expect(comboAxis(measures, 'orders', 'discount')).toBe('right');
    // Two sums of money share one scale, however far apart they are.
    expect(comboAxis(measures, 'total', 'refunds')).toBe('left');
    expect(comboAxis(measures, 'total', 'total')).toBe('left');
  });

  it('follows the first series to whichever side it stands on', () => {
    expect(comboAxis(measures, 'total', 'refunds', 'right')).toBe('right');
    expect(comboAxis(measures, 'total', 'orders', 'right')).toBe('left');
    // Nothing to compare with, or nothing known: the lead's side.
    expect(comboAxis(measures, undefined, 'orders')).toBe('left');
    expect(comboAxis(new Map(), 'orders', 'total')).toBe('left');
  });
});

describe('fitChartSlots: a combo arriving', () => {
  const metrics = [COUNT, TOTAL, REFUNDS];
  const measures = metricMeasures(metrics, FIELDS);
  const combo = (chart: ChartSpec) =>
    fitChartSlots(
      switchChartType(chart, 'combo'),
      [WAREHOUSE],
      metrics,
      new Set(),
      measures,
    );

  it('puts the amounts on the right of a count, and keeps like with like', () => {
    const counted: ChartSpec = {
      type: 'bar',
      cartesian: { x: 'wh', series: [{ metric: 'orders' }] },
    };
    expect(combo(counted).cartesian?.series).toEqual([
      { metric: 'orders', type: 'bar' },
      { metric: 'total', type: 'line', axis: 'right' },
      { metric: 'refunds', type: 'line', axis: 'right' },
    ]);
    // Led by money, the refunds stay beside it and the count goes right.
    const paid: ChartSpec = {
      type: 'bar',
      cartesian: { x: 'wh', series: [{ metric: 'total' }] },
    };
    expect(combo(paid).cartesian?.series).toEqual([
      { metric: 'total', type: 'bar' },
      { metric: 'orders', type: 'line', axis: 'right' },
      { metric: 'refunds', type: 'line' },
    ]);
  });

  it('keeps an axis the analyst chose, and a combo already drawn', () => {
    const chosen: ChartSpec = {
      type: 'bar',
      cartesian: {
        x: 'wh',
        series: [
          { metric: 'orders' },
          { metric: 'total', axis: 'left' },
          { metric: 'refunds' },
        ],
      },
    };
    expect(combo(chosen).cartesian?.series).toEqual([
      { metric: 'orders', type: 'bar' },
      { metric: 'total', type: 'line', axis: 'left' },
      { metric: 'refunds', type: 'line', axis: 'right' },
    ]);
    // Every series of a drawn combo names its mark: a redraw moves nothing.
    const drawn: ChartSpec = {
      type: 'combo',
      cartesian: {
        x: 'wh',
        series: [
          { metric: 'orders', type: 'bar' },
          { metric: 'total', type: 'line' },
        ],
      },
    };
    expect(
      fitChartSlots(drawn, [WAREHOUSE], metrics, new Set(), measures).cartesian
        ?.series,
    ).toEqual(drawn.cartesian?.series);
  });
});

const theme: ChartTheme = {
  palette: ['p1', 'p2', 'p3', 'p4', 'p5', 'p6', 'p7', 'p8'],
  foreground: 'fg',
  muted: 'muted',
  border: 'rule',
  ground: 'ground',
  fontFamily: 'Geist',
  key: 'test',
  resolve: color => color,
};

function context(spec: ChartSpec): CartesianContext {
  return {
    spec,
    label: (alias, value) => `${alias}=${String(value)}`,
    column: alias =>
      ({ orders: 'Orders', total: 'Sum of Amount', wh: 'Warehouse' })[
        alias ?? ''
      ],
    locale: 'en',
    animate: false,
    pickable: false,
  };
}

// The option is the library's loose shape; the tests read into it freely.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Loose = Record<string, any>;

describe('cartesianOption: two axes', () => {
  const data = (orders: number[], total: number[]): CartesianData => ({
    type: 'cartesian',
    chart: 'combo',
    points: orders.map((count, index) => ({
      x: `W${index}`,
      values: { orders: count, total: total[index] ?? null },
    })),
    series: [
      { key: 'orders', label: 'orders', metric: 'orders' },
      { key: 'total', label: 'total', metric: 'total' },
    ],
  });
  const spec: ChartSpec = {
    type: 'combo',
    cartesian: {
      x: 'wh',
      series: [
        { metric: 'orders', type: 'bar' },
        { metric: 'total', type: 'line', axis: 'right' },
      ],
    },
  };

  it('titles the right axis with its column, and rules the plot once', () => {
    const option = cartesianOption(
      data([2, 1], [1920.5, 2450.25]),
      context(spec),
      theme,
    ) as Loose;
    const [left, right] = option.yAxis;
    expect(left.name).toBe('Orders');
    expect(right.name).toBe('Sum of Amount');
    expect(right.position).toBe('right');
    expect(left.splitLine.show).toBe(true);
    expect(right.splitLine.show).toBe(false);
    // The money follows the count's gridlines, a nice step of its own; the
    // count keeps whole steps (audit P2-4).
    expect(right.alignTicks).toBe(true);
    expect(left.alignTicks).toBeUndefined();
    expect(left.minInterval).toBe(1);
  });

  it('makes the axis that can take a fractional step the one that follows', () => {
    // Money led on the left, the count on the right: the count would write
    // a half step as a second 「1」, so the money follows it instead.
    const moneyFirst: ChartSpec = {
      type: 'combo',
      cartesian: {
        x: 'wh',
        series: [
          { metric: 'total', type: 'bar' },
          { metric: 'orders', type: 'line', axis: 'right' },
        ],
      },
    };
    const [left, right] = (
      cartesianOption(
        data([2, 1], [1920.5, 2450.25]),
        context(moneyFirst),
        theme,
      ) as Loose
    ).yAxis;
    expect(left.alignTicks).toBe(true);
    expect(right.alignTicks).toBeUndefined();
    expect(right.minInterval).toBe(1);
  });

  it('aligns nothing while there is one axis', () => {
    const one: ChartSpec = {
      type: 'bar',
      cartesian: { x: 'wh', series: [{ metric: 'orders' }] },
    };
    const option = cartesianOption(
      { ...data([2, 1], []), series: [data([], []).series[0]] },
      context(one),
      theme,
    ) as Loose;
    expect(option.yAxis).toHaveLength(1);
    expect(option.yAxis[0].alignTicks).toBeUndefined();
  });
});
