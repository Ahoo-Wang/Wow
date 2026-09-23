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
import type { CartesianData, ChartSpec } from '../src/index.js';
import {
  BAR_MAX_WIDTH,
  cartesianOption,
  categoryFit,
  type CartesianContext,
} from '../src/ui/charts/cartesianOption.js';
import type { ChartTheme } from '../src/ui/charts/theme.js';
import { escapeHtml, tooltipHtml } from '../src/ui/charts/tooltip.js';

/**
 * A bar chart's option, read without a DOM. What reaches the screen through
 * it — a tick's text, a label hidden rather than drawn over another, the
 * dashed reference line — is the browser stories' (`分析工作台/回归`); what
 * is decided here is what the drawing is asked for.
 */
const theme: ChartTheme = {
  palette: ['p1', 'p2', 'p3', 'p4', 'p5', 'p6', 'p7', 'p8'],
  foreground: 'fg',
  muted: 'muted',
  border: 'rule',
  ground: 'ground',
  fontFamily: 'Geist',
  key: 'test',
  resolve: color => `resolved(${color})`,
};

const data: CartesianData = {
  type: 'cartesian',
  chart: 'bar',
  points: [
    { x: 'CN', values: { orders: 2, total: 30 } },
    { x: 'JP', values: { orders: 1, total: null } },
  ],
  series: [
    { key: 'orders', label: 'orders', metric: 'orders' },
    { key: 'total', label: 'total', metric: 'total' },
  ],
};

function context(spec: ChartSpec, pickable = false): CartesianContext {
  return {
    spec,
    // Says which alias read a value, and whether it was asked for short.
    label: (alias, value, compact) =>
      `${compact ? 'short ' : ''}${alias}=${String(value)}`,
    column: alias =>
      alias === 'orders'
        ? 'Orders'
        : alias === 'warehouse'
          ? 'Warehouse'
          : undefined,
    locale: 'en',
    animate: false,
    pickable,
  };
}

const bar = (cartesian: Partial<NonNullable<ChartSpec['cartesian']>> = {}) =>
  ({
    type: 'bar',
    cartesian: {
      x: 'warehouse',
      series: [{ metric: 'orders' }, { metric: 'total' }],
      ...cartesian,
    },
  }) as ChartSpec;

// The option is the library's loose shape; the tests read into it freely.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Loose = Record<string, any>;
const optionOf = (spec: ChartSpec, pickable = false) =>
  cartesianOption(data, context(spec, pickable), theme) as Loose;

