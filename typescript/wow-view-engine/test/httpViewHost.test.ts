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
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { setTimeout as delay } from 'node:timers/promises';
import {
  MemoryViewHost,
  ViewEngine,
  ViewServiceError,
} from '@ahoo-wang/fetcher-view-engine';
import {
  HttpViewHost,
  HttpViewTransport,
  HttpViewInstanceService,
  VIEW_SERVICE_STATUS,
} from '../dev/http/index.js';

import { startViewService } from '../scripts/fixtures/view-service-server.mjs';
import { definition, instance, setup } from './fixtures/viewPage.js';

let server: Awaited<ReturnType<typeof startViewService>>;
beforeEach(async () => {
  server = await startViewService({
    Host: MemoryViewHost,
    ServiceError: ViewServiceError,
    statuses: VIEW_SERVICE_STATUS,
    definition,
    instances: { instances: [instance], defaultInstanceId: instance.id },
    source: setup().host.resolveSource('orders'),
  });
});
afterEach(async () => {
  await server.close();
});
function client(token = 'alice-token', timeoutMs = 1000) {
  return new HttpViewHost({
    baseUrl: server.baseUrl,
    definitionId: definition.id,
    headers: () => ({ Authorization: `Bearer ${token}` }),
    timeoutMs,
    resolveSource: setup().host.resolveSource,
  });
}
it('returns the authoritative default through DELETE after another client reorders views', async () => {
  const host = client();
  const other = client();
  const first = await other.instance.create(
    { ...instance, title: 'First' },
    { requestId: 'first' },
  );
  const second = await other.instance.create(
    { ...instance, title: 'Second' },
    { requestId: 'second' },
  );
  const engine = new ViewEngine({ definitionId: definition.id, host });
  await engine.load();
  const revision = engine.getSnapshot().sessions[instance.id].baseline.revision;
  await other.preference.saveOrder(definition.id, [
    instance.id,
    second.id,
    first.id,
  ]);
  await engine.deleteInstance(instance.id);
  expect(engine.getSnapshot().defaultInstanceId).toBe(second.id);
  expect(
    (await host.instance.delete(instance.id, revision)).defaultInstance,
  ).toEqual(second);
  engine.dispose();
});
it('allows only the configured browser origin before preflight or writes', async () => {
  const endpoint = `${server.baseUrl}definitions/${definition.id}/instances`;
  for (const method of ['OPTIONS', 'POST']) {
    const rejected = await fetch(endpoint, {
      method,
      headers: {
        Origin: 'https://untrusted.example',
        Authorization: 'Bearer alice-token',
      },
    });
    expect(rejected.status).toBe(403);
    expect(rejected.headers.has('Access-Control-Allow-Origin')).toBe(false);
  }
  expect(server.control.mutations).toBe(0);
  const allowed = await fetch(endpoint, {
    method: 'OPTIONS',
    headers: { Origin: 'http://127.0.0.1:6006' },
  });
  expect(allowed.status).toBe(204);
  expect(allowed.headers.get('Access-Control-Allow-Origin')).toBe(
    'http://127.0.0.1:6006',
  );
  expect(allowed.headers.get('Vary')).toBe('Origin');
  // Node service clients do not send a browser Origin header.
  expect((await client().instance.list(definition.id)).instances).toHaveLength(
    1,
  );
});
it('executes JSON writes over HTTP with shared visibility, private isolation and typed errors', async () => {
  const alice = client(),
    bob = client('bob-token');
  const shared = await alice.instance!.create(
    { ...instance, title: '公共', scope: { type: 'public', source: 'shared' } },
    { requestId: 'shared' },
  );
  expect((await bob.instance!.load(shared.id)).title).toBe('公共');
  await expect(
    bob.instance!.rename(shared.id, '越权', shared.revision),
  ).rejects.toMatchObject({ code: 'FORBIDDEN' });
  const privateView = await alice.instance!.create(
    { ...instance, title: '私人' },
    { requestId: 'private' },
  );
  await expect(bob.instance!.load(privateView.id)).rejects.toMatchObject({
    code: 'NOT_FOUND',
  });
  await expect(
    client('outsider-token').instance!.load(shared.id),
  ).rejects.toMatchObject({ code: 'NOT_FOUND' });
  await expect(
    client('invalid-token').instance!.list(definition.id),
  ).rejects.toMatchObject({ code: 'UNAUTHENTICATED' });
  const renamed = await alice.instance!.rename(
    shared.id,
    '新名称',
    shared.revision,
  );
  await expect(alice.instance!.save(shared)).rejects.toMatchObject({
    code: 'REVISION_CONFLICT',
  });
  await alice.instance!.delete(renamed.id, renamed.revision);
  await expect(bob.instance!.load(renamed.id)).rejects.toMatchObject({
    code: 'NOT_FOUND',
  });
});
it('does not trust posted ownership, and stores each users order independently', async () => {
  const alice = client(),
    bob = client('bob-token');
  const privateView = await alice.instance!.create(
    { ...instance, ownerKey: 'bob' } as typeof instance,
    { requestId: 'owner-forgery' },
  );
  await expect(bob.instance!.load(privateView.id)).rejects.toMatchObject({
    code: 'NOT_FOUND',
  });
  const shared = await alice.instance!.create(
    { ...instance, scope: { type: 'public', source: 'shared' } },
    { requestId: 'public' },
  );
  const before = (await alice.instance!.list(definition.id)).instances.map(
    item => item.id,
  );
  const order = (await bob.instance!.list(definition.id)).instances
    .map(item => item.id)
    .reverse();
  await bob.preference!.saveOrder(definition.id, order);
  expect(
    (await bob.instance!.list(definition.id)).instances.map(item => item.id),
  ).toEqual(order);
  expect(
    (await alice.instance!.list(definition.id)).instances.map(item => item.id),
  ).toEqual(before);
  expect(order).toContain(shared.id);
});
it('round trips a private default preference over HTTP', async () => {
  const alice = client();
  const bob = client('bob-token');
  await alice.preference.saveDefault(definition.id, null);
  expect(
    (await client().instance.list(definition.id)).defaultInstanceId,
  ).toBeNull();
  expect((await bob.instance.list(definition.id)).defaultInstanceId).toBe(
    instance.id,
  );
  await alice.preference.saveDefault(definition.id, instance.id);
  expect((await client().instance.list(definition.id)).defaultInstanceId).toBe(
    instance.id,
  );
  const shared = await alice.instance.create(
    { ...instance, scope: { type: 'public', source: 'shared' } },
    { requestId: 'shared-default' },
  );
  expect(
    bob.permission.getInstance(await bob.instance.load(shared.id)).save,
  ).toBe(false);
  await bob.preference.saveDefault(definition.id, shared.id);
  expect((await bob.instance.list(definition.id)).defaultInstanceId).toBe(
    shared.id,
  );
  await expect(
    alice.preference.saveDefault(definition.id, 'unknown'),
  ).rejects.toMatchObject({ code: 'NOT_FOUND' });
});
it('retries a response-lost create through the real engine with the same idempotency key', async () => {
  const host = client();
  const engine = new ViewEngine({ definitionId: definition.id, host });
  await engine.load();
  const request = { title: '只创建一次', scope: { type: 'personal' } as const };
  server.control.dropNextCreateResponse = true;
  await expect(engine.saveAs(request)).rejects.toMatchObject({
    code: 'UNKNOWN_OUTCOME',
  });
  expect(
    (await host.instance!.list(definition.id)).instances.filter(
      item => item.title === request.title,
    ),
  ).toHaveLength(1);
  await expect(
    engine.saveAs({ ...request, title: '先不能改请求' }),
  ).rejects.toMatchObject({ code: 'UNKNOWN_OUTCOME' });
  await engine.saveAs(request);
  expect(
    (await host.instance!.list(definition.id)).instances.filter(
      item => item.title === request.title,
    ),
  ).toHaveLength(1);
  expect(
    engine.getSnapshot().sessions[engine.getSnapshot().selectedInstanceId!]
      .instance.title,
  ).toBe(request.title);
  engine.dispose();
});
it('reconciles an unknown create by reload and then allows a distinct create', async () => {
  const host = client();
  const engine = new ViewEngine({ definitionId: definition.id, host });
  await engine.load();
  server.control.dropNextCreateResponse = true;
  await expect(
    engine.saveAs({ title: '待核对副本', scope: { type: 'personal' } }),
  ).rejects.toMatchObject({ code: 'UNKNOWN_OUTCOME' });
  await engine.reloadInstance();
  const selected = engine.getSnapshot().selectedInstanceId!;
  expect(engine.getSnapshot().sessions[selected].instance.title).toBe(
    '待核对副本',
  );
  await engine.saveAs({ title: '下一次创建', scope: { type: 'personal' } });
  expect((await host.instance!.list(definition.id)).instances).toHaveLength(3);
  engine.dispose();
});
it.each(['save', 'rename', 'delete'] as const)(
  'blocks writes and retains edits after losing a committed %s response',
  async operation => {
    const method = { save: 'PUT', rename: 'PATCH', delete: 'DELETE' }[
      operation
    ];
    let dropResponse = true;
    const host = new HttpViewHost({
      baseUrl: server.baseUrl,
      definitionId: definition.id,
      headers: () => ({ Authorization: 'Bearer alice-token' }),
      resolveSource: setup().host.resolveSource,
      fetch: async (input, init) => {
        const response = await fetch(input, init);
        if (dropResponse && init?.method === method) {
          dropResponse = false;
          await response.body?.cancel();
          throw new TypeError('Connection lost after the service committed');
        }
        return response;
      },
    });
    const engine = new ViewEngine({ definitionId: definition.id, host });
    try {
      await engine.load();
      engine.setTitle('本地编辑');
      const session = () => engine.getSnapshot().sessions[instance.id];
      const revision = session().baseline.revision;
      await expect(
        operation === 'save'
          ? engine.save()
          : operation === 'rename'
            ? engine.renameInstance('服务端名称')
            : engine.deleteInstance(),
      ).rejects.toMatchObject({ code: 'UNKNOWN_OUTCOME' });
      expect(session()).toMatchObject({
        requiresReload: true,
        writeStatus: 'idle',
        instance: { title: '本地编辑' },
        baseline: { revision },
      });
      const mutations = server.control.mutations;
      await expect(engine.save()).rejects.toThrow('核对');
      await expect(engine.renameInstance('不能写入')).rejects.toThrow('核对');
      await expect(
        engine.saveAs({ title: '不能另存', scope: { type: 'personal' } }),
      ).rejects.toThrow('核对');
      expect(server.control.mutations).toBe(mutations);
      if (operation === 'delete') {
        await engine.deleteInstance();
        expect(session()).toBeUndefined();
        expect((await client().instance.list(definition.id)).instances).toEqual(
          [],
        );
      } else {
        await expect(engine.deleteInstance()).rejects.toThrow('核对');
        await engine.reloadInstance();
        expect(session().requiresReload).toBe(false);
        expect(session().baseline.revision).not.toBe(revision);
        expect(session().instance.title).toBe('本地编辑');
        engine.setTitle('核对后的编辑');
        await engine.save();
        expect((await client().instance.load(instance.id)).title).toBe(
          '核对后的编辑',
        );
      }
    } finally {
      engine.dispose();
    }
  },
);
it('forwards cancellation and distinguishes read timeout from an unknown write outcome', async () => {
  const host = client('alice-token', 50);
  server.control.delayNextRead = 200;
  await expect(host.instance!.list(definition.id)).rejects.toMatchObject({
    code: 'UNAVAILABLE',
  });
  await delay(30);
  expect(server.control.abortedReads).toBe(1);
  server.control.delayNextRead = 200;
  const controller = new AbortController();
  const pending = host.instance!.list(definition.id, controller.signal);
  const assertion = expect(pending).rejects.toMatchObject({
    name: 'AbortError',
  });
  await delay(10);
  controller.abort();
  await assertion;
  await delay(30);
  expect(server.control.abortedReads).toBe(2);
});
it('updates permission subscribers and prevents a delayed old snapshot from restoring revoked grants', async () => {
  const host = client();
  const shared = await host.instance!.create(
    { ...instance, scope: { type: 'public', source: 'shared' } },
    { requestId: 'shared' },
  );
  const engine = new ViewEngine({ definitionId: definition.id, host });
  await engine.load();
  expect(
    engine.getCapabilitiesSnapshot().instances[shared.id].permissions.save,
  ).toBe(true);
  const sessions = engine.getSnapshot().sessions;
  server.control.delayNextPermissionResponse = 300;
  const oldPermissions = host.permission!.refresh();
  await vi.waitFor(() =>
    expect(server.control.delayedPermissionResponses).toBe(1),
  );
  server.setWriter('alice-token', false);
  await host.permission!.refresh();
  await oldPermissions;
  expect(
    engine.getCapabilitiesSnapshot().instances[shared.id].permissions.save,
  ).toBe(false);
  expect(engine.getSnapshot().sessions).toBe(sessions);
  await expect(host.instance!.save(shared)).rejects.toMatchObject({
    code: 'FORBIDDEN',
  });
  engine.dispose();
});

