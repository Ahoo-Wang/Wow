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
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PagedList } from '@ahoo-wang/fetcher-wow';
import {
  MemoryViewStore,
  ViewEngine,
  ViewStoreError,
  defaultRuntimeEnvironment,
  systemInstanceId,
} from '../src/index.js';
import type {
  ViewInstance,
  ViewSource,
  RecordData,
  ViewPermissions,
} from '../src/index.js';
import {
  defaultMessages,
  EmbeddedView,
  RecordWorkbench,
} from '../src/ui/index.js';
import type { RecordWorkbenchProps } from '../src/ui/index.js';
import { SPACE } from '../src/ui/layout.js';
import {
  INSTANT,
  ZONE,
  analysisConfig,
  dashboardConfig,
  deferred,
  inZone,
  namedOrdersDefinition,
  ROWS,
  ordersDefinition,
  overviewDefinition,
  recordConfig,
  testSource,
} from './fixtures.js';
import { mine, mixed, setup } from './fixtures/ui.js';

afterEach(cleanup);

/**
 * The handle of the editor's fold, in the title bar. Its accessible name
 * carries the mode in force — "Filter · Simple" — so it is matched by what
 * it starts with rather than by the whole of it.
 */
function editorToggle(): HTMLElement {
  return screen.getByRole('button', { name: /^Filter/ });
}

/**
 * Conditions added the way a user adds them: the field picker is a checklist
 * that stays open while several fields are ticked, and Done is the way out.
 */
