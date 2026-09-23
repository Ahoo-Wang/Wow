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

import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ChartData, ChartSpec, NumberFormat } from '../src/index.js';
import { AnalysisChart, ViewSurface } from '../src/ui/index.js';
import { concreteColor, readChartTheme } from '../src/ui/charts/theme.js';
import { compactFormat, formatNumber } from '../src/ui/display.js';
import { measureText } from '../src/ui/charts/measure.js';
import { merged } from '../src/ui/charts/EChart.js';
import { ChartLegend } from '../src/ui/charts/ChartLegend.js';

afterEach(() => {
  cleanup();
  document.head.querySelectorAll('style[data-test]').forEach(s => s.remove());
});

/** A stylesheet for one test, as a host would ship its theme. */
function sheet(css: string) {
  const style = document.createElement('style');
  style.dataset.test = '';
  style.textContent = css;
  document.head.append(style);
}

describe('readChartTheme: the stylesheet read back as colours', () => {
  it('converts every token to rgb, whatever space it was written in', () => {
    const ground = document.createElement('div');
    ground.style.backgroundColor = 'rgb(20, 20, 20)';
    ground.style.setProperty('--chart-1', 'oklch(0.6 0.15 250)');
    ground.style.setProperty('--foreground', '#fafafa');
    ground.style.setProperty('--border', 'oklch(1 0 0 / 20%)');
    ground.style.setProperty('--brand', 'hsl(0 100% 50%)');
    const chart = document.createElement('div');
    ground.append(chart);
    document.body.append(ground);

    const theme = readChartTheme(chart);
    expect(theme.palette[0]).toMatch(/^rgb\(/);
    // A slot the stylesheet does not set falls back to the light theme's.
    expect(theme.palette[1]).toBe('rgb(235, 104, 52)');
    expect(theme.foreground).toBe('rgb(250, 250, 250)');
    expect(theme.border).toBe('rgba(255, 255, 255, 0.2)');
    // What the chart stands on is the first ancestor with paint.
    expect(theme.ground).toBe('rgb(20, 20, 20)');

    // A pinned colour: a slot, another token, or any CSS colour.
    expect(theme.resolve('var(--chart-1)')).toBe(theme.palette[0]);
    expect(theme.resolve('var(--chart-10)')).toBe(theme.palette[1]);
    expect(theme.resolve('var(--brand)')).toBe('rgb(255, 0, 0)');
    expect(theme.resolve('#0f766e')).toBe('rgb(15, 118, 110)');
    // Read once and kept.
    expect(theme.resolve('#0f766e')).toBe('rgb(15, 118, 110)');
    // Not a colour: the first slot rather than nothing.
    expect(theme.resolve('var(--unset)')).toBe(theme.palette[0]);
    expect(theme.resolve('banana')).toBe(theme.palette[0]);

    expect(readChartTheme(chart).key).toBe(theme.key);
    ground.remove();
  });

  it('stands on white where nothing under it is painted', () => {
    const chart = document.createElement('div');
    document.body.append(chart);
    expect(readChartTheme(chart).ground).toBe('rgb(255, 255, 255)');
    chart.remove();
  });

  it('reads nothing into a colour that is none', () => {
    expect(concreteColor(undefined)).toBeUndefined();
    expect(concreteColor('  ')).toBeUndefined();
    expect(concreteColor('nope')).toBeUndefined();
    expect(concreteColor('oklch(0.5753 0.1626 255.532deg)')).toBe(
      'rgb(42, 120, 214)',
    );
  });
});

describe('a number written short', () => {
  const short = (value: number, locale: string, currency?: boolean) =>
    formatNumber(
      value,
      compactFormat(
        currency
          ? {
              style: 'currency',
              currency: 'CNY',
              minimumFractionDigits: 2,
              maximumFractionDigits: 2,
            }
          : undefined,
      ),
      locale,
    );

  it('follows the language: 万 and 亿 in Chinese, K and M in English', () => {
    expect(short(11_100_000, 'zh-CN')).toBe('1,110万');
    expect(short(123_456_789, 'zh-CN')).toBe('1.23亿');
    expect(short(11_100_000, 'en')).toBe('11.1M');
    expect(short(4_200, 'en')).toBe('4.2K');
    // The column's currency stays; the decimals it asked of a whole number
    // do not.
    expect(short(11_100_000, 'zh-CN', true)).toBe('¥1,110万');
    expect(short(950, 'zh-CN', true)).toBe('¥950');
  });

  it('keeps three significant digits, adding no zero and inventing none (audit P1-4)', () => {
    // A donut's ¥10,230 is not 「¥1万」, and a week of 49,993 / 49,818 /
    // 49,077 is not three 「4.9万」s.
    expect(short(10_230, 'zh-CN', true)).toBe('¥1.02万');
    expect(short(10_230, 'en', true)).toBe('CN¥10.2K');
    expect(
      [49_993, 49_818, 49_077, 659_000, 1_831_229].map(value =>
        short(value, 'zh-CN'),
      ),
    ).toEqual(['5万', '4.98万', '4.91万', '65.9万', '183万']);
    expect(
      [49_993, 49_818, 49_077, 659_000, 1_831_229].map(value =>
        short(value, 'en'),
      ),
    ).toEqual(['50K', '49.8K', '49.1K', '659K', '1.83M']);
    // No zero added to make up three (「5万」, not 「5.00万」), and none
    // invented where the whole part is longer: 「1,235万」, not 「1,230万」.
    expect(short(50_000, 'zh-CN')).toBe('5万');
    expect(short(12_345_678, 'zh-CN')).toBe('1,235万');
    // Small and fractional numbers keep their digits.
    expect(short(12.345, 'en')).toBe('12.3');
    expect(short(0.5, 'zh-CN', true)).toBe('¥0.5');
  });

  it('groups a Chinese figure under 万 as the table does (audit P2-3)', () => {
    const chinese = (value: number, format?: NumberFormat) =>
      formatNumber(value, compactFormat(format), 'zh-CN');
    expect(short(4_880, 'zh-CN', true)).toBe('¥4,880');
    // Written whole, not rounded to three digits: there is no shorter word.
    expect(chinese(4_885)).toBe('4,885');
    // A field whose numbers are names keeps them ungrouped.
    expect(chinese(2_026, { useGrouping: false })).toBe('2026');
  });
});

describe('measureText', () => {
  it('estimates where there is no canvas: a CJK character wider', () => {
    expect(measureText('订单')).toBeGreaterThan(measureText('ab'));
  });
});

const data: ChartData = {
  type: 'cartesian',
  chart: 'bar',
  points: [
    { x: 'CN', values: { orders: 2 } },
    { x: 'JP', values: { orders: 1 } },
  ],
  series: [{ key: 'orders', label: 'orders', metric: 'orders' }],
};
const spec: ChartSpec = {
  type: 'bar',
  cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
};

const fills = (container: HTMLElement) =>
  [
    ...container.querySelectorAll('[data-slot="chart-plot"] svg path[fill]'),
  ].map(path => path.getAttribute('fill'));

describe('EChart: the drawing bound to its element', () => {
  it('redraws in the other mode’s colours, in place', () => {
    sheet(`
      .fve-root[data-theme='light'] { --chart-1: rgb(1, 2, 3); }
      .fve-root[data-theme='dark'] { --chart-1: rgb(200, 201, 202); }
    `);
    const { container, rerender } = render(
      <ViewSurface theme="light">
        <AnalysisChart data={data} spec={spec} />
      </ViewSurface>,
    );
    const svg = container.querySelector('[data-slot="chart-plot"] svg');
    expect(fills(container)).toContain('rgb(1, 2, 3)');

    rerender(
      <ViewSurface theme="dark">
        <AnalysisChart data={data} spec={spec} />
      </ViewSurface>,
    );
    expect(fills(container)).toContain('rgb(200, 201, 202)');
    expect(fills(container)).not.toContain('rgb(1, 2, 3)');
    // The same drawing, not a new one.
    expect(container.querySelector('[data-slot="chart-plot"] svg')).toBe(svg);
  });

  it('waits for a size, and follows every new one', () => {
    const observed: ResizeObserverCallback[] = [];
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(callback: ResizeObserverCallback) {
          observed.push(callback);
        }
        observe() {}
        disconnect() {}
      },
    );
    const { container } = render(
      <ViewSurface>
        <AnalysisChart data={data} spec={spec} />
      </ViewSurface>,
    );
    const size = (width: number, height: number) =>
      observed[observed.length - 1](
        [{ contentRect: { width, height } } as ResizeObserverEntry],
        {} as ResizeObserver,
      );

    // Folded away, a chart has no size and nothing is drawn yet.
    size(0, 0);
    expect(container.querySelector('[data-slot="chart-plot"] svg')).toBeNull();

    size(400, 200);
    const svg = container.querySelector('[data-slot="chart-plot"] svg')!;
    expect(svg.getAttribute('width')).toBe('400');

    size(700, 300);
    expect(svg.getAttribute('width')).toBe('700');
    vi.unstubAllGlobals();
  });

  it('hands a pressed bar’s group to the follow-up, where it was pressed', () => {
    const onPick = vi.fn();
    const { container } = render(
      <ViewSurface>
        <AnalysisChart data={data} spec={spec} onPick={onPick} />
      </ViewSurface>,
    );
    const bar = [
      ...container.querySelectorAll('[data-slot="chart-plot"] svg path'),
    ].find(path => path.getAttribute('fill') === 'rgb(42, 120, 214)')!;
    // The bar's corners are the points its outline moves and lines to; the
    // rounded ones add arcs, whose numbers are not points.
    const corners = [
      ...(bar.getAttribute('d') ?? '').matchAll(
        /[ML]\s*(-?[\d.]+)[\s,](-?[\d.]+)/g,
      ),
    ].map(([, x, y]) => [Number(x), Number(y)]);
    const xs = corners.map(([x]) => x);
    const ys = corners.map(([, y]) => y);
    const at = {
      clientX: (Math.min(...xs) + Math.max(...xs)) / 2,
      clientY: (Math.min(...ys) + Math.max(...ys)) / 2,
    };
    const surface = container.querySelector(
      '[data-slot="chart-plot"] > div > div',
    )!;
    // The library reads where a press landed off the event's own offset,
    // which jsdom leaves at nothing, so it is given the point.
    const press = (type: string) => {
      const event = new MouseEvent(type, { bubbles: true, ...at });
      Object.defineProperties(event, {
        offsetX: { value: at.clientX },
        offsetY: { value: at.clientY },
      });
      fireEvent(surface, event);
    };
    for (const type of ['mousemove', 'mousedown', 'mouseup', 'click'])
      press(type);

    expect(onPick).toHaveBeenCalledTimes(1);
    const [row, anchor] = onPick.mock.calls[0];
    expect(row).toEqual({ warehouse: 'CN' });
    expect(anchor.getBoundingClientRect()).toMatchObject({
      x: at.clientX,
      y: at.clientY,
    });
  });

  it('says on its frame when the marks have landed', async () => {
    const { container, rerender } = render(
      <ViewSurface>
        <AnalysisChart data={data} spec={spec} />
      </ViewSurface>,
    );
    const frame = container.querySelector('[data-slot="chart"]')!;
    await waitFor(() => expect(frame.hasAttribute('data-drawn')).toBe(true));

    // A new drawing takes it back until that one has landed too.
    rerender(
      <ViewSurface>
        <AnalysisChart data={data} spec={{ ...spec, labels: true }} />
      </ViewSurface>,
    );
    await waitFor(() => expect(frame.hasAttribute('data-drawn')).toBe(true));
  });

  it('says what it draws on its frame', () => {
    const { container } = render(
      <ViewSurface>
        <AnalysisChart data={data} spec={{ ...spec, labels: true }} />
      </ViewSurface>,
    );
    const frame = container.querySelector('[data-slot="chart"]')!;
    expect(frame.getAttribute('data-marks')).toBe('2');
    expect(frame.getAttribute('data-labels')).toBe('on');
    expect(frame.getAttribute('data-legend')).toBe('none');
    expect(screen.getByRole('img', { name: /bar/ })).toBeDefined();
  });
});

