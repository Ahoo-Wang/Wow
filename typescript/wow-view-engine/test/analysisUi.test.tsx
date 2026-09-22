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
import {
  MemoryViewStore,
  ViewEngine,
  defaultRuntimeEnvironment,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { useAnalysisEditor, useOpenView } from '../src/react/index.js';
import { AnalysisWorkbench, defaultMessages, zhCN } from '../src/ui/index.js';
import type { ViewMessages } from '../src/ui/index.js';
import { SPACE } from '../src/ui/layout.js';
import {
  DRAWN,
  ZONE,
  analysisConfig,
  deferred,
  namedOrdersDefinition,
  ordersDefinition,
  testSource,
} from './fixtures.js';
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

function setup(source: ViewSource = testSource()) {
  const store = tracked(new MemoryViewStore({ instances: [analysisView] }));
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

  /**
   * Everything in this editor waits for Run, and until then the table and
   * the chart answer the previous configuration. D17-6: that is "changed,
   * not applied", read off the whole draft against what ran.
   */
  it('reports the draft as pending until Run', async () => {
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
  async function open(messages?: ViewMessages) {
    const store = tracked(new MemoryViewStore({ instances: [analysisView] }));
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
        messages={messages}
      />,
    );
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());
  }

  async function add(menu: RegExp, item: string) {
    fireEvent.click(screen.getByRole('button', { name: menu }));
    fireEvent.click(await screen.findByRole('menuitem', { name: item }));
  }

  /** The Run button wears the dot the filter panel's Apply wears (D17-6). */
  it('marks Run while the draft has not run', async () => {
    await open();
    const run = () =>
      screen.getByRole('button', {
        name: defaultMessages['label.analysis.run'],
      });
    expect(run().hasAttribute('data-pending')).toBe(false);

    fireEvent.click(
      screen.getByRole('checkbox', {
        name: defaultMessages['label.analysis.totals'],
      }),
    );
    expect(run().hasAttribute('data-pending')).toBe(true);
    expect(run().querySelector('[data-slot="pending-dot"]')).not.toBeNull();

    fireEvent.click(run());
    await waitFor(() => expect(run().hasAttribute('data-pending')).toBe(false));
  });

  /**
   * The group and metric rows are rows inside a block, so they take the
   * ruler's row step.
   *
   * Vendored `FieldGroup` carries `gap-5` — 20px, which is not one of the
   * four steps and is wider than the 16px between two whole blocks, so a
   * block's own rows stood further apart than the blocks did. The seam is
   * closed at the call site because `ui/components/**` is upstream's.
   *
   * jsdom applies no stylesheet, so what is pinned here is the class the
   * call site passes; the 12px it resolves to is measured in the browser
   * project (`stories/view-engine/AnalysisWorkbench.test.stories.tsx`,
   * `EditorRowSpacing`).
   */
  it('puts the ruler’s row step between the editor rows', async () => {
    await open();

    const editor = screen.getByRole('region', { name: 'Analysis' });
    const rows = editor.querySelector<HTMLElement>(
      '[data-slot="field-group"]',
    )!;
    expect(rows.classList.contains(SPACE.ROWS)).toBe(true);
    expect(rows.classList.contains('gap-5')).toBe(false);
  });

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

  /**
   * The editor used to label these three controls with the identifier itself
   * — `bar`, `terms`, `sum` — so a host that handed over `zhCN` still got an
   * English analysis editor. They read the catalogue now, which is the only
   * place a translation can come from.
   */
  it('translates the chart type, the grouping and the function with zhCN', async () => {
    await open(zhCN);

    expect(screen.getByLabelText('图表类型').textContent).toContain('柱状图');
    expect(screen.getByLabelText('warehouse 分组方式').textContent).toContain(
      '按值分组',
    );

    await add(/添加指标/, 'Amount');
    expect(
      (await screen.findByLabelText('amount_1 函数')).textContent,
    ).toContain('求和');
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
    const store = tracked(new MemoryViewStore({ instances: [analysisView] }));
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
    // Named by the word beside it rather than by an `aria-label` of its own.
    fireEvent.click(screen.getByRole('checkbox', { name: 'Totals' }));
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

  /**
   * The analysis editor is the condition panel *and* the aggregation
   * editor, so there is no one fold to hang the mode on — the panel keeps
   * its own. Without it an analysis view could never reach OR, NOR or a
   * nested group, because `defaultAnalysisConfig` starts at simple.
   */
  it('reaches advanced mode from the panel, which keeps the control', async () => {
    await open();

    const panel = screen.getByRole('region', { name: 'Filter' });
    expect(
      within(panel).queryByRole('group', { name: 'All conditions' }),
    ).toBeNull();

    fireEvent.click(within(panel).getByRole('button', { name: 'Advanced' }));

    // Advanced draws the root as a group, which is what carries the
    // operator an analysis view would otherwise have no way to set.
    await waitFor(() =>
      expect(
        within(screen.getByRole('region', { name: 'Filter' })).getByRole(
          'group',
          { name: 'All conditions' },
        ),
      ).toBeDefined(),
    );
  });

  /**
   * D17-3. The analysis editor is the filter panel *and* the aggregation
   * editor, so two submit buttons stand on one screen; they are one
   * execution — both `submit`s call `runtime.apply()` — so only one of them
   * is a primary, and it is Apply. Pinned by the variant's class rather than
   * by colour: jsdom loads no stylesheet, so nothing here is painted.
   */
  it('carries one primary button on the screen, and it is Apply', async () => {
    await open();

    const primary = Array.from(
      document.querySelectorAll<HTMLElement>('[data-slot="button"]'),
    ).filter(button => button.classList.contains('bg-primary'));

    expect(primary.map(button => button.textContent?.trim())).toEqual([
      'Apply',
    ]);
    expect(
      screen
        .getByRole('button', { name: /Run/ })
        .classList.contains('border-border'),
    ).toBe(true);
  });

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
      <AnalysisWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    expect(await within(container).findByText('China', DRAWN)).toBeDefined();

    act(() => {
      engine.openRuntimes()[0].edit({
        chart: {
          type: 'bar',
          cartesian: { x: 'elsewhere', series: [{ metric: 'orders' }] },
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
      <AnalysisWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    expect(await within(container).findByText('China', DRAWN)).toBeDefined();
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
    const store = tracked(
      new MemoryViewStore({ instances: [analysisView, other] }),
    );
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
