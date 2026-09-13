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

import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { ViewEngine } from '../../src/engine/ViewEngine.js';
import type * as DataViewModule from '../../src/view/DataViewContent.js';
import type { DashboardViewInstance } from '../../src/dashboard/dashboardModel.js';
import { ViewPageContent } from '../../src/view/ViewPageContent.js';
import { DashboardView } from '../../src/dashboard/DashboardView.js';
import { ViewServiceError } from '../../src/contracts/viewServiceContract.js';
import { dashboardSetup, globalFilter } from './runtimeFixtures.js';
import { instance } from '../engine/fixtures.js';

vi.hoisted(() =>
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      unobserve() {}
      disconnect() {}
    },
  ),
);
const renderFault = vi.hoisted(() => ({ broken: false }));
vi.mock('../../src/view/DataViewContent.js', async importOriginal => {
  const actual = await importOriginal<typeof DataViewModule>();
  return {
    ...actual,
    DataViewContent: (props: Parameters<typeof actual.DataViewContent>[0]) => {
      if (renderFault.broken) throw new Error('result rendering failed');
      return <actual.DataViewContent {...props} />;
    },
  };
});
afterEach(() => {
  cleanup();
  renderFault.broken = false;
});

it('denies definition capabilities and draft editing when the policy getter throws', async () => {
  let unavailable = false;
  const getDefinition = vi.fn(() => {
    if (unavailable) throw new Error('policy unavailable');
    return { createPersonal: true, createShared: true };
  });
  const { engine, host } = dashboardSetup(
    { schemaVersion: 1, panels: [], filters: [] },
    {
      permission: { getDefinition },
      instance: {
        create: async value => ({ ...value, id: 'saved', revision: 'r1' }),
      },
    },
  );
  await engine.load();
  const id = engine.createDashboard({
    title: 'Draft',
    scope: { type: 'personal' },
  });
  unavailable = true;
  engine.updateHost({ ...host });
  expect(engine.getCapabilitiesSnapshot()).toMatchObject({
    createPersonal: false,
    createShared: false,
  });
  expect(engine.getPermissions(id).save).toBe(false);
  expect(engine.dashboard(id).getSnapshot().editable).toBe(false);
  render(<ViewPageContent engine={engine} />);
  expect(screen.queryByRole('button', { name: '新建仪表盘' })).toBeNull();
  cleanup();
  engine.dispose();
});

it.each(['replace', 'reload'] as const)(
  'recovers a crashed panel after reference %s',
  async operation => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    renderFault.broken = true;
    const { engine, host } = dashboardSetup({
      schemaVersion: 1,
      panels: [
        {
          kind: 'view',
          id: 'a',
          instanceId: 'child',
          layout: { x: 0, y: 0, w: 6, h: 18 },
        },
      ],
      filters: [],
    });
    host.instance!.load = async id => instance(id);
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    render(<DashboardView runtime={runtime} />);
    await screen.findByText('面板内容无法显示。');
    renderFault.broken = false;
    await act(async () => {
      if (operation === 'reload') await runtime.reloadReference('a');
      else {
        runtime.edit(config => ({
          ...config,
          panels: config.panels.map(panel => ({
            ...panel,
            instanceId: 'replacement',
          })),
        }));
        await runtime.apply();
      }
    });
    await waitFor(() =>
      expect(screen.queryByText('面板内容无法显示。')).toBeNull(),
    );
    expect(
      await screen.findByRole('cell', { name: '10', exact: true }),
    ).toBeTruthy();
    cleanup();
    engine.dispose();
  },
);

it.each([false, true])(
  'preserves invalid editor buffers across reload with remote change=%s',
  async changed => {
    const { engine, host } = dashboardSetup({
      schemaVersion: 1,
      panels: [],
      filters: [{ ...globalFilter(), bindings: [], excludedPanelIds: [] }],
    });
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    const baseline = runtime.getSnapshot().session.baseline;
    runtime.setEditorValidity('filter:amount', false);
    const epoch = runtime.getSnapshot().session.editorEpoch;
    host.instance!.load = async () => ({
      ...baseline,
      title: changed ? 'Remote title' : baseline.title,
      revision: 'r2',
    });
    await engine.reloadInstance('dashboard');
    const session = runtime.getSnapshot().session;
    expect(session.editorValidity['filter:amount']).toBe(false);
    expect(session.editorEpoch).toBe(epoch);
    expect(session.dirty).toBe(true);
    expect(!!session.conflict).toBe(changed);
    engine.setTitle('Local title', 'dashboard');
    await expect(engine.save('dashboard')).rejects.toThrow();
    cleanup();
    engine.dispose();
  },
);

