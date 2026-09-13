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
import { filter } from '@ahoo-wang/fetcher-wow';
import { definition, instance, setup, deferred } from '../engine/fixtures.js';
import { globalFilter } from './runtimeFixtures.js';
import type { DashboardViewInstance } from '../../src/dashboard/dashboardModel.js';

const localDefinition = {
  id: 'homepage',
  title: 'Homepage',
  dashboard: true as const,
  fields: [{ field: 'localAmount', label: 'Amount', type: 'number' as const }],
};
function dashboard(): DashboardViewInstance {
  const item = globalFilter();
  return {
    id: 'homepage',
    definitionId: localDefinition.id,
    title: 'Homepage',
    kind: 'dashboard',
    revision: 'r1',
    scope: { type: 'personal' },
    config: {
      schemaVersion: 1,
      panels: [
        {
          kind: 'view',
          id: 'a',
          instanceId: 'child',
          layout: { x: 0, y: 0, w: 12, h: 18 },
        },
      ],
      filters: [
        {
          ...item,
          filters: {
            ...item.filters,
            root: { ...item.filters.root, field: 'localAmount' },
          },
          bindings: [
            {
              panelId: 'a',
              kind: 'fields',
              fields: { localAmount: 'state.amount' },
              semanticCompatibility: true,
            },
          ],
        },
      ],
    },
  };
}

it('owns two independent browsing dashboards across managed navigation and administrator filtering', async () => {
  const { engine, host, paged } = setup({
    limits: { maxDashboardResultRows: 1 },
    host: {
      resolveSource: () => ({}),
      instance: { load: async () => instance('child') },
      definition: { load: async () => definition },
    },
  });
  host.resolveSource = () => ({ paged });
  await engine.load();
  const managed = engine.getSnapshot().sessions.mine;
  paged.mockClear();
  const saved = dashboard();
  const left = engine.openPosition(saved, localDefinition);
  const right = engine.openPosition(saved, localDefinition);
  try {
    expect(left.kind).toBe('dashboard');
    expect(left.identity.id).not.toBe(right.identity.id);
    expect(left.identity.instanceId).toBe(right.identity.instanceId);
    expect(paged).not.toHaveBeenCalled();
    expect(left.getSnapshot().active).toBe(false);
    expect(left.runtime.definition).toEqual(localDefinition);
    expect(left.getSnapshot().session.validation).toEqual([]);
    expect(left.getSnapshot().editable).toBe(false);
    expect(() => left.runtime.edit(config => config)).toThrow('权限');
    await Promise.all([left.runtime.resume(), right.runtime.resume()]);
    expect(
      left.getSnapshot().panels.a.position!.getSnapshot().queryStatus,
    ).toBe('success');
    expect(
      right.getSnapshot().panels.a.position!.getSnapshot().queryStatus,
    ).toBe('success');
    const filters = left.getSnapshot().config.filters[0].filters;
    left.runtime.setFilter('amount', {
      ...filters,
      root: { ...filters.root, props: { value: 20 } },
    });
    left.runtime.setEditorValidity('filter:amount', false);
    expect(left.getSnapshot().session.dirty).toBe(false);
    expect(left.getSnapshot().session.editorValidity).toEqual({});
    await expect(left.runtime.apply()).rejects.toThrow('编辑输入无效');
    left.runtime.setEditorValidity('filter:amount', true);
    await left.runtime.apply();
    expect(paged.mock.lastCall![0].filter).toEqual(
      filter.and([filter.matchAll(), filter.eq('state.amount', 20)]),
    );
    expect(right.getSnapshot().config.filters[0].filters.root.props).toEqual({
      value: 10,
    });
    expect(left.getSnapshot().session.instance).toEqual(saved);
    expect(engine.getSnapshot().sessions.mine).toBe(managed);
    await engine.selectInstance('shared');
    expect(left.getSnapshot().active).toBe(true);
    expect(right.getSnapshot().active).toBe(true);
    await expect(engine.save(left.identity.id)).rejects.toThrow('位置');
    await expect(
      engine.saveAs(
        { title: 'Copy', scope: { type: 'personal' } },
        left.identity.id,
      ),
    ).rejects.toThrow('位置');
    expect(engine.getPermissions(left.identity.id).save).toBe(false);
    const childId = left.getSnapshot().panels.a.position!.identity.id;
    left.dispose();
    expect(left.runtime.isDisposed).toBe(true);
    expect(engine.getSnapshot().sessions[childId]).toBeUndefined();
    await right.runtime.refresh();
    expect(
      right.getSnapshot().panels.a.position!.getSnapshot().queryStatus,
    ).toBe('success');
  } finally {
    left.dispose();
    right.dispose();
    engine.dispose();
  }
});

