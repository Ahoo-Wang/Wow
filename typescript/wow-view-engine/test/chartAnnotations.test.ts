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
  stackPeaksFound,
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
    ongoing: 'ongoing',
    ongoingPeriod: period => period,
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
    // Wide enough that 「high 90」 fits a bar's room flat.
    expect(fit(series(THIRTY), spec('bar'), 2400).grid.top).toBe(24);
  });

  /**
   * 「最高 5」 and 「最低 1」 turned on their sides over a row of thirty bars
   * (second review R2-74): the two are the only words on the row, so they
   * are written flat at any width — the outline in the ground's colour
   * keeps the lower one legible where it crosses a taller neighbour — and
   * one near either end of the row is set off towards the plot.
   */
  it('writes the two words flat however narrow the bars', () => {
    const labels = (width: number) =>
      fit(series(THIRTY), spec('bar'), width).series[0].markPoint.data.map(
        (point: Loose) => point.label,
      );
    // 30 bars in 1400px: some 35px a bar, and 「high 90」 is 45px.
    for (const width of [600, 1400, 2400])
      for (const label of labels(width))
        expect(label).toMatchObject({ rotate: 0, verticalAlign: 'bottom' });
    // Only a line of text over the highest bar, not a turned word's length.
    expect(fit(series(THIRTY), spec('bar'), 1400).grid.top).toBe(24);
  });

  it('writes a word under a bar that goes below zero, flat', () => {
    const below = THIRTY.map((value, index) => (index === 19 ? -30 : value));
    const [, low] = fit(series(below), spec('bar'), 1400).series[0].markPoint
      .data;
    expect(low.label).toMatchObject({
      position: 'bottom',
      rotate: 0,
      verticalAlign: 'top',
    });
  });
});

/**
 * A stack's total over each of thirty stacks was a row of numbers on their
 * sides over every bar (the pre-release review): a long upright row of
 * stacks left to its default writes only its highest and its lowest total,
 * with their words, as a long row of single bars writes its peak and trough.
 */
describe('a long row of stacks writes its highest and lowest total', () => {
  const stacked = (count: number): CartesianData => ({
    type: 'cartesian',
    chart: 'bar',
    points: Array.from({ length: count }, (_, index) => ({
      x: `c${index}`,
      values: {
        a: index === 4 ? 60 : index === 9 ? 1 : 20,
        b: index === 9 ? 1 : 10,
      },
    })),
    series: [
      { key: 'a', label: 'a', metric: 'amount', value: 'a' },
      { key: 'b', label: 'b', metric: 'amount', value: 'b' },
    ],
  });
  const STACKED = {
    series: [{ metric: 'amount', stack: 'all' }],
    splitBy: 'kind',
  };
  const chart = spec('bar', STACKED);
  const totalsOf = (data: CartesianData, chartSpec = chart) =>
    cartesianPlan(data, context(chartSpec)).totals[0];

  it('writes the two, with their words, past twelve stacks', () => {
    const total = totalsOf(stacked(30));
    expect(total.texts.filter(text => text !== '')).toEqual([
      'high 70',
      'low 2',
    ]);
    expect(total.texts[4]).toBe('high 70');
    expect(total.texts[9]).toBe('low 2');
    expect(total.every[0]).toBe('30');
  });

  it('writes every total under twelve stacks, or where the analyst asked', () => {
    expect(totalsOf(stacked(11)).texts.every(text => text !== '')).toBe(true);
    const asked = spec('bar', STACKED, { labels: true });
    expect(totalsOf(stacked(30), asked).texts.every(text => text !== '')).toBe(
      true,
    );
  });

  it('writes every total where none is highest or lowest', () => {
    // Fourteen stacks of one total: no two to tell apart.
    const equal: CartesianData = {
      ...stacked(14),
      points: stacked(14).points.map(point => ({
        ...point,
        values: { a: 20, b: 10 },
      })),
    };
    const flat = totalsOf(equal);
    expect(flat.peaksOnly).toBe(false);
    expect(flat.texts).toEqual(Array(14).fill('30'));
    expect(stackPeaksFound(equal, chart)).toBe(false);
    expect(stackPeaksFound(stacked(30), chart)).toBe(true);
  });

  it('writes the one real stack among window-filled ones, and its part', () => {
    // Thirty days of a window, one with a record: the rest filled with 0.
    const filled: CartesianData = {
      ...stacked(30),
      points: stacked(30).points.map((point, index) =>
        index === 29
          ? { ...point, values: { a: 7, b: null } }
          : { ...point, values: { a: 0, b: 0 }, filled: ['a', 'b'] },
      ),
    };
    const plan = cartesianPlan(filled, context(chart));
    expect(plan.totals[0].peaksOnly).toBe(false);
    expect(plan.totals[0].texts.filter(text => text !== '')).toEqual(['7']);
    expect(stackPeaksFound(filled, chart)).toBe(false);
  });

  it('writes every total on screen again once a zoom leaves fewer than twelve', () => {
    const plan = cartesianPlan(stacked(70), context(chart));
    const at = (window?: { start: number; end: number }) =>
      (cartesianFit(plan, 1400, 400, measure, window) as Loose).series[2].label
        .formatter as (at: { dataIndex: number }) => string;
    expect(at({ start: 0, end: 10 })({ dataIndex: 0 })).toBe('30');
    expect(at()({ dataIndex: 0 })).toBe('');
    expect(at()({ dataIndex: 4 })).toBe('high 70');
  });
});

