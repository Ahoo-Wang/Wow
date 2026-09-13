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
import type { AnalysisViewInstance } from '../../src/contracts/viewModel.js';
import { definition, instance, deferred } from '../engine/fixtures.js';
import { dashboardSetup, globalFilter } from './runtimeFixtures.js';

describe('dashboard composition runtime', () => {
  it('loads repeated references as independent positions without permission initialization', async () => {
    const { engine, paged } = dashboardSetup();
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
    const { a, b } = runtime.getSnapshot().panels;
    expect(a.position!.identity.id).not.toBe(b.position!.identity.id);
    expect(a.definition!.id).toBe('orders');
    if (a.position!.kind !== 'record') throw new Error('record');
    await a.position!.commands.setPage(2);
    expect(a.position!.getSnapshot().page).toBe(2);
    expect(b.position!.getSnapshot()).toMatchObject({ page: 1 });
    runtime.edit(config => ({
      ...config,
      panels: [...config.panels]
        .reverse()
        .map(panel => ({ ...panel, layout: { x: 0, y: 0, w: 12, h: 18 } })),
    }));
    await Promise.resolve();
    expect(runtime.getSnapshot().panels.a.position).toBe(a.position);
    expect(paged).toHaveBeenCalledTimes(3);
    engine.dispose();
  });
  it('does not issue broad queries for new or replaced panels before complete apply', async () => {
    const { engine, paged } = dashboardSetup({
      schemaVersion: 1,
      panels: [
        {
          kind: 'view' as const,
          id: 'a',
          instanceId: 'child',
          layout: { x: 0, y: 0, w: 6, h: 18 },
        },
      ],
      filters: [
        { ...globalFilter(), bindings: globalFilter().bindings.slice(0, 1) },
      ],
    });
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(1));
    runtime.edit(config => ({
      ...config,
      panels: [
        ...config.panels,
        {
          kind: 'view' as const,
          id: 'b',
          instanceId: 'child',
          layout: { x: 0, y: 0, w: 6, h: 18 },
        },
      ],
    }));
    await vi.waitFor(() =>
      expect(runtime.getSnapshot().panels.b.blocked).toBe(true),
    );
    expect(paged).toHaveBeenCalledTimes(1);
    await expect(runtime.apply()).rejects.toThrow();
    runtime.edit(config => ({ ...config, filters: [globalFilter()] }));
    await runtime.apply();
    expect(paged).toHaveBeenCalledTimes(2);
    expect(
      paged.mock.calls.every(call => JSON.stringify(call[0]).includes('10')),
    ).toBe(true);
    runtime.edit(config => ({
      ...config,
      panels: config.panels.map(panel =>
        panel.id === 'a' ? { ...panel, instanceId: 'other' } : panel,
      ),
    }));
    expect(runtime.getSnapshot().panels.a.position).toBeUndefined();
    expect(
      runtime
        .getSnapshot()
        .config.filters[0].bindings.some(binding => binding.panelId === 'a'),
    ).toBe(false);
    expect(paged).toHaveBeenCalledTimes(2);
    engine.dispose();
  });
  it('preserves an unaffected inflight sibling while changing only A scope', async () => {
    const gate = deferred<{
      total: number;
      list: { state: { id: string } }[];
    }>();
    const { engine, paged } = dashboardSetup({
      schemaVersion: 1,
      panels: [
        {
          kind: 'view' as const,
          id: 'a',
          instanceId: 'child',
          layout: { x: 0, y: 0, w: 6, h: 18 },
        },
        {
          kind: 'view' as const,
          id: 'b',
          instanceId: 'child',
          layout: { x: 0, y: 0, w: 6, h: 18 },
        },
      ],
      filters: [globalFilter(10, ['b'])],
    });
    paged
      .mockImplementationOnce(async () => ({ total: 1, list: [] }))
      .mockImplementationOnce(() => gate.promise);
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
    const before = runtime.getSnapshot().panels.b;
    runtime.setFilter('amount', globalFilter(20).filters);
    await runtime.apply();
    expect(runtime.getSnapshot().panels.b.scopeVersion).toBe(
      before.scopeVersion,
    );
    expect(runtime.getSnapshot().panels.b.position).toBe(before.position);
    gate.resolve({ total: 1, list: [{ state: { id: 'late-b' } }] });
    await vi.waitFor(() =>
      expect(before.position!.getSnapshot()).toMatchObject({
        queryStatus: 'success',
        rows: [{ state: { id: 'late-b' } }],
      }),
    );
    expect(paged).toHaveBeenCalledTimes(3);
    engine.dispose();
  });
  it('keeps temporary values separate from refresh and save does not apply', async () => {
    const { engine, paged } = dashboardSetup({
      schemaVersion: 1,
      panels: [
        {
          kind: 'view' as const,
          id: 'a',
          instanceId: 'child',
          layout: { x: 0, y: 0, w: 12, h: 18 },
        },
      ],
      filters: [
        { ...globalFilter(), bindings: globalFilter().bindings.slice(0, 1) },
      ],
    });
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(1));
    runtime.setFilter('amount', globalFilter(20).filters);
    await engine.save('dashboard');
    expect(paged).toHaveBeenCalledTimes(1);
    await runtime.refresh();
    expect(paged.mock.lastCall![0]).toMatchObject({
      filter: filter.and([filter.matchAll(), filter.eq('state.amount', 10)]),
    });
    expect(runtime.getSnapshot().pending).toBe(true);
    await runtime.apply();
    expect(paged.mock.lastCall![0]).toMatchObject({
      filter: filter.and([filter.matchAll(), filter.eq('state.amount', 20)]),
    });
    engine.dispose();
  });
  it('keeps invalid editor buffers behind the common apply/save boundary and clears them on restore', async () => {
    const { engine, paged } = dashboardSetup({
      schemaVersion: 1,
      panels: [
        {
          kind: 'view' as const,
          id: 'a',
          instanceId: 'child',
          layout: { x: 0, y: 0, w: 6, h: 18 },
        },
        {
          kind: 'view' as const,
          id: 'b',
          instanceId: 'child',
          layout: { x: 0, y: 0, w: 6, h: 18 },
        },
      ],
      filters: [globalFilter()],
    });
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
    runtime.setEditorValidity('filter:amount', false);
    expect(runtime.getSnapshot().session.dirty).toBe(true);
    await expect(runtime.apply()).rejects.toThrow('编辑输入无效');
    await expect(engine.save('dashboard')).rejects.toThrow('编辑输入无效');
    expect(paged).toHaveBeenCalledTimes(2);
    await engine.restore('dashboard');
    expect(runtime.getSnapshot().session.editorValidity).toEqual({});
    engine.dispose();
  });
  it('admits aggregate retained result budgets before publishing any oversized panel result', async () => {
    const { engine, paged } = dashboardSetup(
      undefined,
      {},
      { maxDashboardResultRows: 1 },
    );
    const retainedCounts: number[] = [];
    engine.subscribe(() =>
      retainedCounts.push(
        Object.values(engine.getSnapshot().sessions).reduce(
          (total, session) =>
            total +
            (session.kind === 'dashboard'
              ? 0
              : (session.result?.rows.length ?? 0)),
          0,
        ),
      ),
    );
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
    await vi.waitFor(() =>
      expect(
        Object.values(runtime.getSnapshot().panels).filter(
          panel => panel.status === 'error',
        ),
      ).toHaveLength(1),
    );
    expect(Math.max(...retainedCounts)).toBe(1);
    expect(
      Object.values(runtime.getSnapshot().panels)
        .find(panel => panel.status === 'error')!
        .position!.getSnapshot().queryError,
    ).toContain('预算');
    expect(
      Object.values(runtime.getSnapshot().panels).every(
        panel => panel.error === null,
      ),
    ).toBe(true);
    engine.dispose();
  });
  it('blocks bad transform output only on its panel and rejects overwriting its configuration', async () => {
    const config = {
      schemaVersion: 1 as const,
      panels: [
        {
          kind: 'view' as const,
          id: 'a',
          instanceId: 'child',
          layout: { x: 0, y: 0, w: 6, h: 18 },
        },
        {
          kind: 'view' as const,
          id: 'b',
          instanceId: 'child',
          layout: { x: 0, y: 0, w: 6, h: 18 },
        },
      ],
      filters: [
        {
          ...globalFilter(),
          bindings: [
            { panelId: 'a', kind: 'transform' as const, name: 'missing' },
            globalFilter().bindings[1],
          ],
        },
      ],
    };
    const { engine, paged } = dashboardSetup(config);
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(1));
    expect(runtime.getSnapshot().panels.a).toMatchObject({
      blocked: true,
      position: undefined,
    });
    expect(runtime.getSnapshot().panels.b.position).toBeDefined();
    await expect(engine.save('dashboard')).rejects.toThrow('转换器');
    engine.dispose();
  });

  it('removes a filtered panel and all binding decisions atomically', async () => {
    const { engine, paged } = dashboardSetup({
      schemaVersion: 1,
      panels: [
        {
          kind: 'view' as const,
          id: 'a',
          instanceId: 'child',
          layout: { x: 0, y: 0, w: 6, h: 18 },
        },
        {
          kind: 'view' as const,
          id: 'b',
          instanceId: 'child',
          layout: { x: 0, y: 0, w: 6, h: 18 },
        },
      ],
      filters: [globalFilter()],
    });
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
    const old = runtime.getSnapshot().panels.a.position!;
    runtime.edit(config => ({
      ...config,
      panels: config.panels.filter(panel => panel.id !== 'a'),
    }));
    expect(
      runtime
        .getSnapshot()
        .config.filters[0].bindings.map(binding => binding.panelId),
    ).toEqual(['b']);
    expect(
      runtime
        .getSnapshot()
        .applied.filters[0].bindings.map(binding => binding.panelId),
    ).toEqual(['b']);
    expect(engine.getSnapshot().sessions[old.identity.id]).toBeUndefined();
    expect(paged).toHaveBeenCalledTimes(2);
    engine.dispose();
  });
  it('rejects an analysis result exceeding the dashboard total without leaving loading stuck', async () => {
    const child: AnalysisViewInstance = {
      ...instance('child'),
      kind: 'analysis',
      config: {
        filters: instance().config.filters,
        dimensions: [],
        metrics: [
          {
            id: 'count',
            component: { name: 'count' },
            alias: 'orders',
            title: 'Orders',
            props: {},
          },
        ],
        sort: [],
        limit: 100,
        presentation: { layout: 'table', columns: [] },
      },
    };
    const aggregate = vi.fn().mockResolvedValue([{ orders: 2 }]);
    const { engine } = dashboardSetup(
      undefined,
      {
        instance: { load: async () => child },
        definition: {
          load: async () => ({
            ...definition,
            analysis: { count: true, fields: [] },
          }),
        },
        resolveSource: () => ({ aggregate }),
      },
      { maxDashboardResultRows: 1 },
    );
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    await vi.waitFor(() => expect(aggregate).toHaveBeenCalledTimes(2));
    await vi.waitFor(() =>
      expect(
        Object.values(runtime.getSnapshot().panels).filter(
          panel => panel.status === 'error',
        ),
      ).toHaveLength(1),
    );
    expect(
      Object.values(runtime.getSnapshot().panels).filter(
        panel => panel.loading,
      ),
    ).toHaveLength(0);
    expect(
      Object.values(engine.getSnapshot().sessions).reduce(
        (sum, session) =>
          sum +
          (session.kind === 'dashboard'
            ? 0
            : (session.result?.rows.length ?? 0)),
        0,
      ),
    ).toBe(1);
    engine.dispose();
  });
});