async function addConditions(fields: string[]): Promise<void> {
  fireEvent.click(screen.getByRole('button', { name: 'Add' }));
  const picker = await screen.findByRole('dialog', {
    name: 'Choose filter fields',
  });
  for (const field of fields)
    fireEvent.click(within(picker).getByRole('checkbox', { name: field }));
  fireEvent.click(within(picker).getByRole('button', { name: 'Done' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
}

describe('RecordWorkbench', () => {
  /** A second view to switch to, so leaving one is a thing that can happen. */
  const yours: ViewInstance = { ...mine, id: 'orders-2', title: 'Yours' };

  function withTwo(permissions?: () => ViewPermissions): ViewEngine {
    return new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({
        instances: [mine, yours],
        permissions,
      }),
      resolveSource: () => testSource(),
    });
  }

  /**
   * The guard is built in the workbench, outside the surface that carries the
   * wording, so the labels it resolves are the ones in force *there* — the
   * defaults. Without the workbench handing its own `messages` over, one
   * dialog in a translated page stayed in English.
   */
  it('asks its leave question in the wording the host gave', async () => {
    const engine = withTwo();
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        messages={{ 'label.leave.heading': '离开这个视图？' }}
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    act(() => engine.openRuntimes()[0].edit({ pageSize: 30 }));

    fireEvent.click(screen.getByRole('button', { name: /^Yours/ }));

    const dialog = await screen.findByRole('dialog');
    expect(dialog.textContent).toContain('离开这个视图？');
  });

  /**
   * Leaving disposes the runtime an unsettled write belongs to, and the
   * engine goes on holding the write: a handle pointing at a runtime nobody
   * can reach again, and a slot held against the next write to that view.
   */
  it('settles an unknown write before it lets the view go', async () => {
    const engine = withTwo();
    const store = engine.store as MemoryViewStore;
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    vi.spyOn(store, 'save').mockRejectedValueOnce(new Error('socket closed'));
    act(() => engine.openRuntimes()[0].edit({ pageSize: 30 }));

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await screen.findByText('The result never came back');
    expect(engine.pendingWrites().size).toBe(1);

    fireEvent.click(screen.getByRole('button', { name: /^Yours/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Leave' }));

    await waitFor(() => expect(engine.pendingWrites().size).toBe(0));
  });

  /**
   * The manage button leads to a dialog of rows and the buttons each row
   * permits. With no permission anywhere it leads to a dialog of read-only
   * rows, which is a button whose only lesson is that it leads nowhere.
   */
  it('offers no way in to a manager with nothing to manage', async () => {
    const engine = withTwo(() => ({
      createPersonal: false,
      createShared: false,
      reorder: false,
      setDefault: false,
      instance: () => ({ save: false, rename: false, delete: false }),
    }));

    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    expect(screen.queryByRole('button', { name: 'Manage views' })).toBeNull();
  });

  it('keeps the way in when one row can still be renamed', async () => {
    const engine = withTwo(() => ({
      createPersonal: false,
      createShared: false,
      reorder: false,
      setDefault: false,
      instance: (id: string) => ({
        save: false,
        rename: id === 'orders-2',
        delete: false,
      }),
    }));

    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    expect(screen.getByRole('button', { name: 'Manage views' })).toBeDefined();
  });

  it('opens a view and shows its rows', async () => {
    const { engine } = setup();

    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    const order = () =>
      [...document.querySelectorAll('[data-slot]')]
        .map(node => node.getAttribute('data-slot'))
        .filter(slot =>
          [
            'view-header',
            'editor-band',
            'applied-bar',
            'result-toolbar',
            'record-pagination',
          ].includes(slot ?? ''),
        );

    // A saved view opens folded, and the fold is an unmount rather than a
    // hide: neither the band nor the way out of it is on the page.
    expect(order()).toEqual([
      'view-header',
      'applied-bar',
      'result-toolbar',
      'record-pagination',
    ]);
    expect(screen.queryByRole('button', { name: /Apply/ })).toBeNull();

    fireEvent.click(editorToggle());

    // Out, the page reads top to bottom: which view this is, the conditions,
    // what the rows were fetched under, the toolbar, the rows.
    expect(await screen.findByRole('button', { name: /Apply/ })).toBeDefined();
    expect(order()).toEqual([
      'view-header',
      'editor-band',
      'applied-bar',
      'result-toolbar',
      'record-pagination',
    ]);
    // The sidebar carries the definition's own title.
    expect(screen.getByRole('navigation', { name: 'Orders' })).toBeDefined();
  });

  /**
   * The main column keeps the shell's own step between its blocks.
   *
   * This workbench used to hand `WorkbenchShell` a `className="gap-2"`, and
   * `cn` let it beat `SPACE.BLOCKS`: the three blocks sat 8px apart while the
   * rows *inside* each of them sat 12px apart, so the ruler was upside down
   * and nothing read as a group. Analysis and Dashboard, on the same shell,
   * measured 16px — it was this one override, not the shell.
   *
   * What a class is worth in pixels is a stylesheet's answer and jsdom has
   * none, so the 16px itself is measured in the browser project
   * (`stories/view-engine/RecordWorkbench.test.stories.tsx`, `BlockSpacing`).
   * What is pinned here is the class that decides it.
   */
  it('leaves the block spacing to the shell', async () => {
    const { engine } = setup();

    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    const main = document.querySelector<HTMLElement>('main')!;
    expect(main.classList.contains(SPACE.BLOCKS)).toBe(true);
    expect(main.classList.contains(SPACE.GROUPS)).toBe(false);
  });

  /**
   * The workbench only ever looked for errors, so a warning the kernel took
   * the trouble to raise reached nobody. It must show, and it must not do
   * what an error does: the rows still come, and nothing says "fix this".
   */
  it('says what is worth noting without stopping the view', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mixed] }),
      resolveSource: () => testSource(),
    });

    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    const notice = document.querySelector('[data-slot="status-strip"]');
    expect(notice?.getAttribute('role')).toBe('status');
    expect(notice?.getAttribute('data-tone')).toBe('warning');
    // One finding, so the line is the finding rather than a count of them.
    expect(notice?.textContent).toContain('advanced editor');
    expect(screen.queryByText(/needs fixing/)).toBeNull();
  });

  // Its own alerts render above the provider of the surface it draws, yet
  // must read the wording it was handed, as everything inside that surface does.
  it("takes the host's wording, for its own alerts and everything inside", async () => {
    const { engine } = setup();

    render(
      <RecordWorkbench
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

  // What "today" filters by and what a row shows read the same clock.
  it("shows times on the clock of the engine's zone, in the language given", async () => {
    const engine = new ViewEngine({
      definitions: [namedOrdersDefinition()],
      store: new MemoryViewStore({
        instances: [
          {
            ...mine,
            config: recordConfig({
              table: { columns: [{ field: 'id' }, { field: 'createdAt' }] },
            }),
          },
        ],
      }),
      resolveSource: () =>
        testSource({
          paged: () =>
            Promise.resolve({
              total: 1,
              list: [{ id: 'o-1', createdAt: INSTANT }],
            }),
        }),
      environment: defaultRuntimeEnvironment({ timeZone: ZONE }),
    });

    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        locale="en-GB"
      />,
    );

    expect(await screen.findByText(inZone(INSTANT))).toBeDefined();
  });

  /**
   * One data definition holds record and analysis instances together, and
   * this page draws records. The sidebar used to offer both, so choosing an
   * analysis view left a header over an empty band — and a stored default of
   * that kind opened one without anybody choosing it.
   */
  describe('and the other kind of view in the same definition', () => {
    const chart: ViewInstance = {
      id: 'orders-chart',
      definitionId: 'orders',
      title: 'By warehouse',
      scope: 'personal',
      revision: '1',
      config: analysisConfig(),
    };

    function withBoth(): ViewEngine {
      return new ViewEngine({
        definitions: [ordersDefinition()],
        store: new MemoryViewStore({ instances: [mine, chart] }),
        resolveSource: () => testSource(),
      });
    }

    it('lists none of them, and opens a record view by default', async () => {
      render(<RecordWorkbench engine={withBoth()} definitionId="orders" />);

      await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
      const sidebar = within(
        document.querySelector<HTMLElement>('[data-slot="view-sidebar"]')!,
      );
      expect(sidebar.getByRole('button', { name: /^Mine/ })).toBeDefined();
      expect(
        sidebar.queryByRole('button', { name: /By warehouse/ }),
      ).toBeNull();
    });

    // A host names the id itself, so the list cannot keep this one out. It
    // opens, and the page says why it is not showing it.
    it('says why an id of the other kind cannot be shown here', async () => {
      render(
        <RecordWorkbench
          engine={withBoth()}
          definitionId="orders"
          instanceId="orders-chart"
        />,
      );

      const alert = await screen.findByText(/another kind/);
      expect(alert.textContent).toContain('analysis');
      expect(
        screen.getByText(defaultMessages['label.view.unopenable']),
      ).toBeDefined();
      // Not a blank body under a title bar: nothing of the view is drawn.
      expect(document.querySelector('[data-slot="view-header"]')).toBeNull();
      expect(screen.queryByRole('table')).toBeNull();
    });
  });

  it('shows an analysis view as its saved layout', async () => {
    const analysis: ViewInstance = {
      ...mine,
      config: analysisConfig({ layout: 'table' }),
    };
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [analysis] }),
      resolveSource: () => testSource(),
    });

    render(<EmbeddedView engine={engine} instanceId="orders-1" />);

    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy());
    expect(screen.queryByRole('button', { name: /Run/ })).toBeNull();
  });

  it('shows a dashboard as its grid of panels', async () => {
    const panelled: ViewInstance = {
      id: 'overview-1',
      definitionId: 'overview',
      title: 'Overview',
      scope: 'personal',
      revision: '1',
      config: dashboardConfig({
        panels: [
          {
            id: 'rows',
            kind: 'markdown',
            title: 'Note',
            content: '# Weekly review',
            layout: { x: 0, y: 0, w: 6, h: 2 },
          },
        ],
      }),
    };
    const engine = new ViewEngine({
      definitions: [ordersDefinition(), overviewDefinition()],
      store: new MemoryViewStore({ instances: [panelled] }),
      resolveSource: () => testSource(),
    });

    render(<EmbeddedView engine={engine} instanceId="overview-1" />);

    await waitFor(() =>
      expect(
        screen.getByRole('heading', { name: 'Weekly review' }),
      ).toBeTruthy(),
    );
  });

  it('reports a failed query inside the embed', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () =>
        testSource({ paged: () => Promise.reject(new Error('down')) }),
    });

    render(<EmbeddedView engine={engine} instanceId="orders-1" />);

    // The strip says what went wrong, not that something did.
    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain(
        'The source answered: down',
      ),
    );
  });

  /**
   * The condition has to reach the runtime before its opening query, not after
   * it. An order page embedding "this customer's shipments" that lets one
   * unscoped query out has already asked the server for everyone else's.
   */
  it('lets no unscoped query out when a scope is given', async () => {
    const { engine, source } = setup();

    render(
      <EmbeddedView
        engine={engine}
        instanceId="orders-1"
        scopeFilter={{
          op: 'and',
          children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
        }}
      />,
    );

    await waitFor(() =>
      expect(vi.mocked(source.paged).mock.calls.length).toBeGreaterThan(0),
    );

    for (const [query] of vi.mocked(source.paged).mock.calls)
      expect(JSON.stringify(query.filter)).toContain('CN');
  });

  it('runs nothing when the host condition is not admissible', async () => {
    const { engine, source } = setup();

    render(
      <EmbeddedView
        engine={engine}
        instanceId="orders-1"
        scopeFilter={{
          op: 'and',
          children: [{ field: 'nope', operator: 'EQ', value: 'x' }],
        }}
      />,
    );

    await waitFor(() => expect(screen.getByText(/needs fixing/i)).toBeTruthy());
    // Widening to every record would be the worst possible reading of a
    // condition the definition rejected.
    expect(vi.mocked(source.paged)).not.toHaveBeenCalled();
  });

  it('reports a saved config the definition no longer admits', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({
        instances: [{ ...mine, config: recordConfig({ pageSize: 0 }) }],
      }),
      resolveSource: () => testSource(),
    });

    render(<EmbeddedView engine={engine} instanceId="orders-1" />);

    // Not an empty table, and not a skeleton that never resolves.
    await waitFor(() => expect(screen.getByText(/needs fixing/i)).toBeTruthy());
  });

  it('honours the card layout the view was saved with', async () => {
    const cards: ViewInstance = {
      ...mine,
      config: recordConfig({ layout: 'card' }),
    };
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [cards] }),
      resolveSource: () => testSource(),
    });

    const { container } = render(
      <EmbeddedView engine={engine} instanceId="orders-1" />,
    );

    // An embed shows what was saved; it offers no layout switch of its own.
    await waitFor(() =>
      expect(
        container.querySelector('[data-slot="record-cards"]'),
      ).not.toBeNull(),
    );
    expect(container.querySelector('table')).toBeNull();
  });

  // A field is a Wow query path. Read as a key, `customer.city` names nothing,
  // and every nested column comes out blank — which is what a Wow snapshot,
  // whose state all sits under `state`, looked like in both layouts.
  it.each(['table', 'card'] as const)(
    'reads a nested field by its path in the %s layout',
    async layout => {
      const nested: ViewInstance = {
        ...mine,
        config: recordConfig({
          layout,
          table: { columns: [{ field: 'id' }, { field: 'customer.city' }] },
          card: { title: 'id', fields: ['customer.city'] },
        }),
      };
      const engine = new ViewEngine({
        definitions: [
          ordersDefinition({
            fields: [
              ...ordersDefinition().fields,
              { name: 'customer.name', label: 'Customer', kind: 'string' },
              { name: 'customer.city', label: 'City', kind: 'string' },
            ],
          }),
        ],
        store: new MemoryViewStore({ instances: [nested] }),
        resolveSource: () =>
          testSource({
            paged: vi.fn(() =>
              Promise.resolve({
                total: 1,
                list: [
                  {
                    id: 'o-1',
                    amount: 10,
                    customer: { name: 'Acme', city: 'Hangzhou' },
                  },
                ],
              }),
            ),
          }),
      });

      render(<EmbeddedView engine={engine} instanceId="orders-1" />);

      await waitFor(() => expect(screen.getByText('Hangzhou')).toBeDefined());
    },
  );

  /**
   * The states a reader actually meets when a source is slow or down. They
   * are easy to skip past with `waitFor`, and then the first time anyone sees
   * them is in production.
   */
  it('shows a placeholder while the first rows are still coming', async () => {
    const pending = deferred<PagedList<RecordData>>();
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => testSource({ paged: () => pending.promise }),
    });

    const { container } = render(
      <EmbeddedView engine={engine} instanceId="orders-1" />,
    );

    await waitFor(() =>
      expect(container.querySelector('[data-slot="skeleton"]')).not.toBeNull(),
    );

    pending.resolve({ total: 2, list: [...ROWS] });
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
  });

  it('shows an analysis as a chart when that is what was saved', async () => {
    const charted: ViewInstance = {
      ...mine,
      config: analysisConfig({ layout: 'chart' }),
    };
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [charted] }),
      resolveSource: () => testSource(),
    });

    const { container } = render(
      <EmbeddedView engine={engine} instanceId="orders-1" />,
    );

    await waitFor(() =>
      expect(container.querySelector('[data-slot="chart"]')).not.toBeNull(),
    );
  });

  it('reports a failed analysis query', async () => {
    const charted: ViewInstance = { ...mine, config: analysisConfig() };
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [charted] }),
      resolveSource: () =>
        testSource({ aggregate: () => Promise.reject(new Error('down')) }),
    });

    render(<EmbeddedView engine={engine} instanceId="orders-1" />);

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain(
        'The source answered: down',
      ),
    );
  });

  it('reports a view it cannot open', async () => {
    const { engine } = setup();

    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="missing"
      />,
    );

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain(
        'This view no longer exists.',
      ),
    );
  });

  it('reports a failed query', async () => {
    const { engine } = setup(
      testSource({
        paged: vi.fn(() => Promise.reject(new Error('gateway down'))),
      }),
    );

    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain(
        'The source answered: gateway down',
      ),
    );
  });
});

