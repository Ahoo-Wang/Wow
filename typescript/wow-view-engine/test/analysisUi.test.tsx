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
  AggregationDateUnit,
  AggregationFunction,
  AggregationGroupType,
} from '@ahoo-wang/fetcher-wow';
import {
  act,
  cleanup,
  fireEvent,
  render,
  renderHook,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AnalysisView, ChartData, ChartSpec } from '../src/index.js';
import {
  MemoryViewStore,
  shapeChart,
  ViewEngine,
  defaultRuntimeEnvironment,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { useAnalysisEditor, useOpenView } from '../src/react/index.js';
import {
  AnalysisChart,
  AnalysisTable,
  AnalysisWorkbench,
  ViewSurface,
} from '../src/ui/index.js';
import {
  ZONE,
  analysisConfig,
  deferred,
  namedOrdersDefinition,
  ordersDefinition,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

const analysisView: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'personal',
  revision: '1',
  config: analysisConfig(),
};

function setup(source: ViewSource = testSource()) {
  const store = new MemoryViewStore({ instances: [analysisView] });
  const engine = new ViewEngine({
    definitions: [ordersDefinition()],
    store,
    resolveSource: () => source,
  });
  return { engine, store, source };
}

describe('useAnalysisEditor', () => {
  async function editor() {
    const { engine } = setup();
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return { opened, analysis: useAnalysisEditor(opened.runtime) };
    });
    await waitFor(() => expect(result.current.opened.runtime).not.toBeNull());
    return result;
  }

  it('is inert without a runtime', () => {
    const { result } = renderHook(() => useAnalysisEditor(null));

    expect(result.current.groups).toEqual([]);
    expect(result.current.metrics).toEqual([]);
    expect(result.current.fields).toEqual([]);
    expect(result.current.countable).toBe(false);
    expect(() => {
      result.current.addGroup({
        type: 'TERMS',
        field: 'warehouse',
        alias: 'w',
      });
      result.current.removeGroup(0);
      result.current.addMetric({ type: 'COUNT', alias: 'c' });
      result.current.removeMetric(0);
      result.current.updateGroup(0, {});
      result.current.updateMetric(0, {});
      result.current.setLimit(10);
      result.current.setLayout('chart');
      result.current.setChartType('pie');
      result.current.updateChart({ legend: 'top' });
      result.current.setTotals(true);
      result.current.setSort([]);
      result.current.submit();
    }).not.toThrow();
  });

  it('offers only what the capability declares', async () => {
    const result = await editor();

    const warehouse = result.current.analysis.fields.find(
      field => field.field === 'warehouse',
    );
    const amount = result.current.analysis.fields.find(
      field => field.field === 'amount',
    );
    expect(warehouse?.groups).toEqual(['TERMS']);
    expect(amount?.functions).toEqual(['SUM']);
    expect(result.current.analysis.countable).toBe(true);
  });

  it('adds, changes and removes a group', async () => {
    const result = await editor();

    act(() =>
      result.current.analysis.addGroup({
        type: 'TERMS',
        field: 'amount',
        alias: 'amount_2',
      }),
    );
    expect(result.current.analysis.groups).toHaveLength(2);

    act(() =>
      result.current.analysis.updateGroup(1, { type: 'HISTOGRAM' } as never),
    );
    expect(result.current.analysis.groups[1].type).toBe('HISTOGRAM');

    act(() => result.current.analysis.removeGroup(1));
    expect(result.current.analysis.groups).toHaveLength(1);
  });

  it('keeps at least one metric', async () => {
    const result = await editor();

    act(() => result.current.analysis.removeMetric(0));

    expect(result.current.analysis.metrics).toHaveLength(1);
  });

  it('records limit, layout, totals and the chart family', async () => {
    const result = await editor();

    act(() => {
      result.current.analysis.setLimit(50);
      result.current.analysis.setLayout('chart');
      result.current.analysis.setTotals(true);
      result.current.analysis.setChartType('pie');
    });

    expect(result.current.analysis.limit).toBe(50);
    expect(result.current.analysis.layout).toBe('chart');
    expect(result.current.analysis.totals).toBe(true);
    expect(result.current.analysis.chart.type).toBe('pie');
    expect(result.current.analysis.aliases).toEqual({
      groups: ['warehouse'],
      metrics: ['orders'],
    });
  });

  it('pauses auto refresh while an editor holds focus', async () => {
    const result = await editor();

    act(() => result.current.analysis.focus());
    expect(result.current.opened.runtime?.getSnapshot().editing).toBe(true);

    act(() => result.current.analysis.blur());
    expect(result.current.opened.runtime?.getSnapshot().editing).toBe(false);
  });

  it('reports the issues that belong to the analysis', async () => {
    const result = await editor();

    act(() => result.current.analysis.setLimit(0));

    expect(
      result.current.analysis.issues.every(found =>
        found.code.startsWith('analysis.'),
      ),
    ).toBe(true);
    expect(result.current.analysis.issues.length).toBeGreaterThan(0);
  });
});

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

    expect(within(container).getByText('Failed')).toBeDefined();
    expect(within(container).queryByText('FAILED')).toBeNull();
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

    expect(within(container).getByText(amount)).toBeDefined();
    expect(within(container).getByText('Yes')).toBeDefined();
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

    expect(within(container).getByText('Failed')).toBeDefined();
    expect(within(container).getByText('Succeeded')).toBeDefined();
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

    expect(within(container).getAllByText('Same')).toHaveLength(4);
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

    expect(screen.getByText('Visited')).toBeDefined();
    expect(screen.getByText('25%')).toBeDefined();
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

    expect(screen.getByText('Visited')).toBeDefined();
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

    expect(screen.getByText('1,200')).toBeDefined();
    expect(screen.getByText('+200')).toBeDefined();
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

