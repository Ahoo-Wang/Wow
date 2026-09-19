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
import { useState } from 'react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  builtinFieldKinds,
  MemoryViewStore,
  ViewEngine,
  ViewStoreError,
  defaultRuntimeEnvironment,
  systemInstanceId,
  withFieldKinds,
  type FieldKind,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import { FilterOperator, type PagedList } from '@ahoo-wang/fetcher-wow';
import type {
  EditorDescriptor,
  FilterTree,
  FilterValue,
  RecordData,
  ViewInstanceSummary,
  ViewPermissions,
} from '../src/index.js';
import type {
  RecordTableController,
  ViewListState,
} from '../src/react/index.js';
import { useFilterEditor } from '../src/react/index.js';
import {
  defaultMessages,
  EmbeddedView,
  FilterPanel,
  FilterValueEditor,
  RecordCards,
  RecordTable,
  RecordWorkbench,
  type RecordWorkbenchProps,
  ErrorStrip,
  unmarkedErrors,
  ViewList,
  ViewSurface,
  WarningStrip,
} from '../src/ui/index.js';
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

afterEach(cleanup);

const mine: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'Mine',
  scope: 'personal',
  revision: '1',
  config: recordConfig(),
};

/**
 * A simple-mode config holding a tree only the advanced editor can show. It
 * opens, runs and saves; the kernel warns about it, and nothing more.
 */
const mixed: ViewInstance = {
  ...mine,
  config: recordConfig({
    filterMode: 'simple',
    filter: {
      op: 'or',
      children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
    },
  }),
};

/** The last value a controlled editor reported. */
function last(changes: FilterValue[]): FilterValue {
  return changes[changes.length - 1];
}

function setup(source: ViewSource = testSource()) {
  const store = new MemoryViewStore({ instances: [mine] });
  const engine = new ViewEngine({
    definitions: [ordersDefinition()],
    store,
    resolveSource: () => source,
  });
  return { engine, store, source };
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
    // The page reads top to bottom: which view this is, the conditions
    // folded away, what the rows were fetched under, the toolbar, the rows.
    expect(
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
        ),
    ).toEqual([
      'view-header',
      'editor-band',
      'applied-bar',
      'result-toolbar',
      'record-pagination',
    ]);
    // A saved view opens folded, so the way out of the editor is not on
    // screen until the band is opened.
    expect(screen.queryByRole('button', { name: /Apply/ })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Filter' }));
    expect(await screen.findByRole('button', { name: /Apply/ })).toBeDefined();
    // The sidebar carries the definition's own title.
    expect(screen.getByRole('navigation', { name: 'Orders' })).toBeDefined();
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
    fireEvent.click(screen.getByRole('button', { name: 'Filter' }));
    await screen.findByRole('button', { name: /Apply/ });
    return harness;
  }

  it('adds a condition, edits it and applies it', async () => {
    const { source } = await open();

    fireEvent.click(screen.getByRole('combobox', { name: 'Add' }));
    fireEvent.click(await screen.findByRole('option', { name: 'Warehouse' }));

    const value = await screen.findByLabelText('warehouse value');
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

    fireEvent.click(screen.getByRole('combobox', { name: 'Add' }));
    fireEvent.click(await screen.findByRole('option', { name: 'Warehouse' }));
    await screen.findByLabelText('warehouse value');

    fireEvent.click(screen.getByRole('button', { name: 'Remove Warehouse' }));
    expect(screen.queryByLabelText('warehouse value')).toBeNull();

    fireEvent.click(screen.getByRole('combobox', { name: 'Add' }));
    fireEvent.click(await screen.findByRole('option', { name: 'Warehouse' }));
    await screen.findByLabelText('warehouse value');
    fireEvent.click(screen.getByRole('button', { name: 'Clear' }));

    expect(screen.queryByLabelText('warehouse value')).toBeNull();
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
      await screen.findByRole('menuitemcheckbox', { name: 'Amount' }),
    );

    await waitFor(() =>
      expect(screen.getAllByRole('columnheader')).toHaveLength(2),
    );
  });

  it('switches the filter editor to advanced mode', async () => {
    const user = userEvent.setup();
    await open();

    await user.click(screen.getByRole('button', { name: 'Advanced' }));

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Advanced' }).ariaPressed).toBe(
        'true',
      ),
    );
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
    fireEvent.click(await screen.findByRole('button', { name: 'Filter' }));
    const apply = await screen.findByRole('button', { name: /Apply/ });

    // The rows are still coming. Typing never re-queries and the next apply
    // supersedes the request in flight, so nothing here has to wait for it.
    expect(apply.hasAttribute('disabled')).toBe(false);
    fireEvent.click(screen.getByRole('combobox', { name: 'Add' }));
    fireEvent.click(await screen.findByRole('option', { name: 'Warehouse' }));
    const value = await screen.findByLabelText('warehouse value');
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
      await screen.findByRole('menuitemcheckbox', { name: 'Warehouse' }),
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
      await screen.findByRole('menuitemcheckbox', { name: 'Warehouse' }),
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

    const band = screen.getByRole('button', { name: /^Filter/ });
    expect(band.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByRole('combobox', { name: 'Add' })).toBeNull();
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
    expect(screen.getByRole('combobox', { name: 'Add' })).toBeDefined();
    // And it says so beside the title, where the save button would otherwise
    // have to be read to find out.
    expect(screen.getByText('Not saved yet')).toBeDefined();
  });

  it('counts what is waiting to be applied on the folded band', async () => {
    workbench([mine]);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    fireEvent.click(screen.getByRole('button', { name: /^Filter/ }));
    fireEvent.click(await screen.findByRole('combobox', { name: 'Add' }));
    fireEvent.click(await screen.findByRole('option', { name: 'Warehouse' }));
    fireEvent.change(await screen.findByLabelText('warehouse value'), {
      target: { value: 'CN' },
    });

    // Folded again, the count is the one thing left saying the editor holds
    // something the rows below were not fetched under.
    fireEvent.click(screen.getByRole('button', { name: /^Filter/ }));
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: /^Filter/ }).textContent,
      ).toContain('1 not applied'),
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
      await screen.findByRole('menuitemcheckbox', { name: 'Warehouse' }),
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

