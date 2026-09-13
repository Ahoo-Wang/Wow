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

import { describe, expect, it, vi } from 'vitest';
import { filter } from '@ahoo-wang/fetcher-wow';
import { ViewServiceError } from '../../src/contracts/viewServiceContract.js';
import { definition, instance, deferred } from '../engine/fixtures.js';
import { dashboardSetup, globalFilter } from './runtimeFixtures.js';

const configured = () => ({
  schemaVersion: 1 as const,
  panels: [
    {
      kind: 'view' as const,
      id: 'a',
      instanceId: 'child',
      layout: { x: 0, y: 0, w: 12, h: 18 },
    },
  ],
  filters: [
    { ...globalFilter(10), bindings: globalFilter().bindings.slice(0, 1) },
  ],
});

describe('dashboard lifetime and recovery', () => {
  it('restores applied values independently of saved and draft values and retains exact reference config', async () => {
    const { engine, paged, load } = dashboardSetup(configured());
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(1));
    const reference = runtime.getSnapshot().panels.a.instance;
    runtime.setFilter('amount', globalFilter(20).filters);
    await runtime.apply();
    runtime.setFilter('amount', globalFilter(30).filters);
    const originalPosition = runtime.getSnapshot().panels.a.position!;
    runtime.suspend();
    expect(
      engine.getSnapshot().sessions[originalPosition.identity.id],
    ).toBeUndefined();
    load.mockResolvedValue({
      ...instance('child'),
      revision: 'r2',
      config: {
        ...instance('child').config,
        filters: globalFilter(99).filters,
      },
    });
    await runtime.resume();
    expect(runtime.getSnapshot().panels.a.instance).toEqual(reference);
    expect(paged.mock.lastCall![0]).toMatchObject({
      filter: filter.and([filter.matchAll(), filter.eq('state.amount', 20)]),
    });
    expect(runtime.getSnapshot().config.filters[0].filters.root.props).toEqual({
      value: 30,
    });
    expect(runtime.getSnapshot().applied.filters[0].filters.root.props).toEqual(
      { value: 20 },
    );
    const session = engine.getSnapshot().sessions.dashboard;
    expect(session.baseline.config).toMatchObject({
      filters: [{ filters: { root: { props: { value: 10 } } } }],
    });
    await runtime.reloadReference('a');
    expect(runtime.getSnapshot().panels.a.instance!.revision).toBe('r2');
    expect(paged.mock.lastCall![0]).toMatchObject({
      filter: filter.and([
        filter.eq('state.amount', 99),
        filter.eq('state.amount', 20),
      ]),
    });
    engine.dispose();
  });
  it('clears metadata, data and bound actions when reauthorization denies a retained reference', async () => {
    const permissionLoad = vi.fn();
    const { engine, paged, load } = dashboardSetup(configured(), {
      permission: {
        getInstance: () => ({
          save: true,
          saveAsPersonal: true,
          saveAsShared: false,
        }),
        load: permissionLoad,
      },
    });
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(1));
    const old = runtime.getSnapshot().panels.a.position!;
    runtime.suspend();
    load.mockRejectedValue(new ViewServiceError('FORBIDDEN', 'denied'));
    await runtime.resume();
    expect(runtime.getSnapshot().panels.a).toMatchObject({
      status: 'blocked',
      instance: undefined,
      definition: undefined,
      position: undefined,
    });
    await expect(
      old.kind === 'record' ? old.commands.refresh() : old.commands.run(),
    ).rejects.toThrow();
    expect(paged).toHaveBeenCalledTimes(1);
    expect(permissionLoad).toHaveBeenCalledTimes(1);
    engine.dispose();
  });
  it('query FORBIDDEN clears old results and rejects old actions', async () => {
    const { engine, paged } = dashboardSetup(configured());
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(1));
    const old = runtime.getSnapshot().panels.a.position!;
    paged.mockRejectedValue(new ViewServiceError('FORBIDDEN', 'denied'));
    await runtime.refresh();
    expect(runtime.getSnapshot().panels.a).toMatchObject({
      status: 'blocked',
      instance: undefined,
      position: undefined,
    });
    expect(engine.getSnapshot().sessions[old.identity.id]).toBeUndefined();
    engine.dispose();
  });
  it('drops ignored-abort load completion after suspension and does not recreate a disposed engine', async () => {
    const gate = deferred<ReturnType<typeof instance>>();
    const { engine, paged, load } = dashboardSetup(configured());
    load.mockImplementation(() => gate.promise);
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    await vi.waitFor(() => expect(load).toHaveBeenCalledTimes(1));
    runtime.suspend();
    engine.dispose();
    gate.resolve(instance('child'));
    await new Promise(resolve => setTimeout(resolve, 10));
    expect(paged).not.toHaveBeenCalled();
    expect(
      Object.keys(engine.getSnapshot().sessions).filter(id =>
        id.startsWith('position:'),
      ),
    ).toEqual([]);
  });
  it('removes queued reference loading immediately without disturbing siblings', async () => {
    const gate = deferred<void>();
    const load = vi.fn(async (id: string) => {
      await gate.promise;
      return instance(id);
    });
    const { engine, paged } = dashboardSetup(
      {
        schemaVersion: 1,
        filters: [],
        panels: Array.from({ length: 8 }, (_, index) => ({
          kind: 'view' as const,
          id: `panel${index}`,
          instanceId: `child${index}`,
          layout: { x: 0, y: 0, w: 6, h: 18 },
        })),
      },
      {
        instance: { load, save: async value => value },
        definition: { load: vi.fn(async () => definition) },
      },
    );
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    await vi.waitFor(() => expect(load).toHaveBeenCalledTimes(4));
    runtime.edit(config => ({
      ...config,
      panels: config.panels.filter(panel => panel.id !== 'panel7'),
    }));
    gate.resolve();
    await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(7));
    expect(load.mock.calls.some(([id]) => id === 'child7')).toBe(false);
    engine.dispose();
  });
  it('permits browsing values but rejects structural edits for a read-only user', async () => {
    const { engine, paged } = dashboardSetup(configured(), {
      permission: {
        getInstance: () => ({
          save: false,
          saveAsPersonal: false,
          saveAsShared: false,
        }),
      },
    });
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(1));
    expect(() => runtime.edit(config => ({ ...config, panels: [] }))).toThrow(
      '权限',
    );
    runtime.setFilter('amount', globalFilter(20).filters);
    await runtime.apply();
    expect(engine.getSnapshot().sessions.dashboard.dirty).toBe(false);
    expect(paged.mock.lastCall![0]).toMatchObject({
      filter: filter.and([filter.matchAll(), filter.eq('state.amount', 20)]),
    });
    engine.dispose();
  });
  it('does not let an aborted old request deny a new successful scope', async () => {
    const gate = deferred<unknown>();
    const { engine, paged } = dashboardSetup(configured());
    paged.mockImplementationOnce(() => gate.promise);
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(1));
    runtime.setFilter('amount', globalFilter(20).filters);
    await runtime.apply();
    const position = runtime.getSnapshot().panels.a.position!;
    gate.reject(new ViewServiceError('FORBIDDEN', 'old aborted response'));
    await new Promise(resolve => setTimeout(resolve, 10));
    expect(runtime.getSnapshot().panels.a.position).toBe(position);
    expect(position.getSnapshot().queryStatus).toBe('success');
    engine.dispose();
  });
});

