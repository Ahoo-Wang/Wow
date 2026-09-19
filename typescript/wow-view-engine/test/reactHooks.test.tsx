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

import { AggregationGroupType, FilterOperator } from '@ahoo-wang/fetcher-wow';
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  builtinFieldKinds,
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewCommandError,
  ViewEngine,
  ViewStoreError,
  ViewWriteError,
  issue,
  type DataViewDefinition,
  type FilterTree,
  type RecordViewRuntime,
  type ViewInstance,
  type ViewPreferences,
  type ViewSource,
} from '../src/index.js';
import {
  browserRuntimeEnvironment,
  documentVisibility,
  toIssue,
  treeController,
  useFilterEditor,
  useOpenView,
  useRecordTable,
  useSaveCommands,
  useViewEngine,
  useViewList,
  useViewRuntime,
} from '../src/react/index.js';
import {
  deferred,
  analysisConfig,
  ordersDefinition,
  recordConfig,
  requireRecordConfig,
  ROWS,
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
    definitions?: DataViewDefinition[];
  } = {},
): { engine: ViewEngine; store: MemoryViewStore } {
  const store =
    options.store ??
    new MemoryViewStore({ instances: options.instances ?? [mine] });
  const engine = new ViewEngine({
    definitions: options.definitions ?? [ordersDefinition()],
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
      scopeIssues: [],
    });
  });

  /**
   * A refused narrowing leaves the wider condition in force, so the result on
   * screen answers a question the host has already withdrawn. The issues were
   * dropped on the floor, which made that silent.
   */
  it('hands back the issues a refused narrowing produced', async () => {
    const { engine } = engineWith();
    const refused: FilterTree = {
      op: 'and',
      children: [
        { field: 'nope', operator: `${FilterOperator.EQ}`, value: 'x' },
      ],
    };
    const { result, rerender } = renderHook(
      ({ scope }: { scope: FilterTree | null }) =>
        useOpenView(engine, 'orders-1', scope),
      { initialProps: { scope: null as FilterTree | null } },
    );
    await waitFor(() => expect(result.current.runtime).not.toBeNull());
    expect(result.current.scopeIssues).toEqual([]);

    rerender({ scope: refused });

    await waitFor(() =>
      expect(
        result.current.scopeIssues.some(found => found.severity === 'error'),
      ).toBe(true),
    );
  });

  /**
   * `setScopeFilter` reports every finding of the merged condition, so a
   * view whose saved config already carried a warning answered an accepted
   * narrowing with that warning, and the hook called it a refusal.
   */
  it('does not call an accepted narrowing refused over a warning', async () => {
    const source = testSource();
    const { engine } = engineWith({
      source,
      instances: [
        {
          ...mine,
          config: recordConfig({
            filterMode: 'simple',
            filter: {
              op: 'or',
              children: [
                {
                  field: 'warehouse',
                  operator: `${FilterOperator.EQ}`,
                  value: 'CN',
                },
              ],
            },
          }),
        },
      ],
    });
    const narrowed: FilterTree = {
      op: 'and',
      children: [
        { field: 'warehouse', operator: `${FilterOperator.EQ}`, value: 'US' },
      ],
    };
    const { result, rerender } = renderHook(
      ({ scope }: { scope: FilterTree | null }) =>
        useOpenView(engine, 'orders-1', scope),
      { initialProps: { scope: null as FilterTree | null } },
    );
    await waitFor(() => expect(result.current.runtime).not.toBeNull());

    rerender({ scope: narrowed });

    // The narrowing went through: the query carries it.
    await waitFor(() =>
      expect(
        vi
          .mocked(source.paged)
          .mock.calls.some(([query]) =>
            JSON.stringify(query.filter).includes('US'),
          ),
      ).toBe(true),
    );
    expect(result.current.scopeIssues).toEqual([]);
    // The warning is still the runtime's to show.
    expect(
      result.current.runtime?.getSnapshot().issues.map(found => found.code),
    ).toContain('config.filterMode.not-simple');
  });

  it('opens the id again when its runtime is disposed under it', async () => {
    const { engine } = engineWith();
    const { result } = renderHook(() => useOpenView(engine, 'orders-1'));
    await waitFor(() => expect(result.current.runtime).not.toBeNull());
    const first = result.current.runtime!;

    // Let go behind the hook's back, as the engine does with the runtime of
    // an instance that was deleted. Disposal is the runtime's last
    // notification, so the hook hears of it without a render from anyone.
    act(() => engine.close(first));

    // Not the dead one, and not handed out as if it were alive.
    expect(result.current.runtime).toBeNull();
    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.runtime).not.toBeNull());
    expect(result.current.runtime).not.toBe(first);
    expect(result.current.runtime?.disposed).toBe(false);
  });

  it('reports a deleted view as gone rather than keeping its dead runtime', async () => {
    const { engine } = engineWith();
    const { result } = renderHook(() => useOpenView(engine, 'orders-1'));
    await waitFor(() => expect(result.current.runtime).not.toBeNull());

    await act(() => engine.delete('orders-1'));

    await waitFor(() =>
      expect(result.current.error).toMatchObject({
        code: 'view.open.failed.not_found',
      }),
    );
    expect(result.current.runtime).toBeNull();
    expect(result.current.loading).toBe(false);
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

  /**
   * One data definition holds record and analysis instances together, and a
   * workbench draws one of them. The narrowing has to happen before the
   * default is resolved: a stored default of the other kind used to be
   * handed to a workbench that then rendered a header over nothing.
   */
  describe('narrowed to one kind', () => {
    const chart: ViewInstance = {
      id: 'orders-chart',
      definitionId: 'orders',
      title: 'By warehouse',
      scope: 'personal',
      revision: '1',
      config: analysisConfig(),
    };

    async function listOf(kind: 'record' | 'analysis' | undefined) {
      const { engine, store } = engineWith({ instances: [mine, chart] });
      await store.setPreferences(
        'orders',
        {
          order: ['orders-chart', 'orders-1'],
          // Stored by the analysis workbench; the record one must not take it.
          defaultInstanceId: 'orders-chart',
          revision: '0',
        },
        { requestId: 'seed' },
      );
      const rendered = renderHook(() =>
        useViewList(engine, 'orders', kind ? { kind } : undefined),
      );
      await waitFor(() => expect(rendered.result.current.loading).toBe(false));
      return rendered.result;
    }

    it('lists every kind when no kind is asked for', async () => {
      const result = await listOf(undefined);

      expect(result.current.items.map(item => item.id)).toEqual([
        'orders-chart',
        'orders-1',
        'system:orders:all',
      ]);
      expect(result.current.defaultInstanceId).toBe('orders-chart');
    });

    it('lists only that kind and defaults among it', async () => {
      const result = await listOf('record');

      // The analysis view is neither offered nor opened by default: the
      // stored default names it, and it is not one of these, so the first
      // of the ordered record views answers instead.
      expect(result.current.items.map(item => item.id)).toEqual([
        'orders-1',
        'system:orders:all',
      ]);
      expect(result.current.defaultInstanceId).toBe('orders-1');
    });

    it('keeps the stored default when it is of the kind asked for', async () => {
      const result = await listOf('analysis');

      expect(result.current.items.map(item => item.id)).toEqual([
        'orders-chart',
      ]);
      expect(result.current.defaultInstanceId).toBe('orders-chart');
    });
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
      revert: false,
      createPersonal: false,
      createShared: false,
    });
    expect(result.current.state).toEqual({
      pending: false,
      error: null,
      write: null,
      dirty: false,
      blocked: false,
      hasErrors: false,
      lastSavedAt: null,
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
    await expect(result.current.retry()).resolves.toEqual({
      landed: false,
      written: false,
      instance: null,
    });
    await expect(result.current.resolveConflict('reload')).resolves.toEqual({
      landed: false,
      written: false,
      instance: null,
    });
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

  it("keeps the current view pending while another view's command settles", async () => {
    const { engine } = engineWith();
    const a = await engine.open('orders-1');
    const b = engine.create('orders', {
      title: 'Draft',
      scope: 'personal',
      config: recordConfig(),
    });

    const aFailed = deferred<void>();
    vi.spyOn(engine, 'save').mockImplementation(async runtime => {
      if (runtime === a) {
        await aFailed.promise;
        throw new ViewCommandError(issue('view.config.invalid', []));
      }
      // B's write never settles within the test.
      return new Promise<ViewInstance>(() => {});
    });

    // A workbench reuses this hook across views: A's save is in flight when
    // the user switches to B and saves there.
    const { result, rerender } = renderHook(
      ({ runtime }: { runtime: typeof a }) => useSaveCommands(engine, runtime),
      { initialProps: { runtime: a } },
    );

    act(() => {
      void result.current.save();
    });
    rerender({ runtime: b });
    act(() => {
      void result.current.save();
    });
    expect(result.current.state.pending).toBe(true);

    await act(async () => {
      aFailed.reject(new Error('A failed'));
      await Promise.resolve();
    });

    // B's write is still in flight, so its buttons stay disabled.
    expect(result.current.state.pending).toBe(true);
  });

  /**
   * A workbench can go back. The hook keeps one queue, so A's second write
   * must still chain behind A's first however many other views were written
   * to in between — a second write against one runtime is what the engine
   * refuses with `view.write.in-flight`, and the header would report that as
   * the failure of the click that was only second.
   */
  it("waits for this view's own write when the workbench returns", async () => {
    const { engine } = engineWith();
    const a = await engine.open('orders-1');
    const b = engine.create('orders', {
      title: 'Draft',
      scope: 'personal',
      config: recordConfig(),
    });

    const held = deferred<ViewInstance>();
    const started: string[] = [];
    vi.spyOn(engine, 'save').mockImplementation(runtime => {
      if (runtime === a) {
        started.push(started.includes('a') ? 'a2' : 'a');
        return held.promise;
      }
      started.push('b');
      return Promise.resolve({ ...mine, id: 'orders-2', revision: '1' });
    });

    const { result, rerender } = renderHook(
      ({ runtime }: { runtime: typeof a }) => useSaveCommands(engine, runtime),
      { initialProps: { runtime: a } },
    );

    act(() => {
      void result.current.save();
    });
    rerender({ runtime: b });
    await act(async () => {
      await result.current.save();
    });
    rerender({ runtime: a });

    // A's first write is still hanging, so its second one has not left.
    let second: Promise<ViewInstance | null> = Promise.resolve(null);
    act(() => {
      second = result.current.save();
    });
    expect(started).toEqual(['a', 'b']);

    await act(async () => {
      held.resolve({ ...mine, revision: '2' });
      await second;
    });
    expect(started).toEqual(['a', 'b', 'a2']);
    // It ran as its own write, not as a refusal of one already in flight.
    expect(result.current.state.error).toBeNull();
  });

  /**
   * Two clicks before the first answer is in. The second is queued behind the
   * first, and by the time it reaches the front the view is `unknown` — the
   * one outcome that refuses a new intent, because the write it stands for
   * may already have landed. Sending anyway earns `view.write.unknown-pending`
   * and puts that refusal on screen over an outcome the user has yet to retry
   * or abandon, so the queued command is dropped instead.
   */
  it('drops a queued write once the one ahead came back unknown', async () => {
    const { store, result } = await openMine();
    const held = deferred<ViewInstance>();
    const saves = vi.spyOn(store, 'save').mockReturnValueOnce(held.promise);

    act(() => result.current.opened.runtime?.edit({ pageSize: 21 }));
    let first: Promise<ViewInstance | null> = Promise.resolve(null);
    let second: Promise<ViewInstance | null> = Promise.resolve(null);
    act(() => {
      first = result.current.commands.save();
      second = result.current.commands.save();
    });

    await act(async () => {
      held.reject(new ViewStoreError('UNAVAILABLE', 'timeout'));
      await Promise.all([first, second]);
    });

    expect(result.current.commands.state.write?.kind).toBe('unknown');
    // The second never left, so the store saw one attempt and the second
    // click resolved as nothing happened rather than as a failure.
    expect(saves).toHaveBeenCalledTimes(1);
    await expect(second).resolves.toBeNull();
    // What is on screen is the first write's own outcome, not a refusal of
    // the second one stacked over it.
    expect(result.current.commands.state.error?.code).toBe(
      'view.save.failed.unknown',
    );
    expect(result.current.commands.state.pending).toBe(false);
  });

  it("lets the current view's abandon take the progress slot", async () => {
    const { engine } = engineWith();
    const a = await engine.open('orders-1');
    const b = engine.create('orders', {
      title: 'Draft',
      scope: 'personal',
      config: recordConfig(),
    });

    // A leaves a failure in the shared slot; the workbench switches to B.
    vi.spyOn(engine, 'save').mockRejectedValueOnce(
      new ViewCommandError(issue('view.config.invalid', [])),
    );
    const { result, rerender } = renderHook(
      ({ runtime }: { runtime: typeof a }) => useSaveCommands(engine, runtime),
      { initialProps: { runtime: a } },
    );
    await act(async () => {
      await result.current.save();
    });
    rerender({ runtime: b });

    // Abandoning is the user acting now, not an old callback arriving late:
    // even a refusal belongs to B, over A's leftover failure.
    vi.spyOn(engine, 'abandonWrite').mockImplementationOnce(() => {
      throw new ViewCommandError(issue('view.write.not-pending', []));
    });
    act(() => {
      result.current.abandon();
    });

    // The refusal shows as itself: toIssue keeps a command error's own code.
    expect(result.current.state.error?.code).toBe('view.write.not-pending');
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
    // A replay that landed is a write that landed, badge and all.
    expect(result.current.commands.state.lastSavedAt).toEqual(
      expect.any(Number),
    );
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

  it('offers revert only with edits and a baseline to drop them for', async () => {
    const { engine, result } = await openMine();
    expect(result.current.commands.can.revert).toBe(false);

    act(() => result.current.opened.runtime?.edit({ pageSize: 44 }));
    expect(result.current.commands.can.revert).toBe(true);

    act(() => result.current.commands.revert());
    expect(result.current.commands.can.revert).toBe(false);
    expect(result.current.opened.runtime?.getSnapshot().draft).toMatchObject({
      pageSize: 20,
    });

    // A view that was never saved is dirty from the start and has nothing to
    // go back to, so reverting is never offered and does nothing.
    const fresh = engine.create('orders', {
      title: 'Draft',
      scope: 'personal',
      config: recordConfig(),
    });
    const { result: unsaved } = renderHook(() =>
      useSaveCommands(engine, fresh),
    );
    expect(unsaved.current.can.revert).toBe(false);
    act(() => unsaved.current.revert());
    expect(fresh.getSnapshot().dirty).toBe(true);
  });

  it('blocks writing on a refused draft or an outcome nobody can read', async () => {
    const { store, result } = await openMine();
    expect(result.current.commands.state.blocked).toBe(false);

    // Every button that writes disables on all three reasons at once, so the
    // hook answers them as one flag rather than making each UI re-derive it.
    act(() => result.current.opened.runtime?.edit({ pageSize: 5000 }));
    expect(result.current.commands.state.blocked).toBe(true);
    act(() => result.current.opened.runtime?.edit({ pageSize: 21 }));
    expect(result.current.commands.state.blocked).toBe(false);

    vi.spyOn(store, 'save').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );
    await act(async () => {
      await result.current.commands.save();
    });
    // Nobody knows whether that write landed, so the next one might be the
    // same write a second time.
    expect(result.current.commands.state.write?.kind).toBe('unknown');
    expect(result.current.commands.state.blocked).toBe(true);
  });

  it('leaves a conflict open to the new intent that resolves it', async () => {
    const { store, result } = await openMine();
    await store.save('orders-1', recordConfig({ pageSize: 77 }), '1', {
      requestId: 'other',
    });

    act(() => result.current.opened.runtime?.edit({ pageSize: 21 }));
    await act(async () => {
      await result.current.commands.save();
    });
    expect(result.current.commands.state.write?.kind).toBe('conflict');

    // A conflict is a definite answer, and "Save my copy" is one of the ways
    // out of it — blocking on it would disable the button that resolves it.
    // A UI that must refuse a *blind* Save here reads `write` itself.
    expect(result.current.commands.state.blocked).toBe(false);
    await act(async () => {
      await expect(
        result.current.commands.saveAs({
          title: 'My copy',
          scope: 'personal',
        }),
      ).resolves.not.toBeNull();
    });
  });

  it('times the write that landed and clears it when the next starts', async () => {
    const { engine, result } = await openMine();
    expect(result.current.commands.state.lastSavedAt).toBeNull();

    act(() => result.current.opened.runtime?.edit({ pageSize: 44 }));
    await act(async () => {
      await result.current.commands.save();
    });
    const landed = result.current.commands.state.lastSavedAt;
    expect(typeof landed).toBe('number');

    // The moment belongs to one write. A UI showing "Saved" for a couple of
    // seconds must not carry the last one's badge over the next attempt.
    const slow = deferred<ViewInstance>();
    vi.spyOn(engine, 'save').mockReturnValueOnce(slow.promise);
    act(() => {
      void result.current.commands.save();
    });
    expect(result.current.commands.state.lastSavedAt).toBeNull();
    expect(result.current.commands.state.blocked).toBe(true);

    await act(async () => {
      slow.resolve({ ...mine, revision: '3' });
      await Promise.resolve();
    });
    expect(result.current.commands.state.lastSavedAt).toEqual(
      expect.any(Number),
    );
  });

  it('leaves the last write unmarked when a command fails', async () => {
    const { engine, result } = await openMine();
    vi.spyOn(engine, 'save').mockRejectedValueOnce(
      new ViewCommandError(issue('view.config.invalid', [])),
    );

    await act(async () => {
      await result.current.commands.save();
    });

    expect(result.current.commands.state.lastSavedAt).toBeNull();
    // A rename that landed is not a save, so it marks no moment either.
    await act(async () => {
      await result.current.commands.rename('Renamed');
    });
    expect(result.current.commands.state.lastSavedAt).toBeNull();
  });

  it('marks no moment for a conflict the user resolved by reloading', async () => {
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
      await expect(
        result.current.commands.resolveConflict('reload'),
      ).resolves.toMatchObject({ landed: true, written: false });
    });

    // Reloading settles the conflict by taking the stored state and dropping
    // the draft. Nothing of the user's was written, so a "View saved" badge
    // over the edits they just gave up is the one thing it must not show.
    expect(result.current.commands.state.lastSavedAt).toBeNull();
  });

  it('marks no moment for a replayed write that is not a save', async () => {
    const { store, result } = await openMine();
    vi.spyOn(store, 'rename').mockRejectedValueOnce(
      new ViewStoreError('UNAVAILABLE', 'timeout'),
    );

    await act(async () => {
      await result.current.commands.rename('Renamed');
    });
    expect(result.current.commands.state.write?.kind).toBe('unknown');

    await act(async () => {
      await expect(result.current.commands.retry()).resolves.toMatchObject({
        landed: true,
        written: false,
      });
    });

    // The replay did reach the store — the title is the new one — but what
    // it wrote is not the config on screen. Saying "View saved" here would
    // tell the user their unsaved edits are safe when nothing of them went.
    expect(result.current.commands.state.lastSavedAt).toBeNull();
    await expect(store.get('orders-1')).resolves.toMatchObject({
      title: 'Renamed',
    });
  });

  it('times a conflict the user resolved by overwriting', async () => {
    const { store, result } = await openMine();
    await store.save('orders-1', recordConfig({ pageSize: 77 }), '1', {
      requestId: 'other',
    });

    act(() => result.current.opened.runtime?.edit({ pageSize: 21 }));
    await act(async () => {
      await result.current.commands.save();
    });

    await act(async () => {
      await expect(
        result.current.commands.resolveConflict('overwrite'),
      ).resolves.toMatchObject({ landed: true, written: true });
    });

    expect(result.current.commands.state.lastSavedAt).toEqual(
      expect.any(Number),
    );
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
  it('sets a condition, or a whole group, back to nothing said', async () => {
    const { engine } = engineWith();
    const runtime = await engine.open('orders-1');
    const { result } = renderHook(() => useFilterEditor(runtime));
    act(() => {
      result.current.addLeaf('warehouse');
      result.current.updateLeaf([0], { value: 'CN' });
      result.current.addGroup('or');
      result.current.addLeaf('status', [1]);
      result.current.updateLeaf([1, 0], { value: 'open' });
    });

    act(() => result.current.clearValue([0]));
    expect(result.current.tree.children[0]).toMatchObject({
      field: 'warehouse',
      value: '',
    });
    act(() => result.current.clearValue([1]));
    expect(result.current.tree.children[1]).toMatchObject({
      op: 'or',
      children: [{ field: 'status', value: '' }],
    });
  });

  it('offers only the fields not yet a condition of the group', async () => {
    const { engine } = engineWith();
    const runtime = await engine.open('orders-1');
    const { result } = renderHook(() => useFilterEditor(runtime));

    const before = result.current.fieldsFor().map(field => field.name);
    act(() => result.current.addLeaf('warehouse'));
    const after = result.current.fieldsFor().map(field => field.name);

    expect(before).toContain('warehouse');
    expect(after).not.toContain('warehouse');
    expect(after.length).toBe(before.length - 1);
    // A nested group starts with every field again.
    act(() => result.current.addGroup('or'));
    expect(result.current.fieldsFor([1]).map(field => field.name)).toContain(
      'warehouse',
    );
  });

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

  it('reseeds a value the new operator cannot hold', async () => {
    const result = await openEditor();

    // `amount` starts at `EQ 0`. `BETWEEN` needs two bounds, so carrying the
    // scalar across would mark the row invalid on a switch the user made on
    // purpose, and block apply on a mistake they did not make.
    act(() => result.current.filter.addLeaf('amount'));
    expect(result.current.filter.issues).toEqual([]);

    act(() =>
      result.current.filter.updateLeaf([0], {
        operator: `${FilterOperator.BETWEEN}`,
      }),
    );

    // Back to unfilled rather than to `[0, 0]`, which would be a condition
    // the user never asked for.
    expect(result.current.filter.tree.children[0]).toEqual({
      field: 'amount',
      operator: `${FilterOperator.BETWEEN}`,
      value: null,
    });
    expect(result.current.filter.issues).toEqual([]);
  });

  it('keeps a value the new operator still admits', async () => {
    const result = await openEditor();

    act(() => result.current.filter.addLeaf('amount'));
    act(() => result.current.filter.updateLeaf([0], { value: 5 }));
    act(() =>
      result.current.filter.updateLeaf([0], {
        operator: `${FilterOperator.GTE}`,
      }),
    );

    // Both operators take one number, so what the user typed survives.
    expect(result.current.filter.tree.children[0]).toMatchObject({ value: 5 });
  });

  it('lets a patch that carries its own value through untouched', async () => {
    const result = await openEditor();

    act(() => result.current.filter.addLeaf('amount'));
    act(() =>
      result.current.filter.updateLeaf([0], {
        operator: `${FilterOperator.BETWEEN}`,
        value: [1, 9],
      }),
    );

    expect(result.current.filter.tree.children[0]).toMatchObject({
      value: [1, 9],
    });
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

    // A value the kind cannot read is an error and the editor has to say so.
    // An *unfilled* one is not — see the tests below.
    act(() => result.current.filter.addLeaf('warehouse'));
    act(() => result.current.filter.updateLeaf([0], { value: 7 as never }));
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

  it('keeps element-scoped filter issues out of this editor', async () => {
    // An element's own filter is validated in its own field scope and its
    // findings are addressed under ['elements', i, 'filter', …]; carrying them
    // by code alone would let them mark top-level conditions as invalid.
    const elemented = ordersDefinition({
      fields: [
        ...ordersDefinition().fields,
        {
          name: 'items',
          label: 'Items',
          kind: 'array',
          elements: [{ name: 'sku', label: 'SKU', kind: 'string' }],
        },
      ],
      analysis: {
        count: true,
        fields: [
          {
            field: 'warehouse',
            groups: [AggregationGroupType.TERMS],
            functions: [],
          },
        ],
        elements: [
          {
            path: 'items',
            aggregations: [{ field: 'sku', groups: [], functions: [] }],
          },
        ],
      },
    });
    const { engine } = engineWith({
      definitions: [elemented],
      instances: [
        {
          ...mine,
          config: analysisConfig({
            elements: [
              {
                path: 'items',
                filter: {
                  op: 'and',
                  children: [{ field: 'ghost', operator: 'EQ', value: 'x' }],
                },
              },
            ],
          }),
        },
      ],
    });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return { opened, filter: useFilterEditor(opened.runtime) };
    });
    await waitFor(() => expect(result.current.opened.runtime).not.toBeNull());

    const codes = result.current.filter.issues.map(found => found.code);
    expect(codes).not.toContain('filter.field.unknown');
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
      // The capability has to match the fields above: `validateDefinition`
      // refuses an analysis over a field the definition does not declare.
      analysis: { count: true, fields: [] },
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

/**
 * The tree-editing half of the controller, over any tree rather than a
 * runtime's draft. It is what lets a condition holding a condition render
 * through the same components as the filter around it.
 */
describe('useFilterEditor pending and applied', () => {
  async function openEditor() {
    const { engine } = engineWith();
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return { opened, filter: useFilterEditor(opened.runtime) };
    });
    await waitFor(() =>
      expect(result.current.opened.runtime?.getSnapshot().result).toBeTruthy(),
    );
    return result;
  }

  it('describes the conditions the rows on screen came back under', async () => {
    const result = await openEditor();
    const filter = () => result.current.filter;
    expect(filter().applied).toEqual([]);

    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
    });
    act(() => filter().submit());

    // Applied, but not answered yet: the summary sits beside the rows and
    // must describe those rows, not the query that is still in flight.
    expect(filter().applied).toEqual([]);
    await waitFor(() => expect(filter().applied).toHaveLength(1));
  });

  it('marks the draft nodes that have not been applied yet', async () => {
    const result = await openEditor();
    const filter = () => result.current.filter;

    expect(filter().pending).toBe(false);
    expect(filter().pendingCount).toBe(0);
    expect(filter().isPending([0])).toBe(false);

    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
    });

    expect(filter().pending).toBe(true);
    // The new condition alone; the root still says the same `and`.
    expect(filter().pendingCount).toBe(1);
    expect(filter().isPending([0])).toBe(true);
    expect(filter().isPending([])).toBe(false);

    act(() => filter().submit());
    expect(filter().pending).toBe(false);
    expect(filter().pendingCount).toBe(0);
    expect(filter().isPending([0])).toBe(false);
  });

  it('counts a changed group operator without counting its children', async () => {
    const result = await openEditor();
    const filter = () => result.current.filter;
    act(() => {
      filter().addGroup('or');
      filter().addLeaf('warehouse', [0]);
      filter().updateLeaf([0, 0], { value: 'CN' });
    });
    act(() => filter().submit());
    expect(filter().pendingCount).toBe(0);

    act(() => filter().updateGroup([0], 'and'));

    // One group moved, and the condition inside it did not: a single edit
    // reported as three would be a number the user cannot act on.
    expect(filter().pendingCount).toBe(1);
    expect(filter().isPending([0])).toBe(true);
    expect(filter().isPending([0, 0])).toBe(false);

    act(() => filter().updateGroup([], 'or'));
    expect(filter().isPending([])).toBe(true);
    expect(filter().pendingCount).toBe(2);
  });

  it('counts a condition the draft no longer has', async () => {
    const result = await openEditor();
    const filter = () => result.current.filter;
    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
    });
    act(() => filter().submit());
    expect(filter().pendingCount).toBe(0);

    act(() => filter().remove([0]));

    // Taking the last condition out is as much an unapplied edit as adding
    // one: the rows on screen are still the narrow ones, and a badge of 0
    // beside a live Apply button is the count contradicting itself.
    expect(filter().pending).toBe(true);
    expect(filter().pendingCount).toBe(1);
    expect(filter().isPending([0])).toBe(true);
  });

  it('counts every condition a cleared filter dropped', async () => {
    const result = await openEditor();
    const filter = () => result.current.filter;
    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
      filter().addLeaf('amount');
      filter().updateLeaf([1], { value: 10 });
    });
    act(() => filter().submit());
    expect(filter().pendingCount).toBe(0);

    act(() => filter().clear());

    expect(filter().pending).toBe(true);
    expect(filter().pendingCount).toBe(2);
  });

  it('says nothing is pending for a draft over the tree budget', async () => {
    const result = await openEditor();
    const filter = () => result.current.filter;

    let nested: FilterTree = { op: 'and', children: [] };
    for (let level = 0; level < 12; level += 1)
      nested = { op: 'and', children: [nested] };
    act(() => result.current.opened.runtime?.edit({ filter: nested }));

    expect(filter().issues.map(found => found.code)).toContain(
      'filter.tree.too-deep',
    );
    // The panel does not draw a tree admission refused, so there is no pill
    // to mark and nothing to offer to apply — and comparing it is work the
    // editor would repeat on every render for an answer nobody reads.
    expect(filter().pending).toBe(false);
    expect(filter().pendingCount).toBe(0);
    expect(filter().isPending([0])).toBe(false);
    expect(filter().applied).toEqual([]);
  });

  it('keeps summarising the result while the draft goes over budget', async () => {
    const result = await openEditor();
    const filter = () => result.current.filter;

    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
    });
    act(() => filter().submit());
    await waitFor(() => expect(filter().applied).toHaveLength(1));

    let nested: FilterTree = { op: 'and', children: [] };
    for (let level = 0; level < 12; level += 1)
      nested = { op: 'and', children: [nested] };
    act(() => result.current.opened.runtime?.edit({ filter: nested }));

    expect(filter().issues.map(found => found.code)).toContain(
      'filter.tree.too-deep',
    );
    expect(filter().pending).toBe(false);
    // The rows on screen are still the narrow ones: what produced them was
    // admitted before it ran, so it is within budget whatever the draft has
    // since become. A summary that blanked while the user edited would stop
    // describing the data it sits beside.
    expect(filter().applied).toHaveLength(1);
    expect(filter().applied[0]?.text).toContain('CN');
  });

  it('keeps the root editor working when a metric owns the oversized tree', async () => {
    const analysis: ViewInstance = {
      id: 'orders-analysis',
      definitionId: 'orders',
      title: 'By warehouse',
      scope: 'personal',
      revision: '1',
      config: analysisConfig(),
    };
    const { engine } = engineWith({ instances: [analysis] });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-analysis');
      return { opened, filter: useFilterEditor(opened.runtime) };
    });
    await waitFor(() => expect(result.current.opened.runtime).not.toBeNull());
    const filter = () => result.current.filter;

    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
    });
    act(() => filter().submit());
    await waitFor(() => expect(filter().applied).toHaveLength(1));

    // A metric's own filter is validated in its own scope and re-pathed under
    // ['metrics', …], and it reports the very same budget codes as this tree.
    const wide: FilterTree = {
      op: 'and',
      children: Array.from({ length: 400 }, () => ({
        field: 'amount',
        operator: 'EQ' as const,
        value: 1,
      })),
    };
    act(() =>
      result.current.opened.runtime?.edit({
        metrics: [{ type: 'COUNT', alias: 'orders', filter: wide }],
      }),
    );
    expect(
      result.current.opened.runtime
        ?.getSnapshot()
        .issues.map(found => found.code),
    ).toContain('filter.tree.too-many-nodes');

    // That tree is not the one this editor draws, so the code alone must not
    // switch the editor off: its own conditions are still comparable, still
    // applicable, and the summary still describes the rows on screen.
    expect(filter().issues).toEqual([]);
    expect(filter().applied).toHaveLength(1);
    act(() => filter().updateLeaf([0], { value: 'US' }));
    expect(filter().pending).toBe(true);
    expect(filter().pendingCount).toBe(1);
    expect(filter().isPending([0])).toBe(true);
  });

  it('counts only the blocking findings that point at a condition', async () => {
    const result = await openEditor();
    const filter = () => result.current.filter;
    expect(filter().blocked).toBe(0);

    act(() => {
      filter().addLeaf('amount');
      filter().updateLeaf([0], { value: 'heavy' as never });
    });

    expect(filter().blocked).toBe(1);

    // An error elsewhere in the config blocks apply too, but no pill can be
    // marked for it, so the filter panel must not claim it.
    act(() => result.current.opened.runtime?.edit({ pageSize: 5000 }));
    expect(filter().blocked).toBe(1);
  });
});

