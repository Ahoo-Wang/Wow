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
  DOTS_UP_TO,
  cartesianOption,
  type CartesianContext,
} from '../src/ui/charts/cartesianOption.js';
import { TITLE_GAP_UNDER, categoryFit } from '../src/ui/charts/cartesianFit.js';
import { measureText } from '../src/ui/charts/measure.js';
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
    // The chart owns its scale (`sharedScales`): a count and a total of
    // whole numbers step in whole numbers, from zero past the highest bar.
    expect(left.min).toBe(0);
    expect(left.max).toBeGreaterThanOrEqual(30);
    expect(Number.isInteger(left.interval)).toBe(true);
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
    // Its step is a nice one of its own, not held to whole numbers.
    expect(option.yAxis[0].max).toBe(2);
    expect(option.yAxis[0].interval).toBe(0.5);
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

  it('writes the values on the bars, and leaves which fit to the plot’s size', () => {
    // Written unless the analyst turned them off, as Metabase writes them
    // where they fit.
    expect(optionOf({ ...bar(), labels: false }).series[0]).not.toHaveProperty(
      'label',
    );

    const option = optionOf(bar());
    const [orders] = option.series;
    expect(orders.label.show).toBe(true);
    expect(orders.label.position).toBe('top');
    // No label is dropped for landing on another: whether they fit is the
    // plot's size's to say (`cartesianFit`), all of them or none.
    expect(orders).not.toHaveProperty('labelLayout');
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
    expect(sum.stack).toBe('left:a');
    expect(sum.silent).toBe(true);
    expect(sum.tooltip).toEqual({ show: false });
    expect(sum.data).toEqual([0, 0]);
    expect(sum.label.position).toBe('top');
    expect(sum.label.formatter({ dataIndex: 0 })).toBe('short orders=32');
    // Only the part that has a value adds to the total.
    expect(sum.label.formatter({ dataIndex: 1 })).toBe('short orders=1');
    expect(sum.label.formatter({ dataIndex: 7 })).toBe('');
  });

  it('stacks each axis on its own: a count is not piled on an amount', () => {
    const option = optionOf({
      ...bar({
        series: [
          { metric: 'orders', stack: 'a', axis: 'right' },
          { metric: 'total', stack: 'a' },
        ],
      }),
      labels: true,
    });
    expect(option.series.map((series: Loose) => series.stack)).toEqual([
      'right:a',
      'left:a',
    ]);
    // Two stacks of one: no totals, each label over its own bar.
    expect(option.series).toHaveLength(2);
    expect(option.series[0].label.position).toBe('top');
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

    const quiet = optionOf({
      ...bar({
        series: [
          { metric: 'orders', stack: 'a' },
          { metric: 'total', stack: 'a' },
        ],
      }),
      labels: false,
    });
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
    // A line past the marks stretches the axis to it; one within them does
    // not move the scale the marks set.
    expect(upright.yAxis[0].max).toBeGreaterThanOrEqual(50);
    expect(upright.yAxis[1].max).toBeGreaterThanOrEqual(3);
    expect(upright.yAxis[0].min).toBe(0);
    const within = optionOf(
      bar({ referenceLines: [{ axis: 'left', value: 1 }] }),
    );
    expect(within.yAxis[0].max).toBe(optionOf(bar()).yAxis[0].max);
    const below = optionOf(
      bar({ referenceLines: [{ axis: 'left', value: -4 }] }),
    );
    expect(below.yAxis[0].min).toBeLessThanOrEqual(-4);
    // Stacked, the marks reach the sum of their parts.
    const stacked = optionOf(
      bar({
        series: [
          { metric: 'orders', stack: 'a' },
          { metric: 'total', stack: 'a' },
        ],
        referenceLines: [{ axis: 'left', value: 31 }],
      }),
    );
    expect(stacked.yAxis[0].max).toBeGreaterThanOrEqual(32);
    // A pinned bound wins over the line, and hands the scale to the library:
    // the other axis lines up by its `alignTicks`.
    const pinned = optionOf(
      bar({ referenceLines: lines, yAxis: { left: { max: 10 } } }),
    );
    expect(pinned.yAxis[0].interval).toBeUndefined();
    expect(pinned.yAxis[1].alignTicks).toBe(true);
    expect(pinned.yAxis[1].max).toBe(3);
    expect(
      optionOf(
        bar({
          referenceLines: lines,
          yAxis: { left: { max: 10 } },
        }),
      ).yAxis[0].max,
    ).toBe(10);

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

  it('keeps room on the value side for the widest label (audit P0-5)', () => {
    // On its side the labels run past the bars' ends to the right: the
    // longest bar's label used to cross the frame, 「59.6万」 read 「59.6」.
    const sideways = optionOf(bar({ orientation: 'horizontal' }));
    const widest = measureText('short total=30', 'Geist');
    expect(sideways.grid.right).toBeGreaterThanOrEqual(widest + 5);
    // Upright, a line of text over the tallest bar.
    const upright = optionOf(bar());
    expect(upright.grid.right).toBe(16);
    expect(upright.grid.top).toBeGreaterThanOrEqual(24);
    // Nothing written, nothing to keep room for.
    const quiet = optionOf({
      ...bar({ orientation: 'horizontal' }),
      labels: false,
    });
    expect(quiet.grid.right).toBe(16);
    expect(quiet.grid.top).toBe(16);
    // A stack writes its parts inside and its total past the end: the room
    // is the total's.
    const stacked = optionOf(
      bar({
        orientation: 'horizontal',
        series: [
          { metric: 'orders', stack: 'a' },
          { metric: 'total', stack: 'a' },
        ],
      }),
    );
    expect(stacked.grid.right).toBe(
      Math.ceil(measureText('short orders=32', 'Geist')) + 9,
    );
  });

  it('sets the title under the plot a clear line below the ticks (audit P1-2)', () => {
    expect(TITLE_GAP_UNDER).toBeGreaterThanOrEqual(36);
    expect(optionOf(bar()).xAxis.nameGap).toBe(TITLE_GAP_UNDER);
    const sideways = optionOf(bar({ orientation: 'horizontal' }));
    expect(sideways.xAxis[0].nameGap).toBe(TITLE_GAP_UNDER);
    // Beside the plot the gap is to the ticks' side, not under them.
    expect(sideways.yAxis.nameGap).toBe(16);
  });

  it('writes a time axis’s ticks short and names the whole bucket in the tooltip', () => {
    const option = cartesianOption(
      data,
      { ...context(bar()), ticks: ['2026年9月1日', undefined] },
      theme,
    ) as Loose;
    expect(option.xAxis.axisLabel.formatter('warehouse=CN', 0)).toBe(
      '2026年9月1日',
    );
    // A point with no short tick reads as its column does.
    expect(option.xAxis.axisLabel.formatter('warehouse=JP', 1)).toBe(
      'warehouse=JP',
    );
    expect(option.tooltip.formatter([{ dataIndex: 0 }])).toContain(
      'warehouse=CN',
    );
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
    // The title under them clears the slant's depth — a name cut at 120px,
    // slanted, plus a line of air — rather than sitting 4px under its end.
    expect(fit.xAxis.nameGap).toBe(Math.ceil(8 + 136 * Math.SQRT1_2 + 14));
    expect(fit.xAxis.nameGap).toBeGreaterThan(TITLE_GAP_UNDER);
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

  it('writes a time axis flat, every few, rather than slanted', () => {
    const days = Array.from({ length: 30 }, (_, i) => `9月${i + 1}日`);
    const fit = categoryFit(days, 600, measure, false, true) as Loose;
    expect(fit.xAxis.axisLabel).toEqual({
      rotate: 0,
      interval: 'auto',
      showMinLabel: true,
    });
    // Room for every one: every one, flat.
    expect(categoryFit(days.slice(0, 3), 600, measure, false, true)).toEqual({
      xAxis: { axisLabel: { rotate: 0, interval: 0 } },
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

describe('cartesianOption: lines, areas and a combo', () => {
  const as = (chart: CartesianData['chart'], spec: ChartSpec) =>
    cartesianOption({ ...data, chart }, context(spec), theme) as Loose;

  it('draws a line with a dot on each point, a gap where one is missing', () => {
    const option = as('line', {
      ...bar({
        series: [{ metric: 'orders', smooth: true }, { metric: 'total' }],
      }),
      type: 'line',
    });
    const [orders, total] = option.series;
    expect(orders.type).toBe('line');
    expect(orders.smooth).toBe(true);
    expect(total.smooth).toBe(false);
    expect(orders.showSymbol).toBe(true);
    expect(orders.connectNulls).toBe(false);
    expect(orders).not.toHaveProperty('areaStyle');
    expect(orders.lineStyle.color).toBe('resolved(var(--chart-1))');
    // A rule through the points rather than a band behind them.
    expect(option.tooltip.axisPointer.type).toBe('line');
  });

  it('leaves the dots off once there are too many to tell apart', () => {
    const many: CartesianData = {
      ...data,
      chart: 'line',
      points: Array.from({ length: DOTS_UP_TO + 1 }, (_, x) => ({
        x,
        values: { orders: x, total: x },
      })),
    };
    const option = cartesianOption(
      many,
      context({ ...bar(), type: 'line' }),
      theme,
    ) as Loose;
    expect(option.series[0].showSymbol).toBe(false);
  });

  it('fills an area under its line, stacked when the spec says so', () => {
    const option = as('area', {
      ...bar({
        series: [
          { metric: 'orders', stack: 'a' },
          { metric: 'total', stack: 'a' },
        ],
      }),
      type: 'area',
      labels: true,
    });
    const [orders] = option.series;
    expect(orders.areaStyle).toEqual({
      color: 'resolved(var(--chart-1))',
      opacity: 0.2,
    });
    expect(orders.stack).toBe('left:a');
    // Its labels sit over the points; a stack's total is a bar's affair.
    expect(orders.label.position).toBe('top');
    expect(option.series).toHaveLength(2);
  });

  it('never stacks a line: it draws its own values (audit P0-2)', () => {
    const stackedSpec = (type: ChartSpec['type']) => ({
      ...bar({
        series: [
          { metric: 'orders', stack: 'a' },
          { metric: 'total', stack: 'a' },
        ],
      }),
      type,
    });
    const line = as('line', stackedSpec('line'));
    expect(line.series.map((series: Loose) => series.stack)).toEqual([
      undefined,
      undefined,
    ]);
    // The axis reaches the highest value, not the sum of the two.
    expect(line.yAxis[0].max).toBeLessThan(32);
    const lined = as('line', {
      ...stackedSpec('line'),
      cartesian: {
        ...stackedSpec('line').cartesian!,
        referenceLines: [{ axis: 'left', value: 31 }],
      },
    });
    // 31 is past every point (30 at most) though within their sum (32).
    expect(lined.yAxis[0].max).toBeGreaterThanOrEqual(31);

    // In a stacked combo the bars stack and the line stands on its own.
    const combo = as('combo', {
      ...bar({
        series: [
          { metric: 'orders', type: 'bar', stack: 'a' },
          { metric: 'total', type: 'line', stack: 'a' },
        ],
      }),
      type: 'combo',
    });
    expect(combo.series.map((series: Loose) => series.stack)).toEqual([
      'left:a',
      undefined,
    ]);
    // A stack of one bar: its label over it, no total.
    expect(combo.series).toHaveLength(2);
    expect(combo.series[0].label.position).toBe('top');
  });

  it('writes the values on bars unasked, on lines and areas only when asked (audit P1-3)', () => {
    expect(as('line', { ...bar(), type: 'line' }).series[0]).not.toHaveProperty(
      'label',
    );
    expect(as('area', { ...bar(), type: 'area' }).series[0]).not.toHaveProperty(
      'label',
    );
    expect(
      as('line', { ...bar(), type: 'line', labels: true }).series[0].label.show,
    ).toBe(true);
    const combo = as('combo', {
      ...bar({
        series: [
          { metric: 'orders', type: 'bar' },
          { metric: 'total', type: 'line' },
        ],
      }),
      type: 'combo',
    });
    expect(combo.series[0].label.show).toBe(true);
    expect(combo.series[1]).not.toHaveProperty('label');
  });

  it('takes each combo series’ mark from the spec, bars where it names none', () => {
    const option = as('combo', {
      ...bar({
        series: [{ metric: 'orders', type: 'line' }, { metric: 'total' }],
      }),
      type: 'combo',
    });
    expect(option.series.map((series: Loose) => series.type)).toEqual([
      'line',
      'bar',
    ]);
    expect(option.tooltip.axisPointer.type).toBe('shadow');
  });
});
