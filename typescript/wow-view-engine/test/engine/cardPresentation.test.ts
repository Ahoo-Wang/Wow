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

import { it, expect, vi } from 'vitest';
import { setup, selected, instance, definition, deferred } from './fixtures.js';

it('preserves both layouts, rows and selection across round trips and saving', async () => {
  const { engine, paged } = setup();
  try {
    await engine.load();
    engine.setSelection(['a']);
    const before = selected(engine);
    engine.setLayout('card');
    expect(paged).toHaveBeenCalledTimes(1);
    expect(selected(engine).rows).toEqual(before.rows);
    expect(selected(engine).selectedRowKeys).toEqual(['a']);
    const card = {
      title: { id: 'title', field: 'state.amount' },
      fields: [],
      actions: { visible: false, renderer: { name: 'custom' } },
    };
    engine.setCardConfig(card);
    engine.setLayout('table');
    expect(selected(engine).instance.config.presentation.table).toEqual(
      before.instance.config.presentation.table,
    );
    engine.setLayout('card');
    expect(selected(engine).instance.config.presentation.card).toEqual(card);
    await engine.save();
    expect(selected(engine).dirty).toBe(false);
    engine.setLayout('table');
    await engine.restore();
    expect(selected(engine).instance.config.presentation.layout).toBe('card');
  } finally {
    engine.dispose();
  }
});

it('cancels hidden summaries and lets background record refresh continue', async () => {
  const pending = deferred<never>();
  const aggregate = vi.fn(() => pending.promise);
  const paged = vi
    .fn()
    .mockResolvedValue({ total: 1, list: [{ state: { id: 'a', amount: 5 } }] });
  const saved = instance();
  saved.config.presentation.table!.columns = [
    { id: 'amount', kind: 'field', field: 'state.amount', summary: ['SUM'] },
  ];
  const { engine } = setup({
    instances: { instances: [saved], defaultInstanceId: saved.id },
    host: { resolveSource: () => ({ paged, aggregate }) },
  });
  try {
    await engine.load();
    expect(selected(engine).allSummary.status).toBe('loading');
    engine.setLayout('card');
    expect(
      (
        aggregate.mock.calls[0] as unknown as [
          unknown,
          unknown,
          AbortController,
        ]
      )[2].signal.aborted,
    ).toBe(true);
    await engine.refresh(undefined, { background: true });
    expect(paged).toHaveBeenCalledTimes(2);
    await engine.refreshSummary();
    expect(aggregate).toHaveBeenCalledTimes(1);
    pending.reject(new Error('late'));
    await Promise.resolve();
    expect(selected(engine).allSummary.status).toBe('idle');
    engine.setLayout('table');
    expect(aggregate).toHaveBeenCalledTimes(1);
    await Promise.resolve();
    expect(aggregate).toHaveBeenCalledTimes(2);
  } finally {
    engine.dispose();
  }
});

it('keeps the current layout when target defaults cannot be created', async () => {
  const saved = instance();
  saved.config.presentation = {
    layout: 'card',
    card: { title: { id: 'title', field: definition.rowKey }, fields: [] },
  };
  const { engine } = setup({
    definition: { ...definition, fields: [] },
    instances: { instances: [saved], defaultInstanceId: saved.id },
  });
  try {
    await engine.load();
    expect(() => engine.setLayout('table')).toThrow();
    expect(selected(engine).instance.config.presentation.layout).toBe('card');
    expect(selected(engine).dirty).toBe(false);
  } finally {
    engine.dispose();
  }
});

it('does not cancel a record query or replace its layout on completion', async () => {
  const { engine, paged } = setup();
  try {
    await engine.load();
    const pending = deferred<{
      total: number;
      list: { state: { id: string; amount: number } }[];
    }>();
    paged.mockReturnValueOnce(pending.promise);
    const refresh = engine.refresh();
    engine.setLayout('card');
    pending.resolve({ total: 1, list: [{ state: { id: 'b', amount: 9 } }] });
    await refresh;
    expect(selected(engine).queryStatus).toBe('success');
    expect(selected(engine).rows[0]).toEqual({ state: { id: 'b', amount: 9 } });
    expect(selected(engine).instance.config.presentation.layout).toBe('card');
    expect(paged).toHaveBeenCalledTimes(2);
  } finally {
    engine.dispose();
  }
});

it('validates renderer options before comparing presentation snapshots', async () => {
  const { engine } = setup();
  try {
    await engine.load();
    engine.setLayout('card');
    const getter = vi.fn(() => 'value');
    const options = {};
    Object.defineProperty(options, 'value', { enumerable: true, get: getter });
    expect(() =>
      engine.setCardConfig({
        title: {
          id: 'title',
          field: 'state.id',
          renderer: { name: 'custom', options },
        },
        fields: [],
      }),
    ).toThrow();
    expect(getter).not.toHaveBeenCalled();
  } finally {
    engine.dispose();
  }
});

it('keeps paged cards mounted during an explicit refresh of the same query', async () => {
  const { engine, paged } = setup();
  try {
    await engine.load();
    engine.setLayout('card');
    const before = selected(engine).rows;
    const pending = deferred<{ list: typeof before; total: number }>();
    paged.mockReturnValueOnce(pending.promise);
    const refresh = engine.refresh();
    expect(selected(engine).queryStatus).toBe('loading');
    expect(selected(engine).rows).toEqual(before);
    pending.resolve({ list: before, total: before.length });
    await refresh;
    expect(selected(engine).queryStatus).toBe('success');
  } finally {
    engine.dispose();
  }
});

it.each(['a', 'b'])(
  'reconciles selections made during refresh against returned key %s',
  async key => {
    const { engine, paged } = setup();
    try {
      await engine.load();
      engine.setLayout('card');
      const pending = deferred<{
        list: { state: { id: string; amount: number } }[];
        total: number;
      }>();
      paged.mockReturnValueOnce(pending.promise);
      const refresh = engine.refresh();
      engine.setSelection(['a']);
      expect(selected(engine).selectedRowKeys).toEqual(['a']);
      pending.resolve({ list: [{ state: { id: key, amount: 10 } }], total: 1 });
      await refresh;
      expect(selected(engine).selectedRowKeys).toEqual(
        key === 'a' ? ['a'] : [],
      );
    } finally {
      engine.dispose();
    }
  },
);

it.each(['table', 'card'] as const)(
  'keeps %s rows through a failed refresh and repeated retries',
  async layout => {
    const { engine, paged } = setup();
    try {
      await engine.load();
      engine.setLayout(layout);
      const before = selected(engine).rows;
      const total = selected(engine).total;
      paged.mockRejectedValueOnce(new Error('refresh failed'));
      await expect(engine.refresh()).rejects.toThrow('refresh failed');
      expect(selected(engine).rows).toEqual(before);
      const retryResult = deferred<unknown>();
      paged.mockReturnValueOnce(retryResult.promise);
      const retry = engine.retryQuery();
      expect(selected(engine).queryStatus).toBe('loading');
      expect(selected(engine).rows).toEqual(before);
      expect(selected(engine).total).toBe(total);
      const failed = expect(retry).rejects.toThrow('retry failed');
      retryResult.reject(new Error('retry failed'));
      await failed;
      expect(selected(engine).rows).toEqual(before);
      await engine.retryQuery();
      expect(selected(engine).queryStatus).toBe('success');
      expect(selected(engine).queryError).toBeNull();
    } finally {
      engine.dispose();
    }
  },
);