describe('merged: the width’s adjustment laid onto a drawing', () => {
  it('merges object into object and replaces anything else', () => {
    expect(
      merged(
        {
          xAxis: { type: 'category', axisLabel: { rotate: 0, color: 'a' } },
          series: [1],
        },
        { xAxis: { axisLabel: { rotate: 45 } }, series: [2], grid: { top: 1 } },
      ),
    ).toEqual({
      xAxis: { type: 'category', axisLabel: { rotate: 45, color: 'a' } },
      series: [2],
      grid: { top: 1 },
    });
  });

  it('merges a list of objects item by item, as the library does', () => {
    expect(
      merged(
        {
          series: [
            { type: 'pie', radius: ['50%', '72%'], label: { color: 'a' } },
            { type: 'pie' },
          ],
        },
        { series: [{ radius: [50, 72], label: { show: false } }] },
      ),
    ).toEqual({
      series: [
        // A list of numbers replaces.
        { type: 'pie', radius: [50, 72], label: { color: 'a', show: false } },
        { type: 'pie' },
      ],
    });
  });
});

describe('EChart: a legend beside the plot, where there is room', () => {
  const pie: ChartData = {
    type: 'pie',
    slices: [
      { category: 'CN', value: 3 },
      { category: 'JP', value: 1 },
    ],
  };
  const pieSpec: ChartSpec = {
    type: 'pie',
    pie: { category: 'country', value: 'orders' },
  };
  const framed = (width: number) => {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        constructor(private readonly callback: ResizeObserverCallback) {}
        observe(target: Element) {
          this.callback(
            [{ target, contentRect: { width, height: 300 } } as never],
            this as never,
          );
        }
        disconnect() {}
      },
    );
    const { container } = render(
      <ViewSurface>
        <AnalysisChart data={pie} spec={pieSpec} />
      </ViewSurface>,
    );
    vi.unstubAllGlobals();
    return container.querySelector<HTMLElement>('[data-slot="chart"]')!;
  };

  it('stands beside a pie on a wide frame', () => {
    const frame = framed(800);
    expect(frame.getAttribute('data-legend')).toBe('right');
    expect(frame.className).toContain('flex-row');
  });

  it('goes under the pie on a phone, and folds to a line there (audit P0-6)', () => {
    const frame = framed(382);
    expect(frame.getAttribute('data-legend')).toBe('bottom');
    expect(frame.className).toContain('flex-col');
    // Under the plot, after it.
    expect(frame.lastElementChild?.getAttribute('data-slot')).toBe(
      'chart-legend',
    );
    // Every share still in it, one decimal each.
    expect(
      [...frame.querySelectorAll('[data-slot="chart-legend-item"]')].map(
        item => item.textContent,
      ),
    ).toEqual(['CN75.0%', 'JP25.0%']);
  });
});

