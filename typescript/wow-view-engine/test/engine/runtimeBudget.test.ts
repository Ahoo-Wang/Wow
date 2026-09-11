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

import type { RuntimeDiagnostic } from '../../src/engine/runtimeLimits.js';
import { afterEach, expect, it, vi } from 'vitest';
import { deferred, instance, selected, setup } from './fixtures.js';
afterEach(() => vi.useRealTimers());
it('treats a dispatched save timeout as unknown and ignores the late response', async () => {
  vi.useFakeTimers();
  const pending = deferred<ReturnType<typeof instance>>();
  const { engine } = setup({
    limits: { writeTimeoutMs: 10 },
    host: { instance: { save: () => pending.promise } } as never,
  });
  await engine.load();
  engine.setTitle('Local');
  const operation = engine.save();
  const failure = expect(operation).rejects.toMatchObject({ code: 'TIMEOUT' });
  await vi.advanceTimersByTimeAsync(11);
  await failure;
  expect(selected(engine)).toMatchObject({
    requiresReload: true,
    writeStatus: 'idle',
    baseline: { revision: 'r1' },
    instance: { title: 'Local' },
  });
  await expect(engine.save()).rejects.toThrow('核对');
  pending.resolve({ ...instance(), title: 'Local', revision: 'r2' });
  await Promise.resolve();
  expect(selected(engine).baseline.revision).toBe('r1');
  engine.dispose();
});
it('bounds record reads including source resolution and accepts an explicit retry', async () => {
  vi.useFakeTimers();
  const { engine, paged } = setup({ limits: { queryTimeoutMs: 10 } });
  await engine.load();
  paged.mockImplementationOnce(() => new Promise(() => {}));
  const reading = engine
    .record(engine.getSnapshot().selectedInstanceId!)
    .refresh();
  const failure = expect(reading).rejects.toMatchObject({ code: 'TIMEOUT' });
  await vi.advanceTimersByTimeAsync(11);
  await failure;
  expect(selected(engine)).toMatchObject({
    queryStatus: 'error',
    requiresReload: false,
  });
  await engine.record(engine.getSnapshot().selectedInstanceId!).retryQuery();
  expect(selected(engine).queryStatus).toBe('success');
  engine.dispose();
});
it('retains oversized working configuration while blocking external saves', async () => {
  const { engine, host } = setup({ limits: { maxConfigBytes: 1024 } });
  await engine.load();
  engine.record('mine').edit(config => ({
    ...config,
    filters: {
      ...config.filters,
      root: { ...config.filters.root, props: { text: 'x'.repeat(2000) } },
    },
  }));
  expect(selected(engine).instance.config.filters.root.props.text).toHaveLength(
    2000,
  );
  expect(
    selected(engine).validation.some(issue => issue.id === 'config-size'),
  ).toBe(true);
  await expect(engine.save()).rejects.toThrow('配置无效');
  expect(host.instance!.save).not.toHaveBeenCalled();
  engine.dispose();
});
it('rejects reentrant edit callbacks before their nested mutation and keeps the old snapshot', async () => {
  const { engine } = setup();
  await engine.load();
  const before = engine.getSnapshot();
  expect(() =>
    engine.record('mine').edit(config => {
      engine.setTitle('illegal');
      return config;
    }),
  ).toThrow('重入');
  expect(engine.getSnapshot()).toBe(before);
  engine.dispose();
});

it('keeps the actual result scope when saving a different page size without querying', async () => {
  const { engine, paged } = setup();
  await engine.load();
  engine.record('mine').edit(config => ({
    ...config,
    pagination: { ...config.pagination, size: 50 },
  }));
  await engine.save();
  expect(paged).toHaveBeenCalledOnce();
  expect(selected(engine).instance.config.pagination.size).toBe(50);
  expect(selected(engine).result?.config.pagination.size).toBe(10);
  engine.dispose();
});

it('paginates and refreshes the result scope after saving unqueried sort and page-size edits', async () => {
  const { engine, paged } = setup();
  paged.mockResolvedValue({
    list: [{ state: { id: 'a', amount: 10 } }],
    total: 100,
  });
  await engine.load();
  engine.record('mine').edit(config => ({
    ...config,
    sort: [{ field: 'state.amount', direction: 'DESC' as never }],
    pagination: { ...config.pagination, size: 50 },
  }));
  await engine.save();
  await engine.record(engine.getSnapshot().selectedInstanceId!).setPage(2);
  expect(paged.mock.lastCall?.[0]).toMatchObject({
    sort: [],
    pagination: { index: 2, size: 10 },
  });
  expect(selected(engine).instance.config.pagination.size).toBe(50);
  await engine.record(engine.getSnapshot().selectedInstanceId!).refresh();
  expect(paged.mock.lastCall?.[0]).toMatchObject({
    sort: [],
    pagination: { index: 2, size: 10 },
  });
  expect(selected(engine).result?.config.pagination.size).toBe(10);
  engine.dispose();
});