it('keeps one create after timeout or cancellation after the server commit', async () => {
  const host = client('alice-token', 70);
  server.control.delayNextCreateResponse = 300;
  const input = { ...instance, title: '超时创建' };
  await expect(
    host.instance!.create(input, { requestId: 'timeout' }),
  ).rejects.toMatchObject({ code: 'UNKNOWN_OUTCOME' });
  const result = await host.instance!.create(input, { requestId: 'timeout' });
  expect(
    (await host.instance!.list(definition.id)).instances.filter(
      item => item.id === result.id,
    ),
  ).toHaveLength(1);
  const normal = client();
  const controller = new AbortController();
  server.control.delayNextCreateResponse = 300;
  const pending = normal.instance!.create(
    { ...input, title: '取消响应' },
    { requestId: 'canceled', signal: controller.signal },
  );
  const assertion = expect(pending).rejects.toMatchObject({
    code: 'UNKNOWN_OUTCOME',
  });
  await vi.waitFor(() => expect(server.control.delayedCreateResponses).toBe(2));
  controller.abort();
  await assertion;
  await normal.instance!.create(
    { ...input, title: '取消响应' },
    { requestId: 'canceled' },
  );
  expect(
    (await normal.instance!.list(definition.id)).instances.filter(
      item => item.title === '取消响应',
    ),
  ).toHaveLength(1);
});
it('performs competing HTTP writes with one authoritative winner', async () => {
  const left = client(),
    right = client();
  const old = await left.instance!.load(instance.id);
  const results = await Promise.allSettled([
    left.instance!.save({ ...old, title: 'first' }),
    right.instance!.save({ ...old, title: 'second' }),
  ]);
  expect(results.filter(item => item.status === 'fulfilled')).toHaveLength(1);
  expect(results.find(item => item.status === 'rejected')).toMatchObject({
    reason: { code: 'REVISION_CONFLICT' },
  });
});

