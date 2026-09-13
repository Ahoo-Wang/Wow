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
import { definition as dataDefinition } from '../engine/fixtures.js';
import { globalFilter } from './runtimeFixtures.js';
import { ViewEngine } from '../../src/engine/ViewEngine.js';
import { MemoryViewHost } from '../../src/record/MemoryViewHost.js';
import { ViewServiceError } from '../../src/contracts/viewServiceContract.js';
import type { ViewHost } from '../../src/contracts/ViewHost.js';
import type { DashboardSession } from '../../src/dashboard/dashboardModel.js';

function setup(withFields = false) {
  const definition = {
    id: 'dashboards',
    title: 'Dashboards',
    fields: withFields ? dataDefinition.fields : [],
    dashboard: true as const,
  };
  const memory = new MemoryViewHost({
    definition,
    instances: { instances: [], defaultInstanceId: null },
    serviceKey: 'tenant',
    scopeKey: 'alice',
    supportedFormats: { record: true, analysis: true, dashboard: 1 },
    definitionPermissions: () => ({
      createPersonal: true,
      createShared: false,
    }),
    resolveSource: vi.fn(() => {
      throw new Error('Dashboard must not resolve a query source');
    }),
  });
  const create = vi.fn(memory.instance.create);
  const host: ViewHost = {
    definition: memory.definition,
    instance: { ...memory.instance, create },
    permission: memory.permission,
    resolveSource: memory.resolveSource.bind(memory),
  };
  const engine = new ViewEngine({ definitionId: definition.id, host });
  return { memory, create, engine };
}
const draft = (engine: ViewEngine, id: string) =>
  engine.getSnapshot().sessions[id] as DashboardSession;
describe('dashboard persistence through public engine', () => {
  it('creates a local draft without a fake saved list item, then creates once on save', async () => {
    const { engine, create, memory } = setup();
    await engine.load();
    const id = engine.createDashboard({
      title: 'Overview',
      scope: { type: 'personal' },
    });
    expect(create).not.toHaveBeenCalled();
    expect(engine.getSnapshot().instanceIds).toEqual([]);
    expect(draft(engine, id)).toMatchObject({ persisted: false, dirty: true });
    expect((await memory.instance.list('dashboards')).instances).toEqual([]);
    engine.dashboard(id).edit(config => ({
      ...config,
      panels: [
        {
          kind: 'markdown',
          id: 'notes',
          title: 'Notes',
          content: '# Saved notes',
          layout: { x: 0, y: 0, w: 6, h: 4 },
        },
      ],
    }));
    await engine.save(id);
    const savedId = engine.getSnapshot().selectedInstanceId!;
    expect(savedId).not.toBe(id);
    expect((await memory.instance.load(savedId)).config).toMatchObject({
      panels: [{ kind: 'markdown', content: '# Saved notes' }],
    });
    expect(draft(engine, savedId)).toMatchObject({
      persisted: true,
      dirty: false,
    });
    expect(engine.getSnapshot().sessions[id]).toBeUndefined();
    expect(create).toHaveBeenCalledTimes(1);
    engine.dispose();
  });
  it('keeps edits made while creation is in flight and restores the authoritative baseline', async () => {
    const { engine, create, memory } = setup();
    let release!: () => void;
    const gate = new Promise<void>(resolve => {
      release = resolve;
    });
    create.mockImplementationOnce(async (input, context) => {
      const saved = await memory.instance.create(input, context);
      await gate;
      return saved;
    });
    await engine.load();
    const id = engine.createDashboard({
      title: 'Submitted',
      scope: { type: 'personal' },
    });
    const saving = engine.save(id);
    await vi.waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    engine.setTitle('Edited in flight', id);
    release();
    await saving;
    const savedId = engine.getSnapshot().selectedInstanceId!;
    expect(draft(engine, savedId).instance.title).toBe('Edited in flight');
    expect(draft(engine, savedId).baseline.title).toBe('Submitted');
    await engine.restore(savedId);
    expect(draft(engine, savedId).instance.title).toBe('Submitted');
    engine.dispose();
  });
  it('preserves a revision conflict and requires explicit remote acceptance', async () => {
    const { engine, memory } = setup();
    await engine.load();
    const id = engine.createDashboard({
      title: 'Original',
      scope: { type: 'personal' },
    });
    await engine.save(id);
    const savedId = engine.getSnapshot().selectedInstanceId!;
    const saved = await memory.instance.load(savedId);
    await memory.instance.save({ ...saved, title: 'Remote' });
    engine.setTitle('Local', savedId);
    await expect(engine.save(savedId)).rejects.toMatchObject({
      code: 'REVISION_CONFLICT',
    });
    await engine.reloadInstance(savedId);
    const session = draft(engine, savedId);
    expect(session.instance.title).toBe('Local');
    expect(session.conflict?.remote.title).toBe('Remote');
    expect(session.conflict?.filterDraft).toBeUndefined();
    await engine.useRemoteInstance(session.conflict!, savedId);
    expect(draft(engine, savedId).instance.title).toBe('Remote');
    engine.dispose();
  });
  it('retains an uncertain draft across full reload without treating it as persisted', async () => {
    const { engine, create, memory } = setup();
    create.mockImplementationOnce(async (input, context) => {
      await memory.instance.create(input, context);
      throw new ViewServiceError('UNKNOWN_OUTCOME', 'lost');
    });
    await engine.load();
    const id = engine.createDashboard({
      title: 'Recover after load',
      scope: { type: 'personal' },
    });
    await expect(engine.save(id)).rejects.toThrow();
    await engine.load();
    expect(engine.getSnapshot().pendingCreates[id]).toMatchObject({
      persisted: false,
      requiresReload: true,
    });
    await engine.reloadInstance(id);
    expect(engine.getSnapshot().pendingCreates[id]).toBeUndefined();
    expect((await memory.instance.list('dashboards')).instances).toHaveLength(
      1,
    );
    engine.dispose();
  });
  it.each([false, true])(
    'preserves edits across unknown create, full load and recovery (target edited: %s)',
    async editedTarget => {
      const { engine, create, memory } = setup();
      create.mockImplementationOnce(async (input, context) => {
        await memory.instance.create(input, context);
        throw new ViewServiceError('UNKNOWN_OUTCOME', 'lost');
      });
      await engine.load();
      const id = engine.createDashboard({
        title: 'Submitted',
        scope: { type: 'personal' },
      });
      await expect(engine.save(id)).rejects.toThrow();
      engine.setTitle('Local edit', id);
      await engine.load();
      const savedId = engine.getSnapshot().instanceIds[0];
      if (editedTarget) {
        engine.setTitle('Other edit', savedId);
        await expect(engine.reloadInstance(id)).rejects.toThrow('两份');
        expect(engine.getSnapshot().pendingCreates[id]?.instance.title).toBe(
          'Local edit',
        );
        expect(draft(engine, savedId).instance.title).toBe('Other edit');
      } else {
        await engine.reloadInstance(id);
        expect(draft(engine, savedId)).toMatchObject({
          instance: { title: 'Local edit' },
          baseline: { title: 'Submitted' },
          dirty: true,
        });
        expect(engine.getSnapshot().pendingCreates[id]).toBeUndefined();
      }
      engine.dispose();
    },
  );
  it('recovers a lost create response with the original requestId', async () => {
    const { engine, create, memory } = setup();
    create.mockImplementationOnce(async (input, context) => {
      await memory.instance.create(input, context);
      throw new ViewServiceError('UNKNOWN_OUTCOME', 'lost');
    });
    await engine.load();
    const id = engine.createDashboard({
      title: 'Recover',
      scope: { type: 'personal' },
    });
    await expect(engine.save(id)).rejects.toThrow();
    expect(draft(engine, id).requiresReload).toBe(true);
    await engine.reloadInstance(id);
    expect(create).toHaveBeenCalledTimes(2);
    expect(create.mock.calls[1][1].requestId).toBe(
      create.mock.calls[0][1].requestId,
    );
    expect((await memory.instance.list('dashboards')).instances).toHaveLength(
      1,
    );
    expect(engine.getSnapshot().instanceIds).toHaveLength(1);
    expect(engine.getSnapshot().sessions[id]).toBeUndefined();
    engine.dispose();
  });
});

