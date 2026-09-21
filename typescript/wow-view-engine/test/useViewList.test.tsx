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
 * The view summaries a sidebar draws: whose views they are, the order the
 * user put them in, the default among them, and what a list narrowed to one
 * kind still shows.
 */

import { act, cleanup, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  ViewStoreError,
  type ViewInstance,
  type ViewPreferences,
} from '../src/index.js';
import { useViewList } from '../src/react/index.js';
import { analysisConfig, mine } from './fixtures.js';
import { engineWith } from './fixtures/hooks.js';

afterEach(cleanup);

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
