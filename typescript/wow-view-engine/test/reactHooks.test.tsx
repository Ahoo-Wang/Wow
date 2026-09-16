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

import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewCommandError,
  ViewEngine,
  ViewStoreError,
  ViewWriteError,
  issue,
  type RecordViewRuntime,
  type ViewInstance,
  type ViewPreferences,
  type ViewSource,
} from '../src/index.js';
import {
  browserRuntimeEnvironment,
  documentVisibility,
  toIssue,
  useFilterEditor,
  useOpenView,
  useRecordTable,
  useSaveCommands,
  useViewEngine,
  useViewList,
  useViewRuntime,
} from '../src/react/index.js';
import {
  ordersDefinition,
  recordConfig,
  requireRecordConfig,
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

function engineWith(
  options: {
    instances?: ViewInstance[];
    source?: ViewSource;
    store?: MemoryViewStore;
  } = {},
): { engine: ViewEngine; store: MemoryViewStore } {
  const store =
    options.store ??
    new MemoryViewStore({ instances: options.instances ?? [mine] });
  const engine = new ViewEngine({
    definitions: [ordersDefinition()],
    store,
    resolveSource: () => options.source ?? testSource(),
  });
  return { engine, store };
}

describe('useViewEngine', () => {
  it('builds one engine and disposes it on unmount', () => {
    const store = new MemoryViewStore();
    const { result, rerender, unmount } = renderHook(() =>
      useViewEngine({
        definitions: [ordersDefinition()],
        store,
        resolveSource: () => testSource(),
      }),
    );
    const engine = result.current;

    rerender();
    expect(result.current).toBe(engine);

    const runtime = engine.create('orders', {
      title: 'New',
      scope: 'personal',
      config: recordConfig(),
    });
    unmount();

    expect(engine.openRuntimes()).toEqual([]);
    // Disposal ends the open views, not the engine: a remount can reuse it,
    // which is what React's development double-invoke does.
    runtime.edit({ pageSize: 10 });
    expect(runtime.getSnapshot().draft.pageSize).toBe(20);
    expect(() =>
      engine.create('orders', {
        title: 'Again',
        scope: 'personal',
        config: recordConfig(),
      }),
    ).not.toThrow();
  });

  it('keeps page-visibility awareness when options carry an explicit undefined', () => {
    const store = new MemoryViewStore();
    const hidden = vi
      .spyOn(document, 'visibilityState', 'get')
      .mockReturnValue('hidden');
    const { result } = renderHook(() =>
      useViewEngine({
        definitions: [],
        store,
        resolveSource: () => testSource(),
        environment: undefined,
      }),
    );

    expect(result.current.environment.visibility.isVisible()).toBe(false);
    hidden.mockRestore();
  });

  it('takes the environment a caller injects', () => {
    const store = new MemoryViewStore();
    const environment = browserRuntimeEnvironment({
      timeZone: 'Asia/Shanghai',
    });
    const { result } = renderHook(() =>
      useViewEngine({
        definitions: [],
        store,
        resolveSource: () => testSource(),
        environment,
      }),
    );
    expect(result.current.environment.timeZone).toBe('Asia/Shanghai');
  });
});

describe('useViewRuntime', () => {
  it('returns null without a runtime and tracks one when given', async () => {
    const { engine } = engineWith();
    const runtime = engine.create('orders', {
      title: 'New',
      scope: 'personal',
      config: recordConfig(),
    });

    const { result, rerender } = renderHook(
      ({ target }: { target: RecordViewRuntime | null }) =>
        useViewRuntime(target),
      { initialProps: { target: null as RecordViewRuntime | null } },
    );
    expect(result.current).toBeNull();

    rerender({ target: runtime });
    await waitFor(() => expect(result.current?.query.status).toBe('success'));

    act(() => runtime.edit({ pageSize: 33 }));
    expect(result.current?.draft.pageSize).toBe(33);
  });
});

describe('useOpenView', () => {
  it('opens, swaps and disposes as the id changes', async () => {
    const { engine, store } = engineWith();
    await store.create(
      {
        definitionId: 'orders',
        title: 'Second',
        scope: 'personal',
        config: recordConfig(),
      },
      { requestId: 'seed' },
    );

    const { result, rerender } = renderHook(
      ({ id }: { id: string | null }) => useOpenView(engine, id),
      { initialProps: { id: 'orders-1' as string | null } },
    );
    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.runtime).not.toBeNull());
    const first = result.current.runtime;

    rerender({ id: 'orders-2' });
    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.runtime?.id).not.toBe(first?.id));

    // The one it replaced is disposed, so it stops answering commands.
    first?.edit({ pageSize: 11 });
    expect(requireRecordConfig(first!.getSnapshot().draft).pageSize).toBe(20);
  });

  it('reports a failure to open as an issue', async () => {
    const { engine } = engineWith();
    const { result } = renderHook(() => useOpenView(engine, 'missing'));

    await waitFor(() =>
      expect(result.current.error).toMatchObject({
        code: 'view.open.failed.not_found',
      }),
    );
    expect(result.current.runtime).toBeNull();
  });

  it('asks the engine about an empty id rather than idling', async () => {
    const { engine } = engineWith();
    const { result } = renderHook(() => useOpenView(engine, ''));

    await waitFor(() => expect(result.current.error).not.toBeNull());
    expect(result.current.loading).toBe(false);
  });

  it('stays idle without an id', () => {
    const { engine } = engineWith();
    const { result } = renderHook(() => useOpenView(engine, null));

    expect(result.current).toEqual({
      runtime: null,
      loading: false,
      error: null,
    });
  });

  it('drops a view that arrives after it was unmounted', async () => {
    const { engine } = engineWith();
    const open = vi.spyOn(engine, 'open');
    const { unmount } = renderHook(() => useOpenView(engine, 'orders-1'));

    unmount();
    const runtime = await open.mock.results[0].value;

    // Disposed on arrival rather than left running behind a dead component.
    runtime.edit({ pageSize: 12 });
    expect(runtime.getSnapshot().draft.pageSize).toBe(20);
  });
});