it('does not orphan a position when a store observer suspends during opening', async () => {
  const { engine, paged, load } = dashboardSetup(configured());
  let suspended = false;
  const off = engine.subscribe(() => {
    if (
      !suspended &&
      Object.keys(engine.getSnapshot().sessions).some(id =>
        id.startsWith('position:'),
      )
    ) {
      suspended = true;
      engine.dashboard('dashboard').suspend();
    }
  });
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  await vi.waitFor(() => expect(suspended).toBe(true));
  expect(
    Object.keys(engine.getSnapshot().sessions).filter(id =>
      id.startsWith('position:'),
    ),
  ).toEqual([]);
  expect(paged).not.toHaveBeenCalled();
  const before = load.mock.calls.length;
  off();
  await runtime.resume();
  expect(load.mock.calls.length).toBeGreaterThan(before);
  engine.dispose();
});
it('clears a prior query failure after a successful local refresh', async () => {
  const { engine, paged } = dashboardSetup(configured());
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  await vi.waitFor(() => expect(paged).toHaveBeenCalledOnce());
  paged.mockRejectedValueOnce(new Error('unavailable'));
  await runtime.refresh('a');
  const position = runtime.getSnapshot().panels.a.position!;
  expect(position.getSnapshot().queryError).toBe('unavailable');
  expect(runtime.getSnapshot().panels.a.error).toBeNull();
  expect(runtime.getSnapshot().panels.a.status).toBe('error');
  await runtime.refresh('a');
  expect(runtime.getSnapshot().panels.a.error).toBeNull();
  expect(runtime.getSnapshot().panels.a.status).toBe('ready');
  expect(position.getSnapshot().queryError).toBeNull();
  engine.dispose();
});
it('keeps exposed browsing drafts and applied configurations immutable', async () => {
  const { engine, paged } = dashboardSetup(configured(), {
    permission: {
      getInstance: () => ({
        save: false,
        saveAsPersonal: false,
        saveAsShared: false,
      }),
    },
  });
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  await vi.waitFor(() => expect(paged).toHaveBeenCalledOnce());
  runtime.setFilter('amount', globalFilter(20).filters);
  expect(
    Object.isFrozen(runtime.getSnapshot().config.filters[0].filters.root.props),
  ).toBe(true);
  await runtime.apply();
  expect(Object.isFrozen(runtime.getSnapshot().applied.filters)).toBe(true);
  expect(Object.isFrozen(runtime.getSnapshot().panels.a.instance!.config)).toBe(
    true,
  );
  engine.dispose();
});

