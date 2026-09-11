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

import { beforeEach, expect, it, vi } from 'vitest';
import { MemoryViewHost } from '../src/record/MemoryViewHost.js';
import { ViewEngine } from '../src/engine/ViewEngine.js';
import { definition, instance, setup } from './fixtures/viewPage.js';

const store = new Map<string, string | null>();
beforeEach(() => store.clear());
function options(scopeKey = 'alice') {
  return {
    serviceKey: 'tenant',
    scopeKey,
    store,
    definition,
    instances: {
      instances: [
        instance,
        {
          ...instance,
          id: 'system',
          scope: { type: 'public', source: 'system' } as const,
        },
      ],
      defaultInstanceId: instance.id,
    },
    resolveSource: setup().host.resolveSource,
  };
}
it('shares public content while isolating personal views, user ordering and tenants', async () => {
  const alice = new MemoryViewHost(options());
  const bob = new MemoryViewHost(options('bob'));
  const shared = await alice.instance!.create(
    { ...instance, title: '共享', scope: { type: 'public', source: 'shared' } },
    { requestId: 'shared' },
  );
  const privateView = await alice.instance!.create(
    { ...instance, title: '私人' },
    { requestId: 'personal' },
  );
  expect((await bob.instance!.load(shared.id)).title).toBe('共享');
  await expect(bob.instance!.load(privateView.id)).rejects.toMatchObject({
    code: 'NOT_FOUND',
  });
  await bob.instance!.rename(shared.id, '共同更新', shared.revision);
  expect((await alice.instance!.load(shared.id)).title).toBe('共同更新');
  const bobIds = (await bob.instance!.list(definition.id)).instances
    .map(item => item.id)
    .reverse();
  const aliceIds = (await alice.instance!.list(definition.id)).instances.map(
    item => item.id,
  );
  await bob.preference!.saveOrder(definition.id, bobIds);
  expect(
    (await bob.instance!.list(definition.id)).instances.map(item => item.id),
  ).toEqual(bobIds);
  expect(
    (await alice.instance!.list(definition.id)).instances.map(item => item.id),
  ).toEqual(aliceIds);
  const isolated = new MemoryViewHost({
    ...options(),
    serviceKey: 'other-tenant',
  });
  await expect(isolated.instance!.load(shared.id)).rejects.toMatchObject({
    code: 'NOT_FOUND',
  });
});
it('serializes competing writes and rejects the stale revision inside the lock', async () => {
  const left = new MemoryViewHost(options()),
    right = new MemoryViewHost(options());
  const old = await left.instance!.load(instance.id);
  const results = await Promise.allSettled([
    left.instance!.save({ ...old, title: 'A' }),
    right.instance!.save({ ...old, title: 'B' }),
  ]);
  expect(results.filter(item => item.status === 'fulfilled')).toHaveLength(1);
  expect(results.find(item => item.status === 'rejected')).toMatchObject({
    reason: { code: 'REVISION_CONFLICT' },
  });
});
it('replays a create receipt across host reconstruction and refuses request ID payload reuse', async () => {
  const host = new MemoryViewHost(options());
  const input = { ...instance, title: '幂等创建' };
  const first = await host.instance!.create(input, {
    requestId: 'stable-request',
  });
  const replay = await new MemoryViewHost(options()).instance!.create(input, {
    requestId: 'stable-request',
  });
  expect(replay).toEqual(first);
  expect(
    (await host.instance!.list(definition.id)).instances.filter(
      item => item.title === input.title,
    ),
  ).toHaveLength(1);
  await expect(
    host.instance!.create(
      { ...input, title: '不同内容' },
      { requestId: 'stable-request' },
    ),
  ).rejects.toMatchObject({ code: 'CONFLICT' });
  const bob = await new MemoryViewHost(options('bob')).instance!.create(input, {
    requestId: 'stable-request',
  });
  expect(bob.id).not.toBe(first.id);
});
it('publishes revoked permissions without replacing the engine and enforces them on direct writes', async () => {
  let allowed = true;
  const { host: source, paged } = setup();
  const host = new MemoryViewHost({
    ...options(),
    resolveSource: source.resolveSource,
    instancePermissions: () => ({
      save: allowed,
      rename: allowed,
      delete: allowed,
      saveAsPersonal: allowed,
      saveAsShared: allowed,
    }),
    canReorder: () => allowed,
  });
  const engine = new ViewEngine({ definitionId: definition.id, host });
  await engine.load();
  engine.setTitle('保留草稿');
  const sessions = engine.getSnapshot().sessions;
  expect(engine.getCapabilitiesSnapshot().instances.mine.permissions.save).toBe(
    true,
  );
  const listener = vi.fn();
  engine.subscribe(listener);
  allowed = false;
  await host.permission!.refresh();
  expect(listener).toHaveBeenCalled();
  expect(engine.getCapabilitiesSnapshot().instances.mine.permissions.save).toBe(
    false,
  );
  expect(engine.canReorderInstances()).toBe(false);
  expect(engine.getSnapshot().sessions).toBe(sessions);
  expect(paged).toHaveBeenCalledTimes(1);
  const current = await host.instance!.load(instance.id);
  await expect(host.instance!.save(current)).rejects.toMatchObject({
    code: 'FORBIDDEN',
  });
  await expect(
    host.preference!.saveOrder(definition.id, ['mine', 'system']),
  ).rejects.toMatchObject({ code: 'FORBIDDEN' });
  engine.dispose();
  listener.mockClear();
  await host.permission!.refresh();
  expect(listener).not.toHaveBeenCalled();
});
it('rejects a cancelled create without changing the in-process store', async () => {
  const host = new MemoryViewHost(options());
  await host.instance.list(definition.id);
  const controller = new AbortController();
  controller.abort();
  await expect(
    host.instance.create(
      { ...instance, title: '不能创建' },
      { requestId: 'aborted', signal: controller.signal },
    ),
  ).rejects.toMatchObject({ name: 'AbortError' });
  expect((await host.instance.list(definition.id)).instances).toHaveLength(2);
});
