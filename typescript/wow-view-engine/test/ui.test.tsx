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
} from '../src/index.js';
import type {
  RecordTableController,
  ViewListState,
} from '../src/react/index.js';
import { useFilterEditor } from '../src/react/index.js';
import {
  EmbeddedView,
  FilterPanel,
  FilterValueEditor,
  RecordCards,
  RecordTable,
  RecordWorkbench,
  SaveActions,
  ViewList,
  ViewSurface,
  WarningNotice,
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
    expect(screen.getByRole('button', { name: /Apply/ })).toBeDefined();
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
    const notice = document.querySelector('[data-slot="view-warnings"]');
    expect(notice?.getAttribute('role')).toBe('status');
    expect(notice?.textContent).toContain('Worth noting');
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

    await waitFor(() => expect(screen.getByText(/query failed/i)).toBeTruthy());
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

    await waitFor(() => expect(screen.getByText(/query failed/i)).toBeTruthy());
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

    fireEvent.click(screen.getByRole('button', { name: 'Save as' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Title'), {
      target: { value: 'My copy' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));

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

  it('saves a copy under a new title', async () => {
    const { store } = await open();

    fireEvent.click(screen.getByRole('button', { name: 'Save as' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Title'), {
      target: { value: 'Pending only' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));

    await waitFor(async () =>
      expect((await store.list('orders')).map(item => item.title)).toContain(
        'Pending only',
      ),
    );
  });

  it('renames the open view', async () => {
    const { store } = await open();

    fireEvent.click(screen.getByRole('button', { name: 'Rename' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Title'), {
      target: { value: 'Renamed' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));

    await waitFor(async () =>
      expect((await store.get('orders-1')).title).toBe('Renamed'),
    );
  });

  it('deletes after a confirmation', async () => {
    const { store } = await open();

    fireEvent.click(screen.getByRole('button', { name: /Delete/ }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'Delete' }));

    await waitFor(async () =>
      expect(await store.list('orders')).toHaveLength(1),
    );
  });

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

    fireEvent.click(screen.getByRole('button', { name: /Delete/ }));
    fireEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', {
        name: 'Delete',
      }),
    );

    // The system view is what is left, and it is the one open now.
    await waitFor(() =>
      expect(
        screen.getByRole('button', { name: /All orders/ }).ariaCurrent,
      ).toBe('true'),
    );
    expect(screen.queryByRole('button', { name: 'Mine' })).toBeNull();
    expect(screen.getAllByRole('row')).toHaveLength(3);
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

    fireEvent.click(screen.getByRole('button', { name: /Delete/ }));
    fireEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', {
        name: 'Delete',
      }),
    );

    // The runtime goes at once — disposal notifies — and the list a moment
    // later, once it has reloaded without the deleted view.
    await waitFor(() => expect(screen.queryByRole('table')).toBeNull());
    expect(await screen.findByText('No view yet')).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Mine' })).toBeNull();
  });

  it('offers a way out of a conflict', async () => {
    const { store } = await open();
    await store.save('orders-1', recordConfig({ pageSize: 30 }), '1', {
      requestId: 'other',
    });

    fireEvent.click(screen.getByRole('button', { name: /Columns/ }));
    fireEvent.click(
      await screen.findByRole('menuitemcheckbox', { name: 'Warehouse' }),
    );
    await waitFor(() =>
      expect(screen.getAllByRole('columnheader')).toHaveLength(4),
    );
    fireEvent.click(screen.getByRole('button', { name: /^Save$/ }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('saved this view first');

    fireEvent.click(screen.getByRole('button', { name: 'Keep mine' }));
    await waitFor(async () =>
      expect((await store.get('orders-1')).revision).toBe('3'),
    );
  });

  it('shows why a write was refused', async () => {
    const { store } = await open();
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('FORBIDDEN', 'not yours'),
    );

    fireEvent.click(screen.getByRole('button', { name: /Columns/ }));
    fireEvent.click(
      await screen.findByRole('menuitemcheckbox', { name: 'Warehouse' }),
    );
    await waitFor(() =>
      expect(screen.getAllByRole('columnheader')).toHaveLength(4),
    );
    fireEvent.click(screen.getByRole('button', { name: /^Save$/ }));

    const alert = await screen.findByRole('alert');
    // The code is the lookup key; what reaches the user is a sentence.
    expect(alert.textContent).toContain('You may not write to this view.');
    expect(alert.textContent).toContain('not yours');
  });

  it('saves a copy everyone can see', async () => {
    const user = userEvent.setup();
    const { store } = await open();

    fireEvent.click(screen.getByRole('button', { name: 'Save as' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Title'), {
      target: { value: 'Ours' },
    });
    await user.click(within(dialog).getByLabelText('Who can see it'));
    await user.click(await screen.findByRole('option', { name: 'Everyone' }));
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));

    await waitFor(async () =>
      expect(
        (await store.list('orders')).find(item => item.title === 'Ours')?.scope,
      ).toBe('shared'),
    );
  });

  /**
   * Save as defaulted to "Only me" whatever the store allowed, so a user who
   * may only publish shared views pressed Save and was refused by the engine
   * for a scope the dialog had picked on their behalf.
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

    fireEvent.click(screen.getByRole('button', { name: 'Save as' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Title'), {
      target: { value: 'Ours' },
    });
    expect(
      within(dialog).getByLabelText('Who can see it').textContent,
    ).toContain('Everyone');

    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));

    await waitFor(async () =>
      expect(
        (await store.list('orders')).find(item => item.title === 'Ours')?.scope,
      ).toBe('shared'),
    );
  });

  it('offers a retry when the result never came back', async () => {
    const { store } = await open();
    vi.spyOn(store, 'save').mockRejectedValueOnce(new Error('socket closed'));

    fireEvent.click(screen.getByRole('button', { name: /Columns/ }));
    fireEvent.click(
      await screen.findByRole('menuitemcheckbox', { name: 'Warehouse' }),
    );
    await waitFor(() =>
      expect(screen.getAllByRole('columnheader')).toHaveLength(4),
    );
    fireEvent.click(screen.getByRole('button', { name: /^Save$/ }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('never came back');

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(async () =>
      expect((await store.get('orders-1')).revision).toBe('2'),
    );
  });

  it('lets go of the view once a recovered delete lands', async () => {
    const { store } = await open();
    vi.spyOn(store, 'delete').mockRejectedValueOnce(new Error('socket closed'));

    fireEvent.click(screen.getByRole('button', { name: /Delete/ }));
    fireEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', {
        name: 'Delete',
      }),
    );

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('never came back');

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

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
    // Another view is the default, so a `chosen` of null would reopen that
    // one instead: the reload must not decide the user had left this view.
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

    fireEvent.click(screen.getByRole('button', { name: /Delete/ }));
    fireEvent.click(
      within(await screen.findByRole('dialog')).getByRole('button', {
        name: 'Delete',
      }),
    );

    fireEvent.click(await screen.findByRole('button', { name: 'Take theirs' }));

    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Mine' }).ariaCurrent).toBe(
        'true',
      ),
    );
    expect(screen.getByRole('table')).toBeDefined();
  });

  it('opens the copy once a recovered save-as lands', async () => {
    const { store } = await open();
    vi.spyOn(store, 'create').mockRejectedValueOnce(new Error('socket closed'));

    fireEvent.click(screen.getByRole('button', { name: 'Save as' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Title'), {
      target: { value: 'My copy' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));

    await screen.findByRole('alert');
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

    // The recovery lands like the original save-as: the copy joins the list
    // and becomes the open view.
    await waitFor(
      () =>
        expect(
          screen.getByRole('button', { name: 'My copy' }).ariaCurrent,
        ).toBe('true'),
      { timeout: 3000 },
    );
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
    // `chosen` is null and a list reload must not close the runtime.
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
    // next toolbar click, which the open menu would swallow.
    fireEvent.keyDown(document.body, { key: 'Escape' });

    fireEvent.click(screen.getByRole('button', { name: 'Rename' }));
    const dialog = await screen.findByRole('dialog');
    fireEvent.change(within(dialog).getByLabelText('Title'), {
      target: { value: 'Renamed' },
    });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save' }));

    // The rename refreshed the list; the unsaved column edit survived it.
    await waitFor(async () =>
      expect((await store.get('orders-1')).title).toBe('Renamed'),
    );
    expect(screen.getAllByRole('columnheader')).toHaveLength(4);
  });

  it('tells onRecovered about a recovered delete too', async () => {
    // A host may wire only the generic callback and keep its list fresh
    // there; a recovered delete is still a recovered write.
    const onDeleted = vi.fn();
    const onRecovered = vi.fn();
    const commands = {
      can: { save: true, saveAs: true, rename: true, delete: true },
      state: {
        pending: false,
        error: null,
        dirty: false,
        write: {
          kind: 'unknown',
          requestId: 'r1',
          payload: { action: 'delete', id: 'orders-1', revision: '1' },
        },
      },
      save: vi.fn(),
      saveAs: vi.fn(),
      rename: vi.fn(),
      delete: vi.fn(),
      retry: vi.fn().mockResolvedValue({ landed: true, instance: null }),
      abandon: vi.fn(),
      resolveConflict: vi.fn(),
    };
    render(
      <ViewSurface>
        <SaveActions
          commands={commands as never}
          title="Mine"
          onDeleted={onDeleted}
          onRecovered={onRecovered}
        />
      </ViewSurface>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

    await waitFor(() => expect(onDeleted).toHaveBeenCalled());
    await waitFor(() => expect(onRecovered).toHaveBeenCalledWith('delete'));
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

  it('takes an applied condition out of force from its badge, keeping the field', async () => {
    const { filter } = panel();
    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
      filter().submit();
    });
    await waitFor(() =>
      expect(screen.getByText('Warehouse EQ CN')).toBeDefined(),
    );

    fireEvent.click(
      screen.getByRole('button', { name: 'Unset Warehouse EQ CN' }),
    );

    await waitFor(() =>
      expect(screen.queryByText('Warehouse EQ CN')).toBeNull(),
    );
    // The row is still there, blank, for the next question.
    const pill = screen.getByRole('group', { name: 'Warehouse condition' });
    expect(pill.hasAttribute('data-blank')).toBe(true);
    expect(filter().applied).toEqual([]);
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
    expect(document.querySelectorAll('[data-slot="badge"]').length).toBe(0);
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
    // A warning blocks nothing: the condition applies as it stands.
    act(() => filter().submit());
    // The summary describes the result, so it arrives with the query.
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

    await waitFor(() => expect(screen.getByText(/query failed/i)).toBeTruthy());
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
    const notice = document.querySelector('[data-slot="view-warnings"]');
    expect(notice?.textContent).toContain('advanced editor');
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
    expect(
      document.querySelector('[data-slot="view-warnings"]')?.textContent,
    ).toContain('advanced editor');
    expect(screen.queryByRole('row')).toBeNull();
  });
});

describe('WarningNotice', () => {
  it('renders nothing when there is no warning to report', () => {
    const { container } = render(
      <WarningNotice issues={[{ code: 'x', severity: 'error', path: [] }]} />,
    );

    expect(container.innerHTML).toBe('');
  });

  it('wears the warning colour, a class of its own, and a status role', () => {
    render(
      <WarningNotice
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
    expect(notice.textContent).toContain('Worth noting');
    // Only the warnings; the error has an alert of its own elsewhere.
    expect(notice.textContent).not.toContain('blocking.elsewhere');
    expect(notice.textContent).toContain('advanced editor');
  });

  /**
   * A dashboard validates a global condition once as its own and once per
   * panel it maps onto, so the same sentence arrived twice with two paths.
   * The code and the params are the sentence; one of each is said.
   */
  it('says the same sentence once, however many paths raise it', () => {
    render(
      <WarningNotice
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

    const text = screen.getByRole('status').textContent ?? '';
    expect(text.match(/advanced editor/g)).toHaveLength(1);
    expect(text).toContain('AVG summary');
    expect(text).toContain('SUM summary');
  });
});