describe('useFilterEditor under a host scope filter', () => {
  /** What an embedding host narrows the view to; never in the draft. */
  const scope: FilterTree = {
    op: 'and',
    children: [{ field: 'status', operator: 'EQ', value: 'OPEN' }],
  };

  /** A summary item's path as the tree editor addresses a node. */
  function indexes(path: readonly (string | number)[]): number[] {
    return path.filter((step): step is number => typeof step === 'number');
  }

  async function openScoped() {
    const { engine } = engineWith();
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1', scope);
      return { opened, filter: useFilterEditor(opened.runtime) };
    });
    await waitFor(() =>
      expect(result.current.opened.runtime?.getSnapshot().result).toBeTruthy(),
    );
    return result;
  }

  it('describes the host conditions apart from the view own', async () => {
    const result = await openScoped();
    const filter = () => result.current.filter;

    expect(filter().scoped).toHaveLength(1);
    expect(filter().scoped[0]).toMatchObject({
      field: 'status',
      path: ['children', 0],
    });
    expect(filter().applied).toEqual([]);

    act(() => {
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
      filter().addLeaf('amount');
      filter().updateLeaf([1], { value: 10 });
    });
    act(() => filter().submit());
    await waitFor(() => expect(filter().applied).toHaveLength(2));

    // The scope ran with them, and `result.config` holds the merged tree —
    // but a badge for it would offer a remove nobody here can honour, and
    // the path it carried would address the draft's next condition instead.
    expect(filter().applied.map(item => item.path)).toEqual([
      ['children', 0],
      ['children', 1],
    ]);
    expect(filter().applied.some(item => item.text.includes('OPEN'))).toBe(
      false,
    );

    // The path a badge carries reaches the leaf it names, and only it.
    act(() => {
      filter().clearValue(indexes(filter().applied[1].path));
      filter().submit();
    });
    await waitFor(() => expect(filter().applied).toHaveLength(1));
    expect(filter().applied[0].text).toContain('CN');
    expect(filter().scoped).toHaveLength(1);
  });

  it('addresses an or-root draft through the config that produced the result', async () => {
    const result = await openScoped();
    const filter = () => result.current.filter;

    act(() => {
      filter().updateGroup([], 'or');
      filter().addLeaf('warehouse');
      filter().updateLeaf([0], { value: 'CN' });
      filter().addLeaf('amount');
      filter().updateLeaf([1], { value: 10 });
    });
    act(() => filter().submit());
    await waitFor(() => expect(filter().applied).toHaveLength(1));

    // `mergeFilters` carries an `or` draft in as the first child of an `and`,
    // so every path into the merged tree is one level deeper than the tree
    // the editor draws. The summary reads `own`, which is that very tree.
    const summary = filter().applied[0];
    expect(summary).toMatchObject({ group: 'or', path: [] });
    expect(summary.text).toContain('CN');
    expect(summary.text).not.toContain('OPEN');

    const runtime = result.current.opened.runtime;
    expect(runtime?.getSnapshot().result?.config.filter).toMatchObject({
      op: 'and',
    });
    expect(runtime?.getSnapshot().result?.own.filter).toMatchObject({
      op: 'or',
    });

    act(() => {
      filter().clearValue(indexes(summary.path));
      filter().submit();
    });
    await waitFor(() => expect(filter().applied).toEqual([]));
    // The host's own condition is not the editor's to take out, and taking
    // the view's out did not touch it.
    expect(filter().scoped).toHaveLength(1);
    expect(runtime?.scopeFilter).toEqual(scope);
  });
});

