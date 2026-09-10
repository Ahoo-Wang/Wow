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
import { ViewEngine } from '../src/record/ViewEngine.js';
import { instance, setup } from './fixtures/viewPage.js';

it('publishes immutable same-scope capabilities without losing sessions or querying', async () => {
  const { host, paged } = setup();
  const engine = new ViewEngine({ definitionId: 'orders', host });
  await engine.load();
  engine.setTitle('保留草稿');
  const state = engine.getSnapshot();
  const before = engine.getCapabilitiesSnapshot();
  expect(engine.getCapabilitiesSnapshot()).toBe(before);
  expect(Object.isFrozen(before.instances.mine.permissions)).toBe(true);
  const notified = vi.fn();
  const unsubscribe = engine.subscribe(notified);
  const nextHost = {
    ...host,
    instance: {
      save: undefined,
      load: vi.fn(async () => structuredClone(instance)),
    },
    preference: { saveOrder: vi.fn(async () => {}) },
  };
  engine.updateHost(nextHost);
  const after = engine.getCapabilitiesSnapshot();
  expect(notified).toHaveBeenCalledTimes(1);
  expect(after).not.toBe(before);
  expect(after.instances.mine.permissions.save).toBe(false);
  expect(before.instances.mine.permissions.save).toBe(true);
  expect(after.instances.mine.reload).toBe(true);
  expect(after.reorder).toBe(true);
  expect(engine.getSnapshot().sessions).toBe(state.sessions);
  expect(engine.getSnapshot().sessions.mine.instance.title).toBe('保留草稿');
  expect(paged).toHaveBeenCalledTimes(1);
  engine.updateHost(nextHost);
  expect(engine.getCapabilitiesSnapshot()).toBe(after);
  expect(notified).toHaveBeenCalledTimes(1);
  await expect(engine.save()).rejects.toThrow();
  expect(host.instance!.save).not.toHaveBeenCalled();
  await engine.reorderInstances(['system', 'mine']);
  expect(nextHost.preference!.saveOrder).toHaveBeenCalledWith('orders', [
    'system',
    'mine',
  ]);
  unsubscribe();
  engine.dispose();
  expect(engine.getCapabilitiesSnapshot().reorder).toBe(false);
});

it('derives reload capability from uncertain creation receipts as well as host methods', async () => {
  const { host } = setup();
  host.instance!.create = vi.fn(async submitted => ({
    ...submitted,
    id: 'copy',
    definitionId: 'invalid',
  }));
  const engine = new ViewEngine({ definitionId: 'orders', host });
  await engine.load();
  expect(engine.getCapabilitiesSnapshot().instances.mine.reload).toBe(true);
  engine.updateHost({
    ...host,
    instance: { ...host.instance, list: undefined },
  });
  expect(engine.getCapabilitiesSnapshot().instances.mine.reload).toBe(false);
  await expect(
    engine.saveAs({ title: '副本', scope: { type: 'personal' } }),
  ).rejects.toThrow();
  // The original request can be replayed even without either read method.
  expect(engine.getCapabilitiesSnapshot().instances.mine.reload).toBe(true);
  engine.updateHost({ ...host, instance: { list: host.instance!.list } });
  // A list-only host can instead reconcile the known creation ID.
  expect(engine.getCapabilitiesSnapshot().instances.mine.reload).toBe(true);
  engine.updateHost({ ...host, instance: { list: undefined } });
  expect(engine.getCapabilitiesSnapshot().instances.mine.reload).toBe(false);
  engine.dispose();
});

it('publishes added and removed default saving capability without disturbing the session', async () => {
  const { host, paged } = setup();
  const engine = new ViewEngine({ definitionId: 'orders', host });
  await engine.load();
  const before = engine.getSnapshot();
  const notified = vi.fn();
  const unsubscribe = engine.subscribe(notified);
  expect(engine.getCapabilitiesSnapshot().setDefault).toBe(false);
  const saveDefault = vi.fn(async () => {});
  engine.updateHost({ ...host, preference: { saveDefault } });
  expect(engine.canSetDefaultInstance()).toBe(true);
  expect(engine.getCapabilitiesSnapshot().setDefault).toBe(true);
  expect(notified).toHaveBeenCalledTimes(1);
  await engine.setDefaultInstance('system');
  expect(saveDefault).toHaveBeenCalledWith('orders', 'system');
  engine.updateHost(host);
  expect(engine.getCapabilitiesSnapshot().setDefault).toBe(false);
  await expect(engine.setDefaultInstance(null)).rejects.toThrow();
  expect(engine.getSnapshot().sessions).toBe(before.sessions);
  expect(paged).toHaveBeenCalledTimes(1);
  engine.updateHost({ ...host, preference: { saveDefault } });
  engine.dispose();
  expect(engine.getCapabilitiesSnapshot().setDefault).toBe(false);
  unsubscribe();
});