describe('FilterValueEditor', () => {
  const CANDIDATES = [
    { value: 'CN', label: 'China' },
    { value: 'JP', label: 'Japan' },
  ];

  function editor(
    descriptor: EditorDescriptor,
    initial: FilterValue = null,
    options: typeof CANDIDATES | null = CANDIDATES,
  ): { changes: FilterValue[]; replace(next: FilterValue): void } {
    const changes: FilterValue[] = [];
    // A host like the filter panel feeds the editor the value it emitted —
    // the same reference — and may later replace the value wholesale.
    function Host({ forced }: { forced: FilterValue | null }) {
      const [current, setCurrent] = useState<FilterValue>(initial);
      if (forced !== null && forced !== current) {
        // A replacement wins over whatever was being typed.
        setCurrent(forced);
      }
      return (
        <ViewSurface>
          <FilterValueEditor
            editor={descriptor}
            value={current}
            label="amount"
            options={options ?? undefined}
            onChange={next => {
              changes.push(next);
              setCurrent(next);
            }}
          />
        </ViewSurface>
      );
    }
    const view = render(<Host forced={null} />);
    return {
      changes,
      replace: (next: FilterValue) => view.rerender(<Host forced={next} />),
    };
  }

  it('renders nothing for an operator that takes no value', () => {
    const { container } = render(
      <FilterValueEditor
        editor={{ input: 'none' }}
        value={null}
        label="amount"
        onChange={() => {}}
      />,
    );
    expect(container.textContent).toBe('');
  });

  it('collects a list from comma separated text', () => {
    const { changes } = editor({ input: 'text', multiple: true }, ['a']);

    fireEvent.change(screen.getByLabelText('amount'), {
      target: { value: 'a, b' },
    });

    expect(changes).toEqual([['a', 'b']]);
  });

  it('keeps the trailing comma while a second list value is typed', () => {
    const { changes } = editor({ input: 'text', multiple: true }, ['a']);
    const input = screen.getByLabelText('amount') as HTMLInputElement;

    fireEvent.change(input, { target: { value: 'a,' } });
    // The comma is the separator being typed; eating it re-derives the text
    // from the parsed list and makes a second value impossible to enter.
    expect(input.value).toBe('a,');
    expect(changes).toEqual([['a']]);

    fireEvent.change(input, { target: { value: 'a, b' } });
    expect(changes).toEqual([['a'], ['a', 'b']]);
  });

  it('adopts a value the host replaced with an equal list', () => {
    const { replace } = editor({ input: 'text', multiple: true }, ['a']);
    const input = screen.getByLabelText('amount') as HTMLInputElement;

    fireEvent.change(input, { target: { value: 'a,' } });
    expect(input.value).toBe('a,');

    // A host that rebuilds its config — a conflict reload, a reset — supplies
    // a fresh list with the same items; the half-typed draft must not survive.
    replace(['a']);
    expect(input.value).toBe('a');
  });

  it('collects one number and a range of two', () => {
    const single = editor({ input: 'number' }, 3);
    fireEvent.change(screen.getByLabelText('amount'), {
      target: { value: '7' },
    });
    expect(single.changes).toEqual([7]);
    cleanup();

    const range = editor({ input: 'number', range: true }, [1, 2]);
    fireEvent.change(screen.getByLabelText('amount to'), {
      target: { value: '9' },
    });
    expect(range.changes).toEqual([[1, 9]]);
  });

  it('offers true and false for a boolean', async () => {
    const user = userEvent.setup();
    const { changes } = editor({ input: 'boolean' }, true);

    await user.click(screen.getByLabelText('amount'));
    await user.click(await screen.findByRole('option', { name: 'False' }));

    expect(changes).toEqual([false]);
  });

  /**
   * A row the user has only just added read as "is False" — a condition
   * already narrowing the list — and picking the False it appeared to hold
   * changed nothing, so the value it showed could not even be confirmed.
   */
  it('shows no choice for a blank boolean, and fires on the first pick', async () => {
    const user = userEvent.setup();
    const { changes } = editor({ input: 'boolean' }, null);

    const trigger = screen.getByLabelText('amount');
    expect(trigger.textContent).not.toContain('False');

    await user.click(trigger);
    await user.click(await screen.findByRole('option', { name: 'False' }));

    expect(changes).toEqual([false]);
  });

  /**
   * `Number('')` is 0, so emptying a number field used to ask for "equals
   * zero", and a half-typed one for `NaN`, which no kind admits.
   */
  it('leaves an emptied number blank rather than asking for zero', () => {
    const { changes } = editor({ input: 'number' }, 3);
    const input = screen.getByLabelText('amount') as HTMLInputElement;

    fireEvent.change(input, { target: { value: '1e' } });
    fireEvent.change(input, { target: { value: '' } });

    expect(last(changes)).toBeNull();
    expect(input.value).toBe('');
    expect(
      changes.some(value => typeof value === 'number' && Number.isNaN(value)),
    ).toBe(false);
  });

  it('blanks a range once both of its ends are emptied', () => {
    const { changes } = editor({ input: 'number', range: true }, [1, 2]);

    fireEvent.change(screen.getByLabelText('amount from'), {
      target: { value: '' },
    });
    expect(last(changes)).toEqual([null, 2]);

    fireEvent.change(screen.getByLabelText('amount to'), {
      target: { value: '' },
    });
    expect(last(changes)).toBeNull();
  });

  it('keeps a relative window blank rather than asking for zero units', () => {
    const { changes } = editor({ input: 'relativeDate' }, {
      type: 'relative',
      amount: 7,
      unit: 'day',
    } as unknown as FilterValue);
    const amount = screen.getByLabelText('amount amount') as HTMLInputElement;

    fireEvent.change(amount, { target: { value: '' } });

    expect(last(changes)).toBeNull();
    // Blank, and still the row the user was writing rather than a calendar.
    expect(amount.value).toBe('');
    expect(screen.getByLabelText('amount kind').textContent).toContain(
      'Relative',
    );
  });

  it('offers the options a kind declared', async () => {
    const { changes } = editor(
      {
        input: 'select',
        options: [
          { value: 'CN', label: 'China' },
          { value: 'JP', label: 'Japan' },
        ],
      },
      'CN',
    );

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount'));
    await user.click(await screen.findByRole('option', { name: 'Japan' }));

    expect(changes).toEqual(['JP']);
  });

  it('uses the candidates a remote editor was given', async () => {
    const user = userEvent.setup();
    const { changes } = editor(
      { input: 'remote', remote: 'warehouses', multiple: true },
      [],
    );

    await user.click(screen.getByLabelText('amount'));
    await user.click(await screen.findByRole('option', { name: 'China' }));

    expect(changes).toEqual([['CN']]);
  });

  it('falls back to typed entry when no remote candidates are given', () => {
    const { changes } = editor(
      { input: 'remote', remote: 'warehouses' },
      '',
      null,
    );

    fireEvent.change(screen.getByLabelText('amount'), {
      target: { value: 'w-1' },
    });

    expect(changes).toEqual(['w-1']);
  });

  it('switches a date between absolute, relative and a period', async () => {
    const { changes } = editor({ input: 'date' }, {
      type: 'absolute',
      from: '2026-09-16T00:00:00.000Z',
    } as unknown as FilterValue);

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount kind'));
    // The shape is neutral now; the direction beside it carries the wording.
    await user.click(await screen.findByRole('option', { name: 'Relative' }));
    expect(last(changes)).toMatchObject({ type: 'relative', unit: 'day' });

    cleanup();
    const relative = editor({ input: 'relativeDate' }, {
      type: 'relative',
      amount: 7,
      unit: 'day',
    } as unknown as FilterValue);
    fireEvent.change(screen.getByLabelText('amount amount'), {
      target: { value: '30' },
    });
    expect(last(relative.changes)).toMatchObject({ amount: 30 });

    await user.click(screen.getByLabelText('amount kind'));
    await user.click(await screen.findByRole('option', { name: 'A period' }));
    expect(last(relative.changes)).toMatchObject({ preset: 'today' });
  });

  it('asks a relative window which way it runs', async () => {
    // The kernel could express "the next 7 days" while this editor could
    // only ever write a backwards window, which left the feature unreachable.
    const { changes } = editor({ input: 'relativeDate' }, {
      type: 'relative',
      amount: 7,
      unit: 'day',
    } as unknown as FilterValue);

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount direction'));
    await user.click(
      await screen.findByRole('option', { name: 'In the next' }),
    );

    expect(last(changes)).toMatchObject({
      type: 'relative',
      amount: 7,
      unit: 'day',
      direction: 'future',
    });
  });

  it('opens a window with no direction as the past one', () => {
    editor({ input: 'relativeDate' }, {
      type: 'relative',
      amount: 7,
      unit: 'day',
    } as unknown as FilterValue);

    expect(screen.getByLabelText('amount direction').textContent).toContain(
      'In the last',
    );
  });

  /**
   * A day picked without a time of day is a calendar day, stored as
   * `YYYY-MM-DD`. Storing `toISOString()` pinned local midnight to UTC with a
   * `Z`, which the kernel treats as one fixed moment: no runtime or condition
   * zone was ever applied, and a range lost its last day.
   */
  it('picks a day from the calendar and stores the day, not an instant', async () => {
    const { changes } = editor({ input: 'date', withTime: false }, {
      type: 'absolute',
      from: '2026-09-16',
    } as unknown as FilterValue);

    // Shown as the day it names, whatever zone the browser is in.
    expect(screen.getByLabelText('amount').textContent).toContain(
      new Date(2026, 8, 16).toLocaleDateString(),
    );

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount'));
    const day = await screen.findByRole('button', { name: /September 20/ });
    await user.click(day);

    expect(last(changes)).toEqual({ type: 'absolute', from: '2026-09-20' });
  });

  it('stores both ends of a day range as days', async () => {
    const { changes } = editor(
      { input: 'dateRange', range: true, withTime: false },
      { type: 'absolute', from: '2026-09-16' } as unknown as FilterValue,
    );

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount'));
    await user.click(
      await screen.findByRole('button', { name: /September 20/ }),
    );

    expect(last(changes)).toEqual({
      type: 'absolute',
      from: '2026-09-16',
      to: '2026-09-20',
    });
  });

  it('stores a moment when the editor carries a time of day', async () => {
    const { changes } = editor({ input: 'date', withTime: true }, {
      type: 'absolute',
      from: '2026-09-16T00:00:00.000Z',
    } as unknown as FilterValue);

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount'));
    await user.click(
      await screen.findByRole('button', { name: /September 20/ }),
    );

    // A real moment the user picked: local midnight of that day, as an
    // instant the kernel will not move.
    expect(last(changes)).toEqual({
      type: 'absolute',
      from: new Date(2026, 8, 20).toISOString(),
    });
  });

  it('seeds a fresh absolute value in the shape the editor stores', async () => {
    const relative = {
      type: 'relative',
      amount: 7,
      unit: 'day',
    } as unknown as FilterValue;
    const user = userEvent.setup();

    const day = editor({ input: 'date', withTime: false }, relative);
    await user.click(screen.getByLabelText('amount kind'));
    await user.click(await screen.findByRole('option', { name: 'On a date' }));
    expect(last(day.changes)).toMatchObject({
      type: 'absolute',
      from: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/),
    });

    cleanup();
    const moment = editor({ input: 'date', withTime: true }, relative);
    await user.click(screen.getByLabelText('amount kind'));
    await user.click(await screen.findByRole('option', { name: 'On a date' }));
    expect(last(moment.changes)).toMatchObject({
      type: 'absolute',
      from: expect.stringMatching(/^\d{4}-\d{2}-\d{2}T[\d:.]+Z$/),
    });
  });
});

function tableController(
  overrides: Partial<RecordTableController> = {},
): RecordTableController {
  return {
    columns: [
      {
        field: 'amount',
        label: 'Amount',
        kind: 'number',
        cell: 'number',
        sortable: true,
        numberFormat: { style: 'currency', currency: 'CNY' },
      },
      {
        field: 'warehouse',
        label: 'Warehouse',
        kind: 'string',
        cell: 'string',
        sortable: false,
      },
    ],
    rows: [
      { key: 'o-1', data: { amount: 10, warehouse: 'CN' } },
      { key: 'o-2', data: { amount: null, warehouse: true } },
    ],
    card: {
      title: 'warehouse',
      fields: [{ field: 'amount', label: 'Amount' }],
    },
    paging: { mode: 'paged', index: 1, total: 2 },
    summaries: null,
    status: 'success',
    error: null,
    loading: false,
    sort: [],
    sortOf: () => null,
    toggleSort: () => {},
    layout: 'table',
    layouts: ['table', 'card'],
    setLayout: () => {},
    columnFields: ['amount', 'warehouse'],
    setColumns: () => {},
    pageSize: 20,
    pageSizes: [10, 20, 50, 100],
    setPageSize: () => {},
    selection: [],
    selectedRows: [],
    isSelected: () => false,
    toggle: () => {},
    toggleAll: () => {},
    clearSelection: () => {},
    goTo: () => {},
    hasNext: true,
    next: () => {},
    previous: () => {},
    refresh: () => {},
    ...overrides,
  };
}

