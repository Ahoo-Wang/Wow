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
  fitChartSlots,
  isPercentStacked,
  offersPercentStack,
  validateChart,
  withPercentStack,
  withStacked,
  type AnalysisGroup,
  type AnalysisMetric,
  type AnalysisViewConfig,
  type CartesianData,
  type CartesianSpec,
  type ChartSpec,
} from '../src/index.js';
import { comparePending } from '../src/runtime/pending.js';
import {
  cartesianOption,
  type CartesianContext,
} from '../src/ui/charts/cartesianOption.js';
import { readChart } from '../src/ui/charts/reading.js';
import type { ChartTheme } from '../src/ui/charts/theme.js';
import { defaultMessages, formatMessage } from '../src/ui/messages.js';
import { analysisKernelConfig } from './fixtures/analysis.js';

/**
 * 「百分比堆叠」 (audit P1-10): a composition over several groups that a pie
 * cannot draw, read as each category's stack reaching 100%. A setting of
 * the drawing only: the rows and the query stay what they were.
 */

const WAREHOUSE: AnalysisGroup = {
  type: 'TERMS',
  field: 'warehouse',
  alias: 'wh',
};
const STATUS: AnalysisGroup = { type: 'TERMS', field: 'status', alias: 'st' };
const COUNT: AnalysisMetric = { type: 'COUNT', alias: 'orders' };
const TOTAL: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'total',
  function: 'SUM',
  expression: { type: 'FIELD', field: 'amount' },
};
const AVERAGE: AnalysisMetric = {
  type: 'NUMERIC',
  alias: 'average',
  function: 'AVG',
  expression: { type: 'FIELD', field: 'amount' },
};

const ADDITIVE = new Set(['orders', 'total']);
const spec = (overrides: Partial<CartesianSpec> = {}): CartesianSpec => ({
  x: 'wh',
  series: [{ metric: 'orders' }, { metric: 'total' }],
  ...overrides,
});

describe('where 100% stacking is offered', () => {
  it('on bars and areas of metrics that add up, with something to share', () => {
    expect(offersPercentStack(spec(), 'bar', ADDITIVE)).toBe(true);
    expect(offersPercentStack(spec(), 'area', ADDITIVE)).toBe(true);
    // A split of one metric is several parts too.
    expect(
      offersPercentStack(
        spec({ splitBy: 'st', series: [{ metric: 'orders' }] }),
        'bar',
        ADDITIVE,
      ),
    ).toBe(true);
  });

  it('nowhere a share would mean nothing', () => {
    // One part is 100% of itself everywhere.
    expect(
      offersPercentStack(
        spec({ series: [{ metric: 'orders' }] }),
        'bar',
        ADDITIVE,
      ),
    ).toBe(false);
    // An average is no part of a whole.
    expect(
      offersPercentStack(
        spec({ series: [{ metric: 'orders' }, { metric: 'average' }] }),
        'bar',
        ADDITIVE,
      ),
    ).toBe(false);
    // A line does not stack; a combo's line would stand on a scale of shares.
    expect(offersPercentStack(spec(), 'line', ADDITIVE)).toBe(false);
    expect(offersPercentStack(spec(), 'combo', ADDITIVE)).toBe(false);
    expect(offersPercentStack(spec({ series: [] }), 'bar', ADDITIVE)).toBe(
      false,
    );
  });

  it('stacks the chart when turned on, and unstacking takes it away', () => {
    const on = withPercentStack(spec(), true, 'bar');
    expect(on.percentStack).toBe(true);
    expect(on.series.every(series => series.stack === 'all')).toBe(true);
    expect(isPercentStacked(on, 'bar')).toBe(true);
    // Off leaves the stack standing, drawing its values again.
    const off = withPercentStack(on, false, 'bar');
    expect(off.percentStack).toBeUndefined();
    expect(off.series.every(series => series.stack === 'all')).toBe(true);
    // No stack, no shares: the box under 「堆叠」 goes with it.
    expect(withStacked(on, false, 'bar').percentStack).toBeUndefined();
    // Asked for without a stack, it is not read.
    expect(isPercentStacked({ ...spec(), percentStack: true }, 'bar')).toBe(
      false,
    );
  });
});

