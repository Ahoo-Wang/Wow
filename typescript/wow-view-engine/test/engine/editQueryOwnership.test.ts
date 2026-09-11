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
import { asc, desc } from '@ahoo-wang/fetcher-wow';
import type { ViewEngine } from '../../src/engine/ViewEngine.js';
import type { RecordQuerySource } from '../../src/contracts/viewModel.js';
import { deferred, instance, selected, setup } from './fixtures.js';

const commands = {
  sort: (engine: ViewEngine, id: string) =>
    engine
      .record(id ?? engine.getSnapshot().selectedInstanceId!)
      .setSort([desc('state.amount')]),
  page: (engine: ViewEngine, id: string) =>
    engine.record(id ?? engine.getSnapshot().selectedInstanceId!).setPage(2),
  pageSize: (engine: ViewEngine, id: string) =>
    engine
      .record(id ?? engine.getSnapshot().selectedInstanceId!)
      .setPageSize(5),
  apply: (engine: ViewEngine, id: string) =>
    engine.record(id ?? engine.getSnapshot().selectedInstanceId!).applyFilter(),
  restore: (engine: ViewEngine, id: string) => engine.restore(id),
  next: (engine: ViewEngine, id: string) =>
    engine.record(id ?? engine.getSnapshot().selectedInstanceId!).nextPage(),
};
type Command = keyof typeof commands;
async function prepared(command: Command) {
  const mode = command === 'next' ? 'cursor' : 'paged';
  const context = setup({
    instances: {
      instances: [instance('mine', mode), instance('shared', mode)],
      defaultInstanceId: 'mine',
    },
  });
  context.paged.mockResolvedValue({
    total: 100,
    list: [{ state: { id: 'result', amount: 1 } }],
  });
  await context.engine.load();
  return { ...context, mode };
}

it.each(Object.keys(commands) as Command[])(
  '%s preserves newer query results and promise ownership',
  async command => {
    const { engine, host, paged, cursor, mode } = await prepared(command);
    const gate = deferred<RecordQuerySource>();
    vi.mocked(host.resolveSource).mockClear().mockReturnValue(gate.promise);
    paged.mockClear();
    cursor.mockClear();
    let fired = false,
      finished = false;
    let newer: Promise<void> | undefined;
    const unsubscribe = engine.subscribe(() => {
      if (fired) return;
      fired = true;
      newer = (
        mode === 'cursor'
          ? engine.record('mine').refresh()
          : engine.record('mine').setPage(3)
      ).then(() => {
        finished = true;
      });
    });
    const old = commands[command](engine, 'mine');
    try {
      expect(host.resolveSource).toHaveBeenCalledTimes(1);
      expect(finished).toBe(false);
      gate.resolve({ paged, cursor });
      await Promise.all([old, newer]);
      expect(finished).toBe(true);
      expect(selected(engine).queryStatus).toBe('success');
      expect(mode === 'cursor' ? cursor : paged).toHaveBeenCalledTimes(1);
      expect(selected(engine).page).toBe(mode === 'cursor' ? 1 : 3);
    } finally {
      unsubscribe();
      engine.dispose();
      gate.resolve({ paged, cursor });
      await Promise.allSettled([old, newer]);
    }
  },
);

it.each(Object.keys(commands) as Command[])(
  '%s leaves a newer query failure with the newer caller',
  async command => {
    const { engine, host, paged, cursor } = await prepared(command);
    const gate = deferred<RecordQuerySource>();
    vi.mocked(host.resolveSource).mockClear().mockReturnValue(gate.promise);
    paged.mockRejectedValue(new Error('newer query failed'));
    cursor.mockRejectedValue(new Error('newer query failed'));
    let fired = false;
    let outcome: Promise<string> | undefined;
    const unsubscribe = engine.subscribe(() => {
      if (fired) return;
      fired = true;
      outcome = engine
        .record('mine')
        .refresh()
        .then(
          () => 'success',
          error => error.message,
        );
    });
    const old = commands[command](engine, 'mine').then(
      () => 'success',
      error => error.message,
    );
    gate.resolve({ paged, cursor });
    try {
      expect(await old).toBe('success');
      expect(await outcome).toBe('newer query failed');
      expect(selected(engine).queryError).toBe('newer query failed');
    } finally {
      unsubscribe();
      engine.dispose();
    }
  },
);

