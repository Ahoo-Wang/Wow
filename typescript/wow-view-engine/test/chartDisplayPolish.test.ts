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
  validateChart,
  type CartesianData,
  type CartesianSpec,
  type ChartData,
  type ChartSpec,
  type ScatterData,
} from '../src/index.js';
import { cartesianAxisValues, logScaleFits } from '../src/analysis/logScale.js';
import { cartesianOption } from '../src/ui/charts/cartesianOption.js';
import {
  cartesianPlan,
  type CartesianContext,
} from '../src/ui/charts/cartesianPlan.js';
import * as library from '../src/ui/charts/echarts.js';
import {
  chartImageSvg,
  headElements,
  pictureTheme,
  type ChartCapture,
} from '../src/ui/charts/image.js';
import { readChart } from '../src/ui/charts/reading.js';
import {
  scatterLogRefused,
  scatterOption,
} from '../src/ui/charts/scatterOption.js';
import { direction } from '../src/ui/charts/sentence.js';
import { CHART_FALLBACK, type ChartTheme } from '../src/ui/charts/theme.js';
import { tooltipHtml } from '../src/ui/charts/tooltip.js';
import { defaultMessages, formatMessage } from '../src/ui/messages.js';
import { zhCN } from '../src/ui/messages/zh-CN.js';
import { analysisConfig } from './fixtures.js';

/**
 * ECharts batch E, display polish (D33): a log scale on value axes and a
 * scatter, greyed and drawn linear over 0 or a negative; a scatter's axis
 * settings and crosshair; the one sentence a screen reader hears after a
 * chart's name; a tooltip that survives a strict `style-src`; and a chart
 * as a picture with its title, legend and range drawn in (Q58).
 */

// The option is the library's loose shape; the tests read into it freely.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Loose = Record<string, any>;

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

const categories = (values: readonly number[]): CartesianData => ({
  type: 'cartesian',
  chart: 'bar',
  points: values.map((value, index) => ({
    x: `c${index}`,
    values: { amount: value },
  })),
  series: [{ key: 'amount', label: 'amount', metric: 'amount' }],
});

const barSpec = (scale?: 'log'): ChartSpec => ({
  type: 'bar',
  cartesian: {
    x: 'x',
    series: [{ metric: 'amount' }],
    ...(scale ? { yAxis: { left: { scale } } } : {}),
  },
});

const context = (spec: ChartSpec): CartesianContext => ({
  spec,
  label: (_alias, value) => String(value),
  column: alias => alias,
  locale: 'en',
  animate: false,
  pickable: false,
});

const catalogue = (messages: typeof defaultMessages) => ({
  label: (key: string, params?: Record<string, string | number>) =>
    formatMessage(messages, key as never, params),
  issue: () => '',
  issues: () => '',
});

describe('a log scale', () => {
  it('fits numbers above zero only', () => {
    expect(logScaleFits([1, 10, 1000, null])).toBe(true);
    expect(logScaleFits([1, 0])).toBe(false);
    expect(logScaleFits([-5, 10])).toBe(false);
    expect(logScaleFits([])).toBe(true);
  });

  it('reads the numbers of the series on one side', () => {
    const data: CartesianData = {
      ...categories([1, 2]),
      series: [
        { key: 'amount', label: 'amount', metric: 'amount' },
        { key: 'orders', label: 'orders', metric: 'orders' },
      ],
      points: [{ x: 'a', values: { amount: 5, orders: 0 } }],
    };
    const spec: CartesianSpec = {
      x: 'x',
      series: [{ metric: 'amount' }, { metric: 'orders', axis: 'right' }],
    };
    expect(cartesianAxisValues(data, spec, 'left')).toEqual([5]);
    expect(cartesianAxisValues(data, spec, 'right')).toEqual([0]);
  });

  it('draws a value axis by powers of ten where the numbers allow', () => {
    const data = categories([3, 300, 30_000]);
    const option = cartesianOption(
      data,
      context(barSpec('log')),
      THEME,
    ) as Loose;
    expect(option.yAxis[0].type).toBe('log');
    expect(option.yAxis[0].logBase).toBe(10);
    // The decades either side of the marks, not the library's 1.
    expect(option.yAxis[0].min).toBe(1);
    expect(option.yAxis[0].max).toBe(100_000);
    // The shared steps are a linear axis's; a log one is the library's.
    expect(option.yAxis[0].interval).toBeUndefined();
    expect(cartesianPlan(data, context(barSpec('log'))).logRefused).toEqual([]);
  });

  it('draws it linear over 0 or a negative, and names the axis that refused', () => {
    const data = categories([0, 300, 30_000]);
    const option = cartesianOption(
      data,
      context(barSpec('log')),
      THEME,
    ) as Loose;
    expect(option.yAxis[0].type).toBe('value');
    expect(cartesianPlan(data, context(barSpec('log'))).logRefused).toEqual([
      'left',
    ]);
  });

  it('is a scale this package knows, or a finding', () => {
    const config = analysisConfig({
      metrics: [{ alias: 'orders', type: 'COUNT' }],
      chart: {
        type: 'bar',
        cartesian: {
          x: 'warehouse',
          series: [{ metric: 'orders' }],
          yAxis: { left: { scale: 'cubic' as never } },
        },
      },
    });
    expect(validateChart(config).map(found => found.path)).toEqual([
      ['chart', 'cartesian', 'yAxis', 'left', 'scale'],
    ]);
  });
});

