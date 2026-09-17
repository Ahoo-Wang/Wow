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
  MemoryViewStore,
  ViewEngine,
  ViewStoreError,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import type { PagedList } from '@ahoo-wang/fetcher-wow';
import type {
  EditorDescriptor,
  FilterValue,
  RecordData,
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
} from '../src/ui/index.js';
import {
  analysisConfig,
  dashboardConfig,
  deferred,
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
    expect(screen.getByRole('navigation', { name: 'Views' })).toBeDefined();
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

    fireEvent.click(screen.getByRole('button', { name: /Add condition/ }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Warehouse' }));

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

    fireEvent.click(screen.getByRole('button', { name: /Add condition/ }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Warehouse' }));
    await screen.findByLabelText('warehouse value');

    fireEvent.click(screen.getByRole('button', { name: 'Remove Warehouse' }));
    expect(screen.queryByLabelText('warehouse value')).toBeNull();

    fireEvent.click(screen.getByRole('button', { name: /Add condition/ }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Warehouse' }));
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
    const { source } = await open();

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
    await user.click(
      await screen.findByRole('option', { name: 'In the last' }),
    );
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

  it('picks a day from the calendar', async () => {
    const { changes } = editor({ input: 'date' }, {
      type: 'absolute',
      from: '2026-09-16T00:00:00.000Z',
    } as unknown as FilterValue);

    const user = userEvent.setup();
    await user.click(screen.getByLabelText('amount'));
    const day = await screen.findByRole('button', { name: /September 20/ });
    await user.click(day);

    expect(last(changes)).toMatchObject({ type: 'absolute' });
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
    paging: { mode: 'paged', index: 1, total: 2 },
    summaries: null,
    status: 'success',
    error: null,
    loading: false,
    sort: [],
    sortOf: () => null,
    toggleSort: () => {},
    layout: 'table',
    setLayout: () => {},
    columnFields: ['amount', 'warehouse'],
    setColumns: () => {},
    pageSize: 20,
    setPageSize: () => {},
    selection: [],
    isSelected: () => false,
    toggle: () => {},
    toggleAll: () => {},
    clearSelection: () => {},
    goTo: () => {},
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

describe('RecordTable on its own', () => {
  it('formats each value by what the column declared', () => {
    render(<RecordTable table={tableController()} />);

    expect(screen.getByText('CN¥10.00')).toBeDefined();
    expect(screen.getByText('Yes')).toBeDefined();
    // A sortable column gets a button; a plain one is just its label.
    expect(screen.getByRole('button', { name: /Amount/ })).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Warehouse' })).toBeNull();
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
  it('titles a card by the field it was told to use', () => {
    render(<RecordCards table={tableController()} title="warehouse" />);

    const [first, second] = screen.getAllByText(
      (_text, element) =>
        (element as HTMLElement | null)?.dataset.slot === 'card-title',
    );
    expect(first.textContent).toContain('CN');
    // The second row's title field is a boolean, which reads as Yes.
    expect(second.textContent).toContain('Yes');
  });

  it('falls back to the row key without a title field', () => {
    render(<RecordCards table={tableController()} />);
    expect(screen.getByText('o-1')).toBeDefined();
  });
});

describe('ViewList on its own', () => {
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

  it('marks the open view and labels the shared ones', () => {
    const opened = vi.fn();
    render(
      <ViewSurface>
        <ViewList
          list={listState({
            items: [
              { ...mine, id: 'a', title: 'Mine', scope: 'personal' },
              { ...mine, id: 'b', title: 'Ours', scope: 'shared' },
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
    expect(screen.getByText('shared')).toBeDefined();

    fireEvent.click(screen.getByRole('button', { name: /Ours/ }));
    expect(opened).toHaveBeenCalledWith('b');
  });
});

describe('FilterPanel tree editing', () => {
  interface PanelHarness {
    filter(): ReturnType<typeof useFilterEditor>;
  }

  function panel(disabled = false): PanelHarness {
    const { engine } = setup();
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
      within(group).getByRole('button', {
        name: 'Add condition in this group',
      }),
    );
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Warehouse' }));

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

  it('reports a view it cannot open', async () => {
    const { engine } = setup();

    render(<EmbeddedView engine={engine} instanceId="gone" />);

    await waitFor(() =>
      expect(screen.getByText(/could not be opened/i)).toBeDefined(),
    );
  });
});
