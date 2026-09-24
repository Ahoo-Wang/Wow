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
  withoutHidden,
  type CartesianContext,
} from '../src/ui/charts/cartesianPlan.js';
import {
  LARGE_FROM,
  SLIDER_ROOM,
  ZOOM_FROM,
  visibleCount,
  zoomOption,
  zooms,
} from '../src/ui/charts/cartesianZoom.js';
import { shortDateTicks } from '../src/ui/charts/dateTicks.js';
import { composed, zoomWindow } from '../src/ui/charts/EChart.js';
import type { ChartTheme } from '../src/ui/charts/theme.js';

/**
 * A long axis read clearly (D33 batch A): zoomed by a slider — and by
 * gestures only where the host lets it take them — never saved and gone
 * with the result; a series switched off leaving the scale; the change from
 * the bucket before in the tooltip; thinned lines, one-path bars; ticks
 * that say their year among the ones named. The pixels are the browser
 * stories 「长时间轴/回归」.
 */

// The option is the library's loose shape; the tests read into it freely.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Loose = Record<string, any>;

const LIGHT: ChartTheme = {
  palette: [],
  foreground: 'rgb(10, 10, 10)',
  muted: 'rgb(115, 115, 115)',
  border: 'rgb(229, 229, 229)',
  ground: 'rgb(255, 255, 255)',
  fontFamily: 'sans-serif',
  key: 'light',
  resolve: () => 'rgb(30, 60, 160)',
};

const measure = (text: string) => text.length * 7;

const day = (n: number) => Date.UTC(2026, 0, 1) + n * 86_400_000;

/** `count` days, one or two series, `chart` the marks. */
function days(
  count: number,
  chart: 'bar' | 'line' = 'line',
  split = false,
): CartesianData {
  return {
    type: 'cartesian',
    chart,
    timeline: true,
    points: Array.from({ length: count }, (_, index) => ({
      x: day(index),
      values: (split
        ? { east: 100 + index, south: 1000 + index }
        : { amount: 100 + (index % 7) }) as Record<string, number>,
    })),
    series: split
      ? [
          { key: 'east', label: 'east', metric: 'amount', value: 'east' },
          { key: 'south', label: 'south', metric: 'amount', value: 'south' },
        ]
      : [{ key: 'amount', label: 'amount', metric: 'amount' }],
  };
}

const spec = (chart: 'bar' | 'line', split = false): ChartSpec => ({
  type: chart,
  cartesian: {
    x: 'day',
    series: [{ metric: 'amount' }],
    ...(split ? { splitBy: 'wh' } : {}),
  },
});

const display = { locale: 'zh-CN', timeZone: 'UTC' };

const context = (
  data: CartesianData,
  extra: Partial<CartesianContext> = {},
): CartesianContext => ({
  spec: spec(data.chart as 'bar' | 'line', data.series.length > 1),
  label: (_alias, value) => String(value),
  column: alias => alias,
  locale: 'zh-CN',
  animate: true,
  pickable: false,
  ticks: shortDateTicks(
    data.points.map(point => point.x),
    'DAY',
    display,
  ),
  tickFor: values => shortDateTicks(values, 'DAY', display),
  ...extra,
});

const option = (data: CartesianData, extra?: Partial<CartesianContext>) =>
  cartesianOption(data, context(data, extra), LIGHT) as Loose;

