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

import { expect, it, vi } from 'vitest';
import type { ViewHost } from '../../src/contracts/ViewHost.js';
import { deferred, instance, selected, setup } from './fixtures.js';

it('retains the current records until a successful refresh replaces them', async () => {
  const response = deferred<{
    list: { state: { id: string; amount: number } }[];
    total: number;
  }>();
  const { engine, paged } = setup();
  await engine.load();
  engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .setColumns([
      { id: 'amount', kind: 'field', field: 'state.amount', width: 240 },
    ]);
  const before = selected(engine);
  paged.mockReturnValueOnce(response.promise);
  const refreshing = engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .refresh({ background: true });
  expect(selected(engine)).toMatchObject({
    rows: before.rows,
    queryStatus: 'success',
    refreshing: true,
  });
  await engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .refresh({ background: true });
  response.resolve({ list: [{ state: { id: 'b', amount: 20 } }], total: 1 });
  await refreshing;
  expect(selected(engine)).toMatchObject({
    rows: [{ state: { id: 'b', amount: 20 } }],
    refreshing: false,
    dirty: true,
  });
  expect(paged).toHaveBeenCalledTimes(2);
  engine.dispose();
});

it('preserves a selection made while a background read is in flight', async () => {
  const response = deferred<{
    list: { state: { id: string; amount: number } }[];
    total: number;
  }>();
  const { engine, paged } = setup();
  await engine.load();
  paged.mockReturnValueOnce(response.promise);
  const refreshing = engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .refresh({ background: true });
  await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  engine.record(engine.getSnapshot().selectedInstanceId!).setSelection(['a']);
  response.resolve({ list: [{ state: { id: 'b', amount: 20 } }], total: 1 });
  await refreshing;
  expect(selected(engine)).toMatchObject({
    selectedRowKeys: ['a'],
    rows: [{ state: { id: 'a', amount: 10 } }],
    refreshing: false,
  });
  engine.dispose();
});

it('keeps records on failure and waits for an explicit retry', async () => {
  const { engine, paged } = setup();
  await engine.load();
  paged.mockRejectedValueOnce(new Error('temporarily unavailable'));
  await expect(
    engine
      .record(engine.getSnapshot().selectedInstanceId!)
      .refresh({ background: true }),
  ).rejects.toThrow('temporarily unavailable');
  expect(selected(engine)).toMatchObject({
    rows: [{ state: { id: 'a', amount: 10 } }],
    queryStatus: 'error',
    refreshing: false,
  });
  await engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .refresh({ background: true });
  expect(paged).toHaveBeenCalledTimes(2);
  await engine.record(engine.getSnapshot().selectedInstanceId!).refresh();
  expect(selected(engine).queryStatus).toBe('success');
  engine.dispose();
});

it('does not refresh selected records, pending filters or a later cursor page', async () => {
  const { engine, paged } = setup();
  await engine.load();
  engine.record(engine.getSnapshot().selectedInstanceId!).setSelection(['a']);
  await engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .refresh({ background: true });
  engine.record(engine.getSnapshot().selectedInstanceId!).setSelection([]);
  engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .setFilterValidity(false);
  await engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .refresh({ background: true });
  expect(paged).toHaveBeenCalledTimes(1);
  engine.dispose();
  const cursorView = setup({
    instances: {
      instances: [instance('mine', 'cursor')],
      defaultInstanceId: 'mine',
    },
  });
  cursorView.cursor.mockImplementation(async query => ({
    list: [{ state: { id: 'a' } }],
    nextCursor: query.cursor === null ? 'next' : null,
  }));
  await cursorView.engine.load();
  await cursorView.engine
    .record(cursorView.engine.getSnapshot().selectedInstanceId!)
    .nextPage();
  await cursorView.engine
    .record(cursorView.engine.getSnapshot().selectedInstanceId!)
    .refresh({ background: true });
  expect(cursorView.cursor).toHaveBeenCalledTimes(2);
  expect(selected(cursorView.engine).page).toBe(2);
  cursorView.engine.dispose();
});

it('waits for an in-flight all-record summary before another background refresh', async () => {
  const paged = vi.fn().mockResolvedValue({
    list: [{ state: { id: 'a', amount: 10 } }],
    total: 1,
  });
  const aggregate = vi.fn(() => new Promise<never>(() => {}));
  const value = instance();
  value.config.presentation.table.columns[0] = {
    id: 'amount',
    kind: 'field',
    field: 'state.amount',
    summary: ['SUM'],
  };
  const { engine } = setup({
    instances: { instances: [value], defaultInstanceId: value.id },
    host: {
      resolveSource: () => ({ paged, cursor: vi.fn(), aggregate }),
    } as unknown as ViewHost,
  });
  await engine.load();
  expect(selected(engine).allSummary.status).toBe('loading');
  await engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .refresh({ background: true });
  expect(paged).toHaveBeenCalledTimes(1);
  engine.dispose();
});