it('keeps navigation available when dashboard metadata cannot be reserved and recovers on reload', async () => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
  const { engine, host } = dashboardSetup(
    undefined,
    {},
    { maxDashboardMetadataBytes: 100 },
  );
  await engine.load();
  const baseline = engine.getSnapshot().sessions.dashboard.baseline;
  render(<ViewPageContent engine={engine} />);
  expect(screen.getByRole('main', { name: '视图工作区' })).toBeTruthy();
  expect(screen.getByText(/仪表盘恢复元数据/)).toBeTruthy();
  host.instance!.load = async () => ({
    ...baseline,
    kind: 'dashboard',
    config: { schemaVersion: 1, panels: [], filters: [] },
    revision: 'r2',
  });
  fireEvent.click(screen.getByRole('button', { name: '重新加载仪表盘' }));
  await waitFor(() =>
    expect(
      engine.getSnapshot().sessions.dashboard.instance.config.panels,
    ).toEqual([]),
  );
  await waitFor(() =>
    expect(
      screen.getByRole('main', { name: '视图工作区' }).textContent,
    ).toContain('仪表盘还没有面板'),
  );
  expect(screen.queryByRole('button', { name: '重新加载仪表盘' })).toBeNull();
  expect(engine.dashboard('dashboard').getSnapshot().active).toBe(true);
  cleanup();
  engine.dispose();
});

it('clears a definitive save error when restoring a dashboard', async () => {
  const { engine, host } = dashboardSetup({
    schemaVersion: 1,
    panels: [],
    filters: [],
  });
  host.instance!.save = async () => {
    throw new ViewServiceError('INVALID_ARGUMENT', 'save rejected');
  };
  await engine.load();
  engine.setTitle('Changed', 'dashboard');
  await expect(engine.save('dashboard')).rejects.toThrow('save rejected');
  expect(engine.getSnapshot().sessions.dashboard.requiresReload).toBe(false);
  expect(engine.getSnapshot().sessions.dashboard.writeError).toBeTruthy();
  await engine.restore('dashboard');
  expect(engine.getSnapshot().sessions.dashboard).toMatchObject({
    dirty: false,
    writeError: null,
  });
  cleanup();
  engine.dispose();
});

it('retries after another runtime releases the budget without requiring a persistence loader', async () => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
  const saved: DashboardViewInstance = {
    id: 'held',
    definitionId: 'root',
    kind: 'dashboard',
    title: 'Held',
    scope: { type: 'personal' },
    revision: 'r1',
    config: { schemaVersion: 1, panels: [], filters: [] },
  };
  const engine = new ViewEngine({
    definitionId: 'root',
    definition: { id: 'root', title: 'Root', fields: [], dashboard: true },
    instances: {
      instances: [saved, { ...saved, id: 'target', title: 'Target' }],
      defaultInstanceId: 'held',
    },
    host: { resolveSource: () => ({}) },
    limits: { maxDashboardMetadataBytes: 150 },
  });
  await engine.load();
  const held = engine.dashboard('held');
  await engine.selectInstance('target');
  render(<ViewPageContent engine={engine} />);
  expect(screen.getByText(/仪表盘恢复元数据/)).toBeTruthy();
  await act(async () => held.dispose());
  fireEvent.click(screen.getByRole('button', { name: '重试加载仪表盘' }));
  await screen.findByRole('heading', { name: '仪表盘还没有面板' });
  expect(engine.dashboard('target').getSnapshot().active).toBe(true);
  cleanup();
  engine.dispose();
});

it('does not resume the old dashboard after navigating away during recovery', async () => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
  const { engine, host } = dashboardSetup(
    undefined,
    {},
    { maxDashboardMetadataBytes: 100 },
  );
  await engine.load();
  const baseline = engine.getSnapshot().sessions.dashboard.baseline;
  const recovered: DashboardViewInstance = {
    ...baseline,
    kind: 'dashboard',
    config: { schemaVersion: 1, panels: [], filters: [] },
    revision: 'r2',
  };
  let complete!: (value: DashboardViewInstance) => void;
  const pending = new Promise<DashboardViewInstance>(resolve => {
    complete = resolve;
  });
  const load = vi.fn(async (id: string) =>
    id === 'dashboard' ? pending : { ...recovered, id, title: 'Other' },
  );
  host.instance!.load = load;
  render(<ViewPageContent engine={engine} />);
  fireEvent.click(screen.getByRole('button', { name: '重新加载仪表盘' }));
  await waitFor(() => expect(load).toHaveBeenCalled());
  await act(() => engine.selectInstance('other'));
  const openRuntime = vi.spyOn(engine, 'dashboard');
  await act(async () => {
    complete(recovered);
    await pending;
  });
  expect(openRuntime.mock.calls.some(([id]) => id === 'dashboard')).toBe(false);
  expect(engine.getSnapshot().selectedInstanceId).toBe('other');
  cleanup();
  engine.dispose();
});