describe('zoomOption: a long axis zooms, a short one does not', () => {
  it('leaves an axis a plot holds side by side alone', () => {
    const short = option(days(ZOOM_FROM));
    expect(short).not.toHaveProperty('dataZoom');
    expect(short.grid.bottom).toBe(4);
    expect(short.animation).toBe(true);
  });

  it('gives a long one a slider only, where gestures are not the chart’s', () => {
    // A dashboard panel, a read-only embedding: the wheel is the page's.
    const long = option(days(ZOOM_FROM + 1));
    expect(long.dataZoom).toHaveLength(1);
    expect(long.dataZoom[0]).toMatchObject({
      type: 'slider',
      xAxisIndex: 0,
      minValueSpan: 6,
      height: 24,
    });
    // Room under the plot for it, and marks that move at once.
    expect(long.grid.bottom).toBe(4 + SLIDER_ROOM);
    expect(long.animation).toBe(false);
  });

  it('adds a pinch and a Ctrl wheel in a workbench, never a plain wheel or a drag', () => {
    const long = option(days(ZOOM_FROM + 1), { zoomGestures: true });
    expect(long.dataZoom).toHaveLength(2);
    expect(long.dataZoom[1]).toEqual({
      type: 'inside',
      xAxisIndex: 0,
      minValueSpan: 6,
      zoomOnMouseWheel: 'ctrl',
      moveOnMouseWheel: false,
      moveOnMouseMove: false,
      preventDefaultMouseMove: false,
    });
  });

  it('does not zoom a chart lying on its side', () => {
    const data = days(ZOOM_FROM + 1, 'bar');
    const plan = cartesianPlan(data, {
      ...context(data),
      spec: {
        ...spec('bar'),
        cartesian: { ...spec('bar').cartesian!, orientation: 'horizontal' },
      },
    });
    expect(zooms(plan)).toBe(false);
    expect(zoomOption(plan, LIGHT, true)).toBeUndefined();
  });

  it('counts the categories a window shows', () => {
    expect(visibleCount(365)).toBe(365);
    expect(visibleCount(365, { start: 0, end: 100 })).toBe(365);
    expect(visibleCount(365, { start: 90, end: 92 })).toBe(8);
    expect(visibleCount(365, { start: 50, end: 50 })).toBe(1);
  });
});

describe('a zoom kept across redraws of one result', () => {
  it('puts the window back on every zoom of a new option', () => {
    const drawn = composed(
      { dataZoom: [{ type: 'slider' }, { type: 'inside' }] },
      false,
      { start: 80, end: 90 },
    );
    expect(drawn.dataZoom).toEqual([
      { type: 'slider', start: 80, end: 90 },
      { type: 'inside', start: 80, end: 90 },
    ]);
  });

  it('opens at the whole range without one, and adds none where there is no zoom', () => {
    const plain = { series: [] };
    expect(composed(plain, false, { start: 1, end: 2 })).toBe(plain);
    const zoomable = { dataZoom: [{ type: 'slider' }] };
    expect(composed(zoomable, false, undefined)).toBe(zoomable);
  });

  it('reads the window off a slider’s event, an inside zoom’s, or the drawing', () => {
    expect(zoomWindow({ start: 10, end: 20 })).toEqual({ start: 10, end: 20 });
    expect(zoomWindow({ batch: [{ start: 30, end: 40 }] })).toEqual({
      start: 30,
      end: 40,
    });
    expect(zoomWindow({ dataZoom: [{ start: 50, end: 60 }] })).toEqual({
      start: 50,
      end: 60,
    });
    expect(zoomWindow({})).toBeUndefined();
    expect(zoomWindow({ batch: ['nothing'] })).toBeUndefined();
  });

  it('draws patterns over the colours when they are asked for', () => {
    expect(composed({ series: [] }, true, undefined)).toEqual({
      series: [],
      aria: { enabled: true, label: { enabled: false }, decal: { show: true } },
    });
  });
});

describe('a large result', () => {
  it('thins a long line to its shape, and draws past a thousand bars as one path', () => {
    expect(option(days(20)).series[0].sampling).toBe('lttb');
    const few = option(days(LARGE_FROM, 'bar'));
    expect(few.series[0]).not.toHaveProperty('large');
    const many = option(days(LARGE_FROM + 1, 'bar'));
    expect(many.series[0]).toMatchObject({
      type: 'bar',
      large: true,
      largeThreshold: LARGE_FROM,
      progressive: 0,
    });
    expect(many.series[0]).not.toHaveProperty('label');
  });

  it('writes no numbers on bars in large mode, not even when asked', () => {
    const data = days(LARGE_FROM + 1, 'bar');
    const plan = cartesianPlan(data, {
      ...context(data),
      spec: { ...spec('bar'), labels: true },
    });
    expect(plan.labelled(plan.series[0])).toBe(false);
    expect(plan.outerTexts.every(text => text === '')).toBe(true);
  });
});