function listState(overrides: Partial<ViewListState> = {}): ViewListState {
  return {
    items: [],
    all: [],
    preferences: null,
    permissions: {
      createPersonal: true,
      createShared: true,
      reorder: true,
      setDefault: true,
      instance: () => ({ save: true, rename: true, delete: true }),
    },
    defaultInstanceId: null,
    loading: false,
    error: null,
    preferencesError: null,
    reload: () => {},
    ...overrides,
  };
}

describe('RecordCards on its own', () => {
  it('reads a nested title field by its path', () => {
    render(
      <RecordCards
        table={tableController({
          card: { title: 'customer.name', fields: [] },
          rows: [{ key: 'o-1', data: { customer: { name: 'Acme' } } }],
        })}
      />,
    );
    expect(screen.getByText('Acme')).toBeDefined();
  });
});

describe('RecordTable on its own', () => {
  it('hands a custom cell the null the record holds', () => {
    const seen: unknown[] = [];
    render(
      <RecordTable
        table={tableController()}
        renderCell={cell => {
          if (cell.key === 'o-2' && cell.column.field === 'amount')
            seen.push(cell.value);
          return null;
        }}
      />,
    );
    expect(seen).toEqual([null]);
  });

  it('formats each value by what the column declared', () => {
    render(<RecordTable table={tableController()} />);

    expect(screen.getByText('CN¥10.00')).toBeDefined();
    expect(screen.getByText('Yes')).toBeDefined();
    // A sortable column gets a button; a plain one is just its label.
    expect(screen.getByRole('button', { name: /Amount/ })).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Warehouse' })).toBeNull();
  });

  /**
   * Wow keeps a time as epoch milliseconds and an enum as its code, so a
   * table that printed values as they came was a column of thirteen-digit
   * numbers beside a column of constants.
   */
  it('shows a time in the zone and language of its surface, and an enum by its label', () => {
    render(
      <ViewSurface locale="en-GB" timeZone={ZONE}>
        <RecordTable
          table={tableController({
            columns: [
              {
                field: 'createdAt',
                label: 'Created',
                kind: 'datetime',
                cell: 'datetime',
                sortable: false,
              },
              {
                field: 'status',
                label: 'Status',
                kind: 'enum',
                cell: 'enum',
                sortable: false,
                options: [{ value: 'FAILED', label: 'Failed' }],
              },
            ],
            rows: [
              { key: 'o-1', data: { createdAt: INSTANT, status: 'FAILED' } },
            ],
          })}
        />
      </ViewSurface>,
    );

    expect(screen.getByText(inZone(INSTANT))).toBeDefined();
    expect(screen.getByText('Failed')).toBeDefined();
  });

  it('still renders a column whose number format Intl refuses', () => {
    render(
      <RecordTable
        table={tableController({
          columns: [
            {
              field: 'amount',
              label: 'Amount',
              kind: 'number',
              cell: 'number',
              sortable: false,
              numberFormat: { style: 'currency' },
            },
          ],
          rows: [{ key: 'o-1', data: { amount: 10 } }],
        })}
      />,
    );

    expect(screen.getByText('10')).toBeDefined();
  });

  it('marks sort direction on the column it applies to', () => {
    render(
      <RecordTable
        table={tableController({
          sortOf: field => (field === 'amount' ? 'DESC' : null),
        })}
      />,
    );
    expect(screen.getByRole('button', { name: /Amount/ })).toBeDefined();

    cleanup();
    render(
      <RecordTable
        table={tableController({
          sortOf: field => (field === 'amount' ? 'ASC' : null),
        })}
      />,
    );
    expect(screen.getByRole('button', { name: /Amount/ })).toBeDefined();
  });

  it('shows skeletons on a first load and an empty state after it', () => {
    render(
      <RecordTable table={tableController({ status: 'loading', rows: [] })} />,
    );
    expect(screen.getAllByRole('row').length).toBeGreaterThan(1);

    cleanup();
    render(<RecordTable table={tableController({ rows: [] })} />);
    expect(screen.getByText('Nothing to show')).toBeDefined();
  });

  it('takes a renderer for the cells', () => {
    render(
      <RecordTable
        table={tableController()}
        renderCell={cell => <em>{String(cell.value)}</em>}
      />,
    );
    expect(screen.getByText('10')).toBeDefined();
  });
});

describe('RecordCards on its own', () => {
  it('titles a card by the field the card spec names', () => {
    render(<RecordCards table={tableController()} />);

    const [first, second] = screen.getAllByText(
      (_text, element) =>
        (element as HTMLElement | null)?.dataset.slot === 'card-title',
    );
    expect(first.textContent).toContain('CN');
    // The second row's title field is a boolean, which reads as Yes.
    expect(second.textContent).toContain('Yes');
  });

  it('shows a card value as its column would', () => {
    render(
      <ViewSurface locale="en-GB" timeZone={ZONE}>
        <RecordCards
          table={tableController({
            card: {
              title: 'status',
              titleField: {
                field: 'status',
                label: 'Status',
                options: [{ value: 'FAILED', label: 'Failed' }],
              },
              fields: [
                { field: 'createdAt', label: 'Created', kind: 'datetime' },
                {
                  field: 'amount',
                  label: 'Amount',
                  numberFormat: { style: 'currency', currency: 'CNY' },
                },
              ],
            },
            rows: [
              {
                key: 'o-1',
                data: { status: 'FAILED', createdAt: INSTANT, amount: 10 },
              },
            ],
          })}
        />
      </ViewSurface>,
    );

    expect(screen.getByText('Failed')).toBeDefined();
    expect(screen.getByText(inZone(INSTANT))).toBeDefined();
    expect(screen.getByText('CN¥10.00')).toBeDefined();
  });

  it("leaves a card's values to the host's renderer when it has one", () => {
    render(
      <ViewSurface locale="en-GB" timeZone={ZONE}>
        <RecordCards
          table={tableController({
            card: {
              title: 'id',
              fields: [
                { field: 'createdAt', label: 'Created', kind: 'datetime' },
              ],
            },
            rows: [{ key: 'o-1', data: { id: 'o-1', createdAt: INSTANT } }],
          })}
          renderValue={value => <em>raw {String(value)}</em>}
        />
      </ViewSurface>,
    );

    expect(screen.getByText(`raw ${INSTANT}`)).toBeDefined();
    expect(screen.queryByText(inZone(INSTANT))).toBeNull();
  });

  it('falls back to the row key without a title field', () => {
    render(
      <RecordCards
        table={tableController({ card: { title: '', fields: [] } })}
      />,
    );
    expect(screen.getByText('o-1')).toBeDefined();
  });

  /**
   * A card is not the table narrowed. Rendering `table.columns` showed the
   * column list under a title nobody configured, so every card setting a user
   * saved — which field titles it, what its body holds, its picture — was
   * stored, validated and then ignored.
   */
  it('shows the body fields and the image of the saved card', () => {
    const { container } = render(
      <RecordCards
        table={tableController({
          card: {
            title: 'warehouse',
            fields: [{ field: 'amount', label: 'Total' }],
            image: 'photo',
            columns: 2,
          },
          rows: [
            { key: 'o-1', data: { warehouse: 'CN', amount: 10, photo: '/a' } },
          ],
        })}
      />,
    );

    // The card's own label, not the column's, and none of the other columns.
    expect(screen.getByText('Total')).toBeDefined();
    expect(screen.queryByText('Warehouse')).toBeNull();
    expect(container.querySelector('img')?.getAttribute('src')).toBe('/a');
    expect(
      container
        .querySelector('[data-slot="record-cards"]')
        ?.className.includes('sm:grid-cols-2'),
    ).toBe(true);
  });
});

