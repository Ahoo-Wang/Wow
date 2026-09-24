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

import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type {
  AnalysisView,
  AnalysisViewConfig,
  ChartData,
  ChartSpec,
} from '../src/index.js';
import { CHART_COLOR_SLOTS, groupKeyText, shapeChart } from '../src/index.js';
import { AnalysisChart, ViewSurface, zhCN } from '../src/ui/index.js';
import { DRAWN, analysisConfig } from './fixtures.js';

afterEach(cleanup);

function chartOf(data: ChartData) {
  return render(
    <ViewSurface>
      <AnalysisChart data={data} />
    </ViewSurface>,
  );
}

/** The colour each legend entry wears, as the CSS the page resolves. */
function legendColours(container: HTMLElement): string[] {
  return [
    ...container.querySelectorAll<HTMLElement>(
      '[data-slot="chart-legend-item"] > [aria-hidden]',
    ),
  ].map(dot => dot.style.background);
}

/** A CSS colour as the page's own style object writes it back. */
function asStyled(color: string): string {
  const probe = document.createElement('span');
  probe.style.background = color;
  return probe.style.background;
}

/** The painted shapes of the drawing: bars, and nothing that is only air. */
function fills(container: HTMLElement): string[] {
  return [
    ...container.querySelectorAll('[data-slot="chart-plot"] svg path[fill]'),
  ]
    .map(path => path.getAttribute('fill')!)
    .filter(fill => fill !== 'none' && fill !== 'transparent');
}