it('cannot revive disposed dashboard positions after a delayed reference load', async () => {
  const pending = deferred<ReturnType<typeof instance>>();
  const load = vi.fn(() => pending.promise);
  const { engine, paged } = setup({
    instances: {
      instances: [instance(), instance('shared')],
      defaultInstanceId: null,
    },
    host: {
      resolveSource: () => ({}),
      instance: { load },
      definition: { load: async () => definition },
    },
  });
  await engine.load();
  const position = engine.openPosition(dashboard(), localDefinition);
  const running = position.runtime.resume();
  await vi.waitFor(() => expect(load).toHaveBeenCalledOnce());
  position.dispose();
  pending.resolve(instance('child'));
  await running;
  expect(Object.keys(engine.getSnapshot().sessions)).toEqual([
    'mine',
    'shared',
  ]);
  expect(paged).not.toHaveBeenCalled();
  await expect(position.runtime.resume()).rejects.toThrow('释放');
  engine.dispose();
});

it('continues rejecting a dashboard used as a child reference', async () => {
  const { engine } = setup({
    instances: {
      instances: [instance(), instance('shared')],
      defaultInstanceId: null,
    },
    host: {
      resolveSource: () => ({}),
      instance: { load: async () => ({ ...dashboard(), id: 'child' }) },
    },
  });
  await engine.load();
  const position = engine.openPosition(dashboard(), localDefinition);
  try {
    await position.runtime.resume();
    expect(position.getSnapshot().panels.a.blocked).toBe(true);
    expect(position.getSnapshot().panels.a.position).toBeUndefined();
  } finally {
    position.dispose();
    engine.dispose();
  }
});

it('releases child reads without publishing late results or affecting a sibling embedding', async () => {
  const held = deferred<{ total: number; list: { state: { id: string } }[] }>();
  const { engine, host, paged } = setup({
    instances: { instances: [], defaultInstanceId: null },
    host: {
      resolveSource: () => ({}),
      instance: { load: async () => instance('child') },
      definition: { load: async () => definition },
    },
  });
  host.resolveSource = () => ({ paged });
  paged.mockImplementationOnce(() => held.promise);
  await engine.load();
  const left = engine.openPosition(dashboard(), localDefinition);
  const right = engine.openPosition(dashboard(), localDefinition);
  try {
    const running = left.runtime.resume();
    await vi.waitFor(() => expect(paged).toHaveBeenCalledOnce());
    const child = left.getSnapshot().panels.a.position!;
    await right.runtime.resume();
    left.dispose();
    held.resolve({ total: 1, list: [{ state: { id: 'late' } }] });
    await running;
    expect(engine.getSnapshot().sessions[left.identity.id]).toBeUndefined();
    expect(engine.getSnapshot().sessions[child.identity.id]).toBeUndefined();
    expect(
      right.getSnapshot().panels.a.position!.getSnapshot().result?.rows,
    ).toEqual([{ state: { id: 'a', amount: 10 } }]);
    expect(right.getSnapshot().active).toBe(true);
  } finally {
    left.dispose();
    right.dispose();
    engine.dispose();
  }
});

it('accounts embedded metadata against the engine total and releases only the closed position', async () => {
  const saved = {
    ...dashboard(),
    config: { schemaVersion: 1 as const, panels: [], filters: [] },
  };
  const { engine } = setup({
    limits: {
      maxDashboardMetadataBytes:
        2 * new TextEncoder().encode(JSON.stringify(saved.config)).byteLength,
    },
  });
  await engine.load();
  const left = engine.openPosition(saved, localDefinition);
  try {
    expect(() => engine.openPosition(saved, localDefinition)).toThrow('元数据');
    expect(
      Object.keys(engine.getSnapshot().sessions).filter(id =>
        id.startsWith('position:'),
      ),
    ).toEqual([left.identity.id]);
    left.dispose();
    const reopened = engine.openPosition(saved, localDefinition);
    reopened.dispose();
  } finally {
    left.dispose();
    engine.dispose();
  }
});

it('suspends only the selected managed dashboard when navigating away from its embeddings', async () => {
  const saved = dashboard();
  saved.definitionId = definition.id;
  saved.config.filters = [];
  const root = { ...definition, dashboard: true as const };
  const { engine, host, paged } = setup({
    definition: root,
    instances: { instances: [saved, instance()], defaultInstanceId: saved.id },
    host: {
      resolveSource: () => ({}),
      instance: { load: async () => instance('child') },
      definition: { load: async () => definition },
    },
  });
  host.resolveSource = () => ({ paged });
  await engine.load();
  const managed = engine.dashboard(saved.id);
  await vi.waitFor(() =>
    expect(managed.getSnapshot().panels.a.position).toBeDefined(),
  );
  const left = engine.openPosition(saved, root);
  const right = engine.openPosition(saved, root);
  try {
    await Promise.all([left.runtime.resume(), right.runtime.resume()]);
    await engine.selectInstance('mine');
    expect(managed.getSnapshot().active).toBe(false);
    expect(left.getSnapshot().active).toBe(true);
    expect(right.getSnapshot().active).toBe(true);
    expect(
      left.getSnapshot().panels.a.position!.getSnapshot().queryStatus,
    ).toBe('success');
    expect(
      right.getSnapshot().panels.a.position!.getSnapshot().queryStatus,
    ).toBe('success');
  } finally {
    left.dispose();
    right.dispose();
    engine.dispose();
  }
});