it('keeps child positions and pagination when the first draft save acquires a saved identity', async () => {
  const { engine, paged, host } = dashboardSetup(undefined, {
    permission: {
      getDefinition: () => ({ createPersonal: true }),
      getInstance: () => ({
        save: true,
        saveAsPersonal: true,
        saveAsShared: false,
      }),
    },
  });
  host.instance!.create = vi.fn(async input => ({
    ...input,
    id: 'created-dashboard',
    revision: '1',
  }));
  await engine.load();
  await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  const id = engine.createDashboard({
    title: 'New',
    scope: { type: 'personal' },
  });
  const runtime = engine.dashboard(id);
  runtime.edit(config => ({
    ...config,
    panels: [
      {
        kind: 'view' as const,
        id: 'new-panel',
        instanceId: 'child',
        layout: { x: 0, y: 0, w: 12, h: 18 },
      },
    ],
  }));
  await vi.waitFor(() =>
    expect(runtime.getSnapshot().panels['new-panel'].position).toBeTruthy(),
  );
  const position = runtime.getSnapshot().panels['new-panel'].position!;
  if (position.kind !== 'record') throw new Error('record');
  await position.commands.setPage(2);
  const before = paged.mock.calls.length;
  await engine.save(id);
  const saved = engine.dashboard('created-dashboard');
  await vi.waitFor(() =>
    expect(saved.getSnapshot().panels['new-panel'].position).toBeTruthy(),
  );
  expect(saved).toBe(runtime);
  expect(saved.getSnapshot().panels['new-panel'].position).toBe(position);
  expect(position.getSnapshot().page).toBe(2);
  expect(paged).toHaveBeenCalledTimes(before);
  engine.dispose();
});