describe('a series switched off in the legend', () => {
  const data = days(10, 'line', true);

  it('leaves the marks, the scale and the tooltip, and keeps its colour', () => {
    const all = cartesianPlan(data, context(data));
    const hidden = cartesianPlan(
      data,
      context(data, { hidden: new Set(['south']) }),
    );
    expect(hidden.series.map(entry => entry.key)).toEqual(['east']);
    expect(hidden.legend.map(entry => entry.key)).toEqual(['east', 'south']);
    expect(hidden.legend[1].color).toBe(all.legend[1].color);
    // The scale reaches the east's numbers, no longer the south's thousand.
    expect(all.span('left').max).toBeGreaterThanOrEqual(1000);
    expect(hidden.span('left').max).toBeLessThan(1000);
    const drawn = cartesianOption(
      data,
      context(data, { hidden: new Set(['south']) }),
      LIGHT,
    ) as Loose;
    expect(drawn.series).toHaveLength(1);
    const tip: string = drawn.tooltip.formatter([{ dataIndex: 3 }]);
    expect(tip).toContain('east');
    expect(tip).not.toContain('south');
  });

  it('is left out of what the reading table reads', () => {
    expect(withoutHidden(data, undefined)).toBe(data);
    expect(withoutHidden(data, new Set())).toBe(data);
    expect(
      withoutHidden(data, new Set(['east'])).series.map(entry => entry.key),
    ).toEqual(['south']);
  });

  it('comes out of a 100% stack’s shares too', () => {
    const stacked = { ...days(3, 'bar', true) };
    const percent: ChartSpec = {
      type: 'bar',
      cartesian: {
        x: 'day',
        splitBy: 'wh',
        series: [{ metric: 'amount', stack: 'all' }],
        percentStack: true,
      },
    };
    const plan = cartesianPlan(stacked, {
      ...context(stacked),
      spec: percent,
      hidden: new Set(['south']),
    });
    expect(plan.shareAt(plan.series[0], 0)).toBe(1);
  });
});

describe('the tooltip on a time axis', () => {
  const data = days(10);

  it('writes each number’s change from the bucket before, and what it is against', () => {
    const drawn = option(data, { against: '较上一期' });
    const tip: string = drawn.tooltip.formatter([{ dataIndex: 1 }]);
    // 100 → 101.
    expect(tip).toContain('data-slot="chart-tooltip-note"');
    expect(tip).toContain('+1%');
    expect(tip).toContain('较上一期');
  });

  it('writes none for the first bucket, on a category axis, or unasked', () => {
    expect(
      option(data, { against: '较上一期' }).tooltip.formatter([
        { dataIndex: 0 },
      ]),
    ).not.toContain('较上一期');
    const categories = { ...data, timeline: undefined };
    expect(
      option(categories, { against: '较上一期' }).tooltip.formatter([
        { dataIndex: 1 },
      ]),
    ).not.toContain('chart-tooltip-note');
    expect(option(data).tooltip.formatter([{ dataIndex: 1 }])).not.toContain(
      'chart-tooltip-note',
    );
  });

  it('writes the amount where the bucket before had none to take a share of', () => {
    const fromZero: CartesianData = {
      ...data,
      points: [
        { x: day(0), values: { amount: 0 } },
        { x: day(1), values: { amount: 5 } },
        { x: day(2), values: { amount: 2 } },
      ],
    };
    const drawn = option(fromZero, { against: '较上一期' });
    expect(drawn.tooltip.formatter([{ dataIndex: 1 }])).toContain('>+5<');
    expect(drawn.tooltip.formatter({ dataIndex: 2 })).toContain('-60%');
  });
});

describe('the ticks of a long time axis', () => {
  const axis = (data: CartesianData, width: number, window?: object) =>
    (
      cartesianFit(
        cartesianPlan(data, context(data)),
        width,
        400,
        measure,
        window as never,
      ) as Loose
    ).xAxis.axisLabel;

  it('names every day where each fits, as its short tick', () => {
    const data = days(7);
    const label = axis(data, 1200);
    expect(label.interval).toBe(0);
    expect(label.formatter(String(day(1)))).toBe('1月2日');
  });

  it('names every few, the year where it changed since the one named before', () => {
    const data = days(1000);
    const label = axis(data, 900);
    const step = label.interval + 1;
    expect(step).toBeGreaterThan(1);
    const name = (index: number) => String(data.points[index].x);
    // The first named has its year; so has the first named in a new year.
    expect(label.formatter(name(0))).toBe('2026年1月1日');
    expect(label.formatter(name(step))).not.toMatch(/年/);
    const newYear = Math.ceil(365 / step) * step;
    expect(label.formatter(name(newYear))).toMatch(/^2027年/);
    expect(label.formatter('not a day')).toBe('not a day');
  });

  it('fits the days on screen when zoomed, not all of them', () => {
    const data = days(365);
    expect(axis(data, 900).interval).toBeGreaterThan(0);
    // A week of it: every day named again.
    expect(axis(data, 900, { start: 98, end: 100 }).interval).toBe(0);
  });
});
