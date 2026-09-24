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
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PagedList } from '@ahoo-wang/wow-client';
import {
  MemoryViewStore,
  ViewEngine,
  defaultRuntimeEnvironment,
} from '../src/index.js';
import type {
  ViewInstance,
  RecordData,
  ViewPermissions,
} from '../src/index.js';
import {
  defaultMessages,
  EmbeddedDashboard,
  EmbeddedView,
  DataWorkbench,
} from '../src/ui/index.js';
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
import { editorToggle } from './fixtures/workbench.js';
import { mine, mixed, setup } from './fixtures/ui.js';

afterEach(cleanup);

describe('DataWorkbench', () => {
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
      <DataWorkbench
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

  it('pins the mode and the preset it is given on its surface (5B)', async () => {
    render(
      <DataWorkbench
        engine={withTwo()}
        definitionId="orders"
        instanceId="orders-1"
        theme="dark"
        preset="neutral"
      />,
    );
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    const surface = document.querySelector('[data-slot="view-surface"]');
    expect(surface?.getAttribute('data-theme')).toBe('dark');
    expect(surface?.getAttribute('data-fve-preset')).toBe('neutral');
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
      <DataWorkbench
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
      <DataWorkbench
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
      <DataWorkbench
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
      <DataWorkbench
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
      <DataWorkbench
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
      <DataWorkbench
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
      <DataWorkbench
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
              // More than a page, so both scopes are drawn (D26 Q40).
              total: 42,
              list: [{ id: 'o-1', createdAt: INSTANT }],
            }),
          // Wow answers MIN on a date with the instant it keeps.
          aggregate: () => Promise.resolve([{ createdAt_min: INSTANT }]),
        }),
      environment: defaultRuntimeEnvironment({ timeZone: ZONE }),
    });

    render(
      <DataWorkbench
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

    it('lists both kinds in one list, and opens either (D20)', async () => {
      render(<DataWorkbench engine={withBoth()} definitionId="orders" />);

      await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
      const sidebar = within(
        document.querySelector<HTMLElement>('[data-slot="view-sidebar"]')!,
      );
      expect(sidebar.getByRole('button', { name: /^Mine/ })).toBeDefined();
      fireEvent.click(sidebar.getByRole('button', { name: /By warehouse/ }));

      // The analysis view opens in the same workbench: its tray and its
      // table replace the record view's, and the frame stays.
      expect(
        await screen.findByRole('heading', { level: 2, name: 'By warehouse' }),
      ).toBeDefined();
      expect(
        await screen.findByRole('button', { name: /^Analysis/ }),
      ).toBeDefined();
    });

    it('lists only the kind a host narrows it to (D9)', async () => {
      render(
        <DataWorkbench
          engine={withBoth()}
          definitionId="orders"
          kinds={['record']}
        />,
      );

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
        <DataWorkbench
          engine={withBoth()}
          definitionId="orders"
          kinds={['record']}
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
        <DataWorkbench
          engine={withBoth()}
          definitionId="orders"
          kinds={['record']}
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
    // No tray and no handle for one: an embedded view is its author's
    // question, read as it was saved.
    expect(document.querySelector('[data-slot="analysis-tray"]')).toBeNull();
    expect(document.querySelector('[data-slot="editor-toggle"]')).toBeNull();
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

    render(<EmbeddedDashboard engine={engine} instanceId="overview-1" />);

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
        'Could not load the data: down',
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
    await waitFor(() =>
      expect(
        screen.getByText('The page size must be a positive number.'),
      ).toBeTruthy(),
    );
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
        'Could not load the data: down',
      ),
    );
  });

  it('reports a view it cannot open', async () => {
    const { engine } = setup();

    render(
      <DataWorkbench
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
      <DataWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain(
        'Could not load the data: gateway down',
      ),
    );
  });
});
