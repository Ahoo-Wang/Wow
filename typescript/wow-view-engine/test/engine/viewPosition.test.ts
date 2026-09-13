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
import { definition, instance, setup } from './fixtures.js';

it('isolates duplicate positions and resolves the definition of each position', async () => {
  const otherSource = {
    paged: vi.fn().mockResolvedValue({
      total: 30,
      list: [{ state: { id: 'b', amount: 12 } }],
    }),
  };
  const { engine, paged, host } = setup();
  host.resolveSource = vi.fn(id =>
    id === 'other-source' ? otherSource : { paged },
  );
  await engine.load();
  const saved = instance();
  const otherDefinition = {
    ...definition,
    id: 'other',
    sourceId: 'other-source',
  };
  const left = engine.openPosition(saved, definition);
  const right = engine.openPosition(saved, definition);
  const other = engine.openPosition(
    { ...saved, definitionId: 'other' },
    otherDefinition,
  );
  try {
    expect(left.identity.id).not.toBe(right.identity.id);
    expect(left.identity.instanceId).toBe(right.identity.instanceId);
    expect(left.kind).toBe('record');
    if (
      left.kind !== 'record' ||
      right.kind !== 'record' ||
      other.kind !== 'record'
    )
      throw new Error('record expected');
    paged.mockResolvedValue({
      total: 30,
      list: [{ state: { id: 'a', amount: 10 } }],
    });
    await Promise.all([
      left.commands.refresh(),
      right.commands.refresh(),
      other.commands.refresh(),
    ]);
    await left.commands.setPage(2);
    expect(left.getSnapshot().page).toBe(2);
    expect(right.getSnapshot().page).toBe(1);
    expect(other.getSnapshot().rows[0]).toEqual({
      state: { id: 'b', amount: 12 },
    });
    expect(otherSource.paged).toHaveBeenCalledOnce();
    const old = left.commands;
    left.dispose();
    await expect(old.refresh()).rejects.toThrow();
    expect(right.getSnapshot().rows).toHaveLength(1);
    expect(engine.getSnapshot().instanceIds).toEqual(['mine', 'shared']);
  } finally {
    left.dispose();
    right.dispose();
    other.dispose();
    engine.dispose();
  }
});

it('cannot persist a display position through the managed instance write path', async () => {
  const { engine, host } = setup();
  await engine.load();
  const panel = engine.openPosition(instance(), definition);
  try {
    await expect(engine.save(panel.identity.id)).rejects.toThrow('位置');
    expect(host.instance!.save).not.toHaveBeenCalled();
  } finally {
    panel.dispose();
    engine.dispose();
  }
});

it('retains active positions without consuming the history result budget', async () => {
  const { engine, paged } = setup({ limits: { maxRetainedResults: 1 } });
  await engine.load();
  const positions = [
    engine.openPosition(instance(), definition),
    engine.openPosition(instance(), definition),
  ];
  try {
    for (const position of positions) {
      if (position.kind !== 'record') throw new Error('record expected');
      await position.commands.refresh();
    }
    // Select another managed instance without fetching a result, leaving mine as history.
    paged.mockRejectedValueOnce(new Error('unavailable'));
    await expect(engine.selectInstance('shared')).rejects.toThrow(
      'unavailable',
    );
    expect(engine.getSnapshot().sessions.mine.result).not.toBeNull();
    for (const position of positions)
      expect(position.getSnapshot().result).not.toBeNull();
  } finally {
    positions.forEach(position => position.dispose());
    engine.dispose();
  }
});

it('validates a supplied definition before registering a runtime position', async () => {
  const { engine } = setup();
  await engine.load();
  const before = engine.getSnapshot();
  try {
    expect(() =>
      engine.openPosition(instance(), { ...definition, sourceId: '' }),
    ).toThrow();
    expect(engine.getSnapshot()).toBe(before);
  } finally {
    engine.dispose();
  }
});

it('runs an analysis position independently of the selected record definition', async () => {
  const { FilterOperator } = await import('@ahoo-wang/fetcher-wow');
  const { engine, host } = setup();
  const aggregate = vi.fn().mockResolvedValue([{ n: 3 }]);
  await engine.load();
  host.resolveSource = vi.fn(() => ({ aggregate }));
  const position = engine.openPosition(
    {
      id: 'count',
      definitionId: 'counts',
      title: 'Count',
      kind: 'analysis',
      scope: { type: 'personal' },
      revision: 'r1',
      config: {
        filters: {
          mode: 'simple',
          root: {
            id: 'all',
            component: { name: 'builtin' },
            operator: FilterOperator.MATCH_ALL,
            props: {},
          },
        },
        dimensions: [],
        metrics: [
          {
            id: 'n',
            alias: 'n',
            title: 'Count',
            component: { name: 'count' },
            props: {},
          },
        ],
        sort: [],
        limit: 100,
        presentation: { layout: 'table', columns: [] },
      },
    },
    {
      id: 'counts',
      title: 'Counts',
      sourceId: 'count-source',
      fields: [],
      analysis: { count: true, fields: [] },
    },
  );
  try {
    if (position.kind !== 'analysis') throw new Error('analysis expected');
    await position.commands.run();
    expect(host.resolveSource).toHaveBeenCalledWith('count-source');
    expect(position.getSnapshot().result?.rows).toEqual([{ n: 3 }]);
    expect(engine.getSnapshot().selectedInstanceId).toBe('mine');
    position.commands.edit(config => ({ ...config, limit: 50 }));
    expect(position.getSnapshot().dirty).toBe(false);
    position.commands.restore();
    expect(position.getSnapshot().instance.config.limit).toBe(100);
    engine
      .analysis(position.identity.id)
      .edit(config => ({ ...config, limit: 25 }));
    await engine.restore(position.identity.id);
    expect(position.getSnapshot().instance.config.limit).toBe(100);
    expect(aggregate).toHaveBeenCalledOnce();
    expect(host.instance!.save).not.toHaveBeenCalled();
    position.dispose();
    await expect(position.commands.run()).rejects.toThrow('失效');
    expect(
      Reflect.get(Reflect.get(engine, 'queries'), 'intents').has(
        position.identity.id,
      ),
    ).toBe(false);
  } finally {
    position.dispose();
    engine.dispose();
  }
});

