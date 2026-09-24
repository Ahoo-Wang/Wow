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
  LONG_NAME,
  cartesianPlan,
  drawsHorizontal,
  type CartesianContext,
} from '../src/ui/charts/cartesianPlan.js';
import type { ChartTheme } from '../src/ui/charts/theme.js';

/**
 * The 2026-09-23 chart audit, the cartesian half: which value labels a plot
 * writes (all or none, never the library's pick), what a stacked segment
 * says, which way a ranking of long names lies, and how a hovered bar and a
 * label on a bar are coloured. The pixels are measured by the browser
 * stories 「分析工作台/回归」.
 */

// The option is the library's loose shape; the tests read into it freely.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Loose = Record<string, any>;

/** Seven pixels a character, at the page's 12px. */
const measure = (text: string) => text.length * 7;

const LIGHT: ChartTheme = {
  palette: [],
  foreground: 'rgb(10, 10, 10)',
  muted: 'rgb(115, 115, 115)',
  border: 'rgb(229, 229, 229)',
  ground: 'rgb(255, 255, 255)',
  fontFamily: 'sans-serif',
  key: 'light',
  resolve: color =>
    color === 'var(--chart-2)' ? 'rgb(250, 230, 120)' : 'rgb(30, 60, 160)',
};

const context = (
  spec: ChartSpec,
  extra: Partial<CartesianContext> = {},
): CartesianContext => ({
  spec,
  label: (_alias, value, compact) =>
    compact && typeof value === 'number' ? `${value}` : String(value),
  column: alias => alias,
  locale: 'en',
  animate: false,
  pickable: false,
  ...extra,
});

/** `count` categories, `values` for the one series at each. */
const bars = (values: number[], names?: string[]): CartesianData => ({
  type: 'cartesian',
  chart: 'bar',
  points: values.map((value, index) => ({
    x: names?.[index] ?? `c${index}`,
    values: { amount: value },
  })),
  series: [{ key: 'amount', label: 'amount', metric: 'amount' }],
});

const bar = (cartesian: Partial<NonNullable<ChartSpec['cartesian']>> = {}) =>
  ({
    type: 'bar',
    cartesian: { x: 'cat', series: [{ metric: 'amount' }], ...cartesian },
  }) as ChartSpec;

const fit = (
  data: CartesianData,
  spec: ChartSpec,
  width: number,
  height = 400,
) => cartesianFit(cartesianPlan(data, context(spec)), width, height, measure);

describe('cartesianFit: the value labels over the bars', () => {
  const wide = bars([1234567, 2345678, 3456789, 4567890]);

  it('writes every label flat where the widest fits its bar’s room', () => {
    const patch = fit(wide, bar(), 1200) as Loose;
    expect(patch.series[0].label).toMatchObject({ show: true, rotate: 0 });
    expect(patch.grid.top).toBe(24);
  });

  it('turns every label to run up from the bar where flat ones do not fit', () => {
    const patch = fit(wide, bar(), 260) as Loose;
    expect(patch.series[0].label).toMatchObject({
      show: true,
      rotate: 90,
      align: 'left',
    });
    // The room over the tallest bar is the widest label's length.
    expect(patch.grid.top).toBeGreaterThan(7 * 7);
  });

  it('writes none at all where not even a turned line fits (audit)', () => {
    // A hundred bars on a narrow plot: the library kept every other number,
    // and a bar without one read as a bar without a value.
    const crowded = bars(Array.from({ length: 100 }, (_, i) => 1000 + i));
    const patch = fit(crowded, bar(), 600) as Loose;
    expect(patch.series[0].label).toEqual({ show: false });
    expect(patch.grid.top).toBe(16);
  });

  it('leaves no label to the library’s overlap rule', () => {
    const option = cartesianOption(wide, context(bar()), LIGHT) as Loose;
    expect(option.series[0]).not.toHaveProperty('labelLayout');
  });

  it('writes a line’s numbers where the steps hold them, and moves two apart', () => {
    const lines: CartesianData = { ...wide, chart: 'line' };
    const spec = { ...bar(), type: 'line', labels: true } as ChartSpec;
    expect((fit(lines, spec, 1200) as Loose).series[0].label.show).toBe(true);
    expect((fit(lines, spec, 200) as Loose).series[0].label.show).toBe(false);
    const option = cartesianOption(lines, context(spec), LIGHT) as Loose;
    expect(option.series[0].labelLayout).toEqual({ moveOverlap: 'shiftY' });
  });

  it('keeps the value side’s room only while labels are written', () => {
    const sideways = bar({ orientation: 'horizontal' });
    expect((fit(wide, sideways, 900) as Loose).grid.right).toBeGreaterThan(16);
    // Rows thinner than a line of text write nothing, and keep no room.
    const tall = bars(Array.from({ length: 80 }, (_, i) => i));
    const cramped = fit(tall, sideways, 900, 300) as Loose;
    expect(cramped.series[0].label).toEqual({ show: false });
    expect(cramped.grid.right).toBe(16);
  });
});