describe('RecordWorkbench interaction', () => {
  async function open(source: ViewSource = testSource()) {
    const harness = setup(source);
    render(
      <RecordWorkbench
        engine={harness.engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    // Every saved view opens with its conditions folded away; the tests
    // below are about what is inside the fold.
    fireEvent.click(editorToggle());
    await screen.findByRole('button', { name: /Apply/ });
    return harness;
  }

  it('adds a condition, edits it and applies it', async () => {
    const { source } = await open();

    await addConditions(['Warehouse']);

    const value = await screen.findByLabelText('Warehouse value');
    fireEvent.change(value, { target: { value: 'CN' } });
    fireEvent.click(screen.getByRole('button', { name: /Apply/ }));

    await waitFor(() => {
      const calls = vi.mocked(source.paged).mock.calls;
      expect(calls[calls.length - 1][0].filter).toMatchObject({
        field: 'warehouse',
        value: 'CN',
      });
    });
  });

  it('removes a condition and clears the tree', async () => {
    await open();

    await addConditions(['Warehouse']);
    await screen.findByLabelText('Warehouse value');

    fireEvent.click(screen.getByRole('button', { name: 'Remove Warehouse' }));
    expect(screen.queryByLabelText('Warehouse value')).toBeNull();

    await addConditions(['Warehouse']);
    await screen.findByLabelText('Warehouse value');
    fireEvent.click(screen.getByRole('button', { name: 'Clear' }));

    expect(screen.queryByLabelText('Warehouse value')).toBeNull();
  });

  it('switches to cards and back', async () => {
    await open();

    fireEvent.click(screen.getByRole('button', { name: 'Cards' }));

    await waitFor(() => expect(screen.queryByRole('table')).toBeNull());
    expect(screen.getAllByRole('checkbox').length).toBeGreaterThan(0);

    fireEvent.click(screen.getByRole('button', { name: 'Table' }));
    await waitFor(() => expect(screen.getByRole('table')).toBeDefined());
  });

  it('selects rows and reports the count', async () => {
    await open();

    fireEvent.click(screen.getByLabelText('Select all rows'));

    await waitFor(() => expect(screen.getByText('2 selected')).toBeDefined());

    fireEvent.click(screen.getByLabelText('Select o-1'));
    await waitFor(() => expect(screen.getByText('1 selected')).toBeDefined());
  });

  it('sorts by a column and pages forward', async () => {
    // A total larger than one page: the toolbar disables Next at the end of
    // the result, so paging forward needs somewhere to go.
    const { source } = await open(
      testSource({
        paged: vi.fn(() => Promise.resolve({ total: 50, list: [...ROWS] })),
      }),
    );

    fireEvent.click(screen.getByRole('button', { name: /Amount/ }));
    await waitFor(() => {
      const calls = vi.mocked(source.paged).mock.calls;
      expect(calls[calls.length - 1][0].sort).toEqual([
        { field: 'amount', direction: 'ASC' },
      ]);
    });

    fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => {
      const calls = vi.mocked(source.paged).mock.calls;
      expect(calls[calls.length - 1][0].pagination).toMatchObject({ index: 2 });
    });

    fireEvent.click(screen.getByRole('button', { name: 'Previous page' }));
    await waitFor(() => {
      const calls = vi.mocked(source.paged).mock.calls;
      expect(calls[calls.length - 1][0].pagination).toMatchObject({ index: 1 });
    });
  });

  /**
   * Next used to be live on every paged result, so the page after the last
   * one was an ordinary click away — and what came back was an empty table
   * with no way to tell it from a filter that matched nothing.
   */
  it('stops Next at the last page', async () => {
    const { source } = await open();
    const before = vi.mocked(source.paged).mock.calls.length;

    const next = screen.getByRole('button', { name: 'Next page' });
    expect(next.hasAttribute('disabled')).toBe(true);

    fireEvent.click(next);
    expect(vi.mocked(source.paged).mock.calls).toHaveLength(before);
  });

  it('hides a column from the picker', async () => {
    await open();

    fireEvent.click(screen.getByRole('button', { name: /Columns/ }));
    fireEvent.click(
      await screen.findByRole('checkbox', { name: 'Show Amount' }),
    );

    await waitFor(() =>
      expect(screen.getAllByRole('columnheader')).toHaveLength(2),
    );
  });

  /**
   * The mode is a way of editing rather than part of the filter, so it is a
   * menu on the editor's own toggle rather than a row inside the panel.
   */
  it('switches the filter editor to advanced mode', async () => {
    const user = userEvent.setup();
    await open();

    await user.click(screen.getByRole('button', { name: 'Editor options' }));
    await user.click(
      await screen.findByRole('menuitemradio', { name: 'Advanced' }),
    );

    // The toggle's own name is the mode in force, said without the menu
    // being opened a second time.
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'Filter · Advanced' }),
      ).toBeDefined(),
    );
    // And the panel below is the advanced editor: a group with an operator.
    expect(
      screen.getByRole('combobox', { name: 'Group operator' }),
    ).toBeDefined();
  });

  it('keeps the filter editable while a query is still running', async () => {
    const pending = deferred<PagedList<RecordData>>();
    const { engine } = setup(testSource({ paged: () => pending.promise }));
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    fireEvent.click(await screen.findByRole('button', { name: /^Filter/ }));
    const apply = await screen.findByRole('button', { name: /Apply/ });

    // The rows are still coming. Typing never re-queries and the next apply
    // supersedes the request in flight, so nothing here has to wait for it.
    expect(apply.hasAttribute('disabled')).toBe(false);
    await addConditions(['Warehouse']);
    const value = await screen.findByLabelText('Warehouse value');
    expect((value as HTMLInputElement).disabled).toBe(false);
    fireEvent.change(value, { target: { value: 'CN' } });
    expect((value as HTMLInputElement).value).toBe('CN');

    pending.resolve({ total: 2, list: [...ROWS] });
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
  });

  it('refreshes on demand', async () => {
    const { source } = await open();
    const before = vi.mocked(source.paged).mock.calls.length;

    fireEvent.click(screen.getByRole('button', { name: /Refresh/ }));

    await waitFor(() =>
      expect(vi.mocked(source.paged).mock.calls.length).toBe(before + 1),
    );
  });

  it('refreshes the list once a save-as lands in the store', async () => {
    await open();

    fireEvent.click(screen.getByRole('button', { name: 'More view actions' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Save as' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Title'), {
      target: { value: 'My copy' },
    });
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Create view' }),
    );

    // The copy joins the sidebar rather than waiting for a remount.
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'My copy' })).toBeDefined(),
    );
  });
});

