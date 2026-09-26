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
import { cartesianFit } from '../src/ui/charts/cartesianFit.js';
import { cartesianOption } from '../src/ui/charts/cartesianOption.js';
import {
  cartesianPlan,
  type CartesianContext,
} from '../src/ui/charts/cartesianPlan.js';
import { CHART_FALLBACK, type ChartTheme } from '../src/ui/charts/theme.js';

/**
 * The 2026-09-26 Storybook review, P1-5 and P1-6: what a cartesian chart
 * writes over its marks keeps off the rest — a reference line's or a band's
 * name stands beside the plot, set apart from the next one, and a peak's or
 * a trough's word stands where no bar and no category name is — and a long
 * row of bars writes its peak and trough rather than a number over each.
 * The pixels are measured by the browser stories.
 */

// The option is the library's loose shape; the tests read into it freely.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Loose = Record<string, any>;

/** Seven pixels a character, at the page's 12px. */
const measure = (text: string) => text.length * 7;

const THEME: ChartTheme = {
  ...CHART_FALLBACK,
  palette: [],
  foreground: 'rgb(10, 10, 10)',
  muted: 'rgb(115, 115, 115)',
  axis: { color: 'rgb(115, 115, 115)' },
  grid: { ...CHART_FALLBACK.grid, color: 'rgb(229, 229, 229)' },
  ground: 'rgb(255, 255, 255)',
  text: { ...CHART_FALLBACK.text, family: 'sans-serif' },
  key: 'light',
  resolve: () => 'rgb(30, 60, 160)',
};

const context = (spec: ChartSpec): CartesianContext => ({
  spec,
  label: (_alias, value) => String(value),
  column: alias => alias,
  locale: 'en',
  animate: false,
  pickable: false,
  words: {
    derived: line => line.kind,
    statistic: (of, value) => `${of} ${value}`,
    high: 'high',
    low: 'low',
    other: 'other',
  },
});

/**
 * One series over `values.length` categories, its extremes found as the
 * kernel finds them (the first of equals).
 */
function series(
  values: number[],
  chart: 'bar' | 'line' = 'bar',
): CartesianData {
  const high = values.indexOf(Math.max(...values));
  const low = values.indexOf(Math.min(...values));
  return {
    type: 'cartesian',
    chart,
    points: values.map((value, index) => ({
      x: `c${index}`,
      values: { amount: value },
    })),
    series: [{ key: 'amount', label: 'amount', metric: 'amount' }],
    ...(high === low ? {} : { extremes: { amount: { high, low } } }),
  };
}

const spec = (
  type: 'bar' | 'line',
  cartesian: Partial<NonNullable<ChartSpec['cartesian']>> = {},
  extra: Partial<ChartSpec> = {},
): ChartSpec => ({
  type,
  cartesian: { x: 'cat', series: [{ metric: 'amount' }], ...cartesian },
  ...extra,
});

const option = (data: CartesianData, chart: ChartSpec) =>
  cartesianOption(data, context(chart), THEME) as Loose;

const fit = (
  data: CartesianData,
  chart: ChartSpec,
  width: number,
  height = 400,
  window?: { start: number; end: number },
) =>
  cartesianFit(
    cartesianPlan(data, context(chart)),
    width,
    height,
    measure,
    window,
  ) as Loose;

/** Thirty days, the fifth the highest and the twentieth the lowest. */
const THIRTY = Array.from({ length: 30 }, (_, index) =>
  index === 4 ? 90 : index === 19 ? 5 : 40 + (index % 7),
);

describe('a long row of bars writes its peak and trough (review P1-6)', () => {
  it('marks the two and writes no number over the other bars', () => {
    const drawn = option(series(THIRTY), spec('bar'));
    const bars = drawn.series[0];
    expect(bars.label.show).toBe(false);
    expect(
      bars.markPoint.data.map((point: Loose) => point.label.formatter),
    ).toEqual(['high 90', 'low 5']);
    // The fit keeps them unwritten at any size.
    expect(fit(series(THIRTY), spec('bar'), 1400).series[0].label).toEqual({
      show: false,
    });
  });

  it('writes every number where the analyst asked for them', () => {
    const chart = spec('bar', {}, { labels: true });
    const bars = option(series(THIRTY), chart).series[0];
    expect(bars.label.show).toBe(true);
    expect(bars.markPoint).toBeUndefined();
    expect(bars.label.formatter({ value: 41, dataIndex: 1 })).toBe('41');
  });

  it('writes a number over each of eleven bars, as before', () => {
    const bars = option(series(THIRTY.slice(0, 11)), spec('bar')).series[0];
    expect(bars.label.show).toBe(true);
    expect(bars.markPoint).toBeUndefined();
  });

  it('keeps a number on each row of bars lying on their side', () => {
    const chart = spec('bar', { orientation: 'horizontal' });
    const bars = option(series(THIRTY), chart).series[0];
    expect(bars.label.show).toBe(true);
    expect(bars.markPoint).toBeUndefined();
  });

  it('writes every number again once a zoom leaves fewer than twelve on screen', () => {
    const year = Array.from({ length: 70 }, (_, index) => 10 + (index % 9));
    const week = fit(series(year), spec('bar'), 1400, 400, {
      start: 0,
      end: 10,
    });
    expect(week.series[0].label).toMatchObject({ show: true });
    const whole = fit(series(year), spec('bar'), 1400, 400);
    expect(whole.series[0].label).toEqual({ show: false });
  });

  it('writes every number of a flat row, which has no peak to mark', () => {
    const bars = option(series(Array(20).fill(7)), spec('bar')).series[0];
    expect(bars.label.show).toBe(true);
    expect(bars.markPoint).toBeUndefined();
  });

  it('keeps a line of text over the highest bar for its word', () => {
    expect(fit(series(THIRTY), spec('bar'), 1400).grid.top).toBe(24);
  });
});