it.each(['json', 'text', 'null'] as const)(
  'clears grants on %s session rejection and ignores older permission replies',
  async format => {
    let token = 'alice-token';
    const host = new HttpViewHost({
      baseUrl: server.baseUrl,
      definitionId: definition.id,
      headers: () => ({ Authorization: `Bearer ${token}` }),
      resolveSource: setup().host.resolveSource,
      fetch: async (input, init) => {
        const response = await fetch(input, init);
        if (response.status !== 401 || format === 'json') return response;
        await response.body?.cancel();
        return new Response(format === 'text' ? 'Session expired' : 'null', {
          status: 401,
        });
      },
    });
    const current = await host.instance!.load(instance.id);
    expect(host.permission!.getInstance(current).save).toBe(true);
    server.control.delayNextPermissionResponse = 300;
    const old = host.permission!.refresh();
    await vi.waitFor(() =>
      expect(server.control.delayedPermissionResponses).toBe(1),
    );
    token = 'expired';
    const rejected = await host.permission!.refresh().catch(error => error);
    const revoked = host.permission!.getInstance(current).save;
    await old;
    expect(revoked).toBe(false);
    expect(host.permission!.getInstance(current).save).toBe(false);
    expect(rejected).toMatchObject({ code: 'UNAUTHENTICATED' });
    token = 'alice-token';
    await host.permission!.refresh();
    expect(host.permission!.getInstance(current).save).toBe(true);
  },
);