describe('save actions', () => {
  async function open() {
    const harness = setup();
    render(
      <RecordWorkbench
        engine={harness.engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    return harness;
  }

  /**
   * Save as lives in the split button's menu whenever saving in place is also
   * allowed: one button says the thing to do now, the menu holds the rest.
   */
  async function openSaveAs() {
    fireEvent.click(screen.getByRole('button', { name: 'More view actions' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Save as' }));
    return screen.findByRole('dialog');
  }

  /** An edit the toolbar can make, so the draft has something to save. */
  async function dropAColumn() {
    fireEvent.click(screen.getByRole('button', { name: /Columns/ }));
    fireEvent.click(
      await screen.findByRole('checkbox', { name: 'Show Warehouse' }),
    );
    await waitFor(() =>
      expect(screen.getAllByRole('columnheader')).toHaveLength(4),
    );
    // The column menu stays open after a pick; close it before the next
    // click, which the open menu would swallow.
    fireEvent.keyDown(document.body, { key: 'Escape' });
  }

  it('has nothing to save until the view is edited', async () => {
    await open();

    const save = screen.getByRole('button', { name: 'Save' });
    expect(save.hasAttribute('disabled')).toBe(true);

    await dropAColumn();
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: 'Save' }).hasAttribute('disabled'),
      ).toBe(false),
    );
  });

  it('saves the open view in place', async () => {
    const { store } = await open();
    await dropAColumn();

    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(async () =>
      expect((await store.get('orders-1')).revision).toBe('2'),
    );
    // The landing is said once, on the button that was pressed and to a
    // screen reader, which does not re-read a control it already announced.
    expect(await screen.findByRole('status')).toBeDefined();
    expect(screen.getByRole('status').textContent).toBe('View saved');
  });

  it('takes the edits back to the last saved config', async () => {
    await open();
    await dropAColumn();

    fireEvent.click(screen.getByRole('button', { name: 'More view actions' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Revert' }));

    await waitFor(() =>
      expect(screen.getAllByRole('columnheader')).toHaveLength(3),
    );
  });

  it('saves a copy under a new title', async () => {
    const { store } = await open();

    const dialog = await openSaveAs();
    fireEvent.change(within(dialog).getByLabelText('Title'), {
      target: { value: 'Pending only' },
    });
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Create view' }),
    );

    await waitFor(async () =>
      expect((await store.list('orders')).map(item => item.title)).toContain(
        'Pending only',
      ),
    );
  });

  it('opens on a copy of the title it was asked from', async () => {
    await open();

    const dialog = await openSaveAs();
    expect(
      (within(dialog).getByLabelText('Title') as HTMLInputElement).value,
    ).toBe('Mine copy');
  });

  it('saves a copy everyone can see', async () => {
    const { store } = await open();

    const dialog = await openSaveAs();
    fireEvent.change(within(dialog).getByLabelText('Title'), {
      target: { value: 'Ours' },
    });
    fireEvent.click(within(dialog).getByRole('radio', { name: 'Everyone' }));
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Create view' }),
    );

    await waitFor(async () =>
      expect(
        (await store.list('orders')).find(item => item.title === 'Ours')?.scope,
      ).toBe('shared'),
    );
  });

  /**
   * Save as defaulted to "Only me" whatever the store allowed, so a user who
   * may only publish shared views pressed Save and was refused by the engine
   * for a scope the dialog had picked on their behalf. The option stays on
   * offer — disabled, and saying why — because a scope that simply vanished
   * reads as one this view cannot have rather than one this user cannot make.
   */
  it('offers only the audience the user may create in', async () => {
    const store = new MemoryViewStore({
      instances: [mine],
      permissions: () => ({
        createPersonal: false,
        createShared: true,
        reorder: true,
        setDefault: true,
        instance: () => ({ save: true, rename: true, delete: true }),
      }),
    });
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    const dialog = await openSaveAs();
    const onlyMe = within(dialog).getByRole('radio', { name: 'Only me' });
    expect(onlyMe.getAttribute('aria-disabled')).toBe('true');
    expect(dialog.textContent).toContain('(no permission to create)');
    expect(
      within(dialog).getByRole('radio', { name: 'Everyone' }).ariaChecked,
    ).toBe('true');

    fireEvent.change(within(dialog).getByLabelText('Title'), {
      target: { value: 'Ours' },
    });
    fireEvent.click(
      within(dialog).getByRole('button', { name: 'Create view' }),
    );

    await waitFor(async () =>
      expect(
        (await store.list('orders')).find(item => item.title === 'Ours')?.scope,
      ).toBe('shared'),
    );
  });

  /**
   * A system view is nobody's to write to, so there is no button group at
   * all — only the copy that is the one thing that can be done with it.
   */
  it('offers only a copy of a view nobody may write to', async () => {
    const { engine } = setup();
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId={systemInstanceId('orders', 'all')}
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    expect(screen.queryByRole('button', { name: 'Save' })).toBeNull();
    expect(
      screen.queryByRole('button', { name: 'More view actions' }),
    ).toBeNull();
    expect(screen.getByRole('button', { name: 'Save as' })).toBeDefined();
  });
});

/**
 * Renaming, deleting, reordering and the default view happen in the sidebar's
 * manager rather than beside the save button — they are about the list, not
 * about the view on screen. What the workbench still owes is to follow: the
 * view it has open may be the one that just went.
 */
describe('managing views from the workbench', () => {
  /** One row of the manager, by the title it shows. */
  function row(title: string): HTMLElement {
    const found = Array.from(
      document.querySelectorAll('[data-slot="view-manager-row"]'),
    ).find(
      candidate =>
        candidate.textContent?.includes(title) ||
        Array.from(candidate.querySelectorAll('input')).some(field =>
          field.value.includes(title),
        ),
    );
    if (!found) throw new Error(`no row for ${title}`);
    return found as HTMLElement;
  }

  /**
   * The manager is a modal: everything behind it is inert, the sidebar
   * included. A test that looks at what the workbench did has to shut it.
   */
  function close() {
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' });
  }

  /** Opens the manager from the sidebar and waits for it to draw. */
  async function manage() {
    fireEvent.click(screen.getByRole('button', { name: 'Manage views' }));
    await screen.findByRole('dialog');
  }

  /** The manager's delete, through its confirmation. */
  async function remove(title: string) {
    await manage();
    fireEvent.click(within(row(title)).getByRole('button', { name: 'Delete' }));
    const confirm = (await screen.findByText('Delete this view?')).closest(
      '[role="dialog"]',
    ) as HTMLElement;
    fireEvent.click(within(confirm).getByRole('button', { name: 'Delete' }));
  }

  it('moves on to the next view once the open default is deleted', async () => {
    // No explicit instance and the personal view is the default: the id the
    // workbench opened came from the list, so a delete has nothing to unpin.
    // The engine disposed the runtime with the instance; what is on screen
    // must follow, and the list must stop offering the view.
    const store = new MemoryViewStore({
      instances: [mine],
      preferences: {
        orders: { order: [], defaultInstanceId: 'orders-1', revision: '0' },
      },
    });
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    render(<RecordWorkbench engine={engine} definitionId="orders" />);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Mine' }).ariaCurrent).toBe(
        'true',
      ),
    );

    await remove('Mine');
    close();

    // The system view is what is left, and it is the one open now.
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: /All orders/ }).ariaCurrent,
      ).toBe('true'),
    );
    expect(screen.queryByRole('button', { name: 'Mine' })).toBeNull();
    expect(screen.getAllByRole('row')).toHaveLength(3);
  });

  /**
   * The view the workbench was *pinned* to, rather than riding on: nothing
   * reloads the pin, so the workbench has to notice that reopening it now
   * answers "no such view" and let go of its own accord.
   */
  it('lets the pinned view go when the manager deletes it', async () => {
    const other: ViewInstance = { ...mine, id: 'orders-2', title: 'Other' };
    const store = new MemoryViewStore({ instances: [mine, other] });
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Mine' }).ariaCurrent).toBe(
        'true',
      ),
    );

    await remove('Mine');
    close();

    await waitFor(
      () => expect(screen.queryByRole('button', { name: 'Mine' })).toBeNull(),
      { timeout: 3000 },
    );
    // The pin is gone, so the list's default is what is open.
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: /All orders/ }).ariaCurrent,
      ).toBe('true'),
    );
  });

  it('shows the empty state once the last view is deleted', async () => {
    const store = new MemoryViewStore({ instances: [mine] });
    const engine = new ViewEngine({
      definitions: [ordersDefinition({ views: [] })],
      store,
      resolveSource: () => testSource(),
    });
    render(<RecordWorkbench engine={engine} definitionId="orders" />);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    await remove('Mine');

    // The runtime goes at once — disposal notifies — and the list a moment
    // later, once it has reloaded without the deleted view.
    await waitFor(() => expect(screen.queryByRole('table')).toBeNull());
    expect(await screen.findByText('No view yet')).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Mine' })).toBeNull();
  });

  it('lets go of the view once a recovered delete lands', async () => {
    const { engine, store } = setup();
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    vi.spyOn(store, 'delete').mockRejectedValueOnce(new Error('socket closed'));

    await remove('Mine');

    // The row that raised the write is the one that says what became of it.
    const outcome = await screen.findByRole('status');
    expect(outcome.textContent).toContain('never came back');

    fireEvent.click(within(outcome).getByRole('button', { name: 'Retry' }));
    close();

    // The store has it now; the workbench follows: the view stops rendering
    // and the list drops the entry once its reload lands.
    await waitFor(
      () => {
        expect(screen.queryByRole('table')).toBeNull();
        expect(screen.queryByRole('button', { name: 'Mine' })).toBeNull();
      },
      { timeout: 3000 },
    );
  });

  it('keeps the view open when a delete conflict ends in a reload', async () => {
    // Another view is the default, so a pin dropped on the reload would
    // reopen that one instead: nothing here says the user left this view.
    const other: ViewInstance = { ...mine, id: 'other-1', title: 'Other' };
    const store = new MemoryViewStore({ instances: [other, mine] });
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    // The server moved on; taking their version adopts the existing
    // instance, it does not delete it.
    const moved = await store.save(
      'orders-1',
      recordConfig({ pageSize: 30 }),
      '1',
      { requestId: 'other' },
    );
    vi.spyOn(store, 'delete').mockImplementationOnce(() =>
      Promise.reject(new ViewStoreError('CONFLICT', 'moved', moved)),
    );

    await remove('Mine');

    const outcome = await screen.findByRole('alert');
    fireEvent.click(
      within(outcome).getByRole('button', { name: 'Reload list' }),
    );

    // The dialog is in the way of the sidebar; close it and look.
    close();
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Mine' }).ariaCurrent).toBe(
        'true',
      ),
    );
    expect(screen.getByRole('table')).toBeDefined();
  });

  it('keeps unsaved edits across a rename of the default view', async () => {
    // The default has to be the personal view: the first list entry is the
    // code-declared system view, which nobody may rename.
    const store = new MemoryViewStore({
      instances: [mine],
      preferences: {
        orders: { order: [], defaultInstanceId: 'orders-1', revision: '0' },
      },
    });
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    // No explicit instance: the workbench rides on the default view, so
    // nothing is pinned and a list reload must not close the runtime.
    render(<RecordWorkbench engine={engine} definitionId="orders" />);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    fireEvent.click(screen.getByRole('button', { name: /Columns/ }));
    fireEvent.click(
      await screen.findByRole('checkbox', { name: 'Show Warehouse' }),
    );
    // The header count includes the select-all column.
    await waitFor(() =>
      expect(screen.getAllByRole('columnheader')).toHaveLength(4),
    );
    // The column menu stays open after a checkbox pick; close it before the
    // next click, which the open menu would swallow.
    fireEvent.keyDown(document.body, { key: 'Escape' });

    await manage();
    fireEvent.click(
      within(row('Mine')).getByRole('button', { name: 'Rename' }),
    );
    fireEvent.change(within(row('Mine')).getByLabelText('Title'), {
      target: { value: 'Renamed' },
    });
    fireEvent.click(
      within(row('Renamed')).getByRole('button', { name: 'Save the title' }),
    );

    // The rename refreshed the list; the unsaved column edit survived it.
    await waitFor(async () =>
      expect((await store.get('orders-1')).title).toBe('Renamed'),
    );
    close();
    await waitFor(() =>
      expect(screen.getAllByRole('columnheader')).toHaveLength(4),
    );
  });
});

