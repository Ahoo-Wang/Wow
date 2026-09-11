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
  bindRecordPagination,
  getRecordPaginationPolicy,
} from '../../src/record/page/recordPaginationPolicy.js';
import { afterEach, expect, it, vi } from 'vitest';
import type { ViewEngine } from '../../src/engine/ViewEngine.js';
import { instance, selected, setup } from './fixtures.js';

const engines: ViewEngine[] = [];
afterEach(() => engines.splice(0).forEach(engine => engine.dispose()));
async function laterPage() {
  const paged = vi.fn().mockResolvedValue({
    total: 100,
    list: [{ state: { id: 'old', amount: 1 } }],
  });
  const aggregate = vi.fn().mockResolvedValue([{ summary0: 100 }]);
  const mine = instance();
  mine.config.presentation.table.columns[0].summary = ['SUM'];
  const { engine } = setup({
    instances: { instances: [mine], defaultInstanceId: 'mine' },
    host: { resolveSource: () => ({ paged, aggregate }) },
  });
  engines.push(engine);
  await engine.load();
  await vi.waitFor(() =>
    expect(selected(engine).allSummary.status).toBe('success'),
  );
  await engine.record(engine.getSnapshot().selectedInstanceId!).setPage(5);
  paged.mockClear();
  aggregate.mockResolvedValue([{ summary0: 50 }]);
  return { engine, paged, aggregate };
}

it.each([false, true])(
  'returns to page one after total shrinks, background=%s',
  async background => {
    const { engine, paged, aggregate } = await laterPage();
    const first = [{ state: { id: 'first', amount: 50 } }];
    paged
      .mockResolvedValueOnce({ total: 22, list: [] })
      .mockResolvedValueOnce({ total: 22, list: first });
    await engine
      .record(engine.getSnapshot().selectedInstanceId!)
      .refresh({ background });
    expect(paged.mock.calls.map(([query]) => query.pagination.index)).toEqual([
      5, 1,
    ]);
    expect(selected(engine)).toMatchObject({
      page: 1,
      total: 22,
      rows: first,
      refreshing: false,
      queryStatus: 'success',
    });
    await vi.waitFor(() =>
      expect(selected(engine).allSummary.values).toEqual({
        amount: { SUM: 50 },
      }),
    );
    expect(aggregate).toHaveBeenCalledTimes(2);
  },
);

it('bounds correction when the result becomes completely empty', async () => {
  const { engine, paged } = await laterPage();
  paged.mockResolvedValue({ total: 0, list: [] });
  await engine.record(engine.getSnapshot().selectedInstanceId!).refresh();
  expect(paged).toHaveBeenCalledTimes(2);
  expect(selected(engine)).toMatchObject({
    page: 1,
    total: 0,
    rows: [],
    queryStatus: 'success',
  });
});

it('exposes a failed correction and retries the corrected page', async () => {
  const { engine, paged } = await laterPage();
  paged
    .mockResolvedValueOnce({ total: 22, list: [] })
    .mockRejectedValueOnce(new Error('first page unavailable'));
  await expect(
    engine.record(engine.getSnapshot().selectedInstanceId!).refresh(),
  ).rejects.toThrow('first page unavailable');
  expect(selected(engine)).toMatchObject({
    page: 1,
    queryStatus: 'error',
    queryError: 'first page unavailable',
    rows: [],
    total: null,
  });
  paged.mockResolvedValueOnce({
    total: 22,
    list: [{ state: { id: 'first' } }],
  });
  await engine.record(engine.getSnapshot().selectedInstanceId!).retryQuery();
  expect(paged.mock.calls.map(([query]) => query.pagination.index)).toEqual([
    5, 1, 1,
  ]);
});

it('lets newer navigation supersede a page correction', async () => {
  const { engine, paged } = await laterPage();
  paged
    .mockResolvedValueOnce({ total: 22, list: [] })
    .mockResolvedValueOnce({ total: 22, list: [{ state: { id: 'newer' } }] });
  let newer: Promise<void> | undefined;
  const unsubscribe = engine.subscribe(() => {
    if (
      selected(engine).page === 1 &&
      selected(engine).queryStatus === 'loading' &&
      !newer
    ) {
      unsubscribe();
      newer = engine
        .record(engine.getSnapshot().selectedInstanceId!)
        .setPage(2);
    }
  });
  await engine.record(engine.getSnapshot().selectedInstanceId!).refresh();
  await newer;
  expect(paged.mock.calls.map(([query]) => query.pagination.index)).toEqual([
    5, 2,
  ]);
  expect(selected(engine)).toMatchObject({
    page: 2,
    rows: [{ state: { id: 'newer' } }],
  });
});

it.each(['paged', 'cursor'] as const)(
  'navigates the displayed %s result after an unrun mode edit',
  async mode => {
    const { engine, paged, cursor } = setup({
      instances: {
        instances: [instance('mine', mode)],
        defaultInstanceId: 'mine',
      },
    });
    paged.mockResolvedValue({
      total: 40,
      list: [{ state: { id: 'a', amount: 10 } }],
    });
    cursor.mockImplementation(async query => ({
      list: [{ state: { id: 'a' } }],
      nextCursor: query.cursor === null ? 'next' : null,
    }));
    try {
      await engine.load();
      const pagination = bindRecordPagination(engine, 'mine');
      engine.record('mine').edit(config => ({
        ...config,
        pagination: { mode: mode === 'paged' ? 'cursor' : 'paged', size: 20 },
      }));
      expect(getRecordPaginationPolicy(selected(engine) as never).mode).toBe(
        mode,
      );
      await pagination.nextPage();
      expect(getRecordPaginationPolicy(selected(engine) as never).page).toBe(2);
      if (mode === 'paged') {
        await pagination.previousPage();
        expect(getRecordPaginationPolicy(selected(engine) as never).page).toBe(
          1,
        );
        await pagination.setPage(3);
        expect(paged.mock.calls.at(-1)![0].pagination).toMatchObject({
          index: 3,
          size: 10,
        });
        expect(cursor).not.toHaveBeenCalled();
      } else {
        expect(cursor.mock.calls.at(-1)![0]).toMatchObject({
          cursor: 'next',
          size: 10,
        });
        expect(paged).not.toHaveBeenCalled();
      }
      expect(selected(engine).instance.config.pagination.size).toBe(20);
    } finally {
      engine.dispose();
    }
  },
);
