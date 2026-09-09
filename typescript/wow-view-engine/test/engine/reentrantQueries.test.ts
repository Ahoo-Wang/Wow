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
import { newFilterNode } from '../../src/filter/filterCore.js';

import { filter } from '@ahoo-wang/fetcher-wow';
import { expect, it, vi } from 'vitest';
import { createFilterConfiguration } from '../../src/filter/filterCore.js';
import { deferred, instance, selected, setup } from './fixtures.js';

it.each(['state', 'abort'] as const)(
  'does not let an older refresh replace a Query started by its %s notification',
  async notification => {
    const saved = instance();
    saved.config.filters = createFilterConfiguration({
      ...newFilterNode(FilterOperator.GTE, 'state.amount'),
      props: { value: 10 },
    });
    const { engine, paged } = setup({
      instances: { instances: [saved], defaultInstanceId: saved.id },
    });
    paged.mockImplementation(async query => ({
      total: 1,
      list: [{ state: { id: 'a', amount: query.filter.value } }],
    }));
    await engine.load();
    const stalled = deferred<{ total: number; list: never[] }>();
    paged.mockReturnValueOnce(stalled.promise);
    const previous = engine.refresh();
    await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
    let next: Promise<void> | undefined;
    let submitted = false;
    const submit = () => {
      if (!submitted) {
        submitted = true;
        engine.setFilterDraft(
          createFilterConfiguration({
            ...newFilterNode(FilterOperator.GTE, 'state.amount'),
            props: { value: 20 },
          }),
        );
        next = engine.applyFilter();
      }
    };
    const unsubscribe = engine.subscribe(() => {
      if (notification === 'state' && selected(engine).queryStatus === 'idle')
        submit();
    });
    if (notification === 'abort')
      paged.mock.calls[1][2].signal.addEventListener('abort', submit, {
        once: true,
      });
    try {
      await engine.refresh();
      await next;
      expect(submitted).toBe(true);
      expect(selected(engine)).toMatchObject({
        appliedFilter: filter.gte('state.amount', 20),
        filterPending: false,
        rows: [{ state: { id: 'a', amount: 20 } }],
      });
      expect(paged.mock.calls.at(-1)?.[0].filter).toEqual(
        filter.gte('state.amount', 20),
      );
    } finally {
      unsubscribe();
      engine.dispose();
      stalled.resolve({ list: [], total: 0 });
      await previous;
    }
  },
);

it.each(['paged', 'cursor'] as const)(
  'does not call the %s source after a summary notification disposes the engine',
  async mode => {
    const saved = instance('mine', mode);
    saved.config.presentation.table.columns[0].summary = ['SUM'];
    const { engine, paged, cursor } = setup({
      instances: { instances: [saved], defaultInstanceId: saved.id },
    });
    const request = mode === 'paged' ? paged : cursor;
    let disposed = false;
    const unsubscribe = engine.subscribe(() => {
      if (
        !disposed &&
        engine.getSnapshot().sessions.mine?.allSummary.status === 'loading'
      ) {
        disposed = true;
        engine.dispose();
      }
    });
    try {
      await engine.load();
      expect(disposed).toBe(true);
      expect(request).not.toHaveBeenCalled();
    } finally {
      unsubscribe();
      engine.dispose();
    }
  },
);

it.each(['load', 'select'] as const)(
  'preserves a newer paging query started by a %s notification',
  async operation => {
    const { engine, host, paged } = setup({
      instances: {
        instances: [instance()],
        defaultInstanceId: operation === 'load' ? 'mine' : null,
      },
    });
    if (operation === 'select') await engine.load();
    const source = deferred<Awaited<ReturnType<typeof host.resolveSource>>>();
    vi.mocked(host.resolveSource).mockReturnValue(source.promise);
    let newer: Promise<void> | undefined;
    let finished = false;
    const unsubscribe = engine.subscribe(() => {
      if (!newer && engine.getSnapshot().selectedInstanceId === 'mine') {
        // Guard before setPage publishes another notification.
        newer = Promise.resolve();
        newer = engine.setPage(2).then(() => {
          finished = true;
        });
      }
    });
    const initial =
      operation === 'load' ? engine.load() : engine.selectInstance('mine');
    try {
      await vi.waitFor(() => expect(host.resolveSource).toHaveBeenCalled());
      expect(host.resolveSource).toHaveBeenCalledTimes(1);
      expect(finished).toBe(false);
      paged.mockResolvedValue({
        total: 20,
        list: [{ state: { id: 'page-2', amount: 2 } }],
      });
      source.resolve({ paged });
      await Promise.all([initial, newer]);
      expect(finished).toBe(true);
      expect(paged).toHaveBeenCalledTimes(1);
      expect(paged.mock.calls[0][0].pagination.index).toBe(2);
      expect(selected(engine).rows).toEqual([
        { state: { id: 'page-2', amount: 2 } },
      ]);
    } finally {
      unsubscribe();
      engine.dispose();
      source.resolve({ paged });
      await Promise.all([initial, newer]);
    }
  },
);