it.each(
  (Object.keys(commands) as Command[]).flatMap(command =>
    ['navigate', 'dispose'].map(action => ({ command, action })),
  ),
)(
  '$command respects $action during its state notification',
  async ({ command, action }) => {
    const { engine, host } = await prepared(command);
    vi.mocked(host.resolveSource).mockClear();
    let fired = false;
    let next: Promise<void> | undefined;
    const unsubscribe = engine.subscribe(() => {
      if (fired) return;
      fired = true;
      if (action === 'dispose') engine.dispose();
      else next = engine.selectInstance('shared');
    });
    try {
      await commands[command](engine, 'mine');
      await next;
      expect(host.resolveSource).toHaveBeenCalledTimes(
        action === 'dispose' ? 0 : 1,
      );
      if (action === 'navigate')
        expect(engine.getSnapshot().selectedInstanceId).toBe('shared');
    } finally {
      unsubscribe();
      engine.dispose();
    }
  },
);

it.each(Object.keys(commands) as Command[])(
  '%s still queries an explicitly addressed non-selected instance',
  async command => {
    const { engine, host, cursor } = await prepared(command);
    await engine.selectInstance('shared');
    await engine.selectInstance('mine');
    cursor.mockResolvedValue({
      list: [{ state: { id: 'next' } }],
      nextCursor: 'after-next',
    });
    vi.mocked(host.resolveSource).mockClear();
    try {
      await commands[command](engine, 'shared');
      expect(host.resolveSource).toHaveBeenCalledTimes(1);
      expect(engine.getSnapshot().selectedInstanceId).toBe('mine');
      expect(selected(engine, 'shared').queryStatus).toBe('success');
    } finally {
      engine.dispose();
    }
  },
);

it.each(Object.keys(commands) as Command[])(
  '%s propagates its own query failure when not superseded',
  async command => {
    const { engine, paged, cursor } = await prepared(command);
    paged.mockRejectedValue(new Error('query failed'));
    cursor.mockRejectedValue(new Error('query failed'));
    try {
      await expect(commands[command](engine, 'mine')).rejects.toThrow(
        'query failed',
      );
      expect(selected(engine).queryStatus).toBe('error');
    } finally {
      engine.dispose();
    }
  },
);

it('does not let a rejected invalid edit suppress the valid outer query', async () => {
  const { engine, paged } = await prepared('sort');
  paged.mockClear();
  let fired = false;
  let rejected: Promise<unknown> | undefined;
  const unsubscribe = engine.subscribe(() => {
    if (fired) return;
    fired = true;
    rejected = engine
      .record(engine.getSnapshot().selectedInstanceId!)
      .setSort([asc('missing')])
      .catch(error => error);
  });
  try {
    await engine
      .record(engine.getSnapshot().selectedInstanceId!)
      .setSort([desc('state.amount')]);
    expect(await rejected).toBeInstanceOf(Error);
    expect(paged).toHaveBeenCalledTimes(1);
    expect(selected(engine).queryStatus).toBe('success');
  } finally {
    unsubscribe();
    engine.dispose();
  }
});

it.each(['apply', 'restore', 'refresh'] as const)(
  '%s respects a newer query started while invalidating a summary',
  async command => {
    const saved = instance();
    saved.config.presentation.table.columns[0] = {
      id: 'amount',
      kind: 'field',
      field: 'state.amount',
      summary: ['SUM'],
    };
    const { engine, host, paged } = setup({
      instances: { instances: [saved], defaultInstanceId: 'mine' },
    });
    const aggregate = vi.fn().mockResolvedValue([{ summary0: 10 }]);
    vi.mocked(host.resolveSource).mockReturnValue({ paged, aggregate });
    paged.mockResolvedValue({
      total: 100,
      list: [{ state: { id: 'result', amount: 1 } }],
    });
    await engine.load();
    await vi.waitFor(() =>
      expect(selected(engine).allSummary.status).toBe('success'),
    );
    const rows = deferred<{
      total: number;
      list: { state: { id: string; amount: number } }[];
    }>();
    paged.mockClear().mockReturnValue(rows.promise);
    let fired = false,
      finished = false;
    let newer: Promise<void> | undefined;
    const unsubscribe = engine.subscribe(() => {
      if (fired || selected(engine).allSummary.status !== 'idle') return;
      fired = true;
      newer = engine
        .record(engine.getSnapshot().selectedInstanceId!)
        .setPage(3)
        .then(() => {
          finished = true;
        });
    });
    const old =
      command === 'refresh'
        ? engine.record(engine.getSnapshot().selectedInstanceId!).refresh()
        : commands[command](engine, 'mine');
    try {
      await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(1));
      expect(finished).toBe(false);
      rows.resolve({
        total: 100,
        list: [{ state: { id: 'newest', amount: 3 } }],
      });
      await Promise.all([old, newer]);
      expect(selected(engine).page).toBe(3);
      expect(selected(engine).rows[0].state).toEqual({
        id: 'newest',
        amount: 3,
      });
    } finally {
      unsubscribe();
      engine.dispose();
      rows.resolve({ total: 0, list: [] });
      await Promise.allSettled([old, newer]);
    }
  },
);

