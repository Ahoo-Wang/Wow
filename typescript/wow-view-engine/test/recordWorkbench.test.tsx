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
  defaultRuntimeEnvironment,
} from '../src/index.js';
import type {
  ViewInstance,
  ViewSource,
  RecordData,
  ViewPermissions,
} from '../src/index.js';
import {
  cellText,
  cellValue,
  defaultMessages,
  displayValue,
  EmbeddedView,
  RecordWorkbench,
  useSurfaceDisplay,
  useViewMessages,
} from '../src/ui/index.js';
import type { RecordCell, RecordWorkbenchProps } from '../src/ui/index.js';
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

    const dialog = await screen.findByRole('alertdialog');
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
    const dialog = await screen.findByRole('alertdialog');
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

  /**
   * What "today" filters by and what a row shows read the same clock — the
   * footer included. A date column's earliest is one of that column's own
   * cells, so it is drawn the way that cell is drawn: the surface's
   * language, the engine's zone, and never the instant it was compared as.
   */
  it("shows times on the clock of the engine's zone, in the language given", async () => {
    const engine = new ViewEngine({
      definitions: [namedOrdersDefinition()],
      store: new MemoryViewStore({
        instances: [
          {
            ...mine,
            config: recordConfig({
              summaries: [{ field: 'createdAt', fn: 'MIN' }],
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
          // Wow answers MIN on a date with the instant it keeps.
          aggregate: () => Promise.resolve([{ createdAt_min: INSTANT }]),
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

    const footer = await waitFor(() => {
      const found = document.querySelector<HTMLElement>('tfoot');
      expect(found).not.toBeNull();
      return found!;
    });
    expect(document.querySelector('tbody')!.textContent).toContain(
      inZone(INSTANT),
    );
    // Both scopes read the column's way, under the word a moment takes.
    expect(footer.textContent).toContain(
      defaultMessages['label.summary.fn.date.MIN'],
    );
    expect(
      [...footer.querySelectorAll('[data-slot="summary-value"]')].map(
        node => node.textContent,
      ),
    ).toEqual([inZone(INSTANT), inZone(INSTANT)]);
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

    /**
     * "Could not be opened" is a normal thing for a link, a bookmark or a
     * deleted view to lead to, and the page around it is working — so it
     * wears the empty state's form rather than a block of red, and offers
     * the one way off the screen: the view the user would have had without
     * naming this one.
     */
    it('offers the default view as the way out of one that cannot open', async () => {
      render(
        <RecordWorkbench
          engine={withBoth()}
          definitionId="orders"
          instanceId="orders-chart"
        />,
      );

      const back = await screen.findByRole('button', {
        name: defaultMessages['label.view.open-default'],
      });
      // Said as an alert all the same: what was asked for is not on screen,
      // and a reader who cannot see the page change has to be told.
      expect(back.closest('[role="alert"]')).not.toBeNull();

      fireEvent.click(back);
      await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
      expect(
        screen.queryByText(defaultMessages['label.view.unopenable']),
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
   * A view whose query matched nothing, which is what the empty state and
   * its one way out are drawn over. `open` waits for rows, so this waits
   * for the sentence that stands in their place.
   */
  async function openEmpty() {
    const source = testSource({
      paged: vi.fn(() => Promise.resolve({ total: 0, list: [] })),
    });
    const harness = setup(source);
    render(
      <RecordWorkbench
        engine={harness.engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    // The empty title, as it is drawn: the live region says the same
    // sentence out loud, which is two nodes holding this text on purpose.
    await screen.findByText(defaultMessages['label.record.empty'], {
      selector: '[data-slot="empty-title"]',
    });
    return source;
  }

  /**
   * The empty result's way out, wired to the conditions that emptied it:
   * clearing the draft alone would leave the rows on screen fetched under
   * the conditions the button had just taken away, so it applies as well.
   */
  it('clears the applied conditions from the empty result', async () => {
    const source = await openEmpty();

    fireEvent.click(editorToggle());
    await screen.findByRole('button', { name: /Apply/ });
    await addConditions(['Warehouse']);
    const value = await screen.findByLabelText('Warehouse value');
    fireEvent.change(value, { target: { value: 'CN' } });
    fireEvent.click(screen.getByRole('button', { name: /Apply/ }));
    await waitFor(() => {
      const calls = vi.mocked(source.paged).mock.calls;
      expect(calls[calls.length - 1][0].filter).toMatchObject({
        field: 'warehouse',
      });
    });

    fireEvent.click(
      await screen.findByRole('button', {
        name: defaultMessages['label.record.empty-clear'],
      }),
    );

    // Cleared *and* asked again: the rows the button is standing on were
    // fetched under the conditions it just removed.
    await waitFor(() => {
      const calls = vi.mocked(source.paged).mock.calls;
      expect(calls[calls.length - 1][0].filter).not.toMatchObject({
        field: 'warehouse',
      });
    });
  });

  /**
   * And with nothing applied there is nothing to clear, so the way out is
   * the other one: the question to change is behind a fold that may not
   * even be on screen, and the button opens it.
   */
  it('opens the conditions from an empty result that had none', async () => {
    await openEmpty();
    // A saved view opens folded, which is the whole reason this way out
    // exists: the question to change is not on screen.
    expect(screen.queryByRole('button', { name: /Apply/ })).toBeNull();

    fireEvent.click(
      screen.getByRole('button', {
        name: defaultMessages['label.record.empty-add'],
      }),
    );

    await screen.findByRole('button', { name: /Apply/ });
  });

  /**
   * Next used to be live on every paged result, so the page after the last
   * one was an ordinary click away — and what came back was an empty table
   * with no way to tell it from a filter that matched nothing. It was then
   * drawn dead; on a result that fits in one page it is not drawn at all
   * (D12), because the count and "Page 1 of 1" have already said so.
   */
  it('draws no arrows on a result that fits in one page', async () => {
    await open();

    expect(screen.queryByRole('button', { name: 'Next page' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Previous page' })).toBeNull();
  });

  /** And stops there on a result that does not. */
  it('stops Next at the last page', async () => {
    // Forty records, twenty to a page: two pages, so the arrows are drawn
    // and there is somewhere for Next to go exactly once.
    const { source } = await open(
      testSource({
        paged: vi.fn(() => Promise.resolve({ total: 40, list: [...ROWS] })),
      }),
    );

    fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Next page' })).toHaveProperty(
        'disabled',
        true,
      ),
    );

    const before = vi.mocked(source.paged).mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
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

    // The host's own button, named by what it would act on — the toolbar's
    // own export is a different control and is there all along.
    expect(screen.queryByRole('button', { name: /\d+ selected/ })).toBeNull();

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
    const asked = await screen.findByRole('alertdialog');
    expect(asked.textContent).toContain('Unsaved changes will be lost.');

    fireEvent.click(within(asked).getByRole('button', { name: 'Stay' }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    expect(screen.getByRole('button', { name: 'Mine' }).ariaCurrent).toBe(
      'true',
    );

    fireEvent.click(screen.getByRole('button', { name: 'Other' }));
    fireEvent.click(
      within(await screen.findByRole('alertdialog')).getByRole('button', {
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
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });
});

/**
 * The workbench with a host holding which view is open. «一键重开» in a
 * browser is a link, so the two directions have to exist on the component and
 * not only in the controller: a route opens a view, and the view the user
 * picks goes back into the route.
 */
describe('a RecordWorkbench a host routes', () => {
  const other: ViewInstance = { ...mine, id: 'orders-2', title: 'Other' };

  function routed(props: Partial<RecordWorkbenchProps> = {}) {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mine, other] }),
      resolveSource: () => testSource(),
    });
    const draw = (overrides: Partial<RecordWorkbenchProps>) => (
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        {...props}
        {...overrides}
      />
    );
    const view = render(draw({}));
    return {
      engine,
      route: (id: string | null) => view.rerender(draw({ instanceId: id })),
    };
  }

  /** The current view is the one the sidebar marks. */
  function current(): string | null {
    return (
      screen
        .getAllByRole('button')
        .find(button => button.ariaCurrent === 'true')?.textContent ?? null
    );
  }

  it('opens the view the host routed to', async () => {
    const { route } = routed();
    await waitFor(() => expect(current()).toBe('Mine'));

    route('orders-2');

    await waitFor(() => expect(current()).toBe('Other'));
  });

  it('opens the effective default when the route names none', async () => {
    const { route } = routed({ instanceId: 'orders-2' });
    await waitFor(() => expect(current()).toBe('Other'));

    route(null);

    // The definition's own view, which wears its audience beside its name.
    await waitFor(() => expect(current()).toContain('All orders'));
  });

  it('reports the view the user picked, so the route can follow', async () => {
    const told = vi.fn();
    routed({ onInstanceChange: told });
    await waitFor(() => expect(current()).toBe('Mine'));

    fireEvent.click(screen.getByRole('button', { name: 'Other' }));

    await waitFor(() => expect(told).toHaveBeenCalledWith('orders-2'));
    await waitFor(() => expect(current()).toBe('Other'));
  });

  /**
   * A route change is a switch, so it is asked about — and a "stay" leaves
   * the host's route naming a view that is not on screen, which is why the
   * workbench then says which one is.
   */
  it('asks before a routed switch loses a draft, and says what stayed', async () => {
    const told = vi.fn();
    const { route } = routed({ onInstanceChange: told });
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    fireEvent.click(screen.getByRole('button', { name: /Columns/ }));
    fireEvent.click(
      await screen.findByRole('checkbox', { name: 'Show Warehouse' }),
    );
    await waitFor(() =>
      expect(screen.getAllByRole('columnheader')).toHaveLength(4),
    );
    fireEvent.keyDown(document.body, { key: 'Escape' });

    route('orders-2');
    const asked = await screen.findByRole('alertdialog');
    expect(told).not.toHaveBeenCalled();

    fireEvent.click(within(asked).getByRole('button', { name: 'Stay' }));

    await waitFor(() => expect(told).toHaveBeenCalledWith('orders-1'));
    expect(current()).toBe('Mine');
  });
});

/**
 * What a host may change about the result without writing the workbench
 * itself. One cell nobody else could draw is the commonest reason to walk
 * away from a default component, and the way back is that the package's own
 * reading of a value is exported: override the column you mean, and fall
 * back for the rest.
 */
describe('a RecordWorkbench a host draws cells in', () => {
  function workbench(
    props: Partial<RecordWorkbenchProps> = {},
    source: ViewSource = testSource(),
  ) {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => source,
    });
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        {...props}
      />,
    );
  }

  /** A host's renderer: its own amount cell, the package's reading for the rest. */
  function HostCell({ cell }: { cell: RecordCell }) {
    const messages = useViewMessages();
    const display = useSurfaceDisplay();
    if (cell.column.field !== 'amount')
      return cellValue(cell.value, cell.column, messages, display);
    return <span data-testid="lamp">{`${cell.value} ●`}</span>;
  }

  it('draws the cells the host renders, and reads the rest itself', async () => {
    workbench({ renderCell: cell => <HostCell cell={cell} /> });

    const lamps = await screen.findAllByTestId('lamp');
    expect(lamps.map(lamp => lamp.textContent)).toEqual(['10 ●', '20 ●']);
    // Everything the host said nothing about still reads as it always did:
    // `cellValue` is the fallback, not a stub.
    expect(screen.getAllByRole('cell').map(cell => cell.textContent)).toContain(
      'o-1',
    );
  });

  it('draws the cards the host renders', async () => {
    const cards: ViewInstance = {
      ...mine,
      config: recordConfig({ layout: 'card' }),
    };
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [cards] }),
      resolveSource: () => testSource(),
    });
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
        renderCell={cell => (
          <span data-testid="plain">{String(cell.value)}</span>
        )}
      />,
    );

    await waitFor(() =>
      expect(screen.getAllByTestId('plain').length).toBeGreaterThan(0),
    );
  });

  /**
   * A surface with nothing to do with a selection shows no checkboxes — in
   * either layout, because a card is a row folded out and switching layout
   * must not hand the choice back.
   */
  it('takes the selection away when the host offers nothing to do with it', async () => {
    workbench({ selectable: false });
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    expect(screen.queryByRole('checkbox', { name: /select/i })).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: 'Cards' }));

    await waitFor(() =>
      expect(
        document.querySelector('[data-slot="record-cards"]'),
      ).not.toBeNull(),
    );
    expect(screen.queryByRole('checkbox', { name: /select/i })).toBeNull();
  });

  it("says the host's own words when there is nothing to show", async () => {
    workbench(
      {
        emptyTitle: 'Nothing is waiting to ship',
        emptyDescription: 'Every order has left the warehouse.',
      },
      testSource({
        paged: vi.fn(() => Promise.resolve({ total: 0, list: [] })),
      }),
    );

    // Drawn as the title, and said by the result's live region in the very
    // same words — two nodes hold it on purpose, so this addresses the one
    // on screen.
    expect(
      await screen.findByText('Nothing is waiting to ship', {
        selector: '[data-slot="empty-title"]',
      }),
    ).toBeDefined();
    expect(
      screen.getByText('Every order has left the warehouse.'),
    ).toBeDefined();
  });

  /**
   * All three come off the package entry, not out of a deep path: a host
   * handed `renderCell` and no way to reach the reading it falls back to has
   * been handed a choice between its own cell and every other cell.
   */
  it('exports the whole reading from the entry, not only the node one', () => {
    expect(typeof cellValue).toBe('function');
    expect(typeof cellText).toBe('function');
    const shown = displayValue(
      INSTANT,
      { kind: 'datetime' },
      { locale: 'en-US', timeZone: ZONE },
    );
    // The same Intl call, not a literal: what is asserted is the zone and
    // the language it reads in, not the ICU data a Node release ships.
    expect(shown).toBe(
      new Intl.DateTimeFormat('en-US', {
        dateStyle: 'medium',
        timeStyle: 'medium',
        timeZone: ZONE,
      }).format(INSTANT),
    );
  });
});