describe('AnalysisTable', () => {
  const view: AnalysisView = {
    columns: [
      { alias: 'warehouse', label: 'Warehouse', role: 'group' },
      {
        alias: 'orders',
        label: 'Orders',
        role: 'metric',
        numberFormat: { style: 'decimal' },
        width: 120,
      },
    ],
    rows: [
      { warehouse: 'CN', orders: 2 },
      { warehouse: 'JP', orders: null },
    ],
  };

  it('renders the rows and formats the numbers', () => {
    render(<AnalysisTable view={view} />);

    expect(screen.getByText('CN')).toBeDefined();
    expect(screen.getByText('2')).toBeDefined();
  });

  it('puts the totals row in the footer', () => {
    render(<AnalysisTable view={{ ...view, totals: { orders: 3 } }} />);

    expect(screen.getByText('Total')).toBeDefined();
    expect(screen.getByText('3')).toBeDefined();
  });

  it('renders every value shape a row can hold', () => {
    render(
      <AnalysisTable
        view={{
          columns: [
            { alias: 'warehouse', label: 'Warehouse', role: 'group' },
            { alias: 'plain', label: 'Plain', role: 'metric' },
            { alias: 'flag', label: 'Flag', role: 'metric' },
            { alias: 'blob', label: 'Blob', role: 'metric' },
          ],
          rows: [
            {
              warehouse: 'CN',
              plain: 7,
              flag: false,
              blob: { nested: true },
            },
          ],
        }}
      />,
    );

    expect(screen.getByText('7')).toBeDefined();
    expect(screen.getByText('No')).toBeDefined();
    expect(screen.getByText('{"nested":true}')).toBeDefined();
  });

  it('shows a date bucket as the day it starts, and an enum key by its label', () => {
    const day = Date.UTC(2026, 8, 18);
    render(
      <ViewSurface locale="en-GB" timeZone="UTC">
        <AnalysisTable
          view={{
            columns: [
              {
                alias: 'day',
                label: 'Day',
                role: 'group',
                kind: 'datetime',
                cell: 'datetime',
                dateUnit: 'DAY',
              },
              {
                alias: 'status',
                label: 'Status',
                role: 'group',
                kind: 'enum',
                cell: 'enum',
                options: [{ value: 'FAILED', label: 'Failed' }],
              },
              { alias: 'orders', label: 'Orders', role: 'metric' },
            ],
            rows: [{ day, status: 'FAILED', orders: 2 }],
          }}
        />
      </ViewSurface>,
    );

    expect(
      screen.getByText(
        new Intl.DateTimeFormat('en-GB', {
          dateStyle: 'medium',
          timeZone: 'UTC',
        }).format(day),
      ),
    ).toBeDefined();
    expect(screen.getByText('Failed')).toBeDefined();
    expect(screen.getByText('2')).toBeDefined();
  });

  it('says when there is nothing to aggregate', () => {
    render(<AnalysisTable view={{ ...view, rows: [] }} />);

    expect(screen.getByText('Nothing to aggregate')).toBeDefined();
  });
});

