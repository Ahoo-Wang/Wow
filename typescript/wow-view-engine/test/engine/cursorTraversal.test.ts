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
import {
  newFilterNode,
  createFilterConfiguration,
} from '../../src/filter/filterCore.js';

import { afterEach, expect, it, vi } from 'vitest';
import { SortDirection } from '@ahoo-wang/fetcher-wow';

import type { ViewEngine } from '../../src/record/ViewEngine.js';
import { deferred, instance, selected, setup } from './fixtures.js';

const engines: ViewEngine[] = [];
afterEach(() => engines.splice(0).forEach(engine => engine.dispose()));

function setupCursor(ids = ['mine']) {
  const value = setup({
    instances: {
      instances: ids.map(id => instance(id, 'cursor')),
      defaultInstanceId: ids[0],
    },
  });
  engines.push(value.engine);
  return value;
}

it.each([
  ['same cursor', ['a', 'a']],
  ['two-step cycle', ['a', 'b', 'a']],
] as const)(
  'rejects a %s instead of enabling another looping page',
  async (_label, cursors) => {
    const { engine, cursor } = setupCursor();
    for (const nextCursor of cursors)
      cursor.mockResolvedValueOnce({ list: [], nextCursor });
    await engine.load();
    for (let page = 1; page < cursors.length - 1; page++)
      await engine.nextPage();
    await expect(engine.nextPage()).rejects.toThrow(/游标/);
    expect(selected(engine)).toMatchObject({
      queryStatus: 'error',
      nextCursor: null,
      rows: [],
    });
    await engine.nextPage();
    expect(cursor).toHaveBeenCalledTimes(cursors.length);
  },
);

function page(cursor: string | null) {
  return {
    list: [],
    nextCursor: cursor === null ? 'a' : cursor === 'a' ? 'b' : 'c',
  };
}

it('keeps consumed cursors per instance and permits reloading its current page', async () => {
  const { engine, cursor } = setupCursor(['mine', 'shared']);
  cursor.mockImplementation(async query => page(query.cursor));
  await engine.load();
  await engine.nextPage();
  await engine.nextPage();
  await engine.selectInstance('shared');
  await engine.nextPage();
  await engine.nextPage();
  await engine.selectInstance('mine');
  expect(selected(engine)).toMatchObject({
    page: 3,
    cursor: 'b',
    nextCursor: 'c',
    queryStatus: 'success',
  });
  cursor.mockResolvedValueOnce({ list: [], nextCursor: 'a' });
  await expect(engine.nextPage()).rejects.toThrow(/游标/);
  expect(cursor.mock.calls.map(([query]) => query.cursor)).toEqual([
    null,
    'a',
    'b',
    null,
    'a',
    'b',
    'b',
    'c',
  ]);
});

it('retries a failed page and accepts a corrected non-looping response', async () => {
  const { engine, cursor } = setupCursor(['mine', 'shared']);
  cursor.mockImplementation(async query => page(query.cursor));
  await engine.load();
  cursor.mockRejectedValueOnce(new Error('temporarily unavailable'));
  await expect(engine.nextPage()).rejects.toThrow('temporarily unavailable');
  await engine.selectInstance('shared');
  await engine.selectInstance('mine');
  expect(selected(engine)).toMatchObject({
    cursor: 'a',
    nextCursor: 'b',
    queryStatus: 'success',
  });
  cursor.mockResolvedValueOnce({ list: [], nextCursor: 'a' });
  await expect(engine.nextPage()).rejects.toThrow(/游标/);
  await engine.selectInstance('shared');
  await engine.selectInstance('mine');
  expect(selected(engine)).toMatchObject({
    cursor: 'b',
    nextCursor: 'c',
    queryStatus: 'success',
  });
});

