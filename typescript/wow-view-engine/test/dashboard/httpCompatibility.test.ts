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

// @vitest-environment node
import { expect, it, vi } from 'vitest';
import { ViewEngine } from '../../src/engine/ViewEngine.js';
import type { ViewHost } from '../../src/contracts/ViewHost.js';
import { MemoryViewHost } from '../../src/record/MemoryViewHost.js';
import { ViewServiceError } from '../../src/contracts/viewServiceContract.js';
import { HttpViewHost, VIEW_SERVICE_STATUS } from '../../dev/http/index.js';
import { startViewService } from '../../scripts/fixtures/view-service-server.mjs';
import { definition, instance } from '../engine/fixtures.js';
import type { DashboardViewInstance } from '../../src/dashboard/dashboardModel.js';

it('negotiates dashboard format across real HTTP list, order, single and deletion receipts', async () => {
  const dashboard: DashboardViewInstance = {
    id: 'dashboard',
    definitionId: definition.id,
    title: 'Dashboard',
    scope: { type: 'personal' },
    revision: 'v1',
    kind: 'dashboard',
    config: { schemaVersion: 1, panels: [], filters: [] },
  };
  const source = { paged: async () => ({ list: [], total: 0 }) };
  const server = await startViewService({
    Host: MemoryViewHost,
    ServiceError: ViewServiceError,
    statuses: VIEW_SERVICE_STATUS,
    definition: { ...definition, dashboard: true },
    instances: {
      instances: [instance('a'), dashboard, instance('b')],
      defaultInstanceId: 'dashboard',
    },
    source,
  });
  try {
    const options = {
      baseUrl: server.baseUrl,
      definitionId: definition.id,
      headers: () => ({ Authorization: 'Bearer alice-token' }),
      resolveSource: () => source,
    };
    const old = new HttpViewHost(options);
    const modern = new HttpViewHost({
      ...options,
      supportedFormats: { record: true, analysis: true, dashboard: 1 },
    });
    expect(
      (await old.instance.list(definition.id)).defaultInstanceId,
    ).toBeNull();
    await old.preference.saveOrder(definition.id, ['b', 'a']);
    expect(
      (await modern.instance.list(definition.id)).instances.map(
        item => item.id,
      ),
    ).toEqual(['b', 'dashboard', 'a']);
    const a = await old.instance.load('a');
    expect(await old.instance.delete('a', a.revision)).toEqual({
      defaultInstance: null,
    });
    expect((await modern.instance.list(definition.id)).defaultInstanceId).toBe(
      'dashboard',
    );
    await expect(old.instance.load('dashboard')).rejects.toMatchObject({
      code: 'UNSUPPORTED_FORMAT',
    });
    const saved = await modern.instance.load('dashboard');
    expect(
      (await modern.instance.rename(saved.id, 'New name', saved.revision))
        .title,
    ).toBe('New name');
  } finally {
    await server.close();
  }
});

it('composes definition-scoped HTTP clients for cross-definition dashboards without mixing permissions or writes', async () => {
  const root = {
    id: 'overview',
    title: 'Overview',
    fields: [],
    dashboard: true as const,
  };
  const dashboard: DashboardViewInstance = {
    id: 'dashboard',
    definitionId: root.id,
    title: 'Dashboard',
    kind: 'dashboard',
    scope: { type: 'personal' },
    revision: 'r1',
    config: {
      schemaVersion: 1,
      panels: [
        {
          kind: 'view',
          id: 'orders',
          instanceId: 'saved-orders',
          layout: { x: 0, y: 0, w: 12, h: 18 },
        },
      ],
      filters: [],
    },
  };
  const source = {
    paged: vi.fn(async () => ({
      total: 1,
      list: [{ state: { id: 'one', amount: 42 } }],
    })),
  };
  const servers = await Promise.all([
    startViewService({
      Host: MemoryViewHost,
      ServiceError: ViewServiceError,
      statuses: VIEW_SERVICE_STATUS,
      definition: root,
      instances: { instances: [dashboard], defaultInstanceId: dashboard.id },
      source,
    }),
    startViewService({
      Host: MemoryViewHost,
      ServiceError: ViewServiceError,
      statuses: VIEW_SERVICE_STATUS,
      definition,
      instances: {
        instances: [instance('saved-orders')],
        defaultInstanceId: 'saved-orders',
      },
      source,
    }),
  ]);
  let engine: ViewEngine | undefined;
  try {
    servers[0].setWriter('alice-token', false);
    servers[1].setWriter('alice-token', false);
    servers[1].setWriter('alice-token', true);
    const clients = servers.map(
      (server, index) =>
        new HttpViewHost({
          baseUrl: server.baseUrl,
          definitionId: index === 0 ? root.id : definition.id,
          headers: () => ({ Authorization: 'Bearer alice-token' }),
          supportedFormats: { record: true, analysis: true, dashboard: 1 },
          resolveSource: () => source,
        }),
    );
    const [overview, orders] = clients;
    await expect(overview.instance.load('saved-orders')).rejects.toMatchObject({
      code: 'NOT_FOUND',
    });
    await expect(overview.definition.load(definition.id)).rejects.toMatchObject(
      { code: 'NOT_FOUND' },
    );
    const definitions = new Map([
      [root.id, overview],
      [definition.id, orders],
    ]);
    const owners = new Map([
      [dashboard.id, overview],
      ['saved-orders', orders],
    ]);
    const clientFor = (registry: Map<string, HttpViewHost>, id: string) => {
      const client = registry.get(id);
      if (!client)
        throw new ViewServiceError('NOT_FOUND', 'Resource not registered');
      return client;
    };
    const host: ViewHost = {
      definition: {
        load: (id, signal) =>
          clientFor(definitions, id).definition.load(id, signal),
      },
      instance: {
        list: overview.instance.list,
        load: (id, signal) => clientFor(owners, id).instance.load(id, signal),
        create: overview.instance.create,
        save: overview.instance.save,
        rename: overview.instance.rename,
        delete: overview.instance.delete,
      },
      permission: overview.permission,
      preference: overview.preference,
      resolveSource: overview.resolveSource,
    };
    engine = new ViewEngine({ definitionId: root.id, host });
    await engine.load();
    const runtime = engine.dashboard(dashboard.id);
    await vi.waitFor(() =>
      expect(
        runtime.getSnapshot().panels.orders.position?.getSnapshot().queryStatus,
      ).toBe('success'),
    );
    expect(runtime.getSnapshot().panels.orders.definition?.id).toBe(
      definition.id,
    );
    expect(orders.permission.getDefinition().createShared).toBe(true);
    expect(engine.getCapabilitiesSnapshot().createShared).toBe(false);
    engine.setTitle('Updated overview', dashboard.id);
    await engine.save(dashboard.id);
    expect(servers[0].control.mutations).toBe(1);
    expect(servers[1].control.mutations).toBe(0);
    expect(source.paged).toHaveBeenCalledTimes(1);
  } finally {
    engine?.dispose();
    await Promise.all(servers.map(server => server.close()));
  }
});