describe('ViewList on its own', () => {
  /** What a list receives: an instance's identity plus its config's kind. */
  function summary(
    overrides: Partial<ViewInstanceSummary> = {},
  ): ViewInstanceSummary {
    return {
      id: 'a',
      definitionId: 'orders',
      title: 'Mine',
      scope: 'personal',
      kind: 'record',
      revision: '1',
      ...overrides,
    };
  }

  it('shows placeholders while loading', () => {
    const { container } = render(
      <ViewList
        list={listState({ loading: true })}
        currentId={null}
        onOpen={() => {}}
      />,
    );
    expect(container.querySelectorAll('[data-slot="skeleton"]').length).toBe(3);
  });

  it('explains an empty list, and says so when it failed', () => {
    render(<ViewList list={listState()} currentId={null} onOpen={() => {}} />);
    expect(screen.getByText(/Save the current conditions/)).toBeDefined();

    cleanup();
    render(
      <ViewList
        list={listState({ error: { code: 'x', severity: 'error', path: [] } })}
        currentId={null}
        onOpen={() => {}}
      />,
    );
    expect(screen.getByText(/could not be loaded/)).toBeDefined();
  });

  it('is named by the definition, and falls back when none is given', () => {
    // The heading names the nav, so the two cannot drift apart.
    render(
      <ViewList
        list={listState({ loading: true })}
        title="订单工作台"
        currentId={null}
        onOpen={() => {}}
      />,
    );
    expect(
      screen.getByRole('navigation', { name: '订单工作台' }),
    ).toBeDefined();
    expect(screen.getByRole('heading', { name: '订单工作台' })).toBeDefined();

    cleanup();
    render(
      <ViewList
        list={listState({ loading: true })}
        currentId={null}
        onOpen={() => {}}
      />,
    );
    expect(screen.getByRole('navigation', { name: 'Views' })).toBeDefined();
  });

  it('files a system view under shared, and tags that one alone', () => {
    render(
      <ViewSurface>
        <ViewList
          list={listState({
            items: [
              summary({ id: 'a', title: 'Mine' }),
              summary({ id: 'b', title: 'Ours', scope: 'shared' }),
              summary({ id: 'c', title: 'All orders', scope: 'system' }),
            ],
          })}
          currentId="a"
          onOpen={() => {}}
        />
      </ViewSurface>,
    );

    const [personal, shared] = screen.getAllByRole('group');
    expect(within(personal).getByText('Personal')).toBeDefined();
    expect(
      within(personal)
        .getAllByRole('button')
        .map(item => item.textContent),
    ).toEqual(['Mine']);
    // A system view is a shared view, so it sits in that group rather than
    // in a third one — the tag is what says where it came from.
    expect(within(shared).getByText('Shared')).toBeDefined();
    expect(within(shared).getByRole('button', { name: /Ours/ })).toBeDefined();
    const tags = screen.getAllByText('system');
    expect(tags).toHaveLength(1);
    expect(tags[0].closest('button')?.textContent).toContain('All orders');
  });

  it('shows only the groups it has views for', () => {
    render(
      <ViewSurface>
        <ViewList
          list={listState({ items: [summary({ scope: 'system' })] })}
          currentId={null}
          onOpen={() => {}}
        />
      </ViewSurface>,
    );

    const groups = screen.getAllByRole('group');
    expect(groups).toHaveLength(1);
    expect(within(groups[0]).getByText('Shared')).toBeDefined();
  });

  it('tells a record view from an analysis view by its icon', () => {
    // One data definition holds both, so the two sit in the same group and
    // the icon is all that separates them.
    render(
      <ViewSurface>
        <ViewList
          list={listState({
            items: [
              summary({ id: 'a', title: 'Rows', kind: 'record' }),
              summary({ id: 'b', title: 'Totals', kind: 'analysis' }),
            ],
          })}
          currentId={null}
          onOpen={() => {}}
        />
      </ViewSurface>,
    );

    const iconOf = (name: string) =>
      screen
        .getByRole('button', { name: new RegExp(name) })
        .querySelector('svg')
        ?.getAttribute('class');
    expect(iconOf('Rows')).toContain('inbox');
    expect(iconOf('Totals')).toContain('sigma');
  });

  it('marks the open view and opens the one clicked', () => {
    const opened = vi.fn();
    render(
      <ViewSurface>
        <ViewList
          list={listState({
            items: [
              summary({ id: 'a', title: 'Mine' }),
              summary({ id: 'b', title: 'Ours', scope: 'shared' }),
            ],
          })}
          currentId="a"
          onOpen={opened}
        />
      </ViewSurface>,
    );

    expect(screen.getByRole('button', { name: /Mine/ }).ariaCurrent).toBe(
      'true',
    );

    fireEvent.click(screen.getByRole('button', { name: /Ours/ }));
    expect(opened).toHaveBeenCalledWith('b');
  });
});

