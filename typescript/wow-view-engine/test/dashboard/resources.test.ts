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
import { dashboardSetup, globalFilter } from './runtimeFixtures.js';

it('honors a raised panel limit consistently at load, edit and save admission', async () => {
  const config = {
    schemaVersion: 1 as const,
    filters: [],
    panels: Array.from({ length: 13 }, (_, i) => ({
      kind: 'view' as const,
      id: String(i),
      instanceId: 'child',
      layout: { x: 0, y: 0, w: 6, h: 18 },
    })),
  };
  const { engine, paged } = dashboardSetup(
    config,
    {},
    { maxDashboardPanels: 13 },
  );
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(13));
  expect(runtime.getSnapshot().validation).toEqual([]);
  runtime.edit(current => ({
    ...current,
    panels: [...current.panels].reverse(),
  }));
  await expect(engine.save('dashboard')).resolves.toBeUndefined();
  engine.dispose();
});
it('supports all 32 configured global items without artificial nested AND depth', async () => {
  const config = {
    schemaVersion: 1 as const,
    panels: [
      {
        kind: 'view' as const,
        id: 'a',
        instanceId: 'child',
        layout: { x: 0, y: 0, w: 12, h: 18 },
      },
    ],
    filters: Array.from({ length: 32 }, (_, i) => ({
      ...globalFilter(i),
      id: String(i),
      bindings: globalFilter().bindings.slice(0, 1),
    })),
  };
  const { engine, paged } = dashboardSetup(config);
  await engine.load();
  await vi.waitFor(() => expect(paged).toHaveBeenCalledOnce());
  expect(engine.dashboard('dashboard').getSnapshot().panels.a.blocked).toBe(
    false,
  );
  engine.dispose();
});
it('rejects oversized result bytes before publishing them', async () => {
  const { engine, paged } = dashboardSetup(
    undefined,
    {},
    { maxDashboardResultBytes: 100 },
  );
  await engine.load();
  await vi.waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
  await vi.waitFor(() =>
    expect(
      engine
        .dashboard('dashboard')
        .getSnapshot()
        .panels.a.position!.getSnapshot().queryStatus,
    ).toBe('error'),
  );
  const positions = Object.values(
    engine.dashboard('dashboard').getSnapshot().panels,
  ).map(panel => panel.position!);
  expect(
    positions.every(position => position.getSnapshot().result === null),
  ).toBe(true);
  engine.dispose();
});
it('rejects reference metadata above the budget without discarding the dashboard draft', async () => {
  const { engine, paged } = dashboardSetup(
    undefined,
    {},
    { maxDashboardMetadataBytes: 600 },
  );
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  await vi.waitFor(() =>
    expect(runtime.getSnapshot().panels.a.blocked).toBe(true),
  );
  expect(runtime.getSnapshot().config.panels).toHaveLength(2);
  expect(runtime.getSnapshot().panels.a.error).toContain('元数据');
  expect(paged).not.toHaveBeenCalled();
  engine.dispose();
});

it('releases cleared reference metadata even when the reload fails', async () => {
  const { instance, definition } = await import('../engine/fixtures.js');
  const a = {
    kind: 'view' as const,
    id: 'a',
    instanceId: 'child',
    layout: { x: 0, y: 0, w: 6, h: 18 },
  };
  const b = {
    kind: 'view' as const,
    id: 'b',
    instanceId: 'other',
    layout: { x: 6, y: 0, w: 6, h: 18 },
  };
  const config = { schemaVersion: 1 as const, panels: [a], filters: [] };
  const bytes = (value: unknown) =>
    new TextEncoder().encode(JSON.stringify(value)).byteLength;
  const { engine, load } = dashboardSetup(
    config,
    {},
    {
      maxDashboardMetadataBytes:
        bytes({ ...config, panels: [a, b] }) * 2 +
        bytes(instance('other')) +
        bytes(definition),
    },
  );
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  await vi.waitFor(() =>
    expect(runtime.getSnapshot().panels.a.status).toBe('ready'),
  );
  load.mockImplementation(async () => {
    throw new Error('temporary failure');
  });
  await runtime.reloadReference('a');
  expect(runtime.getSnapshot().panels.a.instance).toBeUndefined();
  load.mockImplementation(async (...args: unknown[]) => {
    if (args[0] === 'child') throw new Error('temporary failure');
    return instance('other');
  });
  runtime.edit(current => ({ ...current, panels: [a, b] }));
  await vi.waitFor(() =>
    expect(runtime.getSnapshot().panels.b.status).toBe('ready'),
  );
  engine.dispose();
});

const contentConfig = (length: number) => ({
  schemaVersion: 1 as const,
  panels: length
    ? [
        {
          kind: 'markdown' as const,
          id: 'note',
          title: 'Note',
          content: 'x'.repeat(length),
          layout: { x: 0, y: 0, w: 6, h: 8 },
        },
      ]
    : [],
  filters: [],
});
const rootDefinition = {
  id: 'root',
  title: 'Root',
  fields: [],
  dashboard: true as const,
};

