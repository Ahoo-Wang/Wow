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
import { ViewEngine } from '../../src/record/ViewEngine.js';
import { createFilterConfiguration } from '../../src/filter/filterCore.js';
import { filter } from '@ahoo-wang/fetcher-wow';
import {
  setup,
  instance,
  definition,
  deferred,
  selected,
  managementPermissions,
} from './fixtures.js';

it.each(['rename', 'delete'] as const)(
  'isolates throwing subscribers during %s and releases the write lock',
  async operation => {
    const { engine, host } = setup();
    host.permission!.getInstance = managementPermissions;
    host.instance!.rename = vi.fn(async (id, title) => ({
      ...instance(id),
      title,
      revision: '2',
    }));
    host.instance!.delete = vi.fn(async () => ({ defaultInstance: null }));
    await engine.load();
    const report = vi.spyOn(console, 'error').mockImplementation(() => {});
    const notified = vi.fn();
    const stop = engine.subscribe(() => {
      throw new Error('broken subscriber');
    });
    engine.subscribe(notified);
    await expect(
      operation === 'rename'
        ? engine.renameInstance('Renamed')
        : engine.deleteInstance(),
    ).resolves.toBeUndefined();
    expect(host.instance![operation]).toHaveBeenCalledOnce();
    expect(notified).toHaveBeenCalled();
    expect(report).toHaveBeenCalled();
    stop();
    await expect(engine.save()).resolves.toBeUndefined();
    engine.dispose();
  },
);

it('waits for independent permission initialization before becoming ready or querying records', async () => {
  const { host, paged } = setup();
  const pending = deferred<void>();
  let ready = false;
  host.permission = {
    getInstance: () => ({ ...managementPermissions(), save: ready }),
    refresh: vi.fn(async () => {}),
    load: vi.fn(async () => {
      await pending.promise;
      ready = true;
      return {
        revision: 1,
        instances: { mine: managementPermissions() },
        reorder: true,
      };
    }),
  };
  const engine = new ViewEngine({
    definitionId: 'orders',
    definition,
    instances: { instances: [instance()], defaultInstanceId: 'mine' },
    host,
  });
  const loading = engine.load();
  await vi.waitFor(() => expect(host.permission!.load).toHaveBeenCalledOnce());
  expect(engine.getSnapshot().status).toBe('loading');
  expect(paged).not.toHaveBeenCalled();
  pending.resolve();
  await loading;
  expect(engine.getCapabilitiesSnapshot().instances.mine.permissions.save).toBe(
    true,
  );
  expect(paged).toHaveBeenCalledOnce();
  expect(host.permission!.refresh).not.toHaveBeenCalled();
  engine.dispose();
});

it('fails closed on permission initialization errors, retries and forwards cancellation', async () => {
  const { engine, host, paged } = setup();
  host.permission!.load = vi
    .fn()
    .mockRejectedValueOnce(new Error('permissions offline'))
    .mockResolvedValue({ revision: 1, instances: {}, reorder: false });
  await expect(engine.load()).rejects.toThrow('permissions offline');
  expect(paged).not.toHaveBeenCalled();
  const signal = vi.mocked(host.permission!.load).mock.calls[0][1]!;
  expect(signal.aborted).toBe(true);
  await engine.load();
  expect(engine.getSnapshot().status).toBe('ready');
  engine.dispose();
});

it('initializes refresh-only permission services without requiring a snapshot loader', async () => {
  const { engine, host } = setup();
  host.permission!.refresh = vi.fn(async () => {});
  await engine.load();
  expect(host.permission!.refresh).toHaveBeenCalledOnce();
  engine.dispose();
});

it('does not recompile filters for selection, title or query status changes', async () => {
  const value = instance();
  value.config.filters = createFilterConfiguration({
    id: 'custom',
    operator: 'EQ',
    field: 'state.amount',
    component: { name: 'custom' },
    props: { value: 1 },
  });
  const compile = vi.fn((props: { value?: unknown }) =>
    filter.eq('state.amount', props.value),
  );
  const { engine } = setup({
    instances: { instances: [value], defaultInstanceId: 'mine' },
    filterCompilers: { custom: { compile } },
  });
  await engine.load();
  compile.mockClear();
  engine.setSelection(['a']);
  engine.setTitle('new title');
  await engine.refresh();
  expect(compile).not.toHaveBeenCalled();
  expect(selected(engine).dirty).toBe(true);
  engine.setFilterDraft(
    createFilterConfiguration({
      ...selected(engine).filterDraft.root,
      props: { value: 2 },
    }),
  );
  expect(compile).toHaveBeenCalled();
  expect(selected(engine).filterPending).toBe(true);
  await engine.applyFilter();
  expect(selected(engine).filterPending).toBe(false);
  engine.dispose();
});

it.each(['paged', 'cursor'] as const)(
  'accepts a %s-only source and rejects unsupported modes before queries or summaries',
  async mode => {
    const request = vi.fn(async () =>
      mode === 'paged'
        ? { list: [], total: 0 }
        : { list: [], nextCursor: null },
    );
    const aggregate = vi.fn();
    const { engine, host } = setup({
      instances: {
        instances: [instance('mine', mode)],
        defaultInstanceId: 'mine',
      },
    });
    host.resolveSource = () =>
      ({ [mode]: request, aggregate }) as ReturnType<
        Exclude<typeof host.resolveSource, undefined>
      >;
    await engine.load();
    expect(request).toHaveBeenCalledOnce();
    engine.dispose();
    const opposite = mode === 'paged' ? 'cursor' : 'paged';
    const unsupported = instance('mine', opposite);
    unsupported.config.presentation.table.columns = [
      { id: 'amount', kind: 'field', field: 'state.amount', summary: ['SUM'] },
    ];
    const other = setup({
      instances: {
        instances: [unsupported],
        defaultInstanceId: 'mine',
      },
      host,
    });
    await expect(other.engine.load()).rejects.toThrow(
      `数据源不支持 ${opposite}`,
    );
    expect(request).toHaveBeenCalledOnce();
    expect(aggregate).not.toHaveBeenCalled();
    other.engine.dispose();
  },
);

it('cancels permission initialization and ignores its completion after disposal', async () => {
  const { engine, host, paged } = setup();
  const pending = deferred<{
    revision: number;
    instances: Record<string, never>;
    reorder: boolean;
  }>();
  host.permission!.load = vi.fn(() => pending.promise);
  const loading = engine.load();
  await vi.waitFor(() => expect(host.permission!.load).toHaveBeenCalledOnce());
  const signal = vi.mocked(host.permission!.load).mock.calls[0][1]!;
  engine.dispose();
  expect(signal.aborted).toBe(true);
  pending.resolve({ revision: 1, instances: {}, reorder: false });
  await loading;
  expect(paged).not.toHaveBeenCalled();
});