it.each(['permission', 'permission-only', 'baseline', 'restore'] as const)(
  'ends layout editing across %s changes',
  async change => {
    let editable = true;
    let notifyPermissions = () => {};
    const { engine, host, paged } = dashboardSetup(undefined, {
      permission: {
        getInstance: () => ({ save: editable }),
        subscribe: listener => {
          notifyPermissions = listener;
          return () => {};
        },
      },
    });
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    render(<DashboardView runtime={runtime} />);
    await waitFor(() => expect(paged).toHaveBeenCalledTimes(2));
    fireEvent.click(screen.getByRole('button', { name: '编辑布局' }));
    expect(screen.getByRole('button', { name: '取消布局编辑' })).toBeTruthy();
    if (change === 'restore') {
      await act(async () => {
        engine.setTitle('Unsaved', 'dashboard');
        await engine.restore('dashboard');
      });
    } else {
      if (change === 'permission' || change === 'permission-only')
        await act(async () => {
          editable = false;
          notifyPermissions();
        });
      const baseline = runtime.getSnapshot().session.baseline;
      host.instance!.load = async id =>
        id === 'dashboard'
          ? {
              ...baseline,
              revision: 'r2',
              config: {
                ...baseline.config,
                panels: baseline.config.panels.map(panel => ({
                  ...panel,
                  layout: { ...panel.layout, w: 12 },
                })),
              },
            }
          : instance(id);
      if (change !== 'permission-only')
        await act(() => engine.reloadInstance('dashboard'));
      if (change === 'permission' || change === 'permission-only')
        await act(async () => {
          editable = true;
          notifyPermissions();
        });
    }
    expect(screen.queryByRole('button', { name: '取消布局编辑' })).toBeNull();
    expect(screen.getByRole('button', { name: '编辑布局' })).toBeTruthy();
    const baseline = runtime.getSnapshot().session.baseline;
    fireEvent.click(screen.getByRole('button', { name: '编辑布局' }));
    fireEvent.click(screen.getByRole('button', { name: '取消布局编辑' }));
    expect(runtime.getSnapshot().config.panels).toEqual(baseline.config.panels);
    expect(runtime.getSnapshot().session.dirty).toBe(false);
    cleanup();
    engine.dispose();
  },
);

it.each(['pending', 'forbidden'] as const)(
  'saves structurally valid configuration without dropping a %s reference',
  async state => {
    let release!: (value: ReturnType<typeof instance>) => void;
    const pending = new Promise<ReturnType<typeof instance>>(resolve => {
      release = resolve;
    });
    const { engine, host, paged } = dashboardSetup(undefined, {
      instance: {
        load: async () => {
          if (state === 'forbidden')
            throw new ViewServiceError('FORBIDDEN', 'reference unavailable');
          return pending;
        },
        save: vi.fn(async value => ({ ...value, revision: 'r2' })),
      },
    });
    await engine.load();
    const runtime = engine.dashboard('dashboard');
    if (state === 'forbidden')
      await waitFor(() =>
        expect(runtime.getSnapshot().panels.a.blocked).toBe(true),
      );
    else
      await waitFor(() =>
        expect(runtime.getSnapshot().panels.a.loading).toBe(true),
      );
    const panels = runtime.getSnapshot().config.panels;
    engine.setTitle('Updated dashboard', 'dashboard');
    await engine.save('dashboard');
    expect(host.instance!.save).toHaveBeenCalledWith(
      expect.objectContaining({
        title: 'Updated dashboard',
        config: expect.objectContaining({ panels }),
      }),
    );
    expect(paged).not.toHaveBeenCalled();
    engine.dispose();
    release(instance('child'));
  },
);

it('rejects saving a field mapping once loaded metadata proves it invalid', async () => {
  const item = globalFilter();
  item.bindings = item.bindings.map(binding => ({
    ...binding,
    fields: { 'state.amount': 'state.missing' },
  }));
  const { engine, host } = dashboardSetup({
    schemaVersion: 1,
    panels: [
      {
        kind: 'view',
        id: 'a',
        instanceId: 'child',
        layout: { x: 0, y: 0, w: 6, h: 18 },
      },
      {
        kind: 'view',
        id: 'b',
        instanceId: 'child',
        layout: { x: 6, y: 0, w: 6, h: 18 },
      },
    ],
    filters: [item],
  });
  await engine.load();
  const runtime = engine.dashboard('dashboard');
  await waitFor(() =>
    expect(runtime.getSnapshot().panels.a.definition).toBeTruthy(),
  );
  engine.setTitle('Changed', 'dashboard');
  await expect(engine.save('dashboard')).rejects.toThrow();
  expect(host.instance!.save).not.toHaveBeenCalled();
  engine.dispose();
});