it('accounts for growing and shrinking content configurations received by reload', async () => {
  const { engine, host } = dashboardSetup(
    contentConfig(0),
    {},
    { maxDashboardMetadataBytes: 900 },
  );
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  const baseline = runtime.getSnapshot().session.baseline;
  let remote = contentConfig(200);
  host.instance!.load = async () => ({
    ...baseline,
    revision: 'remote',
    config: remote,
  });
  await engine.reloadInstance('dashboard');
  expect(runtime.getSnapshot().config).toEqual(remote);
  expect(() =>
    engine.openPosition(
      { ...baseline, id: 'probe', config: contentConfig(100) },
      rootDefinition,
    ),
  ).toThrow('元数据');
  remote = contentConfig(0);
  await engine.reloadInstance('dashboard');
  const probe = engine.openPosition(
    { ...baseline, id: 'probe', config: contentConfig(100) },
    rootDefinition,
  );
  probe.dispose();
  engine.dispose();
});

it('releases removed reference metadata when a remote reload replaces it with content', async () => {
  const { instance, definition } = await import('../engine/fixtures.js');
  const config = {
    schemaVersion: 1 as const,
    panels: [
      {
        kind: 'view' as const,
        id: 'a',
        instanceId: 'child',
        layout: { x: 0, y: 0, w: 6, h: 18 },
      },
    ],
    filters: [],
  };
  const bytes = (value: unknown) =>
    new TextEncoder().encode(JSON.stringify(value)).byteLength;
  const { engine, host } = dashboardSetup(
    config,
    {},
    {
      maxDashboardMetadataBytes:
        bytes(config) * 2 + bytes(instance('child')) + bytes(definition) + 32,
    },
  );
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  await vi.waitFor(() =>
    expect(runtime.getSnapshot().panels.a.status).toBe('ready'),
  );
  const baseline = runtime.getSnapshot().session.baseline;
  host.instance!.load = async () => ({
    ...baseline,
    revision: 'remote',
    config: contentConfig(0),
  });
  await engine.reloadInstance('dashboard');
  const probe = engine.openPosition(
    { ...baseline, id: 'probe', config: contentConfig(0) },
    rootDefinition,
  );
  probe.dispose();
  engine.dispose();
});

it('keeps the admitted runtime configuration on an oversized reload and retries after budget release', async () => {
  const { engine, host } = dashboardSetup(
    contentConfig(0),
    {},
    { maxDashboardMetadataBytes: 900 },
  );
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  const before = runtime.getSnapshot().config;
  const baseline = runtime.getSnapshot().session.baseline;
  const held = engine.openPosition(
    { ...baseline, id: 'held', config: contentConfig(100) },
    rootDefinition,
  );
  host.instance!.load = async () => ({
    ...baseline,
    revision: 'remote',
    config: contentConfig(200),
  });
  await engine.reloadInstance('dashboard');
  expect(runtime.getSnapshot().error).toContain('元数据');
  expect(runtime.getSnapshot().config).toBe(before);
  expect(runtime.getSnapshot().editable).toBe(false);
  expect(() => runtime.edit(config => ({ ...config, panels: [] }))).toThrow(
    '元数据',
  );
  held.dispose();
  await runtime.apply();
  expect(runtime.getSnapshot().config).toEqual(contentConfig(200));
  expect(runtime.getSnapshot().error).toBeNull();
  expect(runtime.getSnapshot().editable).toBe(true);
  engine.dispose();
});

it('clears temporary invalid input on restore even when the saved configuration identity is unchanged', async () => {
  const { engine } = dashboardSetup(
    {
      schemaVersion: 1,
      panels: [],
      filters: [{ ...globalFilter(), bindings: [] }],
    },
    { permission: { getInstance: () => ({ save: false }) } },
  );
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  runtime.setEditorValidity('filter:amount', false);
  expect(runtime.getSnapshot().validation.length).toBeGreaterThan(0);
  await engine.restore('dashboard');
  expect(runtime.getSnapshot().validation).toEqual([]);
  engine.dispose();
});

it.each(['filter', 'transform'] as const)(
  'accepts a valid configuration with a long %s editor key',
  async kind => {
    const { dashboardEditorKey } =
      await import('../../src/dashboard/dashboardEditorKey.js');
    const id = 'f'.repeat(5000);
    const panelId = 'p'.repeat(1000);
    const item = {
      ...globalFilter(),
      id,
      bindings:
        kind === 'filter'
          ? []
          : [{ kind: 'transform' as const, panelId, name: 'custom' }],
      excludedPanelIds: [],
    };
    const { engine } = dashboardSetup({
      schemaVersion: 1,
      panels:
        kind === 'filter'
          ? []
          : [
              {
                kind: 'view',
                id: panelId,
                instanceId: 'child',
                layout: { x: 0, y: 0, w: 6, h: 18 },
              },
            ],
      filters: [item],
    });
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    const key = dashboardEditorKey(
      id,
      kind === 'transform' ? panelId : undefined,
    );
    expect(key.length).toBeGreaterThan(4096);
    expect(() => runtime.setEditorValidity(key, false)).not.toThrow();
    expect(runtime.getSnapshot().session.editorValidity[key]).toBe(false);
    await expect(engine.save('dashboard')).rejects.toThrow('编辑输入无效');
    engine.dispose();
  },
);