it('reports load, record queries and writes as metadata even when the observer throws', async () => {
  const events: RuntimeDiagnostic[] = [];
  const { engine, host } = setup({
    onDiagnostic: event => {
      events.push(event);
      throw new Error('observer failure');
    },
  });
  await engine.load();
  engine.setTitle('private title');
  await engine.save();
  host.instance!.save = vi
    .fn()
    .mockRejectedValue(new Error('private response body'));
  await expect(engine.save()).rejects.toThrow('private response body');
  expect(events).toEqual(
    expect.arrayContaining([
      expect.objectContaining({
        kind: 'shared',
        operation: 'load',
        phase: 'succeeded',
      }),
      expect.objectContaining({
        kind: 'record',
        operation: 'query',
        phase: 'succeeded',
      }),
      expect.objectContaining({ operation: 'save', phase: 'succeeded' }),
      expect.objectContaining({
        operation: 'save',
        phase: 'failed',
        errorCode: 'OPERATION_FAILED',
      }),
    ]),
  );
  expect(JSON.stringify(events)).not.toContain('private');
  for (const event of events)
    expect(Object.keys(event).sort()).toEqual(
      [
        'elapsedMs',
        'kind',
        'operation',
        'operationId',
        'phase',
        ...(event.errorCode ? ['errorCode'] : []),
      ].sort(),
    );
  const terminals = events.filter(event => event.phase !== 'started');
  expect(new Set(terminals.map(event => event.operationId)).size).toBe(
    terminals.length,
  );
  engine.dispose();
});

it('loads an oversized default record as recoverable without auto-querying it', async () => {
  const oversized = instance();
  oversized.config.filters.root.props = { large: 'x'.repeat(2048) };
  const { engine, paged } = setup({
    limits: { maxConfigBytes: 1024 },
    instances: { instances: [oversized], defaultInstanceId: oversized.id },
  });
  try {
    await expect(engine.load()).resolves.toBeUndefined();
    expect(engine.getSnapshot().status).toBe('ready');
    expect(
      selected(engine).validation.some(error => error.id === 'config-size'),
    ).toBe(true);
    expect(paged).not.toHaveBeenCalled();
  } finally {
    engine.dispose();
  }
});

it('aborts the host signal when an unknown-create replay times out', async () => {
  vi.useFakeTimers();
  let signal: AbortSignal | undefined;
  const create = vi
    .fn()
    .mockRejectedValueOnce(new Error('unknown response'))
    .mockImplementation((_input, context) => {
      signal = context.signal;
      return new Promise(() => {});
    });
  const { engine } = setup({
    limits: { writeTimeoutMs: 10 },
    host: { instance: { create } } as never,
  });
  try {
    await engine.load();
    await expect(
      engine.saveAs({ title: 'Copy', scope: { type: 'personal' } }),
    ).rejects.toThrow();
    const retry = engine.reloadInstance();
    const rejected = expect(retry).rejects.toMatchObject({ code: 'TIMEOUT' });
    await vi.advanceTimersByTimeAsync(11);
    await rejected;
    expect(signal?.aborted).toBe(true);
    expect(selected(engine).requiresReload).toBe(true);
  } finally {
    engine.dispose();
  }
});

it('aborts the first saveAs host request at its deadline and retains uncertain outcome', async () => {
  vi.useFakeTimers();
  let signal: AbortSignal | undefined;
  const pending = deferred<ReturnType<typeof instance>>();
  const { engine } = setup({
    limits: { writeTimeoutMs: 10 },
    host: {
      instance: {
        create: (_input, context) => {
          signal = context.signal;
          return pending.promise;
        },
      },
    } as never,
  });
  try {
    await engine.load();
    const failure = expect(
      engine.saveAs({ title: 'Copy', scope: { type: 'personal' } }),
    ).rejects.toMatchObject({ code: 'TIMEOUT' });
    await vi.advanceTimersByTimeAsync(11);
    await failure;
    expect(signal?.aborted).toBe(true);
    expect(selected(engine).requiresReload).toBe(true);
    pending.resolve({ ...instance('late'), title: 'Copy' });
    await Promise.resolve();
    expect(engine.getSnapshot().instanceIds).not.toContain('late');
  } finally {
    engine.dispose();
  }
});
