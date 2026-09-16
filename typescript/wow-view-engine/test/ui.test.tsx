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
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewEngine,
  ViewStoreError,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import type { EditorDescriptor, FilterValue } from '../src/index.js';
import type {
  RecordTableController,
  ViewListState,
} from '../src/react/index.js';
import {
  FilterValueEditor,
  RecordCards,
  RecordTable,
  RecordWorkbench,
  ViewList,
  ViewSurface,
} from '../src/ui/index.js';
import { ordersDefinition, recordConfig, testSource } from './fixtures.js';

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
        'view.open.failed',
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
        'runtime.query.failed',
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
    expect(alert.textContent).toContain('view.write.forbidden');
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
});

describe('FilterValueEditor', () => {
  const CANDIDATES = [
    { value: 'CN', label: 'China' },
    { value: 'JP', label: 'Japan' },
  ];

  function editor(
    descriptor: EditorDescriptor,
    value: FilterValue = null,
    options: typeof CANDIDATES | null = CANDIDATES,
  ): { changes: FilterValue[] } {
    const changes: FilterValue[] = [];
    render(
      <ViewSurface>
        <FilterValueEditor
          editor={descriptor}
          value={value}
          label="amount"
          options={options ?? undefined}
          onChange={next => changes.push(next)}
        />
      </ViewSurface>,
    );
    return { changes };
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
