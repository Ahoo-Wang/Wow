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
import { definition, instance, setup, deferred } from './fixtures.js';
import type { AnalysisViewInstance } from '../../src/contracts/viewModel.js';
import type { DataViewPosition } from '../../src/engine/ViewEngine.js';

const analysisDefinition = {
  ...definition,
  analysis: { count: true, fields: [] },
};
const analysisInstance: AnalysisViewInstance = {
  ...instance(),
  kind: 'analysis',
  config: {
    filters: instance().config.filters,
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
};
function run(position: DataViewPosition) {
  return position.kind === 'record'
    ? position.commands.refresh()
    : position.commands.run();
}
function retry(position: DataViewPosition) {
  return position.kind === 'record'
    ? position.commands.retryQuery()
    : position.commands.run();
}
function admission(kind: 'record' | 'analysis') {
  const configured = setup({
    limits: { maxConcurrentQueries: 1 },
    instances: { instances: [], defaultInstanceId: null },
  });
  const read = vi
    .fn()
    .mockResolvedValue(kind === 'record' ? { total: 0, list: [] } : [{ n: 1 }]);
  const open = (queryPolicy: 'reject' | 'queue' = 'reject') =>
    configured.engine.openPosition(
      kind === 'record' ? instance() : analysisInstance,
      kind === 'record' ? definition : analysisDefinition,
      { queryPolicy, source: { paged: read, aggregate: read } },
    );
  return { ...configured, open, read };
}

it.each(['record', 'analysis'] as const)(
  'publishes initial %s BUSY admission failure and permits retry',
  async kind => {
    const { engine, open, read } = admission(kind);
    const held = deferred<unknown>();
    read.mockImplementationOnce(() => held.promise);
    await engine.load();
    const active = open(),
      rejected = open();
    try {
      const running = run(active);
      await vi.waitFor(() => expect(read).toHaveBeenCalledOnce());
      await expect(run(rejected)).rejects.toMatchObject({ code: 'BUSY' });
      expect(rejected.getSnapshot()).toMatchObject({
        queryStatus: 'error',
        queryError: expect.stringContaining('并发'),
        queryAttempt: null,
        result: null,
      });
      held.resolve(kind === 'record' ? { total: 0, list: [] } : [{ n: 1 }]);
      await running;
      await retry(rejected);
      expect(rejected.getSnapshot().queryStatus).toBe('success');
    } finally {
      active.dispose();
      rejected.dispose();
      engine.dispose();
    }
  },
);

it.each(['record', 'analysis'] as const)(
  'publishes %s queue overflow without replacing accepted requests',
  async kind => {
    const { engine, open, read } = admission(kind);
    const held = deferred<unknown>();
    read.mockImplementationOnce(() => held.promise);
    await engine.load();
    const positions = Array.from({ length: 50 }, () => open('queue'));
    try {
      const accepted = positions.slice(0, 49).map(run);
      await expect(run(positions[49])).rejects.toMatchObject({
        code: 'RESOURCE_LIMIT',
      });
      expect(positions[49].getSnapshot()).toMatchObject({
        queryStatus: 'error',
        queryError: expect.stringContaining('队列'),
        queryAttempt: null,
        result: null,
      });
      expect(positions[1].getSnapshot().queryStatus).toBe('waiting');
      held.resolve(kind === 'record' ? { total: 0, list: [] } : [{ n: 1 }]);
      await Promise.all(accepted);
      await retry(positions[49]);
      expect(positions[49].getSnapshot().queryStatus).toBe('success');
    } finally {
      positions.forEach(position => position.dispose());
      engine.dispose();
    }
  },
);

it.each(['record', 'analysis'] as const)(
  'retains %s in-flight ownership and snapshots when a replacement is rejected',
  async kind => {
    const { engine, open, read } = admission(kind);
    await engine.load();
    const active = open(),
      waiting = open('queue');
    try {
      await run(active);
      const result = active.getSnapshot().result;
      const held = deferred<unknown>();
      read.mockImplementationOnce(() => held.promise);
      if (active.kind === 'analysis')
        active.commands.edit(config => ({ ...config, limit: 50 }));
      const running = run(active);
      const queued = run(waiting);
      if (active.kind === 'analysis')
        active.commands.edit(config => ({ ...config, limit: 25 }));
      const attempt = active.getSnapshot().queryAttempt;
      await expect(run(active)).rejects.toMatchObject({ code: 'BUSY' });
      expect(active.getSnapshot().queryStatus).toBe('loading');
      expect(active.getSnapshot().queryError).toBeNull();
      expect(active.getSnapshot().result).toBe(result);
      expect(active.getSnapshot().queryAttempt).toBe(attempt);
      held.resolve(kind === 'record' ? { total: 0, list: [] } : [{ n: 2 }]);
      await Promise.all([running, queued]);
      expect(active.getSnapshot().queryStatus).toBe('success');
    } finally {
      active.dispose();
      waiting.dispose();
      engine.dispose();
    }
  },
);

it.each(['filter', 'compile'] as const)(
  'publishes analysis %s rejection without losing retained results',
  async failure => {
    const { engine, open } = admission('analysis');
    await engine.load();
    const position = open();
    if (position.kind !== 'analysis') throw new Error('analysis expected');
    try {
      await position.commands.run();
      const result = position.getSnapshot().result;
      const attempt = position.getSnapshot().queryAttempt;
      if (failure === 'filter') position.commands.setFilterValidity(false);
      else position.commands.edit(config => ({ ...config, metrics: [] }));
      await expect(position.commands.run()).rejects.toThrow();
      expect(position.getSnapshot().queryStatus).toBe('error');
      expect(position.getSnapshot().queryError).toBeTruthy();
      expect(position.getSnapshot().result).toBe(result);
      expect(position.getSnapshot().queryAttempt).toBe(attempt);
    } finally {
      position.dispose();
      engine.dispose();
    }
  },
);

it.each(['filter', 'compile'] as const)(
  'keeps an accepted analysis running when a newer %s input is rejected',
  async failure => {
    const { engine, open, read } = admission('analysis');
    await engine.load();
    const position = open();
    if (position.kind !== 'analysis') throw new Error('analysis expected');
    try {
      await position.commands.run();
      const result = position.getSnapshot().result;
      const held = deferred<unknown>();
      read.mockImplementationOnce(() => held.promise);
      const running = position.commands.run();
      const attempt = position.getSnapshot().queryAttempt;
      if (failure === 'filter') position.commands.setFilterValidity(false);
      else position.commands.edit(config => ({ ...config, metrics: [] }));
      await expect(position.commands.run()).rejects.toThrow();
      expect(position.getSnapshot().queryStatus).toBe('loading');
      expect(position.getSnapshot().queryError).toBeNull();
      expect(position.getSnapshot().result).toBe(result);
      expect(position.getSnapshot().queryAttempt).toBe(attempt);
      held.resolve([{ n: 2 }]);
      await running;
      expect(position.getSnapshot().result?.rows).toEqual([{ n: 2 }]);
    } finally {
      position.dispose();
      engine.dispose();
    }
  },
);