describe('useViewList', () => {
  it('orders by preference and resolves the default', async () => {
    const { engine, store } = engineWith();
    await store.setPreferences(
      'orders',
      {
        order: ['orders-1', 'system:orders:all'],
        defaultInstanceId: 'orders-1',
        revision: '0',
      },
      { requestId: 'seed' },
    );

    const { result } = renderHook(() => useViewList(engine, 'orders'));
    expect(result.current.loading).toBe(true);

    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.items.map(item => item.id)).toEqual([
      'orders-1',
      'system:orders:all',
    ]);
    expect(result.current.defaultInstanceId).toBe('orders-1');
    expect(result.current.permissions.createPersonal).toBe(true);
  });

  it('names no default until preferences have settled', async () => {
    const { engine, store } = engineWith();
    let release: (value: ViewPreferences) => void = () => {};
    vi.spyOn(store, 'getPreferences').mockReturnValueOnce(
      new Promise<ViewPreferences>(resolve => {
        release = resolve;
      }),
    );

    const { result } = renderHook(() => useViewList(engine, 'orders'));
    await waitFor(() => expect(result.current.items).toHaveLength(2));

    // The list is in, the preferences are not: answering now would open one
    // view and swap it for another a moment later.
    expect(result.current.defaultInstanceId).toBeNull();

    act(() =>
      release({ order: [], defaultInstanceId: 'orders-1', revision: '1' }),
    );
    await waitFor(() =>
      expect(result.current.defaultInstanceId).toBe('orders-1'),
    );
  });

  it('falls back to server order once preferences have failed', async () => {
    const { engine, store } = engineWith();
    vi.spyOn(store, 'getPreferences').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'offline'),
    );

    const { result } = renderHook(() => useViewList(engine, 'orders'));

    await waitFor(() =>
      expect(result.current.defaultInstanceId).toBe('system:orders:all'),
    );
  });

  it('keeps a failed list and failed preferences apart', async () => {
    const { engine, store } = engineWith();
    vi.spyOn(store, 'list').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'offline'),
    );
    vi.spyOn(store, 'getPreferences').mockRejectedValueOnce(
      new ViewStoreError('FORBIDDEN', 'nope'),
    );

    const { result } = renderHook(() => useViewList(engine, 'orders'));

    await waitFor(() => expect(result.current.error).not.toBeNull());
    expect(result.current.error?.code).toBe('view.list.failed.unavailable');
    expect(result.current.preferencesError?.code).toBe(
      'view.preferences.failed.forbidden',
    );
    expect(result.current.items).toEqual([]);
    expect(result.current.defaultInstanceId).toBeNull();
  });

  it('reloads on demand', async () => {
    const { engine, store } = engineWith();
    const list = vi.spyOn(store, 'list');
    const { result } = renderHook(() => useViewList(engine, 'orders'));
    await waitFor(() => expect(result.current.loading).toBe(false));

    act(() => result.current.reload());

    await waitFor(() => {
      expect(list).toHaveBeenCalledTimes(2);
      expect(result.current.items).toHaveLength(2);
    });
  });
});

