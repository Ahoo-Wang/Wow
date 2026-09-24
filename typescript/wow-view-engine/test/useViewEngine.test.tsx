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

/**
 * The plumbing every other hook sits on: one engine created and disposed,
 * one open view subscribed to, and the two small modules that stand between
 * the hooks and the host — page visibility and a thrown command as an Issue.
 */

import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  MemoryViewStore,
  ViewCommandError,
  ViewStoreError,
  ViewWriteError,
  issue,
  type FilterTree,
  type RecordViewRuntime,
} from '../src/index.js';
import {
  browserRuntimeEnvironment,
  documentVisibility,
  toIssue,
  useOpenView,
  useViewEngine,
  useViewRuntime,
} from '../src/react/index.js';
import {
  mine,
  ordersDefinition,
  recordConfig,
  requireRecordConfig,
  testSource,
} from './fixtures.js';
import { engineWith } from './fixtures/hooks.js';

afterEach(cleanup);

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
   * The same on the first open (D17-5). The condition goes in at
   * construction, where nobody holds a return value, so the refusal is read
   * off the runtime — and the view runs un-narrowed rather than waiting to
   * be fixed for something that was never its own.
   */
  it('hands back the issues a narrowing refused on open produced', async () => {
    const source = testSource();
    const { engine } = engineWith({ source });
    const refused: FilterTree = {
      op: 'and',
      children: [
        { field: 'nope', operator: `${FilterOperator.EQ}`, value: 'x' },
      ],
    };

    const { result } = renderHook(() =>
      useOpenView(engine, 'orders-1', refused),
    );

    await waitFor(() =>
      expect(result.current.scopeIssues.map(found => found.code)).toEqual([
        'filter.field.unknown',
      ]),
    );
    expect(result.current.runtime?.getSnapshot().issues).toEqual([]);
    await waitFor(() => expect(source.paged).toHaveBeenCalledTimes(1));
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
          payload: {
            action: 'delete',
            id: 'a',
            definitionId: 'orders',
            revision: '1',
          },
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
          payload: {
            action: 'delete',
            id: 'a',
            definitionId: 'orders',
            revision: '1',
          },
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