describe('AnalysisChart', () => {
  const cartesian: ChartData = {
    type: 'cartesian',
    chart: 'bar',
    points: [
      { x: 'CN', values: { orders: 2 } },
      { x: 'JP', values: { orders: 1 } },
    ],
    series: [{ key: 'orders', label: 'orders', metric: 'orders' }],
  };

  const statuses: AnalysisView['columns'] = [
    {
      alias: 'status',
      label: 'Status',
      role: 'group',
      kind: 'enum',
      cell: 'enum',
      options: [
        { value: 'FAILED', label: 'Failed' },
        { value: 'SUCCEEDED', label: 'Succeeded' },
      ],
    },
    { alias: 'orders', label: 'Orders', role: 'metric' },
  ];

  // Each query looks inside the chart: the popups and the rest of the page
  // are not what is asked about.
  it('names a category as its column shows it', () => {
    const { container } = render(
      <ViewSurface>
        <AnalysisChart
          data={{
            ...cartesian,
            points: [{ x: 'FAILED', values: { orders: 2 } }],
          }}
          spec={{
            type: 'bar',
            cartesian: { x: 'status', series: [{ metric: 'orders' }] },
          }}
          columns={statuses}
        />
      </ViewSurface>,
    );

    expect(within(container).getByText('Failed', DRAWN)).toBeDefined();
    expect(within(container).queryByText('FAILED', DRAWN)).toBeNull();
  });

  // The kind of a number or a boolean field has nothing to add, so the axis
  // printed `1000` and `true` where the table beside it read CN¥1,000.00 and Yes.
  it('names a number category in its format and a boolean one in words', () => {
    const amount = new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency: 'CNY',
    }).format(1000);
    const { container } = render(
      <ViewSurface>
        <AnalysisChart
          data={{
            ...cartesian,
            points: [
              { x: 1000, values: { orders: 2 } },
              { x: true, values: { orders: 1 } },
            ],
          }}
          spec={{
            type: 'bar',
            cartesian: { x: 'amount', series: [{ metric: 'orders' }] },
          }}
          columns={[
            {
              alias: 'amount',
              label: 'Amount',
              role: 'group',
              kind: 'number',
              cell: 'number',
              numberFormat: { style: 'currency', currency: 'CNY' },
            },
            { alias: 'orders', label: 'Orders', role: 'metric' },
          ]}
        />
      </ViewSurface>,
    );

    expect(within(container).getByText(amount, DRAWN)).toBeDefined();
    expect(within(container).getByText('Yes', DRAWN)).toBeDefined();
  });

  // A number histogram's key is its band's lower bound: the axis and the
  // reading table name the band, as the analysis table does.
  it('names a number band from its key to the key plus the interval', () => {
    const { container } = render(
      <ViewSurface messages={zhCN} locale="zh-CN">
        <AnalysisChart
          data={{
            ...cartesian,
            points: [
              { x: 0, values: { orders: 2 } },
              { x: 10000, values: { orders: 1 } },
            ],
          }}
          spec={{
            type: 'bar',
            cartesian: { x: 'band', series: [{ metric: 'orders' }] },
          }}
          columns={[
            {
              alias: 'band',
              label: '单价',
              role: 'group',
              kind: 'number',
              cell: 'number',
              numberFormat: { style: 'currency', currency: 'CNY' },
              interval: 10000,
            },
            { alias: 'orders', label: '订单数', role: 'metric' },
          ]}
        />
      </ViewSurface>,
    );

    expect(within(container).getByText('¥0～1万', DRAWN)).toBeDefined();
    expect(within(container).getByText('¥1～2万', DRAWN)).toBeDefined();
    const reading = container.querySelector('[data-slot="chart-reading"]')!;
    expect(within(reading as HTMLElement).getByText('¥0～1万')).toBeDefined();
    expect(within(container).queryByText('¥0.00', DRAWN)).toBeNull();
  });

  it('names a pivot series by the value it was split by', () => {
    const { container } = render(
      <ViewSurface>
        <AnalysisChart
          data={{
            type: 'cartesian',
            chart: 'line',
            points: [{ x: 'CN', values: { a: 1, b: 2 } }],
            series: [
              { key: 'a', label: 'FAILED', metric: 'orders', value: 'FAILED' },
              {
                key: 'b',
                label: 'SUCCEEDED',
                metric: 'orders',
                value: 'SUCCEEDED',
              },
            ],
          }}
          spec={{
            type: 'line',
            cartesian: {
              x: 'warehouse',
              splitBy: 'status',
              series: [{ metric: 'orders' }],
            },
          }}
          columns={statuses}
        />
      </ViewSurface>,
    );

    expect(within(container).getByText('Failed', DRAWN)).toBeDefined();
    expect(within(container).getByText('Succeeded', DRAWN)).toBeDefined();
  });

  // Two values can show alike: two options with one label, or the two 01:00
  // hours of the night the clocks go back. A key made of the shown text
  // collided and let React reuse one row for the other.
  it('keys heatmap rows and cells by value, so values shown alike stay apart', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    const { container } = render(
      <ViewSurface>
        <AnalysisChart
          data={{
            type: 'heatmap',
            xs: ['A', 'B'],
            ys: ['A', 'B'],
            cells: [
              [1, 2],
              [3, 4],
            ],
          }}
          spec={{
            type: 'heatmap',
            heatmap: { x: 'col', y: 'row', value: 'orders' },
          }}
          columns={['row', 'col'].map(alias => ({
            alias,
            label: alias,
            role: 'group' as const,
            kind: 'enum',
            cell: 'enum',
            options: [
              { value: 'A', label: 'Same' },
              { value: 'B', label: 'Same' },
            ],
          }))}
        />
      </ViewSurface>,
    );

    expect(within(container).getAllByText('Same', DRAWN)).toHaveLength(4);
    expect(
      error.mock.calls.some(call => String(call[0]).includes('same key')),
    ).toBe(false);
  });

  it('draws every cartesian variant', () => {
    for (const chart of ['bar', 'line', 'area', 'combo'] as const) {
      const { container } = chartOf({ ...cartesian, chart });
      expect(container.querySelector('[data-slot="chart"]')).not.toBeNull();
      cleanup();
    }
  });

  it('takes the mark of a combo series from the saved spec', () => {
    const { container } = render(
      <ViewSurface>
        <AnalysisChart
          data={{ ...cartesian, chart: 'combo' }}
          spec={{
            type: 'combo',
            cartesian: {
              x: 'warehouse',
              series: [{ metric: 'orders', type: 'line', smooth: true }],
              orientation: 'horizontal',
              referenceLines: [{ axis: 'left', value: 2, label: 'target' }],
            },
          }}
        />
      </ViewSurface>,
    );
    expect(container.querySelector('[data-slot="chart"]')).not.toBeNull();
  });

  /**
   * A reference line was drawn at `y` whatever the orientation, so on a chart
   * laid out horizontally — where the numbers run along X — it landed on the
   * categories and marked a threshold nobody set.
   */
  it('puts a reference line on the numeric axis, whichever way the chart runs', () => {
    const withOrientation = (orientation: 'vertical' | 'horizontal') => ({
      type: 'bar' as const,
      cartesian: {
        x: 'warehouse',
        series: [{ metric: 'orders' }],
        orientation,
        referenceLines: [{ axis: 'left' as const, value: 2 }],
      },
    });
    // The one dashed rule in the drawing, and whether it runs up the plot.
    const bounds = (container: HTMLElement) => {
      const line = container.querySelector(
        '[data-slot="chart-plot"] path[stroke-dasharray]',
      );
      const [x1, y1, x2, y2] = (line?.getAttribute('d') ?? '')
        .match(/-?[\d.]+/g)!
        .map(Number);
      return { vertical: x1 === x2 && y1 !== y2 };
    };

    const upright = render(
      <ViewSurface>
        <AnalysisChart data={cartesian} spec={withOrientation('vertical')} />
      </ViewSurface>,
    );
    expect(bounds(upright.container).vertical).toBe(false);
    cleanup();

    const sideways = render(
      <ViewSurface>
        <AnalysisChart data={cartesian} spec={withOrientation('horizontal')} />
      </ViewSurface>,
    );
    expect(bounds(sideways.container).vertical).toBe(true);
  });

  /**
   * `axis`, `yAxis.left/right` and a line's own `axis` were stored, validated
   * and then never read: every series was measured against one automatic
   * scale, so a rate beside a count was a flat line at the bottom.
   */
  it('gives a right-hand series an axis of its own, with its bounds and format', () => {
    const { container } = render(
      <ViewSurface>
        <AnalysisChart
          data={cartesian}
          spec={{
            type: 'bar',
            cartesian: {
              x: 'warehouse',
              series: [{ metric: 'orders', axis: 'right' }],
              yAxis: { right: { min: 0, max: 10, format: 'percent' } },
            },
          }}
        />
      </ViewSurface>,
    );

    // The bounds the spec pinned, printed the way it asked for: 0 to 10 as
    // a share, on the only numeric axis there is to write it on. That the
    // right-hand axis stands beside the left is the browser story's
    // (`分析工作台/回归 › TwoMetrics`).
    expect(within(container).getByText('1,000%')).toBeDefined();
    expect(within(container).getByText('0%')).toBeDefined();
  });

  /**
   * F5: the axis carries one column's numbers, so its ticks read as that
   * column reads. A table showing ¥1,234.00 beside an axis showing 1234 is
   * two readings of one number, and only one of them is the column's.
   */
  it('reads a numeric axis through the metric on it', () => {
    const money: AnalysisView['columns'] = [
      { alias: 'warehouse', label: 'Warehouse', role: 'group' },
      {
        alias: 'orders',
        label: 'Amount',
        role: 'metric',
        fn: 'SUM',
        numberFormat: { style: 'currency', currency: 'CNY' },
      },
    ];
    const { container } = render(
      <ViewSurface locale="zh-CN">
        <AnalysisChart
          data={{
            type: 'cartesian',
            chart: 'bar',
            points: [
              { x: 'CN', values: { orders: 1234 } },
              { x: 'JP', values: { orders: 800 } },
            ],
            series: [{ key: 'orders', label: 'orders', metric: 'orders' }],
          }}
          spec={{
            type: 'bar',
            cartesian: {
              x: 'warehouse',
              series: [{ metric: 'orders' }],
              // Bounds only, no `format`: jsdom lays nothing out, so the
              // ticks are the ones the domain pins, and the format under
              // test is the column's rather than the axis's own.
              yAxis: { left: { min: 0, max: 2000 } },
            },
          }}
          columns={money}
        />
      </ViewSurface>,
    );

    // A tick the domain pins and no row holds, so it can only be the axis —
    // written short, in the column's currency: the reading table beside it
    // has the whole number. Grouped as the table groups it: Chinese has no
    // short word under 万 (audit P2-3).
    expect(within(container).getByText('¥1,000')).toBeDefined();
    expect(within(container).getByText('¥2,000')).toBeDefined();
    // The reading beside the marks is the same numbers, so the same text.
    expect(within(container).getAllByText('¥1,234.00').length).toBeGreaterThan(
      0,
    );
  });

  /**
   * The chart reads its columns as the table heads them: a time dimension
   * says what one of its buckets spans, and a metric is one phrase — on the
   * axis, in the legend and in the figure's name alike.
   */
  it('titles a time axis, a legend and the figure as the table heads them', () => {
    const dated: AnalysisView['columns'] = [
      {
        alias: 'created',
        label: '创建时间',
        role: 'group',
        kind: 'datetime',
        dateUnit: 'DAY',
      },
      { alias: 'amount', label: '金额', role: 'metric', fn: 'SUM' },
      { alias: 'orders', label: '订单', role: 'metric', fn: 'COUNT' },
    ];
    const { container } = render(
      <ViewSurface messages={zhCN}>
        <AnalysisChart
          data={{
            type: 'cartesian',
            chart: 'bar',
            points: [
              { x: '2026-09-01', values: { amount: 1200, orders: 3 } },
              { x: '2026-09-02', values: { amount: 800, orders: 2 } },
            ],
            series: [
              { key: 'amount', label: 'amount', metric: 'amount' },
              { key: 'orders', label: 'orders', metric: 'orders' },
            ],
          }}
          spec={{
            type: 'bar',
            cartesian: {
              x: 'created',
              series: [{ metric: 'amount' }, { metric: 'orders' }],
            },
          }}
          columns={dated}
        />
      </ViewSurface>,
    );

    expect(
      within(container).getByText('创建时间（按日）', DRAWN),
    ).toBeDefined();
    expect(
      [...container.querySelectorAll('[data-slot="chart-legend-item"]')].map(
        item => item.textContent,
      ),
    ).toEqual(['金额的总和', '记录数']);
    expect(
      container
        .querySelector('[data-slot="chart-plot"]')!
        .getAttribute('aria-label'),
    ).toBe('柱状图：金额的总和、记录数，按创建时间（按日）');
  });

  it('writes a data-valued series name as text, never into a stylesheet', () => {
    // A pivot names its series by raw group values. The drawing is handed
    // concrete colours rather than custom properties keyed by them, so no
    // style element is written at all, and the name is the legend's text.
    const hostile = 'a} *{background:url(//evil/)}<b>';
    const { container } = render(
      <ViewSurface>
        <AnalysisChart
          data={{
            type: 'cartesian',
            chart: 'bar',
            points: [{ x: 'CN', values: { [hostile]: 2 } }],
            series: [{ key: hostile, label: hostile, metric: 'orders' }],
          }}
          spec={{
            type: 'bar',
            cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
            legend: 'top',
          }}
        />
      </ViewSurface>,
    );

    expect(container.querySelector('style')).toBeNull();
    expect(container.querySelector('b')).toBeNull();
    expect(
      container.querySelector('[data-slot="chart-legend-item"]')?.textContent,
    ).toBe(hostile);
    expect(fills(container)).toContain('rgb(38, 117, 211)');
  });

  it('writes a pie category as text, never into a stylesheet', () => {
    const hostile = 'x} *{color:red}<b>';
    const { container } = chartOf({
      type: 'pie',
      slices: [{ category: hostile, value: 2 }],
    });

    expect(container.querySelector('style')).toBeNull();
    expect(container.querySelector('b')).toBeNull();
    expect(
      container.querySelector('[data-slot="chart-legend-item"]')?.textContent,
    ).toContain(hostile);
  });

  it('draws a pie and a donut', () => {
    const { container } = chartOf({
      type: 'pie',
      slices: [
        { category: 'CN', value: 2 },
        { category: null, value: 1, other: true },
      ],
    });
    expect(container.querySelector('[data-slot="chart"]')).not.toBeNull();
  });

  /**
   * `ChartSpec.colors` was stored and validated and then never read, so a
   * pinned series colour did nothing and every chart came out of the palette
   * in index order.
   */
  it('paints a slice the colour the spec pinned for its category', () => {
    const { container } = render(
      <ViewSurface>
        <AnalysisChart
          data={{
            type: 'pie',
            slices: [
              { category: 'CN-EAST', value: 3 },
              { category: 'CN-WEST', value: 2 },
              { category: null, value: 1, other: true },
            ],
          }}
          spec={{
            type: 'pie',
            pie: { category: 'region', value: 'orders' },
            // The merged remainder prints its category as `null`; naming it
            // still does not colour it.
            colors: { 'CN-EAST': '#eb6834', null: '#000000' },
          }}
        />
      </ViewSurface>,
    );

    // The legend wears the colours as written; the remainder is no
    // category, so it wears the neutral, not a slot.
    expect(legendColours(container)).toEqual(
      ['#eb6834', 'var(--chart-2)', 'var(--muted-foreground)'].map(asStyled),
    );
    // And the slice is drawn in it.
    expect(fills(container)).toContain('rgb(235, 104, 52)');
  });

  /**
   * The palette held five slots and cycled, so the sixth category of a pie
   * or a split came out blue again beside the first — two wedges one colour,
   * and a legend that could not say which was which (analysis audit,
   * 2026-09-23).
   */
  it('gives each of eight slices a colour of its own', () => {
    const { container } = render(
      <ViewSurface>
        <AnalysisChart
          data={{
            type: 'pie',
            slices: Array.from({ length: CHART_COLOR_SLOTS }, (_, index) => ({
              category: `c${index}`,
              value: CHART_COLOR_SLOTS - index,
            })),
          }}
          spec={{ type: 'pie', pie: { category: 'region', value: 'orders' } }}
        />
      </ViewSurface>,
    );

    const slots = legendColours(container);
    expect(slots).toHaveLength(CHART_COLOR_SLOTS);
    expect(new Set(slots).size).toBe(CHART_COLOR_SLOTS);
    expect(new Set(fills(container)).size).toBe(CHART_COLOR_SLOTS);
  });

  it('colours a cartesian series by its metric alias', () => {
    const { container } = render(
      <ViewSurface>
        <AnalysisChart
          data={cartesian}
          spec={{
            type: 'bar',
            cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
            colors: { orders: '#0f766e' },
          }}
        />
      </ViewSurface>,
    );

    // Pinned by its alias, and handed to the drawing as a concrete colour.
    expect(fills(container)).toContain('rgb(15, 118, 110)');
  });

  /**
   * A pivoted series is keyed by `seriesKey`, which tags a number so that `1`
   * and `'1'` stay two series, and this file then exchanges that key for
   * `s0` — so a spec could name neither if it tried. Its public name is the
   * label: the split value as the legend prints it. Matching on the key left
   * every pivot uncolourable whenever the split was not a string.
   */
  it('colours a pivoted series by the value the legend prints', () => {
    const spec: ChartSpec = {
      type: 'bar',
      cartesian: {
        x: 'month',
        splitBy: 'warehouse',
        series: [{ metric: 'orders' }],
      },
      // The warehouses are numbered, and a key is written as it prints.
      colors: { '1': '#eb6834' },
    };
    // Shaped by the kernel rather than written out, so the keys under test
    // are the ones a query really produces.
    const pivoted = shapeChart(
      analysisConfig({
        groups: [
          { alias: 'month', field: 'createdAt', type: 'TERMS' },
          { alias: 'warehouse', field: 'warehouse', type: 'TERMS' },
        ],
        chart: spec,
      }),
      [
        { month: '2026-08', warehouse: 1, orders: 2 },
        { month: '2026-08', warehouse: 2, orders: 3 },
      ],
    ) as ChartData;

    const { container } = render(
      <ViewSurface>
        <AnalysisChart data={pivoted} spec={spec} />
      </ViewSurface>,
    );

    // Two series earn a legend, which wears the colours as written.
    expect(legendColours(container)).toEqual(
      ['#eb6834', 'var(--chart-2)'].map(asStyled),
    );
  });

  /**
   * The pie looked a pinned colour up by a spelling of its own and the
   * cartesian family by the kernel's series label — two paths that agreed
   * only because the two functions happened to match. Both now read
   * `groupKeyText`; this pins that one key colours the same category, of
   * every non-string kind, in either chart.
   */
  it('colours a number, a boolean and null by one key in a pie and in a split', () => {
    const categories = [7, true, null];
    const colors = { '7': '#eb6834', true: '#2a78d6', '': '#1f9d55' };
    expect(categories.map(groupKeyText)).toEqual(Object.keys(colors));
    const groups: AnalysisViewConfig['groups'] = [
      { alias: 'month', field: 'createdAt', type: 'TERMS' },
      { alias: 'warehouse', field: 'warehouse', type: 'TERMS' },
    ];
    const pieSpec: ChartSpec = {
      type: 'pie',
      pie: { category: 'warehouse', value: 'orders' },
      colors,
    };
    const splitSpec: ChartSpec = {
      type: 'bar',
      cartesian: {
        x: 'month',
        splitBy: 'warehouse',
        series: [{ metric: 'orders' }],
      },
      colors,
    };
    const rows = categories.map((warehouse, index) => ({
      month: '2026-08',
      warehouse,
      orders: index + 1,
    }));
    const drawn = (spec: ChartSpec) => {
      const data = shapeChart(
        analysisConfig({
          groups: spec.type === 'pie' ? groups.slice(1) : groups,
          chart: spec,
        }),
        rows,
      ) as ChartData;
      const { container, unmount } = render(
        <ViewSurface>
          <AnalysisChart data={data} spec={spec} />
        </ViewSurface>,
      );
      const css = container.querySelector('style')?.textContent ?? '';
      const legend = legendColours(container);
      unmount();
      return { css, legend };
    };

    const pie = drawn(pieSpec).legend;
    const split = drawn(splitSpec).legend;
    expect(pie).toEqual(Object.values(colors).map(asStyled));
    expect(split).toEqual(Object.values(colors).map(asStyled));
  });

  /**
   * A spec handed straight to the renderer never passed the kernel — the
   * stories do exactly that — so the colour is checked again here and an
   * unusable one falls back to the slot rather than reaching the stylesheet.
   */
  it('falls back to the palette when a colour is not one', () => {
    const hostile = 'red; } .fve-root { display: none';
    const { container } = render(
      <ViewSurface>
        <AnalysisChart
          data={cartesian}
          spec={{
            type: 'bar',
            cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
            colors: { orders: hostile },
            legend: 'top',
          }}
        />
      </ViewSurface>,
    );

    expect(container.innerHTML).not.toContain(hostile);
    expect(legendColours(container)).toEqual([asStyled('var(--chart-1)')]);
    expect(fills(container)).toContain('rgb(38, 117, 211)');
  });

  it('draws a scatter', () => {
    const { container } = chartOf({
      type: 'scatter',
      points: [{ category: 'CN', x: 1, y: 2, size: 3 }],
    });
    expect(container.querySelector('[data-slot="chart"]')).not.toBeNull();
  });

  describe('a scatter', () => {
    const regions: AnalysisView['columns'] = [
      { alias: 'region', label: 'Region', role: 'group' },
      { alias: 'orders', label: 'Orders', role: 'metric' },
      {
        alias: 'amount',
        label: 'Amount',
        role: 'metric',
        fn: 'SUM',
        numberFormat: { style: 'currency', currency: 'CNY' },
      },
    ];
    const spec: ChartSpec = {
      type: 'scatter',
      scatter: { category: 'region', x: 'orders', y: 'amount' },
    };
    const scatterOf = (points: { category: string; x: number; y: number }[]) =>
      render(
        <ViewSurface locale="zh-CN">
          <AnalysisChart
            data={{ type: 'scatter', points }}
            spec={spec}
            columns={regions}
          />
        </ViewSurface>,
      );
    // The drawing's texts: a point's name carries a halo, an axis title a
    // heavier weight, and a tick neither — centred under the plot on X,
    // right-aligned beside it on Y.
    const texts = (container: HTMLElement) => [
      ...container.querySelectorAll('[data-slot="chart-plot"] svg text'),
    ];
    const titled = (text: Element) =>
      /font-weight/.test(text.getAttribute('style') ?? '');
    const ticks = (container: HTMLElement, axis: 'x' | 'y') =>
      texts(container)
        .filter(
          text =>
            !text.hasAttribute('stroke') &&
            !titled(text) &&
            text.getAttribute('text-anchor') ===
              (axis === 'x' ? 'middle' : 'end'),
        )
        .map(tick => tick.textContent ?? '');

    /**
     * Counts from 0 to 2 used to tick at 0.5 and 1.5, which the count's own
     * format rounds: an axis reading 0 1 1 2 2 (the 2026-09-23 audit).
     */
    it('ticks a whole-numbered axis at whole numbers, each once', () => {
      const { container } = scatterOf([
        { category: 'East', x: 0, y: 1200 },
        { category: 'West', x: 1, y: 800 },
        { category: 'North', x: 2, y: 300 },
      ]);
      const xs = ticks(container, 'x');
      expect(xs.length).toBeGreaterThan(1);
      expect(xs.every(tick => /^\d+$/.test(tick))).toBe(true);
      expect(new Set(xs).size).toBe(xs.length);
    });

    it('titles each axis as the table heads its column', () => {
      const { container } = scatterOf([
        { category: 'East', x: 1, y: 1200 },
        { category: 'West', x: 3, y: 800 },
      ]);
      const titles = texts(container)
        .filter(titled)
        .map(title => title.textContent);
      expect(titles).toContain('Orders');
      expect(titles).toContain('Sum of Amount');
    });

    /**
     * A point is one group, and nothing about a dot says which: the tooltip
     * is headed by it, and a few points are named where they are drawn.
     */
    it('says which group a point is', () => {
      const { container } = scatterOf([
        { category: 'East', x: 1, y: 1200 },
        { category: 'West', x: 3, y: 800 },
      ]);
      expect(
        texts(container)
          .filter(text => text.hasAttribute('stroke'))
          .map(name => name.textContent)
          .sort(),
      ).toEqual(['East', 'West']);
      // The tooltip is headed by the group: test/scatterOption.test.ts.
    });

    /**
     * The point at the largest value sat on the plot's edge, half of it cut
     * by the chart's own box (the 2026-09-23 audit). The scale is laid out
     * even in jsdom, so where each point's centre falls against the grid is
     * a number here: at least a point's radius in from every edge.
     */
    it('keeps the extreme points off the plot’s edges', () => {
      const { container } = scatterOf([
        { category: 'East', x: 0, y: 0 },
        { category: 'West', x: 4, y: 4000 },
      ]);
      // The rules across the plot mark its edges; a point is a unit circle
      // placed and scaled by its transform.
      const rules = [
        ...container.querySelectorAll('[data-slot="chart-plot"] svg path'),
      ]
        .filter(path => path.getAttribute('fill') === 'none')
        .map(path =>
          (path.getAttribute('d') ?? '').match(/-?[\d.]+/g)!.map(Number),
        )
        .filter(numbers => numbers.length === 4);
      const flat = rules.filter(([, y1, , y2]) => y1 === y2);
      const upright = rules.filter(([x1, , x2]) => x1 === x2);
      const left = Math.min(...flat.map(([x1]) => x1));
      const right = Math.max(...flat.map(([, , x2]) => x2));
      const top = Math.min(...upright.map(([, y1, , y2]) => Math.min(y1, y2)));
      const bottom = Math.max(
        ...upright.map(([, y1, , y2]) => Math.max(y1, y2)),
      );
      const centres = [
        ...container.querySelectorAll('[data-slot="chart-plot"] svg path'),
      ]
        .filter(path => path.getAttribute('fill') === 'rgb(38, 117, 211)')
        .map(point => {
          const matrix = (point.getAttribute('transform') ?? '')
            .match(/-?[\d.]+/g)!
            .map(Number);
          return { x: matrix[4], y: matrix[5] };
        });
      expect(centres).toHaveLength(2);
      // A point is 10px across: a radius of 5 in from every edge.
      for (const { x, y } of centres) {
        expect(x - left).toBeGreaterThanOrEqual(5);
        expect(right - x).toBeGreaterThanOrEqual(5);
        expect(y - top).toBeGreaterThanOrEqual(5);
        expect(bottom - y).toBeGreaterThanOrEqual(5);
      }
    });

    it('names no point on the plot once there are many', () => {
      const { container } = scatterOf(
        Array.from({ length: 9 }, (_, index) => ({
          category: `R${index}`,
          x: index,
          y: index * 100,
        })),
      );
      expect(
        texts(container).filter(text => text.hasAttribute('stroke')),
      ).toHaveLength(0);
    });
  });

  it('draws a heatmap as a grid of cells', () => {
    const { container } = chartOf({
      type: 'heatmap',
      xs: ['Mon', 'Tue'],
      ys: ['CN', 'JP'],
      cells: [
        [1, 4],
        [2, null],
      ],
    });

    // A cell per value, none where nothing fell — a hole is no zero.
    expect(
      container
        .querySelector('[data-chart="heatmap"]')
        ?.getAttribute('data-marks'),
    ).toBe('3');
    expect(fills(container).length).toBeGreaterThanOrEqual(3);
    for (const name of ['Mon', 'Tue', 'CN', 'JP'])
      expect(
        within(container).getAllByText(name, DRAWN).length,
      ).toBeGreaterThan(0);
    // The hole is said in the reading table rather than drawn as zero.
    expect(
      within(
        container.querySelector<HTMLElement>('[data-slot="chart-reading"]')!,
      ).getAllByText('—').length,
    ).toBeGreaterThan(0);
  });

  it('labels every kind of category value', () => {
    const { container } = chartOf({
      type: 'heatmap',
      xs: [null, true, { id: 1 }],
      ys: [3],
      cells: [[1, 2, 3]],
    });

    // A boolean reads as the analysis table writes it, in the catalogue's words.
    const plot = container.querySelector<HTMLElement>(
      '[data-slot="chart-plot"]',
    )!;
    expect(within(plot).getByText('Yes', DRAWN)).toBeDefined();
    expect(within(plot).getByText('{"id":1}', DRAWN)).toBeDefined();
  });

  it('draws a funnel with its conversions', () => {
    chartOf({
      type: 'funnel',
      stages: [
        { label: 'Visited', value: 100 },
        { label: 'Bought', value: 25, conversion: 0.25 },
      ],
    });

    expect(screen.getByText('Visited', DRAWN)).toBeDefined();
    expect(screen.getByText('25%', DRAWN)).toBeDefined();
  });

  /**
   * A bare 「25%」 beside a bar reads as a share of the whole; the kernel
   * divides by the stage before unless the spec asks for the first. The
   * heading over the percentages says which, drawing and reading alike, and
   * the bars wear the palette's first slot rather than the button colour.
   */
  it('says what a funnel’s percentages are relative to, and paints from the palette', () => {
    const stages = [
      { label: 'Visited', value: 100, conversion: 1 },
      { label: 'Bought', value: 25, conversion: 0.25 },
    ];
    const funnel = (conversion?: 'first') =>
      render(
        <ViewSurface>
          <AnalysisChart
            data={{ type: 'funnel', stages }}
            spec={{
              type: 'funnel',
              funnel: {
                stages: { from: 'metrics', items: [] },
                ...(conversion ? { conversion } : {}),
              },
            }}
          />
        </ViewSurface>,
      );
    const heading = (container: HTMLElement) =>
      container.querySelector('[data-slot="funnel-conversion-heading"]')
        ?.textContent;

    const { container, unmount } = funnel();
    expect(heading(container)).toBe('Conversion from previous stage');
    // The reading table's column says the same words.
    expect(
      within(
        container.querySelector<HTMLElement>('[data-slot="chart-reading"]')!,
      ).getByText('Conversion from previous stage'),
    ).toBeDefined();
    // Both stages in the palette's first slot.
    expect(fills(container)).toEqual([
      'rgb(38, 117, 211)',
      'rgb(38, 117, 211)',
    ]);
    unmount();

    const first = funnel('first');
    expect(heading(first.container)).toBe('Conversion from first stage');
  });

  /**
   * A cumulative stage is not the number the table shows beside it, so the
   * drawing says what it is over the stages, and the reading table's value
   * column says the same words; a stage's own number needs no such note.
   */
  it('says a funnel accumulates when it does', () => {
    const drawn = (cumulative: boolean) =>
      render(
        <ViewSurface>
          <AnalysisChart
            data={{
              type: 'funnel',
              stages: [
                { label: 'Visited', value: 125 },
                { label: 'Bought', value: 25 },
              ],
              ...(cumulative ? { cumulative: true as const } : {}),
            }}
            spec={{
              type: 'funnel',
              funnel: {
                conversion: 'none',
                stages: {
                  from: 'group',
                  category: 'step',
                  value: 'orders',
                  order: ['Visited', 'Bought'],
                  cumulative,
                },
              },
            }}
          />
        </ViewSurface>,
      );
    const note = 'Cumulative: reached at least this stage';

    const { container, unmount } = drawn(true);
    const frame = container.querySelector('[data-chart="funnel"]')!;
    expect(frame.getAttribute('data-cumulative')).toBe('on');
    expect(
      container.querySelector('[data-slot="funnel-cumulative-note"]')
        ?.textContent,
    ).toBe(note);
    // No conversion was asked for, so the note stands alone.
    expect(
      container.querySelector('[data-slot="funnel-conversion-heading"]'),
    ).toBeNull();
    expect(
      within(
        container.querySelector<HTMLElement>('[data-slot="chart-reading"]')!,
      ).getByRole('columnheader', { name: note }),
    ).toBeDefined();
    unmount();

    const own = drawn(false).container;
    expect(
      own
        .querySelector('[data-chart="funnel"]')!
        .getAttribute('data-cumulative'),
    ).toBe('off');
    expect(
      own.querySelector('[data-slot="funnel-cumulative-note"]'),
    ).toBeNull();
    expect(
      within(
        own.querySelector<HTMLElement>('[data-slot="chart-reading"]')!,
      ).getByRole('columnheader', { name: 'Value' }),
    ).toBeDefined();
  });

  it('says no conversion where the spec asks for none', () => {
    const { container } = chartOf({
      type: 'funnel',
      stages: [
        { label: 'Visited', value: 100 },
        { label: 'Bought', value: 25 },
      ],
    });
    expect(
      container.querySelector('[data-slot="funnel-conversion-heading"]'),
    ).toBeNull();
  });

  /**
   * A metric stage with no name of its own used to be drawn as its alias —
   * `orders`, which names the query and nothing a reader recognises. The
   * name the analyst typed wins; with none typed the metric's column title
   * stands in, exactly as every slot on the options panel is named.
   */
  it('names a metric stage by its column until the analyst names it', () => {
    const spec = {
      type: 'funnel',
      funnel: {
        stages: {
          from: 'metrics',
          items: [{ metric: 'orders' }, { metric: 'orders', label: 'Bought' }],
        },
      },
    } as const;
    const { rerender } = render(
      <ViewSurface>
        <AnalysisChart
          data={{
            type: 'funnel',
            stages: [
              { label: 'orders', value: 9 },
              { label: 'Bought', value: 3 },
            ],
          }}
          spec={spec}
          columns={statuses}
        />
      </ViewSurface>,
    );

    expect(screen.getByText('Orders', DRAWN)).toBeDefined();
    expect(screen.getByText('Bought', DRAWN)).toBeDefined();
    expect(screen.queryByText('orders', DRAWN)).toBeNull();

    // No column for the alias either: the projection's own label is all
    // there is, and an alias on screen beats a blank stage.
    rerender(
      <ViewSurface>
        <AnalysisChart
          data={{ type: 'funnel', stages: [{ label: 'orders', value: 9 }] }}
          spec={spec}
        />
      </ViewSurface>,
    );
    expect(screen.getByText('orders', DRAWN)).toBeDefined();
  });

  it('lays a funnel out horizontally when the spec asks', () => {
    render(
      <ViewSurface>
        <AnalysisChart
          data={{
            type: 'funnel',
            stages: [{ label: 'Visited', value: 10 }],
          }}
          spec={{
            type: 'funnel',
            funnel: {
              stages: { from: 'metrics', items: [{ metric: 'visited' }] },
              orientation: 'horizontal',
            },
          }}
        />
      </ViewSurface>,
    );

    expect(screen.getByText('Visited', DRAWN)).toBeDefined();
  });

  it('draws a metric card with its comparison, target and trend', () => {
    chartOf({
      type: 'metric',
      value: 1200,
      compare: { value: 1000, delta: 200 },
      target: 2000,
      trend: [
        { x: '2026-09-01', value: 900 },
        { x: '2026-09-02', value: 1200 },
      ],
    });

    expect(screen.getByText('1,200', DRAWN)).toBeDefined();
    expect(screen.getByText('+200', DRAWN)).toBeDefined();
  });

  /**
   * `deltaOf` divides for `mode: 'percent'`, so the comparison is a ratio.
   * Printed as it stood, a quarter more than last week read as "+0.25".
   */
  it('reads a percent comparison as a percentage', () => {
    render(
      <ViewSurface>
        <AnalysisChart
          data={{
            type: 'metric',
            value: 0.4,
            compare: { value: 0.32, delta: 0.25 },
          }}
          spec={{
            type: 'metric',
            metric: {
              metric: 'rate',
              compare: { metric: 'previous', mode: 'percent' },
              format: 'percent',
            },
          }}
        />
      </ViewSurface>,
    );

    expect(screen.getByText('40%')).toBeDefined();
    expect(screen.getByText('+25%')).toBeDefined();
  });

  /**
   * A card's own format is still a number the surface prints, so it is
   * grouped in the surface's language — not in whatever the machine running
   * the page happens to speak, which is what `toLocaleString()` gave it.
   */
  it("prints a card's own format in the surface's language", () => {
    render(
      <ViewSurface locale="de-DE">
        <AnalysisChart
          data={{
            type: 'metric',
            value: 1234567,
            compare: { value: 1000000, delta: 234567 },
          }}
          spec={{
            type: 'metric',
            metric: {
              metric: 'amount',
              compare: { metric: 'previous', mode: 'delta' },
              format: 'auto',
            },
          }}
        />
      </ViewSurface>,
    );

    expect(screen.getByText('1.234.567')).toBeDefined();
    expect(screen.getByText('+234.567')).toBeDefined();
  });

  it('says so when a metric has no value at all', () => {
    chartOf({
      type: 'metric',
      value: null,
      compare: { value: null, delta: null },
    });

    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
  });
});