describe('cartesianOption: a bar chart', () => {
  it('puts the categories on X and the numbers on Y, written short', () => {
    const option = optionOf(bar());

    expect(option.xAxis.type).toBe('category');
    expect(option.xAxis.data).toEqual(['warehouse=CN', 'warehouse=JP']);
    expect(option.xAxis.inverse).toBe(false);
    // Each axis is titled by what it carries: the dimension, and — with two
    // metrics on it — nothing, since the legend names them.
    expect(option.xAxis.name).toBe('Warehouse');
    expect(option.yAxis[0].name).toBeUndefined();
    expect(
      (
        cartesianOption(
          { ...data, series: [data.series[0]] },
          context(bar({ series: [{ metric: 'orders' }] })),
          theme,
        ) as Loose
      ).yAxis[0].name,
    ).toBe('Orders');
    expect(option.yAxis).toHaveLength(1);
    const [left] = option.yAxis;
    expect(left.type).toBe('value');
    // A tick reads as the first series on its axis reads, shortened.
    expect(left.axisLabel.formatter(1200)).toBe('short orders=1200');
    // A count takes no fractional tick.
    expect(left.minInterval).toBe(1);
    // Thin rules, and no axis line or tick marks of its own.
    expect(left.splitLine).toEqual({
      show: true,
      lineStyle: { color: 'rule', width: 1 },
    });
    expect(left.axisLine.show).toBe(false);
  });

  it('takes fractional ticks where a value is not whole', () => {
    const option = cartesianOption(
      {
        ...data,
        points: [{ x: 'CN', values: { orders: 1.5, total: 2 } }],
      },
      context(bar()),
      theme,
    ) as Loose;
    expect(option.yAxis[0].minInterval).toBeUndefined();
  });

  it('lays the chart on its side, the first category on top', () => {
    const option = optionOf(bar({ orientation: 'horizontal' }));

    expect(option.yAxis.type).toBe('category');
    expect(option.yAxis.inverse).toBe(true);
    expect(option.xAxis[0].position).toBe('bottom');
    expect(option.series[0].xAxisIndex).toBe(0);
    expect(option.series[0].itemStyle.borderRadius).toEqual([0, 2, 2, 0]);
  });

  it('names each series by its column, colours it and caps its width', () => {
    const option = optionOf({
      ...bar(),
      colors: { total: '#0f766e' },
    });

    expect(option.series.map((series: Loose) => series.name)).toEqual([
      'Orders',
      // No column holds it, so the kernel's label stands in.
      'total',
    ]);
    expect(
      option.series.map((series: Loose) => series.itemStyle.color),
    ).toEqual(['resolved(var(--chart-1))', 'resolved(#0f766e)']);
    expect(option.series[0].barMaxWidth).toBe(BAR_MAX_WIDTH);
    // A missing value is a gap, not a bar of nothing.
    expect(option.series[1].data).toEqual([30, null]);
  });

  it('points at a bar only where a press does something', () => {
    expect(optionOf(bar()).series[0].cursor).toBe('default');
    expect(optionOf(bar(), true).series[0].cursor).toBe('pointer');
  });

  it('gives a right-hand series an axis of its own, titled, with one set of rules', () => {
    const option = optionOf(
      bar({
        series: [{ metric: 'orders' }, { metric: 'total', axis: 'right' }],
        yAxis: {
          left: { label: 'Count' },
          right: { label: 'Money', min: 0, max: 100, format: 'percent' },
        },
      }),
    );

    const [left, right] = option.yAxis;
    expect([left.position, right.position]).toEqual(['left', 'right']);
    expect([left.name, right.name]).toEqual(['Count', 'Money']);
    expect(right.nameLocation).toBe('middle');
    expect([right.min, right.max]).toEqual([0, 100]);
    expect(right.axisLabel.formatter(0.25)).toBe('25%');
    expect(right.splitLine.show).toBe(false);
    expect(option.series[1].yAxisIndex).toBe(1);
  });

  it('writes the values on the bars, hiding one that would land on another', () => {
    expect(optionOf(bar()).series[0]).not.toHaveProperty('label');

    const option = optionOf({ ...bar(), labels: true });
    const [orders] = option.series;
    expect(orders.label.show).toBe(true);
    expect(orders.label.position).toBe('top');
    expect(orders.labelLayout).toEqual({ hideOverlap: true });
    expect(orders.label.formatter({ value: 2 })).toBe('short orders=2');
    expect(orders.label.formatter({ value: null })).toBe('');
    // A halo of what the chart stands on.
    expect(orders.label.textBorderColor).toBe('ground');

    const sideways = optionOf({
      ...bar({ orientation: 'horizontal' }),
      labels: true,
    });
    expect(sideways.series[0].label.position).toBe('right');
  });

  it('writes a stack’s total over it, and its parts inside', () => {
    const option = optionOf({
      ...bar({
        series: [
          { metric: 'orders', stack: 'a' },
          { metric: 'total', stack: 'a' },
        ],
      }),
      labels: true,
    });

    const [orders, total, sum] = option.series;
    expect([orders.label.position, total.label.position]).toEqual([
      'inside',
      'inside',
    ]);
    expect(sum.stack).toBe('a');
    expect(sum.silent).toBe(true);
    expect(sum.tooltip).toEqual({ show: false });
    expect(sum.data).toEqual([0, 0]);
    expect(sum.label.position).toBe('top');
    expect(sum.label.formatter({ dataIndex: 0 })).toBe('short orders=32');
    // Only the part that has a value adds to the total.
    expect(sum.label.formatter({ dataIndex: 1 })).toBe('short orders=1');
    expect(sum.label.formatter({ dataIndex: 7 })).toBe('');
  });

  it('writes no total over a stack of one, nor without labels', () => {
    const lone = optionOf({
      ...bar({
        series: [{ metric: 'orders', stack: 'a' }, { metric: 'total' }],
      }),
      labels: true,
    });
    expect(lone.series).toHaveLength(2);
    expect(lone.series[0].label.position).toBe('top');

    const quiet = optionOf(
      bar({
        series: [
          { metric: 'orders', stack: 'a' },
          { metric: 'total', stack: 'a' },
        ],
      }),
    );
    expect(quiet.series).toHaveLength(2);
  });

  it('draws a reference line across the numbers, whichever way the chart runs', () => {
    const lines = [
      { axis: 'left' as const, value: 50, label: 'Goal' },
      { axis: 'right' as const, value: 3 },
    ];
    const upright = optionOf(bar({ referenceLines: lines }));
    const carriers = upright.series.slice(2);
    expect(carriers).toHaveLength(2);
    expect(carriers[0].markLine.data).toEqual([{ yAxis: 50 }]);
    expect(carriers[0].yAxisIndex).toBe(0);
    expect(carriers[1].yAxisIndex).toBe(1);
    // No points of its own, so it takes no room beside the bars.
    expect(carriers[0].type).toBe('line');
    expect(carriers[0].data).toEqual([]);
    expect(carriers[0].markLine.label.formatter({ dataIndex: 0 })).toBe('Goal');
    expect(carriers[1].markLine.label.formatter({ dataIndex: 0 })).toBe('');
    // A line past the data stretches the axis to it; a pinned bound wins.
    expect(upright.yAxis[0].max({ max: 2 })).toBe(50);
    expect(upright.yAxis[0].max({ max: 80 })).toBe(80);

    const sideways = optionOf(
      bar({ orientation: 'horizontal', referenceLines: lines.slice(0, 1) }),
    );
    expect(sideways.series[2].markLine.data).toEqual([{ xAxis: 50 }]);
    expect(sideways.series[2].xAxisIndex).toBe(0);
  });

  it('keeps its labels inside its own box', () => {
    expect(optionOf(bar()).grid).toMatchObject({
      outerBoundsMode: 'same',
      outerBoundsContain: 'all',
    });
  });

  it('animates only where motion is welcome', () => {
    expect(optionOf(bar()).animation).toBe(false);
    expect(
      (
        cartesianOption(
          data,
          { ...context(bar()), animate: true },
          theme,
        ) as Loose
      ).animation,
    ).toBe(true);
  });
});

