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

import { expect, it } from 'vitest';
import { filter } from '@ahoo-wang/fetcher-wow';
import { definition, instance, setup, deferred } from '../engine/fixtures.js';

it('queues actual position reads, applies scope before any request and cancels waiting work', async () => {
  const { engine, paged } = setup({ limits: { maxConcurrentQueries: 1 } });
  await engine.load();
  const positions = [0, 1, 2].map(() =>
    engine.openPosition(instance(), definition, {
      queryPolicy: 'queue',
      source: { paged },
    }),
  );
  const held = deferred<{ total: number; list: never[] }>();
  paged.mockImplementationOnce(() => held.promise);
  const scope = filter.eq('state.amount', 10);
  for (const position of positions)
    engine.setPositionScope(position.identity.id, scope);
  try {
    const reads = positions.map(position => position.commands.refresh());
    expect(positions[1].getSnapshot().queryStatus).toBe('waiting');
    expect(paged).toHaveBeenCalledTimes(2);
    positions[2].dispose();
    held.resolve({ total: 0, list: [] });
    await Promise.all(reads);
    expect(paged).toHaveBeenCalledTimes(3);
    expect(paged.mock.calls[1][0].filter).toEqual(
      filter.and([filter.matchAll(), scope]),
    );
    expect(positions[1].getSnapshot().queryStatus).toBe('success');
    const updated = filter.eq('state.amount', 20);
    engine.setPositionScope(positions[1].identity.id, updated);
    await positions[1].commands.refresh();
    expect(paged.mock.lastCall![0].filter).toEqual(
      filter.and([filter.matchAll(), updated]),
    );
  } finally {
    positions.forEach(position => position.dispose());
    engine.dispose();
  }
});

it('rejects dashboard positions without definition capability before allocating a session', async () => {
  const { engine } = setup();
  await engine.load();
  const before = engine.getSnapshot();
  const dashboard = {
    ...instance(),
    kind: 'dashboard' as const,
    config: { schemaVersion: 1 as const, panels: [], filters: [] },
  };
  expect(() => engine.openPosition(dashboard, definition)).toThrow();
  expect(engine.getSnapshot()).toBe(before);
  engine.dispose();
});