it('rejects dashboard creation without a create service before changing selection', async () => {
  const engine = new ViewEngine({
    definitionId: 'root',
    definition: { id: 'root', title: 'Root', fields: [], dashboard: true },
    instances: { instances: [], defaultInstanceId: null },
    host: {
      resolveSource: () => {
        throw new Error('unused');
      },
      permission: {
        getDefinition: () => ({ createPersonal: true, createShared: true }),
      },
    },
  });
  await engine.load();
  const before = engine.getSnapshot();
  for (const scope of [
    { type: 'personal' },
    { type: 'public', source: 'shared' },
  ] as const)
    expect(() => engine.createDashboard({ title: 'Draft', scope })).toThrow(
      '创建服务',
    );
  expect(engine.getSnapshot()).toBe(before);
  engine.dispose();
});

it.each([false, true])(
  'preserves editor validity changed during dashboard creation (lost receipt: %s)',
  async lost => {
    const { engine, create, memory } = setup(true);
    let release!: () => void;
    const gate = new Promise<void>(resolve => {
      release = resolve;
    });
    create.mockImplementationOnce(async (input, context) => {
      const saved = await memory.instance.create(input, context);
      await gate;
      if (lost) throw new ViewServiceError('UNKNOWN_OUTCOME', 'lost');
      return saved;
    });
    await engine.load();
    const id = engine.createDashboard({
      title: 'Draft',
      scope: { type: 'personal' },
    });
    const runtime = engine.dashboard(id);
    runtime.edit(config => ({
      ...config,
      filters: [{ ...globalFilter(), bindings: [], excludedPanelIds: [] }],
    }));
    const saving = engine.save(id);
    await vi.waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    runtime.setEditorValidity('filter:amount', false);
    release();
    if (lost) {
      await expect(saving).rejects.toThrow('lost');
      await engine.reloadInstance(id);
    } else await saving;
    const savedId = engine.getSnapshot().selectedInstanceId!;
    expect(savedId).not.toBe(id);
    expect(draft(engine, savedId)).toMatchObject({
      editorValidity: { 'filter:amount': false },
      dirty: true,
    });
    await expect(engine.save(savedId)).rejects.toThrow();
    const savedRuntime = engine.dashboard(savedId);
    expect(savedRuntime.getSnapshot().validation).toContainEqual({
      id: 'filter:amount',
      message: '编辑输入无效',
    });
    savedRuntime.setEditorValidity('filter:amount', true);
    expect(draft(engine, savedId).validation).toEqual([]);
    engine.dispose();
  },
);