it('resolves replacement host sources without resetting panel positions or pagination', async () => {
  const { engine, paged, host } = dashboardSetup();
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  const position = runtime.getSnapshot().panels.a.position!;
  if (position.kind !== 'record') throw new Error('record');
  await position.commands.setPage(2);
  const replacement = vi.fn().mockResolvedValue({ total: 30, list: [] });
  const resolveSource = vi.fn(async () => ({ paged: replacement }));
  engine.updateHost({ ...host, resolveSource });
  expect(runtime.getSnapshot().panels.a.position).toBe(position);
  expect(replacement).not.toHaveBeenCalled();
  await runtime.refresh('a');
  expect(resolveSource).toHaveBeenCalled();
  expect(replacement).toHaveBeenCalledTimes(1);
  expect(position.getSnapshot().page).toBe(2);
  expect(paged).toHaveBeenCalledTimes(3);
  engine.updateHost({
    ...host,
    resolveSource: async () => ({ aggregate: vi.fn() }),
  });
  await runtime.refresh('a');
  expect(position.getSnapshot().queryStatus).toBe('error');
  expect(position.getSnapshot().queryError).toContain('paged');
  expect(runtime.getSnapshot().panels.a.position).toBe(position);
  engine.updateHost({ ...host, resolveSource });
  await runtime.refresh('a');
  expect(position.getSnapshot().queryStatus).toBe('success');
  expect(position.getSnapshot().page).toBe(2);
  engine.dispose();
});