/** A capability that declares one of everything, so every default is reachable. */
function richDefinition() {
  return ordersDefinition({
    fields: [
      { name: 'id', label: 'Order', kind: 'string' },
      { name: 'warehouse', label: 'Warehouse', kind: 'string' },
      { name: 'createdAt', label: 'Created', kind: 'datetime' },
      { name: 'amount', label: 'Amount', kind: 'number' },
      { name: 'customer', label: 'Customer', kind: 'string' },
      { name: 'note', label: 'Note', kind: 'string' },
    ],
    analysis: {
      count: true,
      fields: [
        {
          field: 'warehouse',
          groups: [AggregationGroupType.TERMS],
          functions: [],
        },
        {
          field: 'createdAt',
          groups: [AggregationGroupType.DATE_HISTOGRAM],
          functions: [],
          dateUnits: [AggregationDateUnit.MONTH],
        },
        {
          field: 'amount',
          groups: [AggregationGroupType.HISTOGRAM],
          functions: [AggregationFunction.SUM, AggregationFunction.AVG],
        },
        {
          field: 'customer',
          groups: [],
          functions: [],
          distinctCount: true,
        },
        { field: 'note', groups: [], functions: [], any: true },
      ],
    },
  });
}

describe('AnalysisEditor defaults', () => {
  async function open() {
    const store = new MemoryViewStore({ instances: [analysisView] });
    const engine = new ViewEngine({
      definitions: [richDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    render(
      <AnalysisWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());
  }

  async function add(menu: RegExp, item: string) {
    fireEvent.click(screen.getByRole('button', { name: menu }));
    fireEvent.click(await screen.findByRole('menuitem', { name: item }));
  }

  it('starts each group at the shape its capability allows', async () => {
    await open();

    await add(/Add group/, 'Created');
    expect(
      (await screen.findByLabelText('createdAt_1 grouping')).textContent,
    ).toContain('date histogram');

    await add(/Add group/, 'Amount');
    expect(
      (await screen.findByLabelText('amount_1 grouping')).textContent,
    ).toContain('histogram');
  });

  it('picks the metric shape each field can support', async () => {
    await open();

    // A field with functions gets a NUMERIC metric, which is the only shape
    // that offers a function to choose.
    await add(/Add metric/, 'Amount');
    expect(await screen.findByLabelText('amount_1 function')).toBeDefined();

    await add(/Add metric/, 'Customer');
    await screen.findByRole('button', { name: 'Remove metric customer_1' });
    expect(screen.queryByLabelText('customer_1 function')).toBeNull();

    await add(/Add metric/, 'Note');
    await screen.findByRole('button', { name: 'Remove metric note_1' });
    expect(screen.queryByLabelText('note_1 function')).toBeNull();
  });

  it('adds the row count when the definition allows counting', async () => {
    await open();

    await add(/Add metric/, 'Row count');

    expect(
      await screen.findByRole('button', { name: 'Remove metric count_1' }),
    ).toBeDefined();
  });

  it('changes a metric function and removes rows again', async () => {
    const user = userEvent.setup();
    await open();

    await add(/Add metric/, 'Amount');
    await user.click(await screen.findByLabelText('amount_1 function'));
    await user.click(await screen.findByRole('option', { name: 'avg' }));
    await waitFor(() =>
      expect(screen.getByLabelText('amount_1 function').textContent).toContain(
        'avg',
      ),
    );

    fireEvent.click(
      screen.getByRole('button', { name: 'Remove metric amount_1' }),
    );
    await waitFor(() =>
      expect(
        screen.queryByRole('button', { name: 'Remove metric amount_1' }),
      ).toBeNull(),
    );

    fireEvent.click(
      screen.getByRole('button', { name: 'Remove group warehouse' }),
    );
    await waitFor(() =>
      expect(
        screen.queryByRole('button', { name: 'Remove group warehouse' }),
      ).toBeNull(),
    );
  });

  /**
   * Numbering by the row count reused a name the moment a row was removed:
   * two metrics called `amount_2`, which React saw as one key and validation
   * reported as a duplicate alias.
   */
  it('names a new row by the first free alias, not by the row count', async () => {
    await open();

    await add(/Add metric/, 'Amount');
    await screen.findByRole('button', { name: 'Remove metric amount_1' });
    await add(/Add metric/, 'Amount');
    await screen.findByRole('button', { name: 'Remove metric amount_2' });

    fireEvent.click(
      screen.getByRole('button', { name: 'Remove metric amount_1' }),
    );
    await waitFor(() =>
      expect(
        screen.queryByRole('button', { name: 'Remove metric amount_1' }),
      ).toBeNull(),
    );

    // The freed name comes back; the kept row keeps its own.
    await add(/Add metric/, 'Amount');
    expect(
      await screen.findByRole('button', { name: 'Remove metric amount_1' }),
    ).toBeDefined();
    expect(
      screen.getAllByRole('button', { name: 'Remove metric amount_2' }),
    ).toHaveLength(1);
  });

  it('refuses to remove the only metric', async () => {
    await open();

    expect(
      (
        screen.getByRole('button', {
          name: 'Remove metric orders',
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
  });

  it('takes a row limit and a totals request', async () => {
    const source = testSource();
    const store = new MemoryViewStore({ instances: [analysisView] });
    const engine = new ViewEngine({
      definitions: [richDefinition()],
      store,
      resolveSource: () => source,
    });
    render(
      <AnalysisWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());

    fireEvent.change(screen.getByLabelText('Row limit'), {
      target: { value: '25' },
    });
    fireEvent.click(screen.getByLabelText('Show totals'));
    fireEvent.click(screen.getByRole('button', { name: /Run/ }));

    await waitFor(() => {
      const calls = vi.mocked(source.aggregate).mock.calls;
      // Totals run their own ungrouped query, which carries no limit.
      expect(calls.some(call => call[0].limit === 25)).toBe(true);
      expect(calls.some(call => call[0].limit === undefined)).toBe(true);
    });
  });
});

describe('AnalysisWorkbench', () => {
  async function open(source: ViewSource = testSource()) {
    const harness = setup(source);
    render(
      <AnalysisWorkbench
        engine={harness.engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());
    return harness;
  }

  it('opens an analysis view and shows its table', async () => {
    await open();

    expect(screen.getByRole('button', { name: /Run/ })).toBeDefined();
    expect(
      screen.getByRole('columnheader', { name: 'Warehouse' }),
    ).toBeDefined();
    // The shell the three workbenches share: which view this is at the top,
    // and what the result was fetched under above it.
    expect(document.querySelector('[data-slot="view-header"]')).not.toBeNull();
    expect(
      screen.getByRole('region', { name: 'Showing' }).textContent,
    ).toContain('All records');
  });

  /**
   * Saving is the title bar's now, not a row of its own below the editor,
   * and what lands there has to reach the sidebar and the open view alike.
   */
  it('saves a copy from the title bar and opens it', async () => {
    const { store } = await open();

    fireEvent.click(screen.getByRole('button', { name: 'More view actions' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Save as' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Title'), {
      target: { value: 'Split by size' },
    });
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Create view' }),
    );

    await waitFor(async () =>
      expect(
        (await store.list('orders')).filter(
          item => item.title === 'Split by size',
        ),
      ).toHaveLength(1),
    );
    // The copy is what is open, and the sidebar says so.
    await waitFor(() =>
      expect(
        within(screen.getByRole('navigation')).getByRole('button', {
          name: 'Split by size',
        }).ariaCurrent,
      ).toBe('true'),
    );
  });

  // Its own alerts render above the provider of the surface it draws, yet
  // must read the wording it was handed, as everything inside that surface does.
  it("takes the host's wording, for its own alerts and everything inside", async () => {
    const { engine } = setup();

    render(
      <AnalysisWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="missing"
        messages={{
          'label.view.unopenable': '打不开这个视图',
          'label.scope.group.personal': '仅自己',
        }}
      />,
    );

    expect(await screen.findByText('打不开这个视图')).toBeDefined();
    // The sidebar is named by the definition rather than by the catalogue;
    // what it says about itself still comes from the wording handed in.
    expect(screen.getByRole('navigation', { name: 'Orders' })).toBeDefined();
    expect(screen.getByText('仅自己')).toBeDefined();
  });

  // A month cut in Kathmandu starts at 18:15 UTC on the last day of the month
  // before: read on any other clock, the bucket names the wrong month. In
  // Chinese it is also written unlike the runtime's own English.
  it("cuts and shows buckets on the clock of the engine's zone, in the language given", async () => {
    const october = Date.UTC(2026, 8, 30, 18, 15);
    const source = testSource({
      aggregate: vi.fn(() => Promise.resolve([{ month: october, orders: 2 }])),
    });
    const engine = new ViewEngine({
      definitions: [namedOrdersDefinition()],
      store: new MemoryViewStore({
        instances: [
          {
            ...analysisView,
            config: analysisConfig({
              groups: [
                {
                  alias: 'month',
                  field: 'createdAt',
                  type: 'DATE_HISTOGRAM',
                  unit: 'MONTH',
                },
              ],
              chart: {
                type: 'bar',
                cartesian: { x: 'month', series: [{ metric: 'orders' }] },
              },
            }),
          },
        ],
      }),
      resolveSource: () => source,
      environment: defaultRuntimeEnvironment({ timeZone: ZONE }),
    });

    render(
      <AnalysisWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        locale="zh-CN"
      />,
    );

    const month = new Intl.DateTimeFormat('zh-CN', {
      year: 'numeric',
      month: 'long',
      timeZone: ZONE,
    }).format(october);
    expect(await screen.findByText(month)).toBeDefined();
    expect(
      vi.mocked(source.aggregate).mock.calls[0][0].groupBy?.[0],
    ).toMatchObject({ timeZone: ZONE });
  });

  // Until Run the result on screen is the applied config's, so its categories
  // are named through the columns that config grouped by, not the draft's.
  it('names chart categories by the config that ran while the chart is edited', async () => {
    const engine = new ViewEngine({
      definitions: [namedOrdersDefinition()],
      store: new MemoryViewStore({
        instances: [
          { ...analysisView, config: analysisConfig({ layout: 'chart' }) },
        ],
      }),
      resolveSource: () => testSource(),
    });
    const { container } = render(
      <AnalysisWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    expect(await within(container).findByText('China')).toBeDefined();

    act(() => {
      engine.openRuntimes()[0].edit({
        chart: {
          type: 'bar',
          cartesian: { x: 'elsewhere', series: [{ metric: 'orders' }] },
        },
      });
    });

    expect(within(container).getByText('China')).toBeDefined();
  });

  it('names chart categories as their field names its values', async () => {
    // The table shows only the count; the chart still groups by warehouse,
    // and names its bars through the schema rather than the table's columns.
    const engine = new ViewEngine({
      definitions: [namedOrdersDefinition()],
      store: new MemoryViewStore({
        instances: [
          {
            ...analysisView,
            config: analysisConfig({
              layout: 'chart',
              table: { columns: [{ alias: 'orders' }] },
            }),
          },
        ],
      }),
      resolveSource: () => testSource(),
    });

    // Recharts measures text in a span of its own on the body.
    const { container } = render(
      <AnalysisWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    expect(await within(container).findByText('China')).toBeDefined();
  });

  it('holds the timer while the editor has focus', async () => {
    const { engine } = await open();
    const runtime = engine.openRuntimes()[0];
    const limit = screen.getByLabelText('Row limit');
    const run = screen.getByRole('button', { name: /Run/ });

    fireEvent.focus(limit, { relatedTarget: null });
    expect(runtime.getSnapshot().editing).toBe(true);

    // Between two controls of the editor: still editing.
    fireEvent.blur(limit, { relatedTarget: run });
    fireEvent.focus(run, { relatedTarget: limit });
    expect(runtime.getSnapshot().editing).toBe(true);

    fireEvent.blur(run, { relatedTarget: document.body });
    expect(runtime.getSnapshot().editing).toBe(false);
  });

  it('keeps the editor usable while an aggregation is still running', async () => {
    const waiting = deferred<Record<string, unknown>[]>();
    const { engine } = setup(testSource({ aggregate: () => waiting.promise }));
    render(
      <AnalysisWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    const limit = (await screen.findByLabelText(
      'Row limit',
    )) as HTMLInputElement;

    // The result is still coming; the inputs are not frozen for it.
    expect(limit.disabled).toBe(false);
    expect(
      screen.getByRole('button', { name: /Run/ }).hasAttribute('disabled'),
    ).toBe(false);
    expect(
      screen.getByRole('button', { name: /Apply/ }).hasAttribute('disabled'),
    ).toBe(false);

    waiting.resolve([{ warehouse: 'CN', orders: 2, amount_sum: 30 }]);
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());
  });

  it('adds a metric from what the capability offers', async () => {
    const { source } = await open();

    fireEvent.click(screen.getByRole('button', { name: /Add metric/ }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Row count' }));
    fireEvent.click(screen.getByRole('button', { name: /Run/ }));

    await waitFor(() => {
      const calls = vi.mocked(source.aggregate).mock.calls;
      expect(calls[calls.length - 1][0].metrics.length).toBe(2);
    });
  });

  it('switches to the chart and back', async () => {
    const user = userEvent.setup();
    await open();

    await user.click(screen.getByRole('button', { name: 'Chart' }));
    await waitFor(() => expect(screen.queryByRole('table')).toBeNull());

    await user.click(
      screen.getByRole('button', { name: 'Table', pressed: false }),
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());
  });

  it('reports a failed aggregation', async () => {
    await open(
      testSource({
        aggregate: vi
          .fn()
          .mockResolvedValueOnce([{ warehouse: 'CN', orders: 2 }])
          .mockRejectedValue(new Error('gateway down')),
      }),
    );

    fireEvent.click(screen.getByRole('button', { name: /Run/ }));

    await waitFor(() =>
      expect(
        screen
          .getAllByRole('alert')
          .some(alert =>
            (alert.textContent ?? '').includes('The source answered'),
          ),
      ).toBe(true),
    );
  });
});

/**
 * The workbench is pinned to the view the host named, and nothing reloads a
 * pin: when the manager deletes that view, the engine disposes the runtime
 * and reopening the id answers "no such view" for as long as the page is
 * open. Every workbench has to let go of its own accord — this one used to
 * sit on the not-found forever.
 */
describe('deleting the open analysis', () => {
  const other: ViewInstance = {
    ...analysisView,
    id: 'orders-2',
    title: 'Also mine',
  };

  it('moves on to the view that is still there', async () => {
    const store = new MemoryViewStore({ instances: [analysisView, other] });
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    render(
      <AnalysisWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'By warehouse' }).ariaCurrent,
      ).toBe('true'),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Manage views' }));
    const manager = await screen.findByRole('dialog');
    const row = Array.from(
      manager.querySelectorAll('[data-slot="view-manager-row"]'),
    ).find(candidate =>
      candidate.textContent?.includes('By warehouse'),
    ) as HTMLElement;
    fireEvent.click(within(row).getByRole('button', { name: 'Delete' }));
    const confirm = (await screen.findByText('Delete this view?')).closest(
      '[role="dialog"]',
    ) as HTMLElement;
    fireEvent.click(within(confirm).getByRole('button', { name: 'Delete' }));
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });

    await waitFor(
      () =>
        expect(
          screen.queryByRole('button', { name: 'By warehouse' }),
        ).toBeNull(),
      { timeout: 3000 },
    );
    // The pin is gone, so the list's default is what is open.
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'Also mine' }).ariaCurrent,
      ).toBe('true'),
    );
  });
});