describe('the tooltip of a bar chart', () => {
  it('reads every series at the category, whole, and skips a gap', () => {
    const { tooltip } = optionOf(bar());
    expect(tooltip.trigger).toBe('axis');
    expect(tooltip.confine).toBe(true);

    const html: string = tooltip.formatter([{ dataIndex: 1 }]);
    expect(html).toContain('warehouse=JP');
    expect(html).toContain('orders=1');
    expect(html).not.toContain('short');
    expect(html).not.toContain('total=');
    expect(tooltip.formatter({ dataIndex: 0 })).toContain('total=30');
    expect(tooltip.formatter([{ dataIndex: 9 }])).toBe('');
  });

  it('writes every text from the data as text', () => {
    expect(escapeHtml(`<img src=x onerror="a('b')">&`)).toBe(
      '&lt;img src=x onerror=&quot;a(&#39;b&#39;)&quot;&gt;&amp;',
    );
    const html = tooltipHtml('<b>', [
      { color: 'red', name: '<i>', value: '1 & 2' },
    ]);
    expect(html).not.toContain('<b>');
    expect(html).not.toContain('<i>');
    expect(html).toContain('1 &amp; 2');
    expect(tooltipHtml('', [])).not.toContain('font-medium');
  });
});

describe('categoryFit: the names under the bars', () => {
  const measure = (text: string) => text.length * 7;

  it('stands the names side by side while each fits its band', () => {
    expect(categoryFit(['A', 'B'], 600, measure, false)).toEqual({
      xAxis: { axisLabel: { rotate: 0, interval: 0 } },
    });
  });

  it('slants them once one does not, every one still named', () => {
    const names = Array.from({ length: 16 }, (_, i) => `EventType${i}Created`);
    const fit = categoryFit(names, 900, measure, false) as Loose;
    expect(fit.xAxis.axisLabel).toMatchObject({
      rotate: 45,
      interval: 0,
      overflow: 'truncate',
    });
  });

  it('names every few bars only when a band is narrower than a line', () => {
    const names = Array.from({ length: 200 }, (_, i) => `day ${i}`);
    const fit = categoryFit(names, 600, measure, false) as Loose;
    expect(fit.xAxis.axisLabel).toMatchObject({
      rotate: 45,
      interval: 'auto',
      showMinLabel: true,
      showMaxLabel: true,
    });
  });

  it('cuts a long name on its side at a share of the width', () => {
    expect(categoryFit(['A'], 1000, measure, true)).toEqual({
      yAxis: { axisLabel: { width: 300, overflow: 'truncate' } },
    });
    expect(
      (categoryFit(['A'], 100, measure, true) as Loose).yAxis.axisLabel.width,
    ).toBe(64);
  });
});
