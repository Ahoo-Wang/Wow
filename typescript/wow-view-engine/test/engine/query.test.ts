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

import { SortDirection } from '@ahoo-wang/fetcher-wow';
import { expect, it, vi } from 'vitest';
import { deferred, instance, selected, setup } from './fixtures.js';

it('sends server pagination without projection and keeps column edits local', async () => {
  const { engine, paged } = setup();
  paged.mockResolvedValue({
    total: 100,
    list: [{ state: { id: 'a', amount: 10 } }],
  });
  await engine.load();
  expect(paged.mock.calls[0]).toEqual([
    {
      filter: { op: 'MATCH_ALL' },
      sort: [],
      pagination: { index: 1, size: 10 },
    },
    undefined,
    expect.any(AbortController),
  ]);
  engine.setSelection(['a']);
  engine.setColumns([
    {
      id: 'amount',
      kind: 'field',
      field: 'state.amount',
      width: 250,
      pinned: 'left',
    },
  ]);
  expect(selected(engine).selectedRowKeys).toEqual(['a']);
  expect(paged).toHaveBeenCalledOnce();
  expect(selected(engine).dirty).toBe(true);
  await engine.setSort([
    { field: 'state.amount', direction: SortDirection.DESC },
  ]);
  expect(selected(engine).selectedRowKeys).toEqual([]);
  expect(paged.mock.calls.at(-1)?.[0]).toMatchObject({
    sort: [{ field: 'state.amount', direction: 'DESC' }],
    pagination: { index: 1, size: 10 },
  });
  engine.setSelection(['a']);
  await engine.setPage(3);
  expect(selected(engine).selectedRowKeys).toEqual([]);
  expect(paged.mock.calls.at(-1)?.[0].pagination.index).toBe(3);
  await engine.setPageSize(25);
  expect(paged.mock.calls.at(-1)?.[0].pagination).toEqual({
    index: 1,
    size: 25,
  });
});

it('clears stale rows and ignores old query resolution and rejection after a newer filter', async () => {
  const { engine, paged } = setup();
  await engine.load();
  const old = deferred<unknown>();
  paged.mockImplementationOnce(() => old.promise);
  const previous = engine.refresh();
  await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  expect(selected(engine)).toMatchObject({
    rows: [],
    queryStatus: 'loading',
    total: null,
  });
  const controller = paged.mock.calls[1][2];
  engine.setFilterDraft(
    createFilterConfiguration({
      ...newFilterNode(FilterOperator.GT, 'state.amount'),
      props: { value: 5 },
    }),
  );
  await engine.applyFilter();
  expect(controller.signal.aborted).toBe(true);
  old.reject(new Error('obsolete error'));
  await previous;
  expect(selected(engine)).toMatchObject({
    queryStatus: 'success',
    queryError: null,
    appliedFilter: { op: 'GT', value: 5 },
  });
  expect(selected(engine).rows).toEqual([{ state: { id: 'a', amount: 10 } }]);

  const older = deferred<unknown>();
  paged.mockImplementationOnce(() => older.promise);
  const olderQuery = engine.refresh();
  await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(4));
  await engine.refresh();
  older.resolve({ total: 1, list: [{ state: { id: 'obsolete' } }] });
  await olderQuery;
  expect(selected(engine).rows[0]).toEqual({
    state: { id: 'a', amount: 10 },
  });
});

it('resets cursor position on filter, sort, size and refresh and exposes no previous-page path', async () => {
  const { engine, cursor } = setup({
    instances: {
      instances: [instance('mine', 'cursor')],
      defaultInstanceId: 'mine',
    },
  });
  cursor.mockImplementation(async query => ({
    list: [{ state: { id: 'a' } }],
    nextCursor: query.cursor === null ? 'next' : null,
  }));
  await engine.load();
  expect(cursor.mock.calls[0][0]).toEqual({
    filter: { op: 'MATCH_ALL' },
    sort: [],
    size: 10,
    cursor: null,
  });
  engine.setSelection(['a']);
  await engine.nextPage();
  expect(cursor.mock.calls.at(-1)?.[0].cursor).toBe('next');
  expect(selected(engine)).toMatchObject({ page: 2, selectedRowKeys: [] });
  await expect(engine.setPage(1)).rejects.toThrow();
  engine.setFilterDraft(
    createFilterConfiguration({
      ...newFilterNode(FilterOperator.GT, 'state.amount'),
      props: { value: 2 },
    }),
  );
  await engine.applyFilter();
  expect(cursor.mock.calls.at(-1)?.[0].cursor).toBeNull();
  await engine.nextPage();
  await engine.setSort([
    { field: 'state.amount', direction: SortDirection.ASC },
  ]);
  expect(cursor.mock.calls.at(-1)?.[0].cursor).toBeNull();
  await engine.nextPage();
  await engine.setPageSize(20);
  expect(cursor.mock.calls.at(-1)?.[0]).toMatchObject({
    cursor: null,
    size: 20,
  });
  await engine.nextPage();
  await engine.refresh();
  expect(selected(engine).page).toBe(1);
  expect(cursor.mock.calls.at(-1)?.[0].cursor).toBeNull();
});

it('rejects unstable or duplicate keys and malformed result metadata', async () => {
  for (const result of [
    { total: 1, list: [{ state: {} }] },
    { total: 2, list: [{ state: { id: 'a' } }, { state: { id: 'a' } }] },
    { total: -1, list: [] },
    { total: 1.5, list: [] },
    { total: 0, list: null },
  ]) {
    const { engine, paged } = setup();
    paged.mockResolvedValue(result);
    await expect(engine.load()).rejects.toThrow();
    expect(engine.getSnapshot().status).toBe('ready');
    expect(selected(engine)).toMatchObject({
      rows: [],
      queryStatus: 'error',
    });
    expect(selected(engine).queryError).toBeTruthy();
  }
  const { engine, cursor } = setup({
    instances: {
      instances: [instance('mine', 'cursor')],
      defaultInstanceId: 'mine',
    },
  });
  cursor.mockResolvedValue({ list: [], nextCursor: 5 });
  await expect(engine.load()).rejects.toThrow();
});