describe('treeController', () => {
  const fields = [
    { name: 'sku', label: 'SKU', kind: 'string' as const },
    { name: 'qty', label: 'Qty', kind: 'number' as const },
  ];

  function controller(tree: FilterTree = { op: 'and', children: [] }) {
    let current = tree;
    const build = () =>
      treeController({
        tree: current,
        fields,
        kinds: builtinFieldKinds,
        issues: [],
        onChange: next => {
          current = next;
        },
      });
    return {
      act: (run: (c: ReturnType<typeof build>) => void) => run(build()),
      tree: () => current,
    };
  }

  it('adds, edits and removes without holding state', () => {
    const own = controller();

    own.act(c => c.addLeaf('sku'));
    expect(own.tree().children).toHaveLength(1);

    own.act(c => c.updateLeaf([0], { value: 'A' }));
    expect(own.tree().children[0]).toMatchObject({ field: 'sku', value: 'A' });

    own.act(c => c.remove([0]));
    expect(own.tree().children).toEqual([]);
  });

  it('nests a group and changes how it combines', () => {
    const own = controller();

    own.act(c => c.addGroup('or'));
    own.act(c => c.addLeaf('qty', [0]));
    expect(own.tree().children[0]).toMatchObject({ op: 'or' });

    own.act(c => c.updateGroup([0], 'nor'));
    expect(own.tree().children[0]).toMatchObject({ op: 'nor' });

    // `updateAt` leaves the root alone, so the root is written directly.
    own.act(c => c.updateGroup([], 'or'));
    expect(own.tree().op).toBe('or');
  });

  it('answers what a field offers and what edits it', () => {
    const own = controller({
      op: 'and',
      children: [{ field: 'sku', operator: 'EQ', value: 'A' }],
    });

    own.act(c => {
      expect(c.operatorsFor('sku')).toContain('CONTAINS');
      expect(c.operatorsFor('gone')).toEqual([]);
      expect(c.editorFor([0])).toMatchObject({ input: 'text' });
      expect(c.editorFor([9])).toBeNull();
    });
  });

  it('ignores a field the caller does not offer', () => {
    const own = controller();

    own.act(c => c.addLeaf('gone'));

    expect(own.tree().children).toEqual([]);
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

  /** More rows than one page holds, so there is a next page to move to. */
  function manyPages(): ViewSource {
    return testSource({
      paged: vi.fn(() => Promise.resolve({ total: 200, list: [...ROWS] })),
    });
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
      layouts: [],
      selectedRows: [],
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

  /**
   * A size above `maxPageSize` is refused by `validateRecord`, so offering
   * one is offering a way to break the view: the user picks 100 from a list
   * the UI drew and the config stops running. The ladder is cut to what the
   * runtime was admitted under.
   */
  it('offers only the page sizes the limits admit', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({ instances: [mine] }),
      resolveSource: () => testSource(),
      limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 50 },
    });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return useRecordTable(opened.runtime as RecordViewRuntime | null);
    });
    await waitFor(() => expect(result.current.status).toBe('success'));

    expect(result.current.pageSizes).toEqual([10, 20, 50]);
  });

  /** Whatever it is: a select whose value is not an item of it shows nothing. */
  it('folds the size in force into the ladder', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: new MemoryViewStore({
        instances: [{ ...mine, config: recordConfig({ pageSize: 25 }) }],
      }),
      resolveSource: () => testSource(),
      limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 50 },
    });
    const { result } = renderHook(() => {
      const opened = useOpenView(engine, 'orders-1');
      return useRecordTable(opened.runtime as RecordViewRuntime | null);
    });
    await waitFor(() => expect(result.current.status).toBe('success'));

    expect(result.current.pageSizes).toEqual([10, 20, 25, 50]);
  });

  it('offers the layouts the definition allows', async () => {
    const result = await openTable();

    // A switcher offers these and nothing else — and nothing at all below two.
    expect(result.current.table.layouts).toEqual(['table', 'card']);
  });

  it('gives the selected rows in result order', async () => {
    const result = await openTable();

    act(() => result.current.table.toggle('o-2'));
    act(() => result.current.table.toggle('o-1'));

    // Clicked in reverse, listed as the table lists them: a bulk action names
    // what it is about to touch, and the list has to read like the rows above.
    expect(result.current.table.selection).toEqual(['o-2', 'o-1']);
    expect(result.current.table.selectedRows.map(row => row.key)).toEqual([
      'o-1',
      'o-2',
    ]);

    act(() => result.current.table.clearSelection());
    expect(result.current.table.selectedRows).toEqual([]);
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
    const result = await openTable(manyPages());

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

  /**
   * The paged source says how many rows there are, and Next asked for the
   * page after the last one anyway: an empty result that reads exactly like
   * a filter matching nothing.
   */
  it('stops at the last page rather than asking past it', async () => {
    const result = await openTable();

    expect(result.current.table.hasNext).toBe(false);
    act(() => result.current.table.next());

    expect(result.current.table.paging).toMatchObject({ index: 1 });
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

  /**
   * The card half of the config is what a card list renders. Without it the
   * UI fell back to the table's columns, so every card setting a user saved
   * was stored and then ignored.
   */
  it('projects the card the config saved, with its labels resolved', async () => {
    const result = await openTable();

    expect(result.current.table.card).toEqual({
      title: 'id',
      // The title and each field carry how their values show.
      titleField: {
        field: 'id',
        label: 'Order',
        kind: 'string',
        cell: 'string',
      },
      fields: [
        { field: 'amount', label: 'Amount', kind: 'number', cell: 'number' },
      ],
    });
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
