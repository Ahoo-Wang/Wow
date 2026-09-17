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
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AnalysisView, ChartData } from '../src/index.js';
import {
  MemoryViewStore,
  ViewEngine,
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
import { analysisConfig, ordersDefinition, testSource } from './fixtures.js';

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
    series: [{ key: 'orders', metric: 'orders' }],
  };

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

    expect(screen.getByTitle('3 · true: 2')).toBeDefined();
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
      (await screen.findByLabelText('createdAt_2 grouping')).textContent,
    ).toContain('date histogram');

    await add(/Add group/, 'Amount');
    expect(
      (await screen.findByLabelText('amount_3 grouping')).textContent,
    ).toContain('histogram');
  });

  it('picks the metric shape each field can support', async () => {
    await open();

    // A field with functions gets a NUMERIC metric, which is the only shape
    // that offers a function to choose.
    await add(/Add metric/, 'Amount');
    expect(await screen.findByLabelText('amount_2 function')).toBeDefined();

    await add(/Add metric/, 'Customer');
    await screen.findByRole('button', { name: 'Remove metric customer_3' });
    expect(screen.queryByLabelText('customer_3 function')).toBeNull();

    await add(/Add metric/, 'Note');
    await screen.findByRole('button', { name: 'Remove metric note_4' });
    expect(screen.queryByLabelText('note_4 function')).toBeNull();
  });

  it('adds the row count when the definition allows counting', async () => {
    await open();

    await add(/Add metric/, 'Row count');

    expect(
      await screen.findByRole('button', { name: 'Remove metric count_2' }),
    ).toBeDefined();
  });

  it('changes a metric function and removes rows again', async () => {
    const user = userEvent.setup();
    await open();

    await add(/Add metric/, 'Amount');
    await user.click(await screen.findByLabelText('amount_2 function'));
    await user.click(await screen.findByRole('option', { name: 'avg' }));
    await waitFor(() =>
      expect(screen.getByLabelText('amount_2 function').textContent).toContain(
        'avg',
      ),
    );

    fireEvent.click(
      screen.getByRole('button', { name: 'Remove metric amount_2' }),
    );
    await waitFor(() =>
      expect(
        screen.queryByRole('button', { name: 'Remove metric amount_2' }),
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