it('returns stable protocol errors for malformed inputs and missing preconditions', async () => {
  const headers = {
    Authorization: 'Bearer alice-token',
    'Content-Type': 'application/json',
    'Idempotency-Key': 'invalid',
  };
  const response = await fetch(
    `${server.baseUrl}definitions/${definition.id}/instances`,
    { method: 'POST', headers, body: 'null' },
  );
  expect(response.status).toBe(400);
  expect((await response.json()).error.code).toBe('INVALID_ARGUMENT');
  const missing = await fetch(
    `${server.baseUrl}definitions/${definition.id}/instances/${instance.id}`,
    { method: 'DELETE', headers },
  );
  expect(missing.status).toBe(428);
  expect((await missing.json()).error.code).toBe('PRECONDITION_REQUIRED');

  const endpoint = `${server.baseUrl}definitions/${definition.id}/default`;
  for (const [body, status, code] of [
    ['{}', 400, 'INVALID_ARGUMENT'],
    ['{"instanceId":1}', 400, 'INVALID_ARGUMENT'],
  ] as const) {
    const mutations = server.control.mutations;
    const invalid = await fetch(endpoint, { method: 'PUT', headers, body });
    expect(invalid.status).toBe(status);
    expect((await invalid.json()).error.code).toBe(code);
    expect(server.control.mutations).toBe(mutations);
  }

  const bobPrivate = await client('bob-token').instance.create(
    { ...instance, title: 'Bob private' },
    { requestId: 'bob-private-default' },
  );
  for (const [token, instanceId, status, code] of [
    ['alice-token', bobPrivate.id, 404, 'NOT_FOUND'],
    ['invalid-token', instance.id, 401, 'UNAUTHENTICATED'],
  ] as const) {
    const mutations = server.control.mutations;
    const rejected = await fetch(endpoint, {
      method: 'PUT',
      headers: { ...headers, Authorization: `Bearer ${token}` },
      body: JSON.stringify({ instanceId }),
    });
    expect(rejected.status).toBe(status);
    expect((await rejected.json()).error.code).toBe(code);
    expect(server.control.mutations).toBe(mutations);
  }
});