describe('FilterPanel tree editing', () => {
  interface PanelHarness {
    filter(): ReturnType<typeof useFilterEditor>;
  }

  /** A definition whose `items` array declares what its entries hold. */
  function withItems() {
    const base = ordersDefinition();
    return {
      ...base,
      fields: [
        ...base.fields,
        {
          name: 'items',
          label: 'Items',
          kind: 'elementMatch' as const,
          elements: [
            { name: 'sku', label: 'SKU', kind: 'string' as const },
            { name: 'qty', label: 'Qty', kind: 'number' as const },
          ],
        },
      ],
    };
  }

  function panel(
    disabled = false,
    definition = ordersDefinition(),
  ): PanelHarness {
    const engine = new ViewEngine({
      definitions: [definition],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => testSource(),
    });
    const runtime = engine.create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: recordConfig(),
    });
    // The panel must re-render with the controller on every runtime commit,
    // so both live inside one component rather than two renders.
    let latest: ReturnType<typeof useFilterEditor> | null = null;
    function Probe() {
      const filter = useFilterEditor(runtime);
      latest = filter;
      return <FilterPanel filter={filter} disabled={disabled} />;
    }
    render(<Probe />);
    return { filter: () => latest as ReturnType<typeof useFilterEditor> };
  }

  it('lists the fields of the picker by the groups the definition declares', async () => {
    const grouped = ordersDefinition({
      fieldGroups: [
        { id: 'state', label: 'State', fields: ['status'] },
        { id: 'money', label: 'Money', fields: ['amount'] },
      ],
    });
    panel(false, grouped);

    fireEvent.click(screen.getByRole('combobox', { name: 'Add' }));
    const text = (await screen.findByRole('listbox')).textContent ?? '';

    // Ungrouped fields first, then each declared group under its label, in
    // the catalogue's order rather than the fields' own.
    const at = (word: string) => text.indexOf(word);
    expect(at('Order')).toBeGreaterThanOrEqual(0);
    expect(at('Order')).toBeLessThan(at('State'));
    expect(at('State')).toBeLessThan(at('Status'));
    expect(at('Status')).toBeLessThan(at('Money'));
    expect(at('Money')).toBeLessThan(at('Amount'));
  });

  it('narrows the fields to what is typed, across every group', async () => {
    panel();
    fireEvent.click(screen.getByRole('combobox', { name: 'Add' }));
    const search = await screen.findByRole('combobox', {
      name: 'Search fields',
    });

    fireEvent.change(search, { target: { value: 'sta' } });

    const names = (await screen.findAllByRole('option')).map(
      option => option.textContent,
    );
    expect(names).toEqual(['Status']);
  });

  it('offers a field once per group when adding a condition', async () => {
    const { filter } = panel();
    act(() => filter().addLeaf('warehouse'));

    fireEvent.click(screen.getByRole('combobox', { name: 'Add' }));

    const names = (await screen.findAllByRole('option')).map(
      item => item.textContent,
    );
    expect(names).toContain('Status');
    expect(names).not.toContain('Warehouse');
  });

  it('leaves the applied summary to the bar that owns it', () => {
    const { filter } = panel();
    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
      filter().submit();
    });

    // The panel is the draft and nothing else. What ran is described beside
    // the rows it fetched, where it can be read against them.
    expect(document.querySelectorAll('[data-slot="badge"]')).toHaveLength(0);
    expect(document.querySelector('[data-slot="applied-bar"]')).toBeNull();
  });

  it('puts the way in and the way out in one row under the tree', () => {
    const { filter } = panel();
    act(() => filter().addLeaf('warehouse'));
    const actions = document.querySelector(
      '[data-slot="filter-actions"]',
    ) as HTMLElement;

    // The top row is the mode switch alone; everything that acts on the tree
    // sits under the tree it acts on.
    expect(
      within(actions).getByRole('combobox', { name: 'Add' }),
    ).toBeDefined();
    expect(
      within(actions).getByRole('button', { name: 'Clear' }),
    ).toBeDefined();
    expect(
      within(actions).getByRole('button', { name: /Apply/ }),
    ).toBeDefined();
    const conditions = document.querySelector(
      '[data-slot="filter-conditions"]',
    ) as HTMLElement;
    expect(
      actions.compareDocumentPosition(conditions) &
        Node.DOCUMENT_POSITION_PRECEDING,
    ).toBeTruthy();
  });

  it('keeps the fields to add with when it has no way out of its own', () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => testSource(),
    });
    const runtime = engine.create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: recordConfig(),
    });
    function Probe() {
      return <FilterPanel filter={useFilterEditor(runtime)} submit={false} />;
    }
    render(<Probe />);

    // An editor applied from elsewhere keeps its fields and loses the pair
    // that would run the query a second time.
    expect(screen.getByRole('combobox', { name: 'Add' })).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Clear' })).toBeNull();
    expect(screen.queryByRole('button', { name: /Apply/ })).toBeNull();
  });

  it('marks a condition, and the button that would run it, as not applied', async () => {
    const { filter } = panel();
    act(() => filter().addLeaf('warehouse'));
    const pill = () =>
      screen.getByRole('group', { name: 'Warehouse condition' });
    const apply = () => screen.getByRole('button', { name: /Apply/ });

    // A draft is only worth keeping apart from what ran if the difference is
    // visible, and it is visible on the condition that carries it.
    expect(pill().hasAttribute('data-pending')).toBe(true);
    expect(within(pill()).getByText('Not applied yet')).toBeDefined();
    expect(apply().hasAttribute('data-pending')).toBe(true);

    act(() => filter().submit());

    await waitFor(() =>
      expect(pill().hasAttribute('data-pending')).toBe(false),
    );
    expect(apply().hasAttribute('data-pending')).toBe(false);
  });

  it('marks a group that was flipped since the last apply', () => {
    const { filter } = panel();
    act(() => {
      filter().setMode('advanced');
      filter().addGroup('or');
    });

    const group = screen.getByRole('group', { name: 'Any of' });
    expect(group.hasAttribute('data-pending')).toBe(true);
  });

  it('marks nothing inside a predicate, which has nothing to compare against', async () => {
    const { filter } = panel(false, withItems());
    act(() => filter().addLeaf('items'));

    // The outer condition is new, so it is pending; the tree it carries is
    // edited straight into that leaf and has no applied tree of its own.
    const block = await screen.findByRole('group', { name: 'Items condition' });
    expect(block.hasAttribute('data-pending')).toBe(true);
    expect(
      block
        .querySelector('[data-slot="filter-group"]')
        ?.hasAttribute('data-pending'),
    ).toBe(false);
  });

  it('refuses to apply while a condition is wrong, and says how many', () => {
    const { filter } = panel();
    act(() => {
      filter().addLeaf('amount');
      filter().updateLeaf([0], { value: 'ten' as never });
    });
    const apply = () =>
      screen.getByRole('button', { name: /Apply/ }) as HTMLButtonElement;

    // The pill says where; this says how many, beside the button that will
    // not move until they are gone.
    expect(apply().disabled).toBe(true);
    expect(screen.getByText('1 to fix')).toBeDefined();

    act(() => filter().updateLeaf([0], { value: 10 }));

    expect(apply().disabled).toBe(false);
    expect(screen.queryByText('1 to fix')).toBeNull();
  });

  it('reads a stored leaf with a stray children property as a condition', () => {
    const { filter } = panel();
    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { children: null } as never);
    });

    // Admission and the walk read it as a leaf; so does the strip.
    expect(
      screen.getByRole('group', { name: 'Warehouse condition' }),
    ).toBeDefined();
    expect(
      document.querySelectorAll('[data-slot="filter-group"]'),
    ).toHaveLength(0);
  });

  it('lays a group conditions out in one strip, as pills', () => {
    const { filter } = panel();
    act(() => {
      filter().addLeaf('warehouse');
      filter().addLeaf('status');
      filter().addLeaf('amount');
    });

    const strips = document.querySelectorAll('[data-slot="filter-conditions"]');
    expect(strips).toHaveLength(1);
    expect(
      strips[0].querySelectorAll('[data-slot="filter-condition"]'),
    ).toHaveLength(3);
    expect(
      screen.getByRole('group', { name: 'Warehouse condition' }),
    ).toBeDefined();
  });

  it('marks a condition blank until it says something, and invalid when wrong', () => {
    const { filter } = panel();
    act(() => filter().addLeaf('amount'));
    const pill = () => screen.getByRole('group', { name: 'Amount condition' });

    expect(pill().hasAttribute('data-blank')).toBe(true);
    act(() => filter().updateLeaf([0], { value: 10 }));
    expect(pill().hasAttribute('data-blank')).toBe(false);
    expect(pill().hasAttribute('data-invalid')).toBe(false);
    act(() => filter().updateLeaf([0], { value: 'ten' as never }));
    expect(pill().hasAttribute('data-invalid')).toBe(true);
  });

  it('renders a condition that holds a tree as a block, like a group', async () => {
    const { filter } = panel(false, withItems());
    act(() => filter().addLeaf('items'));

    const block = await screen.findByRole('group', { name: 'Items condition' });
    expect(block.getAttribute('data-slot')).toBe('filter-element');
    // Nothing said inside it yet: blank, like a condition with no value.
    expect(block.hasAttribute('data-blank')).toBe(true);
    // Its own conditions strip sits inside it.
    expect(block.querySelector('[data-slot="filter-group"]')).not.toBeNull();
  });

  it('shows a tree whole, groups and their leaves included', () => {
    const { filter } = panel();
    act(() => {
      filter().addLeaf('warehouse');
      filter().addGroup('or');
      filter().addLeaf('status', [1]);
    });

    // The nested condition stays visible and editable rather than dropped.
    expect(screen.getByLabelText('warehouse value')).toBeDefined();
    expect(screen.getByLabelText('status value')).toBeDefined();
    expect(screen.getByRole('group', { name: 'Any of' })).toBeDefined();
  });

  it('flips a group between all and any', () => {
    const { filter } = panel();
    act(() => filter().addGroup('and'));
    // The root stays `All of`; the toggle inside the nested group is the one
    // that flips, and both render an "Any of" button of their own.
    const toggles = document.querySelector(
      '[aria-label="Group operator 0"]',
    ) as HTMLElement;

    fireEvent.click(within(toggles).getByRole('button', { name: 'Any of' }));

    expect(filter().tree.children[0]).toMatchObject({ op: 'or' });
  });

  it('flips the root group too, not only the nested ones', () => {
    const { filter } = panel();
    act(() => {
      filter().setMode('advanced');
      filter().addLeaf('warehouse');
    });
    const root = document.querySelector(
      '[aria-label="Group operator"]',
    ) as HTMLElement;

    fireEvent.click(within(root).getByRole('button', { name: 'Any of' }));

    expect(filter().tree.op).toBe('or');
  });

  it('offers "none of" and writes it to the tree', () => {
    const { filter } = panel();
    act(() => {
      filter().setMode('advanced');
      filter().addLeaf('warehouse');
    });
    const root = document.querySelector(
      '[aria-label="Group operator"]',
    ) as HTMLElement;

    fireEvent.click(within(root).getByRole('button', { name: 'None of' }));

    // Wow's third logical operator; a group is the only place a config can
    // say "none of these".
    expect(filter().tree.op).toBe('nor');
  });

  it('names an operator the catalogue spells out, and derives the rest', () => {
    const { filter } = panel();
    act(() => filter().addLeaf('warehouse'));

    const options = screen.getByRole('combobox', {
      name: /Warehouse operator/i,
    });

    // `EQ` reads fine derived; `NOT_IN` as "not in" does not, so it has an
    // entry. Neither should ever render as its key.
    expect(options.textContent).not.toContain('label.operator');
    expect(filter().operatorsFor('warehouse')).toContain('NOT_IN');
  });

  /**
   * A condition whose value is a condition. The kernel could express it and
   * the editor could not, which is the shape of mistake this package has made
   * before — a pipeline computing something nothing renders.
   */
  it('builds a condition inside an element match', async () => {
    const { filter } = panel(false, withItems());
    const user = userEvent.setup();

    act(() => filter().addLeaf('items'));

    // The row renders the same group builder the outer filter uses, over the
    // fields the entries declare rather than the view's own. Its controls
    // carry the field's name so they are not two "Group operator"s.
    await waitFor(() =>
      expect(screen.getByLabelText('Items Group operator')).toBeTruthy(),
    );
    await user.click(
      screen.getByRole('combobox', {
        name: 'Items Add in this group',
      }),
    );
    await user.click(await screen.findByRole('option', { name: 'SKU' }));

    const predicate = filter().tree.children[0] as unknown as {
      value: FilterTree;
    };
    expect(predicate.value.children[0]).toMatchObject({ field: 'items.sku' });
  });

  it('offers the entry fields, not the view fields', async () => {
    const { filter } = panel(false, withItems());
    const user = userEvent.setup();

    act(() => filter().addLeaf('items'));
    await user.click(
      screen.getByRole('combobox', {
        name: 'Items Add in this group',
      }),
    );

    // `warehouse` belongs to the order, not to a line, and a predicate that
    // named it would compile into something Wow cannot answer.
    expect(await screen.findByRole('option', { name: 'SKU' })).toBeTruthy();
    expect(screen.queryByRole('option', { name: 'Warehouse' })).toBeNull();
  });

  it('marks the row inside a predicate that is wrong, not the one holding it', async () => {
    const { filter } = panel(false, withItems());
    const user = userEvent.setup();

    act(() => filter().addLeaf('items'));
    await user.click(
      screen.getByRole('combobox', {
        name: 'Items Add in this group',
      }),
    );
    await user.click(await screen.findByRole('option', { name: 'Qty' }));

    // A number field given text: the kind reports it under the leaf that
    // carries the predicate, and the row inside is what has to light up.
    act(() =>
      filter().updateLeaf([0], {
        value: {
          op: 'and',
          children: [{ field: 'items.qty', operator: 'EQ', value: 'x' }],
        } as never,
      }),
    );

    await waitFor(() => {
      const invalid = document.querySelectorAll('[data-invalid]');
      expect(invalid.length).toBe(1);
      expect(invalid[0].textContent).toContain('Qty');
    });
  });

  it('disables the group operator with the rest of the panel', () => {
    const { filter } = panel(true);
    act(() => filter().setMode('advanced'));
    const root = document.querySelector(
      '[aria-label="Group operator"]',
    ) as HTMLElement;
    // The panel freezes the tree while a query runs; the operator toggle is
    // part of the tree.
    expect(
      within(root).getByRole('button', { name: 'Any of' }).ariaDisabled,
    ).toBe('true');
  });

  it('adds a condition inside the group it was asked for', async () => {
    const { filter } = panel();
    act(() => filter().addGroup('or'));
    const group = screen.getByRole('group', { name: 'Any of' });

    fireEvent.click(
      within(group).getByRole('combobox', {
        name: 'Add in this group',
      }),
    );
    fireEvent.click(await screen.findByRole('option', { name: 'Warehouse' }));

    expect(filter().tree.children[0]).toMatchObject({
      op: 'or',
      children: [{ field: 'warehouse' }],
    });
  });

  it('removes a group with everything in it', () => {
    const { filter } = panel();
    act(() => {
      filter().addLeaf('warehouse');
      filter().addGroup('or');
      filter().addLeaf('status', [1]);
    });

    fireEvent.click(screen.getByRole('button', { name: 'Remove group' }));

    expect(filter().tree.children).toHaveLength(1);
    expect(filter().tree.children[0]).toMatchObject({ field: 'warehouse' });
  });

  it('shows a notice instead of rendering an over-budget tree', () => {
    // A stored tree can exceed the depth budget; the validator reports it as
    // an error, and recursing into it anyway would build as many DOM nodes
    // as the store saw fit to save.
    const deep: { op: 'and'; children: unknown[] } = {
      op: 'and',
      children: [],
    };
    let node = deep;
    for (let depth = 0; depth < 5_000; depth += 1) {
      const child = { op: 'and' as const, children: [] as unknown[] };
      node.children.push(child);
      node = child;
    }
    const { engine } = setup();
    const runtime = engine.create('orders', {
      title: 'Deep',
      scope: 'personal',
      config: recordConfig({ filter: deep as never }),
    });
    let latest: ReturnType<typeof useFilterEditor> | null = null;
    function Probe() {
      const filter = useFilterEditor(runtime);
      latest = filter;
      return <FilterPanel filter={filter} />;
    }

    expect(() => render(<Probe />)).not.toThrow();
    const editor = latest as ReturnType<typeof useFilterEditor> | null;
    expect(
      editor?.issues.some(found => found.code === 'filter.tree.too-deep'),
    ).toBe(true);
    expect(
      screen.getByText('This filter is too large to edit here.'),
    ).toBeDefined();
  });

  it('skips the applied summary of an over-budget tree', () => {
    // Opening a saved over-wide view leaves the oversized tree as `applied`
    // too (apply is refused), and summarising it would walk every leaf and
    // render one badge per condition.
    const wide = {
      op: 'and' as const,
      children: Array.from({ length: 5_000 }, () => ({
        field: 'warehouse',
        operator: 'EQ',
        value: 'CN',
      })),
    };
    const { engine } = setup();
    const runtime = engine.create('orders', {
      title: 'Wide',
      scope: 'personal',
      config: recordConfig({ filter: wide as never }),
    });
    let latest: ReturnType<typeof useFilterEditor> | null = null;
    function Probe() {
      const filter = useFilterEditor(runtime);
      latest = filter;
      return <FilterPanel filter={filter} />;
    }

    render(<Probe />);

    const editor = latest as ReturnType<typeof useFilterEditor> | null;
    expect(editor?.applied).toEqual([]);
    // Nor does the editor recurse into it: the notice stands in for the tree.
    expect(
      document.querySelectorAll('[data-slot="filter-condition"]').length,
    ).toBe(0);
    expect(
      screen.getByText('This filter is too large to edit here.'),
    ).toBeDefined();
  });

  it('keeps Clear as the way out of an over-budget tree', () => {
    // Nine empty groups hit the depth budget; with no leaf, Clear would be
    // the only undo, and disabling it would leave the tree stuck.
    const deep: { op: 'and'; children: unknown[] } = {
      op: 'and',
      children: [],
    };
    let node = deep;
    for (let depth = 0; depth < 12; depth += 1) {
      const child = { op: 'and' as const, children: [] as unknown[] };
      node.children.push(child);
      node = child;
    }
    const { engine } = setup();
    const runtime = engine.create('orders', {
      title: 'Deep',
      scope: 'personal',
      config: recordConfig({ filter: deep as never }),
    });
    function Probe() {
      return <FilterPanel filter={useFilterEditor(runtime)} />;
    }
    render(<Probe />);

    expect(
      (screen.getByRole('button', { name: 'Clear' }) as HTMLButtonElement)
        .disabled,
    ).toBe(false);
  });

  it('shows the effective mode when a simple config holds an advanced tree', () => {
    const { engine } = setup();
    const runtime = engine.create('orders', {
      title: 'Mixed',
      scope: 'personal',
      config: recordConfig({
        filterMode: 'simple',
        filter: {
          op: 'or',
          children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
        },
      }),
    });
    function Probe() {
      return <FilterPanel filter={useFilterEditor(runtime)} />;
    }
    render(<Probe />);

    // The tree needs the advanced editor; the mode toggle says so rather
    // than claiming Simple over a group editor.
    expect(screen.getByRole('button', { name: 'Advanced' }).ariaPressed).toBe(
      'true',
    );
    expect(screen.getByRole('button', { name: 'Simple' }).ariaPressed).toBe(
      'false',
    );
  });

  /**
   * The pill read every issue at its path as "invalid", so a warning — a
   * finding that blocks nothing — painted the same red as a value the kind
   * refused. No built-in kind warns about a leaf, so one is registered here:
   * a number it will round, worth pointing out and not worth refusing.
   */
  it('marks a condition with a warning apart from one that is invalid', async () => {
    const rounded: FieldKind = {
      id: 'rounded',
      operators: ['EQ'],
      defaultOperator: 'EQ',
      emptyValue: () => null,
      validate: ({ value, path }) =>
        typeof value !== 'number'
          ? [{ code: 'filter.value.expected-number', severity: 'error', path }]
          : Number.isInteger(value)
            ? []
            : [{ code: 'filter.value.rounded', severity: 'warning', path }],
      compile: ({ leaf, field }) => ({
        op: FilterOperator.EQ,
        field: field.name,
        value: Math.round(leaf.value as number),
      }),
      editor: () => ({ input: 'number' }),
      describe: ({ leaf, field }) => `${field.label} = ${String(leaf.value)}`,
    };
    const base = ordersDefinition();
    const engine = new ViewEngine({
      definitions: [
        {
          ...base,
          fields: [
            ...base.fields,
            { name: 'weight', label: 'Weight', kind: 'rounded' },
          ],
        },
      ],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => testSource(),
      kinds: withFieldKinds(builtinFieldKinds, [rounded]),
    });
    const runtime = engine.create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: recordConfig(),
    });
    let latest: ReturnType<typeof useFilterEditor> | null = null;
    function Probe() {
      const filter = useFilterEditor(runtime);
      latest = filter;
      return <FilterPanel filter={filter} />;
    }
    render(<Probe />);
    const filter = () => latest as ReturnType<typeof useFilterEditor>;
    const pill = () => screen.getByRole('group', { name: 'Weight condition' });

    act(() => filter().addLeaf('weight'));
    act(() => filter().updateLeaf([0], { value: 2.5 }));

    expect(pill().hasAttribute('data-warning')).toBe(true);
    expect(pill().hasAttribute('data-invalid')).toBe(false);
    // A warning blocks nothing: the condition applies as it stands — and the
    // summary describes the query that came back, so it waits for one.
    act(() => filter().submit());
    await waitFor(() => expect(filter().applied).toHaveLength(1));

    act(() => filter().updateLeaf([0], { value: 'heavy' as never }));

    expect(pill().hasAttribute('data-invalid')).toBe(true);
    expect(pill().hasAttribute('data-warning')).toBe(false);
  });
});