it('keeps display positions out of managed navigation and reload operations', async () => {
  const { engine, host } = setup();
  await engine.load();
  const load = vi.fn().mockResolvedValue(instance());
  host.instance!.load = load;
  const position = engine.openPosition(instance(), definition);
  try {
    expect(() => engine.setTitle('wrong target', position.identity.id)).toThrow(
      '位置',
    );
    expect(engine.getPermissions(position.identity.id).save).toBe(false);
    expect(engine.canReloadInstance(position.identity.id)).toBe(false);
    await expect(engine.selectInstance(position.identity.id)).rejects.toThrow(
      '位置',
    );
    await expect(engine.reloadInstance(position.identity.id)).rejects.toThrow(
      '位置',
    );
    expect(load).not.toHaveBeenCalled();
    expect(engine.getSnapshot().selectedInstanceId).toBe('mine');
    expect(engine.getSnapshot().sessions.mine.instance.title).toBe('mine');
  } finally {
    position.dispose();
    engine.dispose();
  }
});

it('invalidates a position before cancellation can reenter its commands', async () => {
  const { engine, paged } = setup({ limits: { maxConcurrentQueries: 1 } });
  await engine.load();
  const position = engine.openPosition(instance(), definition);
  if (position.kind !== 'record') throw new Error('record expected');
  let orphan: AbortController | undefined;
  paged.mockImplementationOnce(() => new Promise(() => {}));
  const reading = position.commands.refresh();
  await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  paged.mockImplementationOnce(
    (_query, _headers, controller: AbortController) => {
      orphan = controller;
      return new Promise(() => {});
    },
  );
  let reentered = false;
  const unsubscribe = engine.subscribe(() => {
    if (
      !reentered &&
      engine.getSnapshot().sessions[position.identity.id]?.queryStatus ===
        'idle'
    ) {
      reentered = true;
      void position.commands.refresh().catch(() => {});
    }
  });
  try {
    position.dispose();
    await reading;
    expect(orphan).toBeUndefined();
    paged.mockReset().mockResolvedValue({ total: 0, list: [] });
    await engine.record('mine').refresh();
    expect(engine.getSnapshot().sessions.mine.queryStatus).toBe('success');
  } finally {
    unsubscribe();
    orphan?.abort();
    position.dispose();
    engine.dispose();
  }
});

it('restores a record position locally without writing its saved instance', async () => {
  const { engine, host, paged } = setup();
  await engine.load();
  const position = engine.openPosition(instance(), definition);
  if (position.kind !== 'record') throw new Error('record expected');
  try {
    await position.commands.setPageSize(20);
    expect(position.getSnapshot().dirty).toBe(false);
    await position.commands.restore();
    expect(position.getSnapshot().instance).toEqual(
      position.getSnapshot().baseline,
    );
    expect(position.getSnapshot().dirty).toBe(false);
    expect(
      engine.getSnapshot().sessions.mine.instance.config.pagination.size,
    ).toBe(10);
    expect(paged).toHaveBeenCalledTimes(3);
    expect(host.instance!.save).not.toHaveBeenCalled();
  } finally {
    position.dispose();
    engine.dispose();
  }
});

it('releases metadata after repeatedly closing cursor positions', async () => {
  const { engine } = setup();
  await engine.load();
  const store = Reflect.get(engine, 'store');
  const queries = Reflect.get(engine, 'queries');
  const sizes = () => [
    Reflect.get(store, 'generations').size,
    Reflect.get(queries, 'intents').size,
    Reflect.get(queries, 'consumedCursors').size,
    Reflect.get(Reflect.get(engine, 'viewQueries'), 'opened').size,
    Reflect.get(Reflect.get(engine, 'summaries'), 'keys').size,
  ];
  const before = sizes();
  try {
    for (let index = 0; index < 10; index++) {
      const position = engine.openPosition(
        instance('mine', 'cursor'),
        definition,
      );
      if (position.kind !== 'record') throw new Error('record expected');
      await position.commands.refresh();
      position.dispose();
      await expect(position.commands.refresh()).rejects.toThrow('失效');
    }
    expect(sizes()).toEqual(before);
  } finally {
    engine.dispose();
  }
});

it.each([200000000, 2147483647])(
  'accepts the public query timeout %i',
  async queryTimeoutMs => {
    const { engine } = setup({ limits: { queryTimeoutMs } });
    try {
      await engine.load();
    } finally {
      engine.dispose();
    }
  },
);