describe('a 100% stack in the kernel', () => {
  const chart = (cartesian: CartesianSpec): ChartSpec => ({
    type: 'bar',
    cartesian,
  });

  it('is carried through a refit while its metrics add up, and dropped after', () => {
    const stacked = chart(withPercentStack(spec(), true, 'bar'));
    expect(
      fitChartSlots(stacked, [WAREHOUSE], [COUNT, TOTAL]).cartesian
        ?.percentStack,
    ).toBe(true);
    const averaged = chart({
      ...withPercentStack(spec(), true, 'bar'),
      series: [{ metric: 'orders' }, { metric: 'average' }],
    });
    expect(
      fitChartSlots(averaged, [WAREHOUSE], [COUNT, AVERAGE]).cartesian
        ?.percentStack,
    ).toBeUndefined();
    // The missing-value choice is carried as it is.
    expect(
      fitChartSlots(
        chart(spec({ missing: 'gap' })),
        [WAREHOUSE],
        [COUNT, TOTAL],
      ).cartesian?.missing,
    ).toBe('gap');
  });

  it('is refused over a metric that does not add up', () => {
    const codes = (cartesian: CartesianSpec, metrics: AnalysisMetric[]) =>
      validateChart(
        analysisKernelConfig({
          groups: [WAREHOUSE],
          metrics: metrics as AnalysisViewConfig['metrics'],
          chart: chart(cartesian),
          sort: [],
          table: { columns: [] },
        }),
      ).map(issue => [issue.code, issue.params?.metric]);
    expect(
      codes(
        {
          ...spec({ series: [{ metric: 'orders' }, { metric: 'average' }] }),
          percentStack: true,
        },
        [COUNT, AVERAGE],
      ),
    ).toEqual([['chart.cartesian.percent-not-additive', 'average']]);
    expect(
      codes(withPercentStack(spec(), true, 'bar'), [COUNT, TOTAL]),
    ).toEqual([]);
  });

  it('is presentation: changing it or the missing values asks for no run', () => {
    const base = analysisKernelConfig({
      groups: [WAREHOUSE, STATUS],
      metrics: [COUNT] as AnalysisViewConfig['metrics'],
      chart: chart(spec({ splitBy: 'st', series: [{ metric: 'orders' }] })),
    });
    const drawn = {
      ...base,
      chart: chart({
        ...withPercentStack(base.chart.cartesian!, true, 'bar'),
        missing: 'gap',
      }),
    };
    expect(comparePending(drawn, base, [])).toEqual(
      comparePending(base, base, []),
    );
    expect(comparePending(drawn, base, []).pending).toBe(false);
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

// The option is the library's loose shape; the tests read into it freely.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Loose = Record<string, any>;

describe('cartesianOption: a 100% stack', () => {
  const data: CartesianData = {
    type: 'cartesian',
    chart: 'bar',
    points: [
      { x: 'CN', values: { open: 3, done: 1 } },
      { x: 'US', values: { open: 0, done: 0 } },
      { x: 'JP', values: { open: 2, done: null } },
    ],
    series: [
      { key: 'open', label: 'open', metric: 'orders', value: 'open' },
      { key: 'done', label: 'done', metric: 'orders', value: 'done' },
    ],
  };
  const context = (
    cartesian: CartesianSpec,
    labels?: boolean,
  ): CartesianContext => ({
    spec: {
      type: 'bar',
      cartesian,
      ...(labels === undefined ? {} : { labels }),
    },
    label: (alias, value) => `${alias}=${String(value)}`,
    column: () => undefined,
    locale: 'en',
    animate: false,
    pickable: false,
  });
  const split = spec({ splitBy: 'st', series: [{ metric: 'orders' }] });
  const option = (cartesian: CartesianSpec, labels?: boolean) =>
    cartesianOption(data, context(cartesian, labels), theme) as Loose;

  it('draws each part as its share of its stack, each stack reaching 100%', () => {
    const drawn = option(withPercentStack(split, true, 'bar'));
    const [open, done] = drawn.series;
    expect(open.data).toEqual([0.75, null, 1]);
    expect(done.data).toEqual([0.25, null, null]);
    // A category whose parts are all nothing has no whole to share.
    // The scale runs from nothing to the whole, in percent.
    const axis = drawn.yAxis[0];
    expect([axis.min, axis.max]).toEqual([0, 1]);
    expect(axis.minInterval).toBeUndefined();
    expect(axis.axisLabel.formatter(0.5)).toBe('50%');
  });

  it('writes shares on the parts, and no total over a stack of 100%', () => {
    const drawn = option(withPercentStack(split, true, 'bar'));
    expect(drawn.series[0].label.formatter({ dataIndex: 0 })).toBe('75.0%');
    // A part that is its whole stack still says its share: there is no
    // total over a stack of 100% to say it instead.
    expect(drawn.series[0].label.formatter({ dataIndex: 2 })).toBe('100.0%');
    // Two series and nothing more: no carrier of a total.
    expect(drawn.series).toHaveLength(2);
    // Stacked by value, the total is there.
    expect(option(withStacked(split, true, 'bar')).series).toHaveLength(3);
  });

  it('says both the value and its share in the tooltip', () => {
    const drawn = option(withPercentStack(split, true, 'bar'));
    const html: string = drawn.tooltip.formatter([{ dataIndex: 0 }]);
    expect(html).toContain('orders=3 · 75.0%');
    expect(html).toContain('orders=1 · 25.0%');
  });

  it('reads out each part’s share beside its value (audit)', () => {
    // A screen reader heard the values of stacks the picture drew as equal
    // heights; the table says what the tooltip says.
    const messages = {
      label: (key: string, params?: Record<string, string | number>) =>
        formatMessage(defaultMessages, key as never, params),
      issue: () => '',
      issues: () => '',
    };
    const reading = (cartesian: CartesianSpec) =>
      readChart(data, context(cartesian).spec, {
        messages,
        label: (alias, value) => `${alias}=${String(value)}`,
        column: () => undefined,
        locale: 'en',
      });
    const shared = reading(withPercentStack(split, true, 'bar'));
    expect(shared.rows[0]).toEqual([
      'wh=CN',
      'orders=3 · 75.0%',
      'orders=1 · 25.0%',
    ]);
    // A stack of nothing has no shares to say.
    expect(shared.rows[1]).toEqual(['wh=US', 'orders=0', 'orders=0']);
    // Stacked by value, the values alone.
    expect(reading(withStacked(split, true, 'bar')).rows[0]).toEqual([
      'wh=CN',
      'orders=3',
      'orders=1',
    ]);
  });

  it('draws values where it is not asked, or not stacked, or not bars or areas', () => {
    expect(option(withStacked(split, true, 'bar')).series[0].data).toEqual([
      3, 0, 2,
    ]);
    expect(option({ ...split, percentStack: true }).series[0].data).toEqual([
      3, 0, 2,
    ]);
    const line = cartesianOption(
      { ...data, chart: 'line' },
      {
        ...context(withPercentStack(split, true, 'bar')),
        spec: { type: 'line', cartesian: withPercentStack(split, true, 'bar') },
      },
      theme,
    ) as Loose;
    expect(line.series[0].data).toEqual([3, 0, 2]);
  });
});