describe('FilterPanel and auto refresh', () => {
  /** The panel over a fresh runtime, with the runtime in reach. */
  function panelWithRuntime() {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => testSource(),
    });
    const runtime = engine.create('orders', {
      title: 'Scratch',
      scope: 'personal',
      config: recordConfig({
        filter: {
          op: 'and',
          children: [
            { field: 'warehouse', operator: 'EQ', value: 'CN' },
            { field: 'status', operator: 'EQ', value: 'open' },
          ],
        },
      }),
    });
    function Probe() {
      return <FilterPanel filter={useFilterEditor(runtime)} />;
    }
    render(
      <>
        <Probe />
        <button type="button">Elsewhere</button>
      </>,
    );
    const editing = () => runtime.getSnapshot().editing;
    return { runtime, editing };
  }

  it('holds the timer while an input inside has focus, and lets go after', () => {
    const { editing } = panelWithRuntime();
    const input = screen.getByLabelText('warehouse value');

    expect(editing()).toBe(false);
    fireEvent.focus(input, { relatedTarget: null });
    expect(editing()).toBe(true);

    fireEvent.blur(input, {
      relatedTarget: screen.getByRole('button', { name: 'Elsewhere' }),
    });
    expect(editing()).toBe(false);
  });

  it('does not let go while focus moves between two inputs inside', () => {
    const { runtime, editing } = panelWithRuntime();
    const setEditing = vi.spyOn(runtime, 'setEditing');
    const first = screen.getByLabelText('warehouse value');
    const second = screen.getByLabelText('status value');

    fireEvent.focus(first, { relatedTarget: null });
    // A move within the panel: the blur names the input gaining focus and
    // the focus names the one losing it. Neither crosses the panel's edge.
    fireEvent.blur(first, { relatedTarget: second });
    fireEvent.focus(second, { relatedTarget: first });

    expect(editing()).toBe(true);
    expect(setEditing).toHaveBeenCalledTimes(1);
    expect(setEditing).toHaveBeenCalledWith(true);
  });

  it('keeps holding while focus is in a popup of one of its controls', () => {
    const { editing } = panelWithRuntime();
    const input = screen.getByLabelText('warehouse value');
    const trigger = screen.getByRole('combobox', {
      name: /Warehouse operator/i,
    });
    fireEvent.focus(input, { relatedTarget: null });

    // A select's list renders in a portal outside the panel, so focus moving
    // into it looks like leaving. Base UI marks the trigger of an open popup
    // with `data-popup-open`, which is what the panel goes by.
    trigger.setAttribute('data-popup-open', '');
    fireEvent.blur(trigger, { relatedTarget: document.body });
    expect(editing()).toBe(true);

    // Closed again, focus back on the trigger: a later blur is a real leave.
    trigger.removeAttribute('data-popup-open');
    fireEvent.focus(trigger, { relatedTarget: document.body });
    fireEvent.blur(trigger, {
      relatedTarget: screen.getByRole('button', { name: 'Elsewhere' }),
    });
    expect(editing()).toBe(false);
  });

  it('lets go when focus leaves the document altogether', () => {
    const { editing } = panelWithRuntime();
    const input = screen.getByLabelText('warehouse value');

    fireEvent.focus(input, { relatedTarget: null });
    fireEvent.blur(input, { relatedTarget: null });

    expect(editing()).toBe(false);
  });
});

