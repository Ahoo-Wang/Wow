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
import type { AnalysisView, ChartData, ChartSpec } from '../src/index.js';
import { shapeChart } from '../src/index.js';
import { AnalysisChart, ViewSurface } from '../src/ui/index.js';
import { TooltipValue } from '../src/ui/charts/TooltipValue.js';
import { DRAWN, analysisConfig } from './fixtures.js';

afterEach(cleanup);

function chartOf(data: ChartData) {
  return render(
    <ViewSurface>
      <AnalysisChart data={data} />
    </ViewSurface>,
  );
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

  // Recharts measures text in a span of its own on the body, so each query
  // looks inside the chart.
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
    const bounds = (container: HTMLElement) => {
      const line = container.querySelector('.recharts-reference-line-line');
      return {
        vertical: line?.getAttribute('x1') === line?.getAttribute('x2'),
      };
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

    expect(container.querySelectorAll('.recharts-yAxis')).toHaveLength(2);
    // The bounds the spec pinned, printed the way it asked for.
    expect(screen.getByText('900%')).toBeDefined();
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

    // A tick the domain pins and no row holds, so it can only be the axis.
    expect(screen.getByText('¥1,000.00')).toBeDefined();
    // The reading beside the marks is the same numbers, so the same text.
    expect(within(container).getAllByText('¥1,234.00').length).toBeGreaterThan(
      0,
    );
  });

  /**
   * The row a tooltip shows is drawn here rather than by the vendored
   * content, whose only hook replaces the whole row — see
   * `ui/charts/TooltipValue.tsx`. What each row reads it through is the
   * series' own metric, which is the same labeller the ticks and the reading
   * table go through, asserted above.
   */
  it('draws a tooltip row as the swatch, the series and the number', () => {
    render(
      <ViewSurface>
        <TooltipValue color="#0f766e" name="Amount" value="¥1,234.00" />
      </ViewSurface>,
    );

    expect(screen.getByText('Amount')).toBeDefined();
    expect(screen.getByText('¥1,234.00')).toBeDefined();
    expect(
      document.querySelector<HTMLElement>('[data-slot="chart-tooltip-swatch"]')
        ?.style.background,
    ).toBe('rgb(15, 118, 110)');
  });

  it('maps a data-valued series key to a synthetic one before CSS sees it', () => {
    // A pivot names its series by raw group values, which the style element
    // interpolates into custom properties; anything but an identifier breaks
    // the rule or breaks out of it.
    const hostile = 'a} *{background:url(//evil/)}';
    const { container } = chartOf({
      type: 'cartesian',
      chart: 'bar',
      points: [{ x: 'CN', values: { [hostile]: 2 } }],
      series: [{ key: hostile, label: hostile, metric: 'orders' }],
    });

    const css = container.querySelector('style')?.textContent ?? '';
    expect(css).not.toContain(hostile);
    // The series keeps its colour: the config carries a synthetic key.
    expect(css).toContain('--color-s0');
  });

  it('maps a pie category to a synthetic key before CSS sees it', () => {
    const hostile = 'x} *{color:red}';
    const { container } = chartOf({
      type: 'pie',
      slices: [{ category: hostile, value: 2 }],
    });

    const css = container.querySelector('style')?.textContent ?? '';
    expect(css).not.toContain(hostile);
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

    // recharts draws no sector in jsdom — the pie layer comes out empty — so
    // the colours are read where they are declared: the custom properties the
    // container emits, which are the same values the cells are given.
    const css = container.querySelector('style')?.textContent ?? '';
    expect(css).toContain('--color-p0: #eb6834;');
    expect(css).toContain('--color-p1: var(--chart-2);');
    expect(css).toContain('--color-p2: var(--chart-3);');
  });

  it('colours a cartesian series by its metric alias', () => {
    const { container } = render(
      <ViewSurface>
        <AnalysisChart
          data={cartesian}
          spec={{
            type: 'bar',
            cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
            colors: { orders: '#2a78d6' },
          }}
        />
      </ViewSurface>,
    );

    // The series is drawn from the custom property the container emits, so
    // that is where a configured colour has to land.
    const css = container.querySelector('style')?.textContent ?? '';
    expect(css).toContain('--color-s0: #2a78d6');
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

    const css = container.querySelector('style')?.textContent ?? '';
    expect(css).toContain('--color-s0: #eb6834');
    expect(css).toContain('--color-s1: var(--chart-2)');
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
          }}
        />
      </ViewSurface>,
    );

    const css = container.querySelector('style')?.textContent ?? '';
    expect(css).not.toContain(hostile);
    expect(css).toContain('--color-s0: var(--chart-1)');
  });

  it('draws a scatter', () => {
    const { container } = chartOf({
      type: 'scatter',
      points: [{ category: 'CN', x: 1, y: 2, size: 3 }],
    });
    expect(container.querySelector('[data-slot="chart"]')).not.toBeNull();
  });

  it('draws a heatmap as a grid of cells', () => {
    chartOf({
      type: 'heatmap',
      xs: ['Mon', 'Tue'],
      ys: ['CN', 'JP'],
      cells: [
        [1, 4],
        [2, null],
      ],
    });

    expect(screen.getByTitle('CN · Mon: 1')).toBeDefined();
    // A missing cell says so rather than pretending to be zero.
    expect(screen.getByTitle('JP · Tue: —')).toBeDefined();
  });

  it('labels every kind of category value', () => {
    chartOf({
      type: 'heatmap',
      xs: [null, true, { id: 1 }],
      ys: [3],
      cells: [[1, 2, 3]],
    });

    // A boolean reads as the analysis table writes it, in the catalogue's words.
    expect(screen.getByTitle('3 · Yes: 2')).toBeDefined();
    expect(screen.getByTitle('3 · {"id":1}: 3')).toBeDefined();
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

  it('says so when a metric has no value at all', () => {
    chartOf({
      type: 'metric',
      value: null,
      compare: { value: null, delta: null },
    });

    expect(screen.getAllByText('—').length).toBeGreaterThan(0);
  });
});