describe('a stack’s segments (audit)', () => {
  const split: CartesianData = {
    type: 'cartesian',
    chart: 'bar',
    points: [
      { x: 'east', values: { a: 1280, b: 640, c: 0 } },
      { x: 'north', values: { a: 2450, b: 0, c: 0 } },
      { x: 'south', values: { a: 1760, b: 10, c: 3120 } },
    ],
    series: ['a', 'b', 'c'].map(key => ({
      key,
      label: key,
      metric: 'amount',
      value: key,
    })),
  };
  const stacked = {
    ...bar({ splitBy: 'st', series: [{ metric: 'amount', stack: 's' }] }),
    labels: true,
  } as ChartSpec;
  const option = cartesianOption(split, context(stacked), LIGHT) as Loose;
  const [a, b] = option.series;

  it('says nothing for a part of nothing', () => {
    expect(b.label.formatter({ dataIndex: 1 })).toBe('');
    expect(option.series[2].label.formatter({ dataIndex: 0 })).toBe('');
  });

  it('writes a stack of one part once, as its total', () => {
    // 华北 was only 待出库: the segment and the total over it said ¥2,450
    // twice.
    expect(a.label.formatter({ dataIndex: 1 })).toBe('');
    const total = option.series[3];
    expect(total.label.formatter({ dataIndex: 1 })).toBe('2450');
    expect(a.label.formatter({ dataIndex: 0 })).toBe('1280');
  });

  it('writes a part in the ink that stands off its segment, with no halo', () => {
    // A deep blue segment takes the ground's ink; a pale yellow one the
    // foreground's.
    expect(a.label.color).toBe(LIGHT.ground);
    expect(b.label.color).toBe(LIGHT.foreground);
    expect(a.label).not.toHaveProperty('textBorderWidth');
  });

  it('writes a part only where its segment holds it', () => {
    const patch = cartesianFit(
      cartesianPlan(split, context(stacked)),
      900,
      400,
      measure,
    ) as Loose;
    const said = (series: number, index: number) =>
      patch.series[series].label.formatter({ dataIndex: index });
    // ¥10 over a stack of ¥4,890 is a sliver: its number is the tooltip's.
    expect(said(1, 2)).toBe('');
    expect(said(0, 0)).toBe('1280');
    expect(said(2, 2)).toBe('3120');
  });
});

describe('the bar under the pointer (audit)', () => {
  it('steps toward the ink, and keeps its label’s colour', () => {
    const option = cartesianOption(
      bars([3, 4]),
      context(bar()),
      LIGHT,
    ) as Loose;
    const [series] = option.series;
    expect(series.emphasis.itemStyle.color).not.toBe(series.itemStyle.color);
    // Darker than the bar on a light page: more, never less.
    const sum = (rgb: string) =>
      (rgb.match(/\d+/g) ?? []).reduce((total, n) => total + Number(n), 0);
    expect(sum(series.emphasis.itemStyle.color)).toBeLessThan(
      sum(series.itemStyle.color),
    );
    expect(series.emphasis.label.color).toBe(series.label.color);
  });
});

describe('drawsHorizontal: which way a bar chart lies (audit)', () => {
  const long = ['OrderItemReservedTrackEventProcessor', 'Short'];

  it('lays a bar chart of long names on its side unless the analyst said', () => {
    expect(measure('OrderItemReservedTrackEventProcessor')).toBeGreaterThan(
      LONG_NAME,
    );
    expect(drawsHorizontal(bar(), long, false, measure)).toBe(true);
    expect(drawsHorizontal(bar(), ['East', 'West'], false, measure)).toBe(
      false,
    );
    expect(
      drawsHorizontal(bar({ orientation: 'vertical' }), long, false, measure),
    ).toBe(false);
    expect(
      drawsHorizontal(
        bar({ orientation: 'horizontal' }),
        ['East'],
        false,
        measure,
      ),
    ).toBe(true);
  });

  it('keeps a time axis, a line and a combo upright', () => {
    expect(drawsHorizontal(bar(), long, true, measure)).toBe(false);
    expect(
      drawsHorizontal({ ...bar(), type: 'line' }, long, false, measure),
    ).toBe(false);
    expect(
      drawsHorizontal({ ...bar(), type: 'combo' }, long, false, measure),
    ).toBe(false);
  });

  it('draws the chart the way the rule says', () => {
    const option = cartesianOption(
      bars([3, 4], long),
      context(bar(), { label: (_alias, value) => String(value) }),
      LIGHT,
    ) as Loose;
    // The browser's canvas and jsdom's estimate both read this as long.
    expect(option.yAxis.type).toBe('category');
    expect(option.yAxis.inverse).toBe(true);
  });
});