describe('useSaveCommands', () => {
  async function openMine() {
    const { engine, store } = engineWith();
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return { opened, commands: useSaveCommands(engine, opened.runtime) };
    });
    await waitFor(() => expect(result.current.opened.runtime).not.toBeNull());
    return { engine, store, result };
  }

  it('answers with no abilities when nothing is open', () => {
    const { engine } = engineWith();
    const { result } = renderHook(() => useSaveCommands(engine, null));

    expect(result.current.can).toEqual({
      save: false,
      saveAs: false,
      rename: false,
      delete: false,
    });
    expect(result.current.state).toEqual({
      pending: false,
      error: null,
      write: null,
      dirty: false,
    });
  });

  it('does nothing when a command has no target', async () => {
    const { engine } = engineWith();
    const { result } = renderHook(() => useSaveCommands(engine, null));

    await expect(result.current.save()).resolves.toBeNull();
    await expect(
      result.current.saveAs({ title: 'x', scope: 'personal' }),
    ).resolves.toBeNull();
    await expect(result.current.rename('x')).resolves.toBeNull();
    await expect(result.current.delete()).resolves.toBe(false);
    await expect(result.current.retry()).resolves.toBeUndefined();
    await expect(
      result.current.resolveConflict('reload'),
    ).resolves.toBeUndefined();
    expect(() => result.current.abandon()).not.toThrow();
  });

  it('saves, renames and deletes an open view', async () => {
    const { store, result } = await openMine();

    act(() => result.current.opened.runtime?.edit({ pageSize: 44 }));
    await act(async () => {
      await result.current.commands.save();
    });
    expect(result.current.commands.state.dirty).toBe(false);

    await act(async () => {
      await result.current.commands.rename('Renamed');
    });
    await expect(store.get('orders-1')).resolves.toMatchObject({
      title: 'Renamed',
    });

    await act(async () => {
      expect(await result.current.commands.delete()).toBe(true);
    });
    await expect(store.list('orders')).resolves.toEqual([]);
  });

  it('turns a refusal into state instead of a rejection', async () => {
    const { engine, result } = await openMine();
    vi.spyOn(engine, 'save').mockRejectedValueOnce(
      new ViewCommandError(issue('view.config.invalid', [])),
    );

    await act(async () => {
      expect(await result.current.commands.save()).toBeNull();
    });

    expect(result.current.commands.state.error?.code).toBe(
      'view.config.invalid',
    );
    expect(result.current.commands.state.pending).toBe(false);
  });

  it('exposes an unresolved write and its recovery actions', async () => {
    const { engine, store, result } = await openMine();
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );

    act(() => result.current.opened.runtime?.edit({ pageSize: 21 }));
    await act(async () => {
      await result.current.commands.save();
    });
    expect(result.current.commands.state.write?.kind).toBe('unknown');

    await act(async () => {
      await result.current.commands.retry();
    });
    expect(result.current.commands.state.write).toBeNull();
    await expect(store.get('orders-1')).resolves.toMatchObject({
      config: { pageSize: 21 },
    });

    vi.spyOn(engine, 'abandonWrite').mockImplementationOnce(() => {
      throw new ViewCommandError(issue('view.write.not-pending', []));
    });
    act(() => result.current.commands.abandon());
    expect(result.current.commands.state.error?.code).toBe(
      'view.write.not-pending',
    );
  });

  it('abandons an outcome and clears the error', async () => {
    const { store, result } = await openMine();
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );
    await act(async () => {
      await result.current.commands.save();
    });

    act(() => result.current.commands.abandon());

    expect(result.current.commands.state.write).toBeNull();
    expect(result.current.commands.state.error).toBeNull();
  });

  it('resolves a conflict by reloading', async () => {
    const { store, result } = await openMine();
    await store.save('orders-1', recordConfig({ pageSize: 77 }), '1', {
      requestId: 'other',
    });

    act(() => result.current.opened.runtime?.edit({ pageSize: 21 }));
    await act(async () => {
      await result.current.commands.save();
    });
    expect(result.current.commands.state.write?.kind).toBe('conflict');

    await act(async () => {
      await result.current.commands.resolveConflict('reload');
    });
    expect(result.current.opened.runtime?.getSnapshot().draft).toMatchObject({
      pageSize: 77,
    });
  });

  it('leaves a failure behind when another view opens', async () => {
    const { engine, store } = engineWith();
    await store.create(
      {
        definitionId: 'orders',
        title: 'Second',
        scope: 'personal',
        config: recordConfig(),
      },
      { requestId: 'seed' },
    );
    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('FORBIDDEN', 'nope'),
    );

    const { result, rerender } = renderHook(
      ({ id }: { id: string }) => {
        const opened = useOpenView(engine, id);
        return { opened, commands: useSaveCommands(engine, opened.runtime) };
      },
      { initialProps: { id: 'orders-1' } },
    );
    await waitFor(() => expect(result.current.opened.runtime).not.toBeNull());

    await act(async () => {
      await result.current.commands.save();
    });
    expect(result.current.commands.state.error).not.toBeNull();

    rerender({ id: 'orders-2' });
    await waitFor(() =>
      expect(result.current.opened.runtime?.getSnapshot().saved?.id).toBe(
        'orders-2',
      ),
    );

    expect(result.current.commands.state.error).toBeNull();
    expect(result.current.commands.state.pending).toBe(false);
  });

  it('offers only a copy of a system view', async () => {
    const { engine } = engineWith();
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'system:orders:all');
      return useSaveCommands(engine, opened.runtime);
    });

    await waitFor(() => expect(result.current.can.saveAs).toBe(true));
    expect(result.current.can).toMatchObject({
      save: false,
      rename: false,
      delete: false,
    });
  });
});