it('does not access a disposed session after a column edit notification', async () => {
  const { engine } = await prepared('sort');
  const unsubscribe = engine.subscribe(() => engine.dispose());
  try {
    expect(() =>
      engine
        .record(engine.getSnapshot().selectedInstanceId!)
        .setColumns([
          { id: 'amount', kind: 'field', field: 'state.amount', width: 200 },
        ]),
    ).not.toThrow();
  } finally {
    unsubscribe();
    engine.dispose();
  }
});

it('does not start a summary query after its invalidation notification disposes the engine', async () => {
  const saved = instance();
  saved.config.presentation.table.columns[0] = {
    id: 'amount',
    kind: 'field',
    field: 'state.amount',
    summary: ['SUM'],
  };
  const { engine, host, paged } = setup({
    instances: { instances: [saved], defaultInstanceId: 'mine' },
  });
  const aggregate = vi.fn().mockResolvedValue([{ summary0: 10 }]);
  vi.mocked(host.resolveSource).mockReturnValue({ paged, aggregate });
  await engine.load();
  await vi.waitFor(() =>
    expect(selected(engine).allSummary.status).toBe('success'),
  );
  aggregate.mockClear();
  const unsubscribe = engine.subscribe(() => {
    if (selected(engine).allSummary.status === 'idle') engine.dispose();
  });
  try {
    await expect(
      engine.record(engine.getSnapshot().selectedInstanceId!).refreshSummary(),
    ).resolves.toBeUndefined();
    expect(aggregate).not.toHaveBeenCalled();
  } finally {
    unsubscribe();
    engine.dispose();
  }
});

it('keeps the newer aggregate request started by a page-summary notification', async () => {
  const saved = instance();
  saved.config.presentation.table.columns[0] = {
    id: 'amount',
    kind: 'field',
    field: 'state.amount',
    summary: ['SUM'],
  };
  const { engine, host, paged } = setup({
    instances: { instances: [saved], defaultInstanceId: 'mine' },
  });
  const rows = deferred<{
    total: number;
    list: { state: { id: string; amount: number } }[];
  }>();
  const values = deferred<{ summary0: number }[]>();
  paged.mockReturnValue(rows.promise);
  const aggregate = vi
    .fn()
    .mockResolvedValueOnce([{ summary0: 10 }])
    .mockImplementation(() => values.promise);
  vi.mocked(host.resolveSource).mockReturnValue({ paged, aggregate });
  let started = false,
    nested = false,
    finished = false;
  let older: Promise<void> | undefined, newer: Promise<void> | undefined;
  const unsubscribe = engine.subscribe(() => {
    const current = engine.getSnapshot().sessions.mine;
    if (!current) return;
    if (
      !started &&
      current.queryStatus === 'success' &&
      current.pageSummary.status === 'loading'
    ) {
      started = true;
      older = engine
        .record(engine.getSnapshot().selectedInstanceId!)
        .refreshSummary();
    } else if (started && !nested && current.pageSummary.status === 'success') {
      nested = true;
      newer = engine
        .record(engine.getSnapshot().selectedInstanceId!)
        .refreshSummary()
        .then(() => {
          finished = true;
        });
    }
  });
  const load = engine.load();
  try {
    await vi.waitFor(() =>
      expect(selected(engine).allSummary.status).toBe('success'),
    );
    rows.resolve({ total: 1, list: [{ state: { id: 'row', amount: 2 } }] });
    await load;
    expect(aggregate).toHaveBeenCalledTimes(2);
    expect(finished).toBe(false);
    values.resolve([{ summary0: 20 }]);
    await Promise.all([older, newer]);
    expect(selected(engine).allSummary.status).toBe('success');
  } finally {
    unsubscribe();
    engine.dispose();
    rows.resolve({ total: 0, list: [] });
    values.resolve([{ summary0: 0 }]);
    await Promise.allSettled([load, older, newer]);
  }
});