it('skips unrelated position notifications while active and suspended, but observes permission changes', async () => {
  let editable = true;
  let permissionsChanged = () => {};
  const { engine, paged } = dashboardSetup(undefined, {
    permission: {
      getInstance: () => ({ save: editable }),
      subscribe: listener => {
        permissionsChanged = listener;
        return () => {};
      },
    },
  });
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  const notified = vi.fn();
  runtime.subscribe(notified);
  const unrelated = engine.openPosition(instance(), definition);
  await unrelated.commands.refresh();
  expect(notified).not.toHaveBeenCalled();
  editable = false;
  permissionsChanged();
  expect(runtime.getSnapshot().editable).toBe(false);
  expect(notified).toHaveBeenCalledTimes(1);
  runtime.suspend();
  notified.mockClear();
  await unrelated.commands.refresh();
  expect(notified).not.toHaveBeenCalled();
  unrelated.dispose();
  engine.dispose();
});

it('recreates explicitly disposed runtimes and resumes their saved references', async () => {
  const { engine, paged } = dashboardSetup();
  await engine.load();
  const previous = engine.dashboard('dashboard');
  await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  previous.dispose();
  const next = engine.dashboard('dashboard');
  expect(next).not.toBe(previous);
  expect(previous.isDisposed).toBe(true);
  await next.resume();
  await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(4));
  expect(next.getSnapshot().panels.a.position).toBeTruthy();
  engine.dispose();
});

it.each([false, true])(
  'keeps temporary editor validity outside saved sessions across permission changes (config changed: %s)',
  async changedConfig => {
    let editable = false;
    let permissionsChanged = () => {};
    const item = { ...globalFilter(), bindings: [] };
    const { engine } = dashboardSetup(
      { schemaVersion: 1, panels: [], filters: [item] },
      {
        permission: {
          getInstance: () => ({ save: editable }),
          subscribe: listener => {
            permissionsChanged = listener;
            return () => {};
          },
        },
      },
    );
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    const session = engine.getSnapshot().sessions.dashboard;
    if (changedConfig) runtime.setFilter('amount', globalFilter(20).filters);
    runtime.setEditorValidity('filter:amount', false);
    expect(engine.getSnapshot().sessions.dashboard).toBe(session);
    expect(runtime.getSnapshot().session.dirty).toBe(false);
    await expect(runtime.apply()).rejects.toThrow('编辑输入无效');
    editable = true;
    permissionsChanged();
    expect(() => runtime.assertSavable()).not.toThrow();
    runtime.setEditorValidity('filter:amount', true);
    expect(runtime.getSnapshot().validation).toEqual([]);
    expect(runtime.getSnapshot().session.editorValidity).toEqual({});
    engine.dispose();
  },
);
