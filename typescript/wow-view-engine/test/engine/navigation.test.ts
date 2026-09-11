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
import { createFilterConfiguration } from '../../src/filter/filterCore.js';

import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { expect, it, vi } from 'vitest';
import { newFilterNode } from '../../src/filter/filterCore.js';
import type { ViewInstance } from '../../src/contracts/viewModel.js';
import type { ViewHost } from '../../src/contracts/ViewHost.js';
import { deferred, instance, selected, setup } from './fixtures.js';

it('keeps a subscriber navigation newer than the selection canceling a query', async () => {
  const { engine, paged } = setup({
    instances: {
      instances: [instance(), instance('shared'), instance('third')],
      defaultInstanceId: 'mine',
    },
  });
  await engine.load();
  const entered = deferred<void>();
  const stalled = deferred<{ list: never[]; total: number }>();
  paged.mockImplementationOnce(() => {
    entered.resolve();
    return stalled.promise;
  });
  const refreshing = engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .refresh();
  await entered.promise;
  let redirected: Promise<void> | undefined;
  let redirect = true;
  const unsubscribe = engine.subscribe(() => {
    const state = engine.getSnapshot();
    if (
      redirect &&
      state.selectedInstanceId === 'mine' &&
      state.sessions.mine.queryStatus === 'idle'
    ) {
      redirect = false;
      redirected = engine.selectInstance('third');
    }
  });
  try {
    await engine.selectInstance('shared');
    await redirected;
    expect(engine.getSnapshot().selectedInstanceId).toBe('third');
    expect(engine.getSnapshot().sessions.third.queryStatus).toBe('success');
    expect(engine.getSnapshot().sessions.shared.queryStatus).toBe('idle');
  } finally {
    unsubscribe();
    engine.dispose();
    stalled.resolve({ list: [], total: 0 });
    await refreshing;
  }
});

it('retains each instance draft and mode, clearing selection for the new query when navigating', async () => {
  const { engine, paged } = setup();
  await engine.load();
  const draft = newFilterNode(FilterOperator.EQ, 'state.amount');
  engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .setFilterDraft(createFilterConfiguration(draft));
  engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .setFilterValidity(false);
  engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .setFilterMode('advanced');
  engine.setTitle('Local draft');
  engine.record(engine.getSnapshot().selectedInstanceId!).setSelection(['a']);
  await engine.selectInstance('shared');
  expect(selected(engine).instance.title).toBe('shared');
  expect(selected(engine).filterPending).toBe(false);
  await engine.selectInstance('mine');
  expect(selected(engine)).toMatchObject({
    filterDraft: { root: draft, mode: 'advanced' },
    filterPending: true,

    selectedRowKeys: [],
    instance: { title: 'Local draft' },
  });
  expect(paged).toHaveBeenCalledTimes(3);
  await expect(
    engine.record(engine.getSnapshot().selectedInstanceId!).applyFilter(),
  ).rejects.toThrow(/筛选/);
  engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .setFilterValidity(true);
  await engine.record(engine.getSnapshot().selectedInstanceId!).applyFilter();
  expect(selected(engine).filterDraft.root).toEqual(draft);
  expect(selected(engine).filterPending).toBe(false);
  expect(selected(engine).selectedRowKeys).toEqual([]);
  await engine.restore();
  expect(selected(engine)).toMatchObject({
    dirty: false,
    filterPending: false,
    page: 1,
    instance: { title: 'mine' },
  });
});

it('ignores a switched-away read and obsolete unknown-instance selections', async () => {
  const first = deferred<ViewInstance>();
  const second = deferred<ViewInstance>();
  const loadInstance = vi
    .fn()
    .mockImplementationOnce(() => first.promise)
    .mockImplementationOnce(() => second.promise);
  const { engine, paged } = setup({
    host: { instance: { load: loadInstance } } as unknown as ViewHost,
  });
  await engine.load();
  const previousRows = selected(engine).rows;
  const old = deferred<unknown>();
  paged.mockImplementationOnce(() => old.promise);
  const query = engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .refresh();
  await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  await engine.selectInstance('shared');
  old.resolve({ total: 1, list: [{ state: { id: 'old' } }] });
  await query;
  expect(selected(engine, 'mine').rows).toEqual(previousRows);
  const readFirst = engine.selectInstance('first');
  const readSecond = engine.selectInstance('second');
  second.resolve(instance('second'));
  await readSecond;
  first.reject(new Error('obsolete selection'));
  await readFirst;
  expect(engine.getSnapshot().selectedInstanceId).toBe('second');
  expect(engine.getSnapshot().error).toBeNull();
  expect(engine.getSnapshot().instanceIds).not.toContain('first');
});

it('ignores an older reload rejection after a newer reload has completed', async () => {
  const old = deferred<ViewInstance>();
  const loadInstance = vi
    .fn()
    .mockImplementationOnce(() => old.promise)
    .mockResolvedValue({ ...instance(), title: 'Fresh', revision: 'r5' });
  const { engine } = setup({
    host: { instance: { load: loadInstance } } as unknown as ViewHost,
  });
  await engine.load();
  const first = engine.reloadInstance();
  await engine.reloadInstance();
  old.reject(new Error('old reload failed'));
  await first;
  expect(selected(engine)).toMatchObject({
    baseline: { title: 'Fresh', revision: 'r5' },
    instance: { title: 'Fresh', revision: 'r5' },
    writeError: null,
  });
});

it('keeps the reload started synchronously by abort as the current owner', async () => {
  const first = deferred<ViewInstance>();
  const newest = deferred<ViewInstance>();
  const stale = deferred<ViewInstance>();
  let nested: Promise<void> | undefined;
  const loadInstance = vi.fn((_id: string, signal: AbortSignal) => {
    if (loadInstance.mock.calls.length === 1) {
      signal.addEventListener(
        'abort',
        () => {
          nested = engine.reloadInstance();
        },
        { once: true },
      );
      return first.promise;
    }
    return loadInstance.mock.calls.length === 2
      ? newest.promise
      : stale.promise;
  });
  const { engine } = setup({
    host: { instance: { load: loadInstance } } as unknown as ViewHost,
  });
  await engine.load();
  const initial = engine.reloadInstance();
  const outer = engine.reloadInstance();
  newest.resolve({ ...instance(), title: 'Newest', revision: 'r3' });
  await nested;
  stale.resolve({ ...instance(), title: 'Stale', revision: 'r2' });
  await outer;
  expect(selected(engine)).toMatchObject({
    baseline: { title: 'Newest', revision: 'r3' },
    instance: { title: 'Newest', revision: 'r3' },
  });
  engine.dispose();
  first.resolve(instance());
  await initial;
});

it('retains the workspace and its in-flight query when an unknown selection fails', async () => {
  const pending = deferred<ViewInstance>();
  const { engine, paged } = setup({
    host: { instance: { load: () => pending.promise } } as unknown as ViewHost,
  });
  await engine.load();
  const queryResult = deferred<unknown>();
  paged.mockImplementationOnce(() => queryResult.promise);
  const query = engine.record('mine').refresh();
  const opening = engine.selectInstance('missing');
  const rejected = expect(opening).rejects.toThrow('unavailable');
  expect(engine.getSnapshot().selectedInstanceId).toBe('mine');
  pending.reject(new Error('unavailable'));
  await rejected;
  expect(engine.getSnapshot().selectedInstanceId).toBe('mine');
  queryResult.resolve({ total: 1, list: [{ state: { id: 'retained' } }] });
  await query;
  expect(selected(engine).rows).toEqual([{ state: { id: 'retained' } }]);
  engine.dispose();
});