it('composes independently supplied definition, instance and policy services', async () => {
  const remote = client();
  const loadDefinition = vi.fn(async () => structuredClone(definition));
  const localSource = setup().host.resolveSource;
  const host = {
    definition: { load: loadDefinition },
    instance: remote.instance,
    preference: remote.preference,
    permission: remote.permission,
    resolveSource: localSource,
  };
  const engine = new ViewEngine({ definitionId: definition.id, host });
  await engine.load();
  expect(loadDefinition).toHaveBeenCalledOnce();
  expect(engine.getSnapshot().status).toBe('ready');
  engine.setTitle('组合服务保存');
  await engine.save();
  expect((await remote.instance.load(instance.id)).title).toBe('组合服务保存');
  expect(remote).not.toHaveProperty('loadDefinition');
  expect(remote).not.toHaveProperty('saveInstance');
  engine.dispose();
});

it('uses the instance REST client without a ViewHost or runtime source resolver', async () => {
  const transport = new HttpViewTransport({
    baseUrl: server.baseUrl,
    definitionId: definition.id,
    headers: () => ({ Authorization: 'Bearer alice-token' }),
  });
  const instances = new HttpViewInstanceService(transport);
  const list = await instances.list(definition.id);
  expect(list.instances[0].id).toBe(instance.id);
  expect(transport.permission.getInstance(list.instances[0]).save).toBe(true);
  const saved = await instances.save({
    ...list.instances[0],
    title: '独立客户端',
  });
  expect((await instances.load(saved.id)).title).toBe('独立客户端');
});

it('loads the permission resource directly and shares its projection with instance clients', async () => {
  const host = client();
  const snapshot = await host.permission.load(definition.id);
  expect(snapshot.instances[instance.id].save).toBe(true);
  expect(host.permission.getInstance(instance).save).toBe(true);
  await expect(host.permission.load('wrong-definition')).rejects.toMatchObject({
    code: 'NOT_FOUND',
  });
});

it('rejects permission payloads that bypass the accepted snapshot', async () => {
  const host = new HttpViewHost({
    baseUrl: server.baseUrl,
    definitionId: definition.id,
    headers: () => ({ Authorization: 'Bearer alice-token' }),
    resolveSource: setup().host.resolveSource,
    fetch: async (input, init) => {
      const response = await fetch(input, init);
      const envelope = await response.json();
      return Response.json({ ...envelope, data: null });
    },
  });
  await expect(host.permission.load(definition.id)).rejects.toMatchObject({
    code: 'UNAVAILABLE',
  });
});