describe('ChartLegend: one line, and the rest counted', () => {
  it('counts the entries past the first line, and opens the whole list', async () => {
    // jsdom lays nothing out: every entry past the third stands a line lower.
    vi.spyOn(HTMLElement.prototype, 'offsetTop', 'get').mockImplementation(
      function (this: HTMLElement) {
        const siblings = [...(this.parentElement?.children ?? [])];
        return this.dataset.slot === 'chart-legend-item' &&
          siblings.indexOf(this) >= 3
          ? 20
          : 0;
      },
    );
    const entries = Array.from({ length: 7 }, (_, i) => ({
      key: `k${i}`,
      label: `Series ${i}`,
      color: 'red',
    }));
    render(
      <ViewSurface>
        <ChartLegend entries={entries} at="top" />
      </ViewSurface>,
    );
    const more = screen.getByRole('button', { name: '4 more' });
    expect(more.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(more);
    const less = screen.getByRole('button', { name: 'Show less' });
    expect(less.getAttribute('aria-expanded')).toBe('true');
    expect(
      document
        .querySelector('[data-slot="chart-legend"]')!
        .hasAttribute('data-open'),
    ).toBe(true);
  });

  it('scrolls beside the plot rather than folding', () => {
    render(
      <ViewSurface>
        <ChartLegend
          entries={[{ key: 'a', label: 'A', color: 'red' }]}
          at="right"
        />
      </ViewSurface>,
    );
    expect(screen.queryByRole('button')).toBeNull();
  });
});