describe('the extremes’ words keep off the bars and the names (review P1-5)', () => {
  it('writes both over a bar’s end, never down its body', () => {
    const drawn = option(series([4, 9, 1, 6]), spec('bar', { extremes: true }));
    const [high, low] = drawn.series[0].markPoint.data;
    expect(high.label.position).toBe('top');
    // Under the lowest bar's end, 「最低 ¥1,940」 ran down it and over its
    // neighbours.
    expect(low.label.position).toBe('top');
  });

  it('writes a line’s lowest point under it, but over it at the plot’s floor', () => {
    const middle = option(
      series([40, 90, 30, 60], 'line'),
      spec('line', { extremes: true }),
    ).series[0].markPoint.data[1];
    expect(middle.label.position).toBe('bottom');
    // 「最低 0%」 under a point at 0 fell on the first week's name.
    const floor = option(
      series([0, 90, 30, 60], 'line'),
      spec('line', { extremes: true }),
    ).series[0].markPoint.data[1];
    expect(floor.label.position).toBe('top');
  });
});

describe('reference names beside the plot (review P1-5)', () => {
  const lines = spec('bar', {
    referenceLines: [
      { axis: 'left', value: 42, label: 'average' },
      { axis: 'left', value: 40, label: 'median' },
    ],
    referenceBands: [{ axis: 'left', from: 60, to: 80, label: 'target' }],
  });
  const data = series([30, 45, 70, 20, 50]);
  const carrierOf = (patch: Loose) =>
    patch.series.find((entry: Loose) => entry?.markLine || entry?.markArea);

  it('names each line past its end and a band beside its middle, in a margin kept for them', () => {
    const patch = fit(data, lines, 1200);
    const carrier = carrierOf(patch);
    expect(carrier.markLine.label.position).toBe('end');
    expect(carrier.markArea.label.position).toBe('right');
    // The widest name, 「average」, and its distance.
    expect(patch.grid.right).toBeGreaterThanOrEqual(7 * 7 + 6);
    // Whole items, the value each stands at included: a resize replaces
    // the list.
    expect(carrier.markLine.data.map((item: Loose) => item.yAxis)).toEqual([
      42, 40,
    ]);
    expect(carrier.markArea.data[0][0]).toMatchObject({
      yAxis: 60,
      name: 'target',
    });
  });

  it('sets two names a line apart where their lines are nearer than that', () => {
    const carrier = carrierOf(fit(data, lines, 1200));
    const [average, median] = carrier.markLine.data.map(
      (item: Loose) => item.label.offset[1] as number,
    );
    // 42 over 40: the lower name moves down, the upper one stays.
    expect(average).toBe(0);
    expect(median).toBeGreaterThan(0);
    // The band, far above, is not moved.
    expect(carrier.markArea.data[0][0].label.offset).toEqual([0, 0]);
  });

  it('keeps the names inside a plot too narrow for the margin', () => {
    const patch = fit(data, lines, 200);
    const carrier = carrierOf(patch);
    expect(carrier.markLine.label.position).toBe('insideEndTop');
    expect(carrier.markArea.label.position).toBe('insideTopLeft');
    expect(patch.grid.right).toBe(16);
    expect(
      carrier.markLine.data.map((item: Loose) => item.label.offset),
    ).toEqual([
      [0, 0],
      [0, 0],
    ]);
  });

  it('keeps them inside where a right axis stands in the margin', () => {
    const twoAxes = spec('bar', {
      series: [{ metric: 'amount' }, { metric: 'amount', axis: 'right' }],
      referenceLines: [{ axis: 'left', value: 42, label: 'average' }],
    });
    const carrier = carrierOf(fit(data, twoAxes, 1200));
    expect(carrier.markLine.label.position).toBe('insideEndTop');
  });

  it('names nothing beside a plot whose lines say nothing', () => {
    const quiet = spec('bar', {
      referenceLines: [{ axis: 'left', value: 42 }],
    });
    expect(fit(data, quiet, 1200).grid.right).toBe(16);
  });
});