describe('a scatter’s axes', () => {
  const points: ScatterData = {
    type: 'scatter',
    points: [
      { category: 'a', x: 1, y: 10 },
      { category: 'b', x: 100, y: 1000 },
    ],
  };
  const scatter = (
    xAxis?: NonNullable<ChartSpec['scatter']>['xAxis'],
    yAxis?: NonNullable<ChartSpec['scatter']>['yAxis'],
  ): ChartSpec => ({
    type: 'scatter',
    scatter: { category: 'c', x: 'orders', y: 'amount', xAxis, yAxis },
  });
  const option = (spec: ChartSpec, data = points) =>
    scatterOption(
      data,
      {
        spec,
        label: (_alias, value) => `#${String(value)}`,
        column: alias => alias,
        fallback: { x: 'X', y: 'Y' },
        animate: false,
        pickable: false,
        locale: 'en',
      },
      THEME,
    ) as Loose;

  it('takes the analyst’s title, bounds, format and scale', () => {
    const drawn = option(
      scatter(
        { label: 'Orders', min: 0, max: 200 },
        { scale: 'log', format: 'percent' },
      ),
    );
    expect(drawn.xAxis.name).toBe('Orders');
    expect(drawn.xAxis.min).toBe(0);
    expect(drawn.xAxis.max).toBe(200);
    expect(drawn.yAxis.type).toBe('log');
    expect(drawn.yAxis.axisLabel.formatter(0.5)).toBe('50%');
  });

  it('draws a crosshair on both axes, each reading its number', () => {
    const drawn = option(scatter());
    for (const axis of [drawn.xAxis, drawn.yAxis]) {
      expect(axis.axisPointer).toMatchObject({ show: true, snap: false });
      expect(axis.axisPointer.label.formatter({ value: 7 })).toBe('#7');
    }
  });

  it('draws a log axis linear where a point sits at 0', () => {
    const zero: ScatterData = {
      type: 'scatter',
      points: [...points.points, { category: 'c', x: 0, y: 5 }],
    };
    const spec = scatter({ scale: 'log' }, { scale: 'log' });
    expect(option(spec, zero).xAxis.type).toBe('value');
    expect(option(spec, zero).yAxis.type).toBe('log');
    expect(scatterLogRefused(zero, spec)).toEqual(['x']);
  });
});

describe('the tooltip under a strict style-src (CSP)', () => {
  it('carries no inline style: its swatch is an SVG fill', () => {
    const html = tooltipHtml('Heading', [
      { color: 'rgb(1, 2, 3)', name: 'East', value: '12', note: '+3%' },
    ]);
    expect(html).not.toMatch(/\sstyle=/);
    expect(html).toContain('fill="rgb(1, 2, 3)"');
  });

  it('sizes its swatch by attributes, so no stylesheet is needed to keep it small', () => {
    const host = document.createElement('div');
    host.innerHTML = tooltipHtml('Heading', [
      { color: 'rgb(1, 2, 3)', name: 'East', value: '12' },
    ]);
    const swatch = host.querySelector('[data-slot="chart-tooltip-swatch"]');
    expect(swatch?.getAttribute('width')).toBe('10');
    expect(swatch?.getAttribute('height')).toBe('10');
  });
});