describe('the summary row', () => {
  const withSummary: ViewInstance = {
    ...mine,
    config: recordConfig({ summaries: [{ field: 'amount', fn: 'SUM' }] }),
  };

  function setupSummary(source: ViewSource = testSource()) {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [withSummary] }),
      resolveSource: () => source,
    });
    return render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
  }

  it('shows the total the aggregation returned', async () => {
    const { container } = setupSummary();

    const footer = await waitFor(() => {
      const found = container.querySelector('tfoot');
      expect(found).not.toBeNull();
      return found as HTMLElement;
    });

    expect(footer.dataset.scope).toBe('total');
    expect(footer.textContent).toContain('30');
  });

  /**
   * A failed summary query costs the summary, not the page. What it must not
   * cost is the reader's ability to tell the two numbers apart: the sum of the
   * rows on screen presented as the sum over everything is the one mistake
   * this row could make.
   */
  it('shows every function configured for one field', async () => {
    const both: ViewInstance = {
      ...mine,
      config: recordConfig({
        summaries: [
          { field: 'amount', fn: 'SUM' },
          { field: 'amount', fn: 'AVG' },
        ],
      }),
    };
    // The shared fixture allows only SUM on `amount`; this view asks for two.
    const definition = ordersDefinition();
    const engine = new ViewEngine({
      definitions: [
        {
          ...definition,
          fields: definition.fields.map(field =>
            field.name === 'amount'
              ? { ...field, summary: ['SUM' as const, 'AVG' as const] }
              : field,
          ),
        },
      ],
      store: new MemoryViewStore({ instances: [both] }),
      resolveSource: () => testSource(),
    });
    const { container } = render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );

    const footer = await waitFor(() => {
      const found = container.querySelector('tfoot');
      expect(found).not.toBeNull();
      return found as HTMLElement;
    });

    // Keying the cells by field would keep only the last one configured.
    expect(footer.textContent).toContain('SUM');
    expect(footer.textContent).toContain('AVG');
  });

  it('says so when it fell back to the rows on screen', async () => {
    const { container } = setupSummary(
      testSource({ aggregate: () => Promise.reject(new Error('down')) }),
    );

    const footer = await waitFor(() => {
      const found = container.querySelector('tfoot');
      expect(found).not.toBeNull();
      return found as HTMLElement;
    });

    expect(footer.dataset.scope).toBe('page');
    expect(footer.textContent).toContain('This page');
  });
});

describe('EmbeddedView', () => {
  function embed(config: ViewInstance['config']) {
    return new ViewEngine({
      definitions: [namedOrdersDefinition()],
      store: new MemoryViewStore({ instances: [{ ...mine, config }] }),
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
  }

  it("shows times on the clock of the engine's zone, in the language given", async () => {
    const engine = embed(
      recordConfig({
        table: { columns: [{ field: 'id' }, { field: 'createdAt' }] },
      }),
    );

    render(
      <EmbeddedView engine={engine} instanceId="orders-1" locale="en-GB" />,
    );

    expect(await screen.findByText(inZone(INSTANT))).toBeDefined();
  });

  it("takes the host's wording, for its own alerts and everything inside", async () => {
    render(
      <EmbeddedView
        engine={setup().engine}
        instanceId="missing"
        messages={{ 'label.view.unopenable': '打不开这个视图' }}
      />,
    );
    expect(await screen.findByText('打不开这个视图')).toBeDefined();
    cleanup();

    render(
      <EmbeddedView
        engine={setup().engine}
        instanceId="orders-1"
        messages={{ 'label.record.select-all': '全选' }}
      />,
    );
    expect(await screen.findByRole('checkbox', { name: '全选' })).toBeDefined();
  });

  it('names chart categories as their field names its values', async () => {
    // The table shows only the count; the chart still groups by warehouse,
    // and names its bars through the schema rather than the table's columns.
    const engine = embed(
      analysisConfig({
        layout: 'chart',
        table: { columns: [{ alias: 'orders' }] },
      }),
    );

    // Recharts measures text in a span of its own on the body; the chart is
    // what is asked about.
    const { container } = render(
      <EmbeddedView engine={engine} instanceId="orders-1" />,
    );

    expect(await within(container).findByText('China')).toBeDefined();
  });

  it('shows the result and none of the workbench chrome', async () => {
    const { engine } = setup();

    render(<EmbeddedView engine={engine} instanceId="orders-1" />);

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    // What a business page embeds is the answer, not a second application:
    // no view list, no condition editor, no save commands.
    expect(screen.queryByRole('button', { name: /Apply/ })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Save/ })).toBeNull();
  });

  it('runs the host condition without touching the saved config', async () => {
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

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    await waitFor(() =>
      expect(
        vi
          .mocked(source.paged)
          .mock.calls.some(([query]) =>
            JSON.stringify(query.filter).includes('CN'),
          ),
      ).toBe(true),
    );

    const runtime = engine.openRuntimes()[0];
    expect(runtime.getSnapshot().dirty).toBe(false);
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

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain(
        'The source answered: down',
      ),
    );
  });

  /**
   * A refused narrowing leaves the previous one running. The hook dropped the
   * issues `setScopeFilter` returns, so the embed went on showing a result
   * for a condition the page had already replaced.
   */
  it('says so when a narrowing it is given later is refused', async () => {
    const { engine, source } = setup();
    const scope: FilterTree = {
      op: 'and',
      children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
    };
    const { rerender } = render(
      <EmbeddedView
        engine={engine}
        instanceId="orders-1"
        scopeFilter={scope}
      />,
    );
    await waitFor(() =>
      expect(vi.mocked(source.paged).mock.calls.length).toBeGreaterThan(0),
    );

    rerender(
      <EmbeddedView
        engine={engine}
        instanceId="orders-1"
        scopeFilter={{
          op: 'and',
          children: [{ field: 'nope', operator: 'EQ', value: 'x' }],
        }}
      />,
    );

    expect(await screen.findByText(/could not narrow/i)).toBeTruthy();
  });

  it('reports a view it cannot open', async () => {
    const { engine } = setup();

    render(<EmbeddedView engine={engine} instanceId="gone" />);

    await waitFor(() =>
      expect(screen.getByText(/could not be opened/i)).toBeDefined(),
    );
  });

  /**
   * An embed hides the editor, so this notice is the one way a reader learns
   * the view is not quite what its author saved. Unlike an error it does not
   * take the result's place: the rows are real, and they still show.
   */
  it('shows a warning above the result rather than instead of it', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mixed] }),
      resolveSource: () => testSource(),
    });

    render(<EmbeddedView engine={engine} instanceId="orders-1" />);

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    const notice = screen.getByRole('status');
    expect(notice.getAttribute('data-tone')).toBe('warning');
    expect(notice.textContent).toContain('advanced editor');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  /**
   * A config can carry both. The error branch returned before the warning
   * was rendered, so an embed said one level less than the workbench did.
   */
  it('keeps saying what is worth noting when an error takes the result place', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({
        instances: [
          {
            ...mixed,
            config: recordConfig({
              filterMode: 'simple',
              filter: {
                op: 'or',
                children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
              },
              // Blocks: the page size must be positive.
              pageSize: 0,
            }),
          },
        ],
      }),
      resolveSource: () => testSource(),
    });

    render(<EmbeddedView engine={engine} instanceId="orders-1" />);

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain('needs fixing'),
    );
    expect(screen.getByRole('status').textContent).toContain('advanced editor');
    expect(screen.queryByRole('row')).toBeNull();
  });

  /**
   * The page narrowed the view, and the bar above the rows is the only place
   * that says so. It is named as the page's rather than mixed in with the
   * view's own conditions, and it carries no remove: no path of the editor
   * addresses it, so the only thing a ✕ could do is fail.
   */
  it("names the host scope among the conditions, as nobody's to remove", async () => {
    const { engine } = setup();

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

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    const scoped = await waitFor(() => {
      const badge = document.querySelector('[data-scoped]');
      if (!badge) throw new Error('no scoped badge yet');
      return badge;
    });
    expect(scoped.textContent).toContain('CN');
    expect(scoped.textContent).toContain('Set by the page');
  });

  /**
   * An embed shows what somebody already decided. The ✕ on a saved condition
   * would let a reader widen the view — on a page that embedded "this
   * customer's shipments", that is the page listing everyone's.
   */
  it('offers no way to drop a saved condition from the summary', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({
        instances: [
          {
            ...mine,
            config: recordConfig({
              filter: {
                op: 'and',
                children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
              },
            }),
          },
        ],
      }),
      resolveSource: () => testSource(),
    });

    render(<EmbeddedView engine={engine} instanceId="orders-1" />);

    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));
    expect(
      document.querySelector('[data-slot="applied-bar"]')?.textContent,
    ).toContain('CN');
    expect(screen.queryByRole('button', { name: /^Unset/ })).toBeNull();
  });

  /**
   * The strip says "showing the last successful result", so the last
   * successful result has to still be there. Replacing the table with the
   * failure made that line describe an empty frame.
   */
  it('keeps the rows under the line that reports a failed refresh', async () => {
    let attempt = 0;
    const source = testSource({
      paged: () => {
        attempt += 1;
        return attempt === 1
          ? Promise.resolve({ total: 2, list: [{ id: 'o-1' }, { id: 'o-2' }] })
          : Promise.reject(new Error('down'));
      },
    });
    const { engine } = setup(source);

    render(<EmbeddedView engine={engine} instanceId="orders-1" />);
    await waitFor(() => expect(screen.getAllByRole('row')).toHaveLength(3));

    act(() => engine.openRuntimes()[0].refresh());

    await waitFor(() =>
      expect(screen.getByRole('alert').textContent).toContain(
        'The source answered: down',
      ),
    );
    // Both at once, the way the workbenches do it: the rows that did come
    // back are still the real ones, and the strip says so behind its
    // disclosure rather than over an empty frame.
    fireEvent.click(screen.getByRole('button', { name: '1 more' }));
    expect(screen.getByRole('alert').textContent).toContain(
      'Showing the last successful result',
    );
    expect(screen.getAllByRole('row')).toHaveLength(3);
  });
});