it('gives each actual reference request its own execution deadline', async () => {
  vi.useFakeTimers();
  const delay = <T>(value: T) =>
    new Promise<T>(resolve => setTimeout(() => resolve(value), 14000));
  const { engine, paged } = dashboardSetup(configured(), {
    instance: { load: async () => delay(instance('child')) },
    definition: { load: async () => delay(definition) },
  });
  try {
    await engine.load();
    await vi.advanceTimersByTimeAsync(28010);
    expect(paged).toHaveBeenCalledOnce();
    expect(engine.dashboard('dashboard').getSnapshot().panels.a.status).toBe(
      'ready',
    );
  } finally {
    engine.dispose();
    vi.useRealTimers();
  }
});

it('ignores a cancelled source resolver FORBIDDEN after a replacement scope succeeds', async () => {
  const gate = deferred<never>();
  const source = { paged: vi.fn().mockResolvedValue({ total: 0, list: [] }) };
  const resolveSource = vi
    .fn()
    .mockResolvedValue(source)
    .mockImplementationOnce(() => gate.promise);
  const { engine } = dashboardSetup(configured(), { resolveSource });
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  await vi.waitFor(() => expect(resolveSource).toHaveBeenCalledTimes(1));
  runtime.setFilter('amount', globalFilter(20).filters);
  await runtime.apply();
  const position = runtime.getSnapshot().panels.a.position!;
  expect(position.getSnapshot().queryStatus).toBe('success');
  gate.reject(new ViewServiceError('FORBIDDEN', 'obsolete source credentials'));
  await new Promise(resolve => setTimeout(resolve, 10));
  expect(runtime.getSnapshot().panels.a.position).toBe(position);
  expect(runtime.getSnapshot().panels.a.blocked).toBe(false);
  expect(position.getSnapshot().queryStatus).toBe('success');
  engine.dispose();
});

it.each([
  ['instance', false],
  ['instance', true],
  ['definition', false],
  ['definition', true],
] as const)(
  'isolates a same-ID replacement from obsolete %s completion (denied: %s)',
  async (stage, denied) => {
    const oldInstance = deferred<ReturnType<typeof instance>>();
    const oldDefinition = deferred<typeof definition>();
    let oldSignal: AbortSignal | undefined;
    const load = vi.fn(async (id: string, signal?: AbortSignal) => {
      if (id === 'child' && stage === 'instance') {
        oldSignal = signal;
        return oldInstance.promise;
      }
      return { ...instance(id), definitionId: id };
    });
    const loadDefinition = vi.fn(async (id: string, signal?: AbortSignal) => {
      if (id === 'child') {
        oldSignal = signal;
        return oldDefinition.promise;
      }
      return { ...definition, id };
    });
    const { engine, paged } = dashboardSetup(
      { ...configured(), filters: [] },
      {
        instance: { load, save: async value => value },
        definition: { load: loadDefinition },
      },
    );
    try {
      await engine.load();
      const runtime = engine.dashboard('dashboard');
      await vi.waitFor(() =>
        expect(
          stage === 'instance' ? load : loadDefinition,
        ).toHaveBeenCalledOnce(),
      );
      expect(oldSignal?.aborted).toBe(false);
      runtime.edit(config => ({
        ...config,
        panels: config.panels.map(panel => ({ ...panel, instanceId: 'other' })),
      }));
      expect(oldSignal?.aborted).toBe(true);
      await vi.waitFor(() => expect(paged).toHaveBeenCalledOnce());
      const position = runtime.getSnapshot().panels.a.position!;
      const gate = stage === 'instance' ? oldInstance : oldDefinition;
      if (denied)
        gate.reject(new ViewServiceError('FORBIDDEN', 'obsolete owner'));
      else if (stage === 'instance')
        oldInstance.resolve({ ...instance('child'), definitionId: 'child' });
      else oldDefinition.resolve({ ...definition, id: 'child' });
      await new Promise(resolve => setTimeout(resolve, 10));
      expect(runtime.getSnapshot().panels.a).toMatchObject({
        status: 'ready',
        instance: { id: 'other' },
        position,
      });
      expect(position.getSnapshot().queryStatus).toBe('success');
      expect(
        Object.keys(engine.getSnapshot().sessions).filter(id =>
          id.startsWith('position:'),
        ),
      ).toEqual([position.identity.id]);
      expect(paged).toHaveBeenCalledOnce();
    } finally {
      engine.dispose();
    }
  },
);