it.each(['revocation', 'session'] as const)(
  'rejects a stale permission return value after %s',
  async reason => {
    let token = 'alice-token';
    const host = new HttpViewHost({
      baseUrl: server.baseUrl,
      definitionId: definition.id,
      headers: () => ({ Authorization: `Bearer ${token}` }),
      resolveSource: setup().host.resolveSource,
    });
    const shared = await host.instance.create(
      { ...instance, scope: { type: 'public', source: 'shared' } },
      { requestId: 'permission-race' },
    );
    server.control.delayNextPermissionResponse = 150;
    const old = host.permission.load(definition.id).catch(error => error);
    await vi.waitFor(() =>
      expect(server.control.delayedPermissionResponses).toBe(1),
    );
    if (reason === 'revocation') {
      server.setWriter('alice-token', false);
      await host.permission.load(definition.id);
    } else {
      token = 'expired';
      await expect(host.permission.load(definition.id)).rejects.toMatchObject({
        code: 'UNAUTHENTICATED',
      });
    }
    expect(await old).toMatchObject({ code: 'UNAVAILABLE' });
    expect(host.permission.getInstance(shared).save).toBe(false);
  },
);

it('preserves the configured source resolver receiver after facade composition', () => {
  const source = setup().host.resolveSource('orders');
  const options = {
    baseUrl: server.baseUrl,
    definitionId: definition.id,
    resolveSource(id: string) {
      expect(id).toBe('orders');
      expect(this.definitionId).toBe(definition.id);
      return source;
    },
  };
  const host = new HttpViewHost(options);
  expect(host.resolveSource('orders')).toBe(source);
  const resolve = host.resolveSource;
  options.definitionId = 'changed-after-construction';
  expect(resolve('orders')).toBe(source);
});

it.each(['', ' ', '.', '..', '\ud800', null, 1])(
  'rejects invalid resource ID %j before dispatching any request',
  async input => {
    const id = input as string;
    const request = vi.fn<typeof fetch>(async () =>
      Response.json({
        data: instance,
        permissions: { revision: 1, instances: {}, reorder: false },
      }),
    );
    const options = {
      baseUrl: server.baseUrl,
      definitionId: definition.id,
      fetch: request,
      resolveSource: setup().host.resolveSource,
    };
    const host = new HttpViewHost(options);
    const results = await Promise.allSettled([
      host.instance.load(id),
      host.instance.save({ ...instance, id, revision: 'r1' }),
      host.instance.rename(id, 'title', 'r1'),
      host.instance.delete(id, 'r1'),
      host.instance.list(id),
      host.definition.load(id),
      host.preference.saveOrder(id, []),
      host.preference.saveDefault(id, null),
      host.permission.load(id),
    ]);
    expect(
      results.every(
        result =>
          result.status === 'rejected' &&
          result.reason.code === 'INVALID_ARGUMENT',
      ),
    ).toBe(true);
    expect(request).not.toHaveBeenCalled();
    expect(() => new HttpViewHost({ ...options, definitionId: id })).toThrow(
      expect.objectContaining({ code: 'INVALID_ARGUMENT' }),
    );
  },
);

it('encodes valid resource IDs exactly once without changing the requested resource', async () => {
  for (const id of ['订单 /%?#', '%2e%2e', 'orders.v1']) {
    const request = vi.fn<typeof fetch>(async () =>
      Response.json({
        data: { ...instance, id, definitionId: id },
        permissions: { revision: 1, instances: {}, reorder: false },
      }),
    );
    const host = new HttpViewHost({
      baseUrl: server.baseUrl,
      definitionId: id,
      fetch: request,
      resolveSource: setup().host.resolveSource,
    });
    expect((await host.instance.load(id)).id).toBe(id);
    await host.preference.saveDefault(id, null);
    const root = `/view-service/definitions/${encodeURIComponent(id)}`;
    expect(new URL(request.mock.calls[0][0] as string).pathname).toBe(
      `${root}/instances/${encodeURIComponent(id)}`,
    );
    expect(new URL(request.mock.calls[1][0] as string).pathname).toBe(
      `${root}/default`,
    );
  }
});