/**
 * The result is the point of a record view, so everything above it earns its
 * height: the editor folds away, findings are lines rather than banners, and
 * the one thing that always shows is what the rows on screen were asked for.
 */
describe('the record workbench layout', () => {
  /** A view whose saved condition the applied bar has something to say about. */
  const filtered: ViewInstance = {
    ...mine,
    config: recordConfig({
      filter: {
        op: 'and',
        children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
      },
    }),
  };

  function workbench(
    instances: ViewInstance[],
    props: Partial<RecordWorkbenchProps> = {},
    source: ViewSource = testSource(),
  ) {
    const store = new MemoryViewStore({ instances });
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store,
      resolveSource: () => source,
    });
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId={instances[0]?.id ?? null}
        {...props}
      />,
    );
    return { engine, store, source };
  }

  it('opens a saved view with its conditions folded away', async () => {
    workbench([mine]);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    expect(editorToggle().getAttribute('aria-expanded')).toBe('false');
    // Folded is unmounted, not hidden: a fold that kept the draft's inputs
    // on the page would keep them in the tab order too.
    expect(document.querySelector('[data-slot="editor-band"]')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Add' })).toBeNull();
  });

  /**
   * A view that was never saved has nothing to show yet, so the editor is
   * the point of the screen rather than something in the way of it.
   */
  it('opens a view that was never saved with its conditions out', async () => {
    const { engine } = workbench([]);
    // Nothing in the store is unsaved — the store is what saving means — so
    // the engine is asked for a fresh runtime the way a "new view" would.
    const fresh = engine.create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: recordConfig(),
    });
    cleanup();
    vi.spyOn(engine, 'open').mockResolvedValue(fresh);
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    const band = await screen.findByRole('button', { name: /^Filter/ });
    expect(band.getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByRole('button', { name: 'Add' })).toBeDefined();
    // And it says so beside the title, where the save button would otherwise
    // have to be read to find out.
    expect(screen.getByText('Not saved yet')).toBeDefined();
  });

  it('counts what is waiting to be applied on the folded band', async () => {
    workbench([mine]);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    fireEvent.click(editorToggle());
    await addConditions(['Warehouse']);
    fireEvent.change(await screen.findByLabelText('Warehouse value'), {
      target: { value: 'CN' },
    });

    // Folded again, the count is the one thing left saying the editor holds
    // something the rows below were not fetched under.
    fireEvent.click(editorToggle());
    await waitFor(() =>
      expect(editorToggle().textContent).toContain('1 not applied'),
    );
  });

  it('says what the rows were fetched under, and takes one out of force', async () => {
    const { source } = workbench([filtered]);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    const bar = screen.getByRole('region', { name: 'Showing' });
    expect(bar.textContent).toContain('Warehouse');
    expect(bar.textContent).toContain('CN');

    fireEvent.click(within(bar).getByRole('button', { name: /^Unset/ }));

    // Unsetting applies at once: the condition leaves the query, and the bar
    // that describes the new result says there is nothing narrowing it.
    await waitFor(() => {
      const calls = vi.mocked(source.paged).mock.calls;
      // Nothing left to narrow by: the query asks for everything.
      expect(calls[calls.length - 1][0].filter).toEqual({ op: 'MATCH_ALL' });
    });
    await waitFor(() =>
      expect(
        screen.getByRole('region', { name: 'Showing' }).textContent,
      ).toContain('All records'),
    );
  });

  it('keeps the rows a failed refresh could not replace, and retries', async () => {
    let fail = false;
    const source = testSource({
      paged: vi.fn(() =>
        fail
          ? Promise.reject(new Error('gateway down'))
          : Promise.resolve({ total: 2, list: [...ROWS] }),
      ),
    });
    workbench([mine], {}, source);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    fail = true;
    fireEvent.click(screen.getByRole('button', { name: /Refresh/ }));

    const strip = await screen.findByRole('alert');
    expect(strip.textContent).toContain('The source answered: gateway down');
    // The rows are the last ones that came back, and the strip says so
    // rather than the table emptying itself over a dropped connection.
    expect(screen.getAllByRole('row')).toHaveLength(3);
    fireEvent.click(within(strip).getByRole('button', { name: '1 more' }));
    expect(strip.textContent).toContain('last successful result');

    fail = false;
    fireEvent.click(within(strip).getByRole('button', { name: 'Try again' }));

    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
    expect(screen.getAllByRole('row')).toHaveLength(3);
  });

  it("offers the host's bulk action only while rows are picked", async () => {
    workbench([mine], {
      actions: {
        bulk: ({ rows }) => (
          <button type="button">Export {rows.length} selected</button>
        ),
      },
    });
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    expect(screen.queryByRole('button', { name: /Export/ })).toBeNull();

    fireEvent.click(screen.getByLabelText('Select all rows'));

    expect(
      await screen.findByRole('button', { name: 'Export 2 selected' }),
    ).toBeDefined();
  });

  it("puts the host's row action in the pinned column, and in the card", async () => {
    workbench([mine], {
      actions: {
        global: () => <button type="button">New order</button>,
        row: ({ row }) => <button type="button">Open {String(row.key)}</button>,
      },
    });
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    // The global action sits in the title bar, beside the save commands.
    const header = document.querySelector(
      '[data-slot="view-header"]',
    ) as HTMLElement;
    expect(
      within(header).getByRole('button', { name: 'New order' }),
    ).toBeDefined();

    // The row action is a column of its own, pinned so a wide table cannot
    // scroll it out of reach.
    const head = screen.getByRole('columnheader', { name: 'Actions' });
    expect(head.className).toContain('sticky');
    const cell = screen
      .getByRole('button', { name: 'Open o-1' })
      .closest('[data-slot="row-actions"]');
    expect(cell).not.toBeNull();
    expect(cell?.closest('td')?.className).toContain('sticky');

    // The same buttons follow the rows into the card layout, in a footer.
    fireEvent.click(screen.getByRole('button', { name: 'Cards' }));
    await waitFor(() => expect(screen.queryByRole('table')).toBeNull());
    expect(
      screen
        .getByRole('button', { name: 'Open o-1' })
        .closest('[data-slot="card-footer"]'),
    ).not.toBeNull();
  });

  it('asks before a switch that would lose an unsaved draft', async () => {
    const other: ViewInstance = { ...mine, id: 'orders-2', title: 'Other' };
    workbench([mine, other]);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    // An edit the toolbar can make, so there is a draft worth keeping.
    fireEvent.click(screen.getByRole('button', { name: /Columns/ }));
    fireEvent.click(
      await screen.findByRole('checkbox', { name: 'Show Warehouse' }),
    );
    await waitFor(() =>
      expect(screen.getAllByRole('columnheader')).toHaveLength(4),
    );
    fireEvent.keyDown(document.body, { key: 'Escape' });

    fireEvent.click(screen.getByRole('button', { name: 'Other' }));
    const asked = await screen.findByRole('dialog');
    expect(asked.textContent).toContain('Unsaved changes will be lost.');

    fireEvent.click(within(asked).getByRole('button', { name: 'Stay' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.getByRole('button', { name: 'Mine' }).ariaCurrent).toBe(
      'true',
    );

    fireEvent.click(screen.getByRole('button', { name: 'Other' }));
    fireEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', {
        name: 'Leave',
      }),
    );

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Other' }).ariaCurrent).toBe(
        'true',
      ),
    );
  });

  /** Nothing to lose, nothing to ask: a guard that always fires is ignored. */
  it('switches straight over when there is nothing to lose', async () => {
    const other: ViewInstance = { ...mine, id: 'orders-2', title: 'Other' };
    workbench([mine, other]);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    fireEvent.click(screen.getByRole('button', { name: 'Other' }));

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Other' }).ariaCurrent).toBe(
        'true',
      ),
    );
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