describe('useFilterEditor', () => {
  async function openEditor() {
    const { engine } = engineWith();
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return { opened, filter: useFilterEditor(opened.runtime) };
    });
    await waitFor(() => expect(result.current.opened.runtime).not.toBeNull());
    return result;
  }

  it('is inert without a runtime', () => {
    const { result } = renderHook(() => useFilterEditor(null));

    expect(result.current.tree).toEqual({ op: 'and', children: [] });
    expect(result.current.fields).toEqual([]);
    expect(result.current.operatorsFor('id')).toEqual([]);
    expect(result.current.editorFor([0])).toBeNull();
    expect(result.current.applied).toEqual([]);
    expect(() => {
      result.current.addLeaf('id');
      result.current.addGroup('or');
      result.current.remove([0]);
      result.current.clear();
      result.current.submit();
      result.current.setMode('advanced');
      result.current.focus();
      result.current.blur();
    }).not.toThrow();
  });

  it('adds, edits and removes nodes on the draft', async () => {
    const result = await openEditor();

    act(() => result.current.filter.addLeaf('warehouse'));
    expect(result.current.filter.count).toBe(1);
    expect(result.current.filter.simple).toBe(true);
    expect(result.current.filter.editorFor([0])).toMatchObject({
      input: 'text',
    });
    expect(
      result.current.filter.operatorsFor('warehouse').length,
    ).toBeGreaterThan(0);

    act(() =>
      result.current.filter.updateLeaf([0], {
        operator: `${FilterOperator.EQ}`,
        value: 'CN',
      }),
    );
    expect(result.current.opened.runtime?.getSnapshot().dirty).toBe(true);

    act(() => result.current.filter.submit());
    await waitFor(() => expect(result.current.filter.applied).toHaveLength(1));

    act(() => result.current.filter.remove([0]));
    expect(result.current.filter.count).toBe(0);
  });

  it('ignores a field the definition does not declare', async () => {
    const result = await openEditor();

    act(() => result.current.filter.addLeaf('nope'));

    expect(result.current.filter.count).toBe(0);
  });

  it('nests a group, which leaves simple mode behind', async () => {
    const result = await openEditor();

    act(() => result.current.filter.addGroup('or'));
    act(() => result.current.filter.addLeaf('warehouse', [0]));

    expect(result.current.filter.simple).toBe(false);
    expect(result.current.filter.count).toBe(1);

    act(() => result.current.filter.setMode('advanced'));
    expect(result.current.filter.mode).toBe('advanced');

    act(() => result.current.filter.clear());
    expect(result.current.filter.tree.children).toEqual([]);
  });

  it('reports the filter issues the validator produced, and only those', async () => {
    const result = await openEditor();

    // A condition with no value yet is an error, and it is the reason submit
    // does nothing, so the editor has to be able to say so.
    act(() => result.current.filter.addLeaf('warehouse'));
    act(() => result.current.opened.runtime?.edit({ pageSize: 0 }));

    const codes = result.current.filter.issues.map(found => found.code);
    expect(codes).toContain('filter.value.expected-string');
    expect(
      codes.every(
        code =>
          code.startsWith('filter.') || code.startsWith('config.filterMode.'),
      ),
    ).toBe(true);
    // The page size error belongs to the view, not to this editor.
    expect(codes.some(code => code.startsWith('record.'))).toBe(false);
  });

  it('starts a condition on an operator the field allows', async () => {
    const definition = ordersDefinition({
      fields: [
        { name: 'id', label: 'Order', kind: 'string' },
        {
          name: 'warehouse',
          label: 'Warehouse',
          kind: 'string',
          operators: [`${FilterOperator.CONTAINS}`],
        },
      ],
      record: { rowKey: 'id', paging: 'paged', layouts: ['table'] },
      views: [],
    });
    const engine = new ViewEngine({
      definitions: [definition],
      store: new MemoryViewStore({
        instances: [
          {
            ...mine,
            config: recordConfig({ table: { columns: [{ field: 'id' }] } }),
          },
        ],
      }),
      resolveSource: () => testSource(),
    });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return { opened, filter: useFilterEditor(opened.runtime) };
    });
    await waitFor(() => expect(result.current.opened.runtime).not.toBeNull());

    act(() => result.current.filter.addLeaf('warehouse'));

    expect(result.current.filter.tree.children[0]).toMatchObject({
      operator: `${FilterOperator.CONTAINS}`,
    });
  });

  it('composes edits made in one batch', async () => {
    const result = await openEditor();

    act(() => {
      result.current.filter.addGroup('or');
      result.current.filter.addLeaf('warehouse', [0]);
    });

    const [group] = result.current.filter.tree.children;
    expect(group).toMatchObject({ op: 'or' });
    expect(result.current.filter.count).toBe(1);
  });

  it('pauses auto refresh while an editor holds focus', async () => {
    const result = await openEditor();

    act(() => result.current.filter.focus());
    expect(result.current.opened.runtime?.getSnapshot().editing).toBe(true);

    act(() => result.current.filter.blur());
    expect(result.current.opened.runtime?.getSnapshot().editing).toBe(false);
  });
});

