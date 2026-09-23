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
import {
  CHART_TYPES,
  MemoryViewStore,
  ViewEngine,
  defaultRuntimeEnvironment,
  type DataViewDefinition,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { useAnalysisEditor, useOpenView } from '../src/react/index.js';
import { DataWorkbench, defaultMessages } from '../src/ui/index.js';
import {
  DRAWN,
  ZONE,
  analysisConfig,
  deferred,
  namedOrdersDefinition,
  ordersDefinition,
  testSource,
} from './fixtures.js';
import { openTray } from './fixtures/workbench.js';
import { landed, tracked } from './fixtures/writes.js';

afterEach(cleanup);

const analysisView: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'personal',
  revision: '1',
  config: analysisConfig(),
};

function setup(
  source: ViewSource = testSource(),
  definition: DataViewDefinition = ordersDefinition(),
) {
  const store = tracked(new MemoryViewStore({ instances: [analysisView] }));
  const engine = new ViewEngine({
    definitions: [definition],
    store,
    resolveSource: () => source,
  });
  return { engine, store, source };
}

describe('useAnalysisEditor', () => {
  async function editor(definition?: DataViewDefinition) {
    const { engine } = setup(testSource(), definition);
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
    expect(result.current.sortNow([])).toBe(false);
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

  /**
   * The whole spec, not just its `type`: a switch that changed the one word
   * and left the family absent reported `chart.family.missing` and drew
   * nothing, which is the first thing anybody who opened this editor hit.
   */
  it('records limit, layout, totals and the chart the type needs', async () => {
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
    expect(result.current.analysis.chart).toEqual({
      type: 'pie',
      pie: { category: 'warehouse', value: 'orders' },
      cartesian: { x: 'warehouse', series: [{ metric: 'orders' }] },
    });
    expect(result.current.analysis.issues).toEqual([]);
    expect(result.current.analysis.aliases).toEqual({
      groups: ['warehouse'],
      metrics: ['orders'],
    });
  });

  /**
   * A type is how the numbers are drawn, not which (audit P0-10): bars of
   * the total switched to a pie used to come back as a pie of the first
   * metric, the order count, with nothing on screen saying the number had
   * changed.
   */
  it('keeps the metric the chart measured across a type switch', async () => {
    const result = await editor();

    act(() => {
      result.current.analysis.addMetric({
        type: 'NUMERIC',
        alias: 'total',
        function: 'SUM',
        expression: { type: 'FIELD', field: 'amount' },
      });
      result.current.analysis.updateChart({
        cartesian: { x: 'warehouse', series: [{ metric: 'total' }] },
      });
    });
    act(() => result.current.analysis.setChartType('pie'));

    expect(result.current.analysis.chart.pie?.value).toBe('total');
    expect(result.current.analysis.issues).toEqual([]);

    act(() => result.current.analysis.setChartType('bar'));

    expect(result.current.analysis.chart.cartesian?.series[0]?.metric).toBe(
      'total',
    );
  });

  /** Every type the editor offers, switched into from the same draft. */
  it('leaves no chart type the editor offers without its family', async () => {
    const result = await editor();

    for (const type of CHART_TYPES) {
      act(() => result.current.analysis.setChartType(type));
      expect(
        result.current.analysis.issues.map(found => found.code),
      ).not.toContain('chart.family.missing');
    }
  });

  /**
   * A dimension added or removed used to leave the chart naming aliases that
   * were gone — `chart.group.unconsumed`, then `chart.group.unknown` — and
   * the sort naming one too. The one edit carries everything that points at
   * a dimension or a metric.
   */
  it('re-fits the chart and the sort when the shape changes', async () => {
    // A definition with a second groupable field, so a dimension can be
    // added at all.
    const result = await editor(namedOrdersDefinition());
    const analysis = () => result.current.analysis;

    act(() =>
      analysis().addGroup({
        type: 'DATE_HISTOGRAM',
        field: 'createdAt',
        alias: 'month',
        unit: 'MONTH',
      }),
    );
    // Two dimensions: the second is the split, and a pivot draws one metric.
    expect(analysis().chart.cartesian).toEqual({
      x: 'warehouse',
      splitBy: 'month',
      series: [{ metric: 'orders' }],
    });
    expect(analysis().issues).toEqual([]);

    act(() => analysis().removeGroup(1));
    expect(analysis().chart.cartesian).toEqual({
      x: 'warehouse',
      series: [{ metric: 'orders' }],
    });
    expect(analysis().issues).toEqual([]);

    // The last dimension leaving takes the ordering with it: Wow refuses a
    // sort over an ungrouped aggregation, which answers one row anyway.
    act(() => analysis().removeGroup(0));
    expect(analysis().sort).toEqual([]);
    expect(analysis().issues).toEqual([]);
  });

  /**
   * Everything in the tray waits for Apply, and until then the table and
   * the chart answer the previous configuration. D17-6: that is "changed,
   * not applied", read off the whole draft against what ran.
   */
  it('reports the draft as pending until it is applied', async () => {
    const result = await editor();
    const analysis = () => result.current.analysis;
    expect(analysis().pending).toBe(false);

    act(() => analysis().setLimit(50));
    expect(analysis().pending).toBe(true);

    act(() => analysis().submit());
    expect(analysis().pending).toBe(false);
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

describe('DataWorkbench', () => {
  async function open(source: ViewSource = testSource()) {
    const harness = setup(source);
    render(
      <DataWorkbench
        engine={harness.engine}
        definitionId="orders"
        instanceId="orders-1"
        kinds={['analysis']}
      />,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());
    return harness;
  }

  /** The tray's one submit: Apply runs the range and the question together. */
  function apply(): HTMLElement {
    return within(
      document.querySelector<HTMLElement>(
        '[data-slot="analysis-tray-actions"]',
      )!,
    ).getByRole('button', { name: defaultMessages['label.filter.apply'] });
  }

  /**
   * F12: a chart of no rows is a pair of empty axes, which reads as a drawing
   * that failed. Both layouts answer the same question, so an aggregation
   * that matched no group says the same one sentence either way — and the
   * sentence is about the range, not about the analysis.
   */
  it('says that no group matched, chart layout included', async () => {
    const store = tracked(new MemoryViewStore({ instances: [analysisView] }));
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => testSource({ aggregate: vi.fn(async () => []) }),
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        kinds={['analysis']}
      />,
    );

    expect(
      await screen.findByText(defaultMessages['label.analysis.empty']),
    ).toBeDefined();
    expect(screen.queryByRole('table')).toBeNull();
  });

  /**
   * F13, read the other way round since D20: the rows on screen are the
   * config that ran, and how they are looked at is the draft. A layout is
   * presentation (`ANALYSIS_PRESENTATION_MEMBERS`), so the switch redraws
   * the same rows as a chart and asks the source nothing.
   */
  it('redraws the layout from the rows on hand, without a run', async () => {
    const source = testSource({
      aggregate: vi.fn(() => Promise.resolve([{ warehouse: 'CN', orders: 2 }])),
    });
    const store = tracked(new MemoryViewStore({ instances: [analysisView] }));
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => source,
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        kinds={['analysis']}
      />,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());
    expect(source.aggregate).toHaveBeenCalledTimes(1);

    fireEvent.click(
      screen.getByRole('button', {
        name: defaultMessages['label.layout.chart'],
      }),
    );
    await waitFor(() =>
      expect(document.querySelector('[data-slot="chart"]')).not.toBeNull(),
    );
    expect(source.aggregate).toHaveBeenCalledTimes(1);
    // Nothing waits for a run: the draft differs from what ran only in how
    // it is looked at.
    expect(document.querySelector('[data-slot="pending-dot"]')).toBeNull();
  });

  it('opens an analysis view and shows its table', async () => {
    await open();

    // The tray is folded away — a saved view's author has already decided
    // what it asks — so what says this is an analysis view is its result.
    expect(document.querySelector('[data-slot="analysis-tray"]')).toBeNull();
    expect(
      document.querySelector('[data-slot="analysis-reading"]')?.textContent,
    ).toBe('By Warehouse · Record count');
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

    await landed(store);
    expect(
      (await store.list('orders')).filter(
        item => item.title === 'Split by size',
      ),
    ).toHaveLength(1);
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
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="missing"
        messages={{
          'label.view.unopenable': '打不开这个视图',
          'label.scope.group.personal': '仅自己',
        }}
        kinds={['analysis']}
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
      store: tracked(
        new MemoryViewStore({
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
      ),
      resolveSource: () => source,
      environment: defaultRuntimeEnvironment({ timeZone: ZONE }),
    });

    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        locale="zh-CN"
        kinds={['analysis']}
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

  // A dimension added in the tray but not yet run is no column of the rows
  // on screen, so the chart is fitted to the config that ran and its
  // categories are named through that config's columns, not the draft's.
  it('names chart categories by the config that ran while the question is edited', async () => {
    const engine = new ViewEngine({
      definitions: [namedOrdersDefinition()],
      store: tracked(
        new MemoryViewStore({
          instances: [
            { ...analysisView, config: analysisConfig({ layout: 'chart' }) },
          ],
        }),
      ),
      resolveSource: () => testSource(),
    });
    const { container } = render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        kinds={['analysis']}
      />,
    );
    expect(await within(container).findByText('China', DRAWN)).toBeDefined();

    act(() => {
      const runtime = engine.openRuntimes()[0];
      const draft = runtime.getSnapshot().draft;
      if (draft.kind !== 'analysis') throw new Error('not an analysis');
      runtime.edit({
        groups: [
          ...draft.groups,
          { type: 'TERMS', field: 'status', alias: 'status_1' },
        ],
        chart: {
          type: 'bar',
          cartesian: { x: 'status_1', series: [{ metric: 'orders' }] },
        },
      });
    });

    expect(within(container).getByText('China', DRAWN)).toBeDefined();
  });

  it('names chart categories as their field names its values', async () => {
    // The table shows only the count; the chart still groups by warehouse,
    // and names its bars through the schema rather than the table's columns.
    const engine = new ViewEngine({
      definitions: [namedOrdersDefinition()],
      store: tracked(
        new MemoryViewStore({
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
      ),
      resolveSource: () => testSource(),
    });

    // Recharts measures text in a span of its own on the body.
    const { container } = render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        kinds={['analysis']}
      />,
    );

    expect(await within(container).findByText('China', DRAWN)).toBeDefined();
  });

  it('holds the timer while the editor has focus', async () => {
    const { engine } = await open();
    await openTray();
    const runtime = engine.openRuntimes()[0];
    const limit = screen.getByLabelText('Top N groups');

    fireEvent.focus(limit, { relatedTarget: null });
    expect(runtime.getSnapshot().editing).toBe(true);

    // Between two controls of the tray: still editing.
    fireEvent.blur(limit, { relatedTarget: apply() });
    fireEvent.focus(apply(), { relatedTarget: limit });
    expect(runtime.getSnapshot().editing).toBe(true);

    fireEvent.blur(apply(), { relatedTarget: document.body });
    expect(runtime.getSnapshot().editing).toBe(false);
  });

  it('keeps the editor usable while an aggregation is still running', async () => {
    const waiting = deferred<Record<string, unknown>[]>();
    const { engine } = setup(testSource({ aggregate: () => waiting.promise }));
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        kinds={['analysis']}
      />,
    );
    await openTray();
    const limit = (await screen.findByLabelText(
      'Top N groups',
    )) as HTMLInputElement;

    // The result is still coming; the inputs are not frozen for it.
    expect(limit.disabled).toBe(false);
    expect(apply().hasAttribute('disabled')).toBe(false);

    waiting.resolve([{ warehouse: 'CN', orders: 2, amount_sum: 30 }]);
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());
  });

  it('adds a metric from what the capability offers', async () => {
    const { source } = await open();
    await openTray();

    fireEvent.click(screen.getByRole('button', { name: /Add metric/ }));
    fireEvent.click(
      await screen.findByRole('menuitem', { name: 'Record count' }),
    );
    fireEvent.click(apply());

    await waitFor(() => {
      const calls = vi.mocked(source.aggregate).mock.calls;
      expect(calls[calls.length - 1][0].metrics.length).toBe(2);
    });
  });

  it('switches to the chart and back', async () => {
    const user = userEvent.setup();
    await open();

    await user.click(screen.getByRole('button', { name: 'Chart' }));
    await waitFor(() =>
      expect(document.querySelector('[data-slot="analysis-table"]')).toBeNull(),
    );

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
    await openTray();

    fireEvent.click(apply());

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
    const store = tracked(
      new MemoryViewStore({ instances: [analysisView, other] }),
    );
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    render(
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        kinds={['analysis']}
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
    const confirm = await screen.findByRole('alertdialog');
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