describe('WarningStrip', () => {
  it('renders nothing when there is no warning to report', () => {
    const { container } = render(
      <WarningStrip issues={[{ code: 'x', severity: 'error', path: [] }]} />,
    );

    expect(container.innerHTML).toBe('');
  });

  it('wears the warning colour, a class of its own, and a status role', () => {
    render(
      <WarningStrip
        className="mt-2"
        issues={[
          { code: 'blocking.elsewhere', severity: 'error', path: [] },
          {
            code: 'config.filterMode.not-simple',
            severity: 'warning',
            path: ['filterMode'],
          },
        ]}
      />,
    );

    // A status, not an alert: a screen reader mentions it without
    // interrupting whatever its user was doing.
    const notice = screen.getByRole('status');
    expect(notice.className).toContain('border-warning');
    expect(notice.className).toContain('mt-2');
    // One finding is its own sentence: no count to read, nothing to unfold.
    expect(notice.textContent).toContain('advanced editor');
    expect(notice.textContent).not.toContain('worth noting');
    // Only the warnings; the error has a strip of its own elsewhere.
    expect(notice.textContent).not.toContain('blocking.elsewhere');
    expect(screen.queryByRole('button', { name: /more/ })).toBeNull();
  });

  /**
   * A dashboard validates a global condition once as its own and once per
   * panel it maps onto, so the same sentence arrived twice with two paths.
   * The code and the params are the sentence; one of each is said.
   */
  it('says the same sentence once, however many paths raise it', () => {
    render(
      <WarningStrip
        issues={[
          {
            code: 'config.filterMode.not-simple',
            severity: 'warning',
            path: [],
          },
          {
            code: 'config.filterMode.not-simple',
            severity: 'warning',
            path: ['panels', 0, 'filterMode'],
          },
          {
            code: 'record.summary.unsupported',
            severity: 'warning',
            path: ['summaries', 0],
            params: { field: 'Amount', fn: 'AVG' },
          },
          {
            code: 'record.summary.unsupported',
            severity: 'warning',
            path: ['summaries', 1],
            params: { field: 'Amount', fn: 'SUM' },
          },
        ]}
      />,
    );

    // Three sentences behind one line, which says how many there are.
    expect(screen.getByRole('status').textContent).toContain(
      '3 things worth noting',
    );
    fireEvent.click(screen.getByRole('button', { name: '3 more' }));

    const text = screen.getByRole('status').textContent ?? '';
    expect(text.match(/advanced editor/g)).toHaveLength(1);
    expect(text).toContain('AVG summary');
    expect(text).toContain('SUM summary');
  });
});

describe('ErrorStrip', () => {
  /** A draft holding two conditions, which is what the editor draws pills for. */
  const twoConditions: FilterTree = {
    op: 'and',
    children: [
      { field: 'warehouse', operator: 'EQ', value: 'CN' },
      { field: 'status', operator: 'EQ', value: 'PENDING' },
    ],
  };

  it('leaves the conditions the editor marks to the editor', () => {
    // A wrong condition is marked on its own pill and counted on Apply,
    // which is where it can be fixed; the strip would only say it again.
    const marked = unmarkedErrors(
      [
        {
          code: 'filter.value.expected-date',
          severity: 'error',
          path: ['children', 0],
        },
        {
          code: 'record.column.unknown',
          severity: 'error',
          path: ['table', 'columns', 0],
          params: { field: 'gone' },
        },
        {
          code: 'config.filterMode.not-simple',
          severity: 'warning',
          path: [],
        },
      ],
      twoConditions,
    );

    expect(marked.map(found => found.code)).toEqual(['record.column.unknown']);

    render(<ErrorStrip issues={marked} />);
    const strip = screen.getByRole('alert');
    expect(strip.className).toContain('border-destructive');
    expect(strip.textContent).toContain('needs fixing');
  });

  it('says nothing when every error is marked elsewhere', () => {
    const { container } = render(
      <ErrorStrip
        issues={unmarkedErrors(
          [
            {
              code: 'filter.value.expected-text',
              severity: 'error',
              path: ['children', 1],
            },
          ],
          twoConditions,
        )}
      />,
    );

    expect(container.innerHTML).toBe('');
  });

  /**
   * A condition-shaped path is not the same thing as a pill. The panel skips
   * a malformed child — there is nothing to draw a field, an operator or a
   * value editor from — so a finding about it is marked nowhere, and the
   * strip is the only place it can ever be read.
   */
  it('keeps an error about a node the editor cannot draw', () => {
    const kept = unmarkedErrors(
      [
        {
          code: 'filter.node.invalid',
          severity: 'error',
          path: ['children', 0],
        },
      ],
      { op: 'and', children: [null as unknown as FilterTree] },
    );

    expect(kept.map(found => found.path)).toEqual([['children', 0]]);
  });

  /** A group carries no marker of its own; only its conditions do. */
  it('keeps an error about a group, which wears no mark', () => {
    const kept = unmarkedErrors(
      [
        {
          code: 'filter.group.duplicate-field',
          severity: 'error',
          path: ['children', 0],
          params: { field: 'warehouse' },
        },
      ],
      { op: 'and', children: [{ op: 'or', children: [] }] },
    );

    expect(kept).toHaveLength(1);
  });

  /**
   * A condition inside an element match is drawn by the same components,
   * from the tree the leaf carries, so it wears a pill like any other.
   */
  it('leaves a condition inside a predicate to the editor', () => {
    const kept = unmarkedErrors(
      [
        {
          code: 'filter.value.expected-text',
          severity: 'error',
          path: ['children', 0, 'children', 1],
        },
      ],
      {
        op: 'and',
        children: [
          {
            field: 'lines',
            operator: 'ELEMENT_MATCH',
            value: {
              op: 'and',
              children: [
                { field: 'sku', operator: 'EQ', value: 'a' },
                { field: 'qty', operator: 'EQ', value: 1 },
              ],
            } as unknown as FilterValue,
          },
        ],
      },
    );

    expect(kept).toEqual([]);
  });
});

/**
 * A stored tree can hold a child that is not a node at all — another
 * release's shape, a broken write. The editor skips it: there is no field,
 * operator or value to draw a pill from. So the finding has to be read
 * somewhere, and Apply has to stay refused while it is there.
 */
describe('a stored condition the editor cannot draw', () => {
  const broken: ViewInstance = {
    ...mine,
    config: recordConfig({
      filter: {
        op: 'and',
        children: [
          { field: 'warehouse', operator: 'EQ', value: 'CN' },
          null as unknown as FilterTree,
        ],
      },
    }),
  };

  async function openBroken() {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [broken] }),
      resolveSource: () => testSource(),
    });
    render(
      <RecordWorkbench
        engine={engine}
        definitionId="orders"
        instanceId="orders-1"
      />,
    );
    await screen.findByRole('alert');
  }

  it('says so in the strip, where nothing else can say it', async () => {
    await openBroken();

    const strip = screen.getByRole('alert');
    expect(strip.textContent).toContain('needs fixing');
    fireEvent.click(within(strip).getByRole('button', { name: '1 more' }));
    expect(strip.textContent).toContain('This condition could not be read.');
    // One finding, one sentence: the well-formed condition beside it is fine.
    expect(within(strip).queryByRole('button', { name: '2 more' })).toBeNull();
  });

  it('refuses to apply while it is there', async () => {
    await openBroken();

    fireEvent.click(screen.getByRole('button', { name: 'Filter' }));
    const apply = (await screen.findByRole('button', {
      name: /Apply/,
    })) as HTMLButtonElement;
    expect(apply.disabled).toBe(true);
    // And says how many, beside the button that will not move: a count that
    // left out what no pill could carry would be a button disabled for
    // nothing the user can see.
    expect(screen.getByText('1 to fix')).toBeDefined();
  });
});