describe('useRecordTable', () => {
  async function openTable(source?: ViewSource) {
    const { engine } = engineWith({ source });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return {
        opened,
        table: useRecordTable(opened.runtime as RecordViewRuntime | null),
      };
    });
    await waitFor(() => expect(result.current.table.status).toBe('success'));
    return result;
  }

  it('is inert without a runtime', () => {
    const { result } = renderHook(() => useRecordTable(null));

    expect(result.current).toMatchObject({
      columns: [],
      rows: [],
      paging: null,
      status: 'idle',
      pageSize: 0,
      layout: 'table',
    });
    expect(result.current.sortOf('id')).toBeNull();
    expect(result.current.isSelected('o-1')).toBe(false);
    expect(() => {
      result.current.toggleSort('id');
      result.current.toggle('o-1');
      result.current.toggleAll();
      result.current.clearSelection();
      result.current.setColumns(['id']);
      result.current.setLayout('card');
      result.current.setPageSize(10);
      result.current.goTo(2);
      result.current.next();
      result.current.previous();
      result.current.refresh();
    }).not.toThrow();
  });

  it('keeps the priority of a column when its direction changes', async () => {
    const result = await openTable();

    act(() => result.current.table.toggleSort('amount'));
    act(() => result.current.table.toggleSort('id'));
    act(() => result.current.table.toggleSort('amount'));

    expect(result.current.table.sort).toEqual([
      { field: 'amount', direction: 'DESC' },
      { field: 'id', direction: 'ASC' },
    ]);
  });

  it('cycles a column through ascending, descending and off', async () => {
    const result = await openTable();

    act(() => result.current.table.toggleSort('amount'));
    expect(result.current.table.sortOf('amount')).toBe('ASC');

    act(() => result.current.table.toggleSort('amount'));
    expect(result.current.table.sortOf('amount')).toBe('DESC');

    act(() => result.current.table.toggleSort('amount'));
    expect(result.current.table.sort).toEqual([]);
  });

  it('selects rows of the current result and clears them', async () => {
    const result = await openTable();

    act(() => result.current.table.toggleAll());
    expect(result.current.table.selection).toEqual(['o-1', 'o-2']);

    act(() => result.current.table.toggle('o-1'));
    expect(result.current.table.isSelected('o-1')).toBe(false);

    act(() => result.current.table.toggleAll());
    expect(result.current.table.selection).toEqual(['o-1', 'o-2']);

    act(() => result.current.table.clearSelection());
    expect(result.current.table.selection).toEqual([]);
  });

  it('moves between pages and stops at the first', async () => {
    const result = await openTable();

    act(() => result.current.table.previous());
    expect(result.current.table.paging).toMatchObject({ index: 1 });

    act(() => result.current.table.next());
    await waitFor(() =>
      expect(result.current.table.paging).toMatchObject({ index: 2 }),
    );

    act(() => result.current.table.previous());
    await waitFor(() =>
      expect(result.current.table.paging).toMatchObject({ index: 1 }),
    );

    act(() => result.current.table.goTo(4));
    await waitFor(() =>
      expect(result.current.table.paging).toMatchObject({ index: 4 }),
    );
  });

  it('follows a cursor when the definition declares one', async () => {
    const { engine } = engineWith();
    const definition = ordersDefinition({
      record: { rowKey: 'id', paging: 'cursor', layouts: ['table'] },
    });
    const cursorEngine = new ViewEngine({
      definitions: [definition],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => testSource(),
    });
    void engine;

    const { result } = renderHook(() => {
      const opened = useOpenView(cursorEngine, 'orders-1');
      return useRecordTable(opened.runtime as RecordViewRuntime | null);
    });
    await waitFor(() => expect(result.current.status).toBe('success'));

    expect(result.current.paging).toEqual({
      mode: 'cursor',
      nextCursor: 'cursor-2',
    });
    act(() => result.current.previous());
    act(() => result.current.next());
    await waitFor(() => expect(result.current.status).toBe('success'));
  });

  it('changes layout, columns and page size', async () => {
    const result = await openTable();

    act(() => result.current.table.setLayout('card'));
    expect(result.current.table.layout).toBe('card');

    act(() => result.current.table.setColumns(['id', 'warehouse']));
    await waitFor(() =>
      expect(result.current.table.columns.map(column => column.field)).toEqual([
        'id',
        'warehouse',
      ]),
    );
    expect(result.current.table.columnFields).toEqual(['id', 'warehouse']);

    act(() => result.current.table.setPageSize(5));
    await waitFor(() => expect(result.current.table.pageSize).toBe(5));
  });

  it('keeps the width and pinning of a column it keeps', async () => {
    const { engine } = engineWith({
      instances: [
        {
          ...mine,
          config: recordConfig({
            table: {
              columns: [
                { field: 'id', width: 120, pinned: 'left' },
                { field: 'amount' },
              ],
            },
          }),
        },
      ],
    });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return useRecordTable(opened.runtime as RecordViewRuntime | null);
    });
    await waitFor(() => expect(result.current.status).toBe('success'));

    act(() => result.current.setColumns(['id', 'warehouse']));

    await waitFor(() =>
      expect(result.current.columns[0]).toMatchObject({
        field: 'id',
        width: 120,
        pinned: 'left',
      }),
    );
  });

  it('ignores a page number on a cursor source and a next with no cursor', async () => {
    const definition = ordersDefinition({
      record: { rowKey: 'id', paging: 'cursor', layouts: ['table'] },
    });
    const source = testSource({
      cursor: vi.fn(() => Promise.resolve({ nextCursor: null, list: [] })),
    });
    const engine = new ViewEngine({
      definitions: [definition],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => source,
    });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return useRecordTable(opened.runtime as RecordViewRuntime | null);
    });
    await waitFor(() => expect(result.current.status).toBe('success'));
    expect(source.cursor).toHaveBeenCalledTimes(1);

    act(() => result.current.goTo(3));
    act(() => result.current.next());

    // A page number means nothing here, and the sequence has ended.
    expect(source.cursor).toHaveBeenCalledTimes(1);
  });

  it('reports a failed query', async () => {
    const result = await openTable(
      testSource({
        paged: vi
          .fn()
          .mockResolvedValueOnce({ total: 0, list: [] })
          .mockRejectedValue(new Error('gateway down')),
      }),
    );

    act(() => result.current.table.refresh());

    await waitFor(() => expect(result.current.table.status).toBe('error'));
    expect(result.current.table.error?.code).toBe('runtime.query.failed');
  });
});