describe('a chart in one sentence', () => {
  const read = (
    data: ChartData,
    spec: ChartSpec | undefined,
    messages: typeof defaultMessages,
  ) =>
    readChart(data, spec, {
      messages: catalogue(messages),
      label: (_alias, value) => String(value),
      column: () => undefined,
      locale: 'en',
    }).sentence;

  /**
   * A funnel's sentence says what its taper and its heavier drop say: from
   * what first to what last, the whole conversion, where it leaks most.
   */
  it('says a funnel from first to last, its whole, and where it leaks most', () => {
    const funnel: ChartData = {
      type: 'funnel',
      stages: [
        { label: 'Placed', value: 200, conversion: 1, share: 1 },
        { label: 'Paid', value: 150, conversion: 0.75, share: 0.75, drop: 50 },
        { label: 'Done', value: 140, conversion: 0.9333, share: 0.7, drop: 10 },
      ],
      largestDrop: 1,
    };
    expect(read(funnel, undefined, defaultMessages)).toBe(
      '3 stages from Placed, 200, to Done, 140; 70.0% overall. The largest drop is from Placed to Paid: \u221250 (\u221225.0%).',
    );
    expect(read(funnel, undefined, zhCN)).toBe(
      '共 3 段，从 Placed 200 到 Done 140，总转化 70.0%。流失最多在 Placed → Paid：\u221250（\u221225.0%）。',
    );
    // Nothing lost, nothing to say about a leak.
    expect(
      read(
        {
          type: 'funnel',
          stages: [
            { label: 'a', value: 5, conversion: 1, share: 1 },
            { label: 'b', value: 5, conversion: 1, share: 1, drop: 0 },
          ],
        },
        undefined,
        defaultMessages,
      ),
    ).toBe('2 stages from a, 5, to b, 5; 100.0% overall.');
  });

  it('names how many groups, the highest and the lowest, in either language', () => {
    const data = categories([5, 12, 3]);
    expect(read(data, barSpec(), defaultMessages)).toBe(
      '3 groups; highest c1, 12; lowest c2, 3.',
    );
    expect(read(data, barSpec(), zhCN)).toBe(
      '共 3 组，最高 c1 12，最低 c2 3。',
    );
  });

  it('says where a time axis starts, ends and which way it went', () => {
    const data: CartesianData = { ...categories([2, 5, 9]), timeline: true };
    expect(read(data, barSpec(), zhCN)).toBe(
      '共 3 期，从 c0 到 c2，总体上升；最高 c2 9，最低 c0 2。',
    );
    expect(direction(100, 102)).toBe('flat');
    expect(direction(100, 50)).toBe('down');
    expect(direction(0, 0)).toBe('flat');
  });

  it('names a filled-in 0 nowhere, and a scatter by its spans', () => {
    const data: CartesianData = {
      ...categories([4, 0]),
      points: [
        { x: 'a', values: { amount: 4 } },
        { x: 'b', values: { amount: 0 }, filled: ['amount'] },
        { x: 'c', values: { amount: 6 } },
      ],
    };
    expect(read(data, barSpec(), defaultMessages)).toBe(
      '3 groups; highest c, 6; lowest a, 4.',
    );
    expect(
      read(
        {
          type: 'scatter',
          points: [
            { category: 'a', x: 1, y: 9 },
            { category: 'b', x: 4, y: 2 },
          ],
        },
        undefined,
        zhCN,
      ),
    ).toBe('共 2 个点，X 轴 从 1 到 4，Y 轴 从 2 到 9。');
  });

  it('says nothing of a metric card, whose face is words', () => {
    expect(
      read({ type: 'metric', value: 3 } as ChartData, undefined, zhCN),
    ).toBeUndefined();
  });
});

describe('a chart as a picture (Q58)', () => {
  const capture = (legend: ChartCapture['legend']): ChartCapture => ({
    library,
    option: cartesianOption(categories([5, 12, 3]), context(barSpec()), THEME),
    width: 640,
    height: 360,
    theme: THEME,
    legend,
  });

  it('draws the title, the range and the legend over the drawing, as one SVG', () => {
    const picture = chartImageSvg(
      capture([
        { key: 'a', label: 'East', color: 'var(--chart-1)' },
        { key: 'b', label: 'Trend', color: 'currentColor', dashed: 'dashed' },
      ]),
      { title: 'Orders by warehouse', range: 'Conditions: status is shipped' },
    );
    expect(picture.width).toBe(640);
    expect(picture.height).toBeGreaterThan(360);
    const svg = picture.svg;
    expect(svg.startsWith('<svg xmlns="http://www.w3.org/2000/svg"')).toBe(
      true,
    );
    for (const text of [
      'Orders by warehouse',
      'Conditions: status is shipped',
      'East',
      'Trend',
    ])
      expect(svg).toContain(text);
    // The drawing nested under the head, at the head's height.
    expect(svg).toMatch(/<svg x="0" y="\d+"/);
    // Nothing for a pointer: no slider, no tooltip.
    expect(
      new DOMParser()
        .parseFromString(svg, 'image/svg+xml')
        .querySelector('parsererror'),
    ).toBeNull();
  });

  it('lays the legend out in as many rows as the width takes', () => {
    const many = Array.from({ length: 12 }, (_, index) => ({
      key: String(index),
      label: `Warehouse number ${index}`,
      color: 'var(--chart-1)',
    }));
    const one = headElements(
      { title: 'T', range: 'R' },
      many.slice(0, 1),
      THEME,
      640,
    );
    const all = headElements({ title: 'T', range: 'R' }, many, THEME, 640);
    expect(all.height).toBeGreaterThan(one.height);
  });

  it('parses whatever quotes the page’s font stack is written in', () => {
    const theme = pictureTheme({
      ...THEME,
      text: {
        ...CHART_FALLBACK.text,
        family: 'system-ui, "Segoe UI", sans-serif',
      },
    });
    const picture = chartImageSvg(
      {
        library,
        option: cartesianOption(categories([5, 12]), context(barSpec()), theme),
        width: 640,
        height: 360,
        theme,
        legend: [],
      },
      { title: 'T', range: 'R' },
    );
    expect(
      new DOMParser()
        .parseFromString(picture.svg, 'image/svg+xml')
        .querySelector('parsererror'),
    ).toBeNull();
  });
});