it.each(['refresh', 'filter', 'sort', 'size', 'restore', 'load'] as const)(
  'starts a fresh cursor traversal after %s',
  async action => {
    const { engine, cursor } = setupCursor();
    cursor.mockImplementation(async query => page(query.cursor));
    await engine.load();
    await engine.nextPage();
    await engine.nextPage();
    switch (action) {
      case 'refresh':
        await engine.refresh();
        break;
      case 'filter':
        engine.setFilterDraft(
          createFilterConfiguration({
            ...newFilterNode(FilterOperator.GT, 'state.amount'),
            props: { value: 10 },
          }),
        );
        await engine.applyFilter();
        break;
      case 'sort':
        await engine.setSort([
          { field: 'state.amount', direction: SortDirection.DESC },
        ]);
        break;
      case 'size':
        await engine.setPageSize(20);
        break;
      case 'restore':
        await engine.restore();
        break;
      case 'load':
        await engine.load();
        break;
    }
    expect(selected(engine)).toMatchObject({
      page: 1,
      cursor: null,
      nextCursor: 'a',
      queryStatus: 'success',
    });
    await engine.nextPage();
    expect(selected(engine)).toMatchObject({
      page: 2,
      cursor: 'a',
      nextCursor: 'b',
      queryStatus: 'success',
    });
  },
);

it('allows a background refresh of the first cursor page', async () => {
  const { engine, cursor } = setupCursor();
  cursor.mockImplementation(async query => page(query.cursor));
  await engine.load();
  await engine.refresh(undefined, { background: true });
  await engine.nextPage();
  expect(selected(engine)).toMatchObject({
    cursor: 'a',
    nextCursor: 'b',
    queryStatus: 'success',
  });
  expect(cursor.mock.calls.map(([query]) => query.cursor)).toEqual([
    null,
    null,
    'a',
  ]);
});

it('detects a cycle when a successful page notification starts the next page synchronously', async () => {
  const { engine, cursor } = setupCursor();
  cursor.mockImplementation(async query =>
    query.cursor === 'b' ? { list: [], nextCursor: 'a' } : page(query.cursor),
  );
  await engine.load();
  let next: Promise<void> | undefined;
  let advanced = false;
  const unsubscribe = engine.subscribe(() => {
    const session = selected(engine);
    if (
      !advanced &&
      session.cursor === 'a' &&
      session.nextCursor === 'b' &&
      session.queryStatus === 'success'
    ) {
      advanced = true;
      next = engine.nextPage();
    }
  });
  try {
    await engine.nextPage();
    expect(next).toBeDefined();
    await expect(next).rejects.toThrow(/游标/);
    expect(selected(engine)).toMatchObject({
      queryStatus: 'error',
      nextCursor: null,
    });
  } finally {
    unsubscribe();
  }
});

it.each(['success', 'failure'] as const)(
  'ignores stale cursor %s after starting a new traversal',
  async outcome => {
    const { engine, cursor } = setupCursor();
    cursor.mockImplementation(async query => page(query.cursor));
    await engine.load();
    await engine.nextPage();
    const stalled = deferred<{ list: never[]; nextCursor: string | null }>();
    cursor.mockReturnValueOnce(stalled.promise);
    const previous = engine.nextPage();
    await vi.waitFor(() => expect(cursor).toHaveBeenCalledTimes(3));
    const controller = cursor.mock.calls[2][2];
    await engine.refresh();
    expect(controller.signal.aborted).toBe(true);
    if (outcome === 'success') stalled.resolve({ list: [], nextCursor: 'c' });
    else stalled.reject(new Error('obsolete failure'));
    await previous;
    await engine.nextPage();
    expect(selected(engine)).toMatchObject({
      cursor: 'a',
      nextCursor: 'b',
      queryStatus: 'success',
      queryError: null,
    });
  },
);

it('ignores a late cursor result after disposal', async () => {
  const { engine, cursor } = setupCursor();
  cursor.mockImplementation(async query => page(query.cursor));
  await engine.load();
  const stalled = deferred<{ list: never[]; nextCursor: string | null }>();
  cursor.mockReturnValueOnce(stalled.promise);
  const previous = engine.nextPage();
  await vi.waitFor(() => expect(cursor).toHaveBeenCalledTimes(2));
  const before = engine.getSnapshot();
  engine.dispose();
  stalled.resolve({ list: [], nextCursor: 'a' });
  await previous;
  expect(engine.getSnapshot()).toBe(before);
  expect(cursor.mock.calls[1][2].signal.aborted).toBe(true);
});