describe('the extremes’ words where every value is written (R2-P1-8)', () => {
  const every = spec('bar', { extremes: true }, { labels: true });

  it('writes them in the bars’ own labels while those are written, and on the mark where none are', () => {
    const wide = fit(series(THIRTY), every, 1400);
    expect(wide.series[0].label.show).toBe(true);
    expect(wide.series[0].markPoint).toMatchObject({
      symbolSize: 0,
      label: { show: false },
    });
    // Thirty bars in 200px write no number at all: the mark says the two.
    const narrow = fit(series(THIRTY), every, 200);
    expect(narrow.series[0].label).toEqual({ show: false });
    expect(narrow.series[0].markPoint).toMatchObject({
      symbolSize: 8,
      label: { show: true },
    });
  });

  it('counts the words in the room the labels take', () => {
    const flat = fit(series([4, 9, 1, 6]), every, 1400);
    const carried = option(series([4, 9, 1, 6]), every).series[0];
    expect(carried.label.formatter({ value: 9, dataIndex: 1 })).toBe('high 9');
    expect(flat.grid.top).toBe(24);
  });

  it('carries them in a long row zoomed to fewer bars than it marks only the two of', () => {
    const year = Array.from({ length: 70 }, (_, index) =>
      index === 3 ? 90 : index === 5 ? 1 : 10 + (index % 9),
    );
    const week = fit(series(year), spec('bar'), 1400, 400, {
      start: 0,
      end: 10,
    });
    expect(week.series[0].label.formatter({ value: 90, dataIndex: 3 })).toBe(
      'high 90',
    );
    expect(week.series[0].markPoint).toMatchObject({
      symbolSize: 0,
      label: { show: false },
    });
    const whole = fit(series(year), spec('bar'), 1400, 400);
    expect(whole.series[0].markPoint).toMatchObject({
      symbolSize: 8,
      label: { show: true },
    });
  });

  it('carries them on a line whose points are all labelled', () => {
    const line = spec('line', { extremes: true }, { labels: true });
    const drawn = option(series([40, 90, 30, 60], 'line'), line).series[0];
    expect(drawn.label.formatter({ value: 30, dataIndex: 2 })).toBe('low 30');
    const wide = fit(series([40, 90, 30, 60], 'line'), line, 1400);
    expect(wide.series[0].markPoint).toMatchObject({
      symbolSize: 0,
      label: { show: false },
    });
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