describe('visibility and issues', () => {
  it('follows the document and stops listening when asked', () => {
    const visibility = documentVisibility();
    const listener = vi.fn();
    const unsubscribe = visibility.subscribe(listener);

    document.dispatchEvent(new Event('visibilitychange'));
    expect(listener).toHaveBeenCalledTimes(1);
    expect(visibility.isVisible()).toBe(true);

    unsubscribe();
    document.dispatchEvent(new Event('visibilitychange'));
    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('assumes visible where there is no document at all', () => {
    vi.stubGlobal('document', undefined);
    try {
      expect(documentVisibility().isVisible()).toBe(true);
      expect(documentVisibility().subscribe(() => {})).toBeInstanceOf(Function);
    } finally {
      vi.unstubAllGlobals();
    }
  });

  it('reports a hidden document as not visible', () => {
    const hidden = vi
      .spyOn(document, 'visibilityState', 'get')
      .mockReturnValue('hidden');

    expect(documentVisibility().isVisible()).toBe(false);
    hidden.mockRestore();
  });

  it('maps every failure onto one Issue shape', () => {
    expect(
      toIssue(new ViewCommandError(issue('view.title.empty', [])), 'x'),
    ).toMatchObject({ code: 'view.title.empty' });

    expect(
      toIssue(
        new ViewWriteError({
          kind: 'rejected',
          requestId: 'r',
          payload: { action: 'delete', id: 'a', revision: '1' },
          issue: issue('view.write.forbidden', []),
        }),
        'x',
      ),
    ).toMatchObject({ code: 'view.write.forbidden' });

    expect(
      toIssue(
        new ViewWriteError({
          kind: 'unknown',
          requestId: 'r',
          payload: { action: 'delete', id: 'a', revision: '1' },
        }),
        'view.delete.failed',
      ),
    ).toMatchObject({ code: 'view.delete.failed.unknown' });

    expect(toIssue(new ViewStoreError('CONFLICT', 'moved'), 'x')).toMatchObject(
      {
        code: 'x.conflict',
      },
    );
    expect(toIssue(new Error('boom'), 'x')).toMatchObject({
      code: 'x',
      params: { reason: 'boom' },
    });
    expect(toIssue('boom', 'x')).toMatchObject({ params: { reason: 'boom' } });
  });
});
