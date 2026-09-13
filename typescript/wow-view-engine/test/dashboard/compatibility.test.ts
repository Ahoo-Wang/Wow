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

import { describe, expect, it } from 'vitest';
import { MemoryViewHost } from '../../src/record/MemoryViewHost.js';
import { definition, instance } from '../engine/fixtures.js';
import type { DashboardViewInstance } from '../../src/dashboard/dashboardModel.js';

const dashboard: DashboardViewInstance = {
  id: 'dashboard',
  definitionId: definition.id,
  title: 'Dashboard',
  scope: { type: 'personal' },
  revision: 'v1',
  kind: 'dashboard',
  config: { schemaVersion: 1, panels: [], filters: [] },
};
function hosts() {
  const options = {
    serviceKey: 'service',
    scopeKey: 'alice',
    store: new Map<string, string | null>(),
    definition: { ...definition, dashboard: true as const },
    instances: {
      instances: [instance('a'), dashboard, instance('b')],
      defaultInstanceId: 'dashboard',
    },
    resolveSource: () => ({ paged: async () => ({ total: 0, list: [] }) }),
    definitionPermissions: () => ({
      createPersonal: true,
      createShared: false,
    }),
  };
  return {
    old: new MemoryViewHost(options),
    modern: new MemoryViewHost({
      ...options,
      supportedFormats: { record: true, analysis: true, dashboard: 1 },
    }),
  };
}
describe('dashboard service compatibility', () => {
  it('projects hidden defaults in list/delete/retry without changing the stored preference', async () => {
    const { old, modern } = hosts();
    const oldList = await old.instance.list(definition.id);
    expect(oldList.instances.map(item => item.id)).toEqual(['a', 'b']);
    expect(oldList.defaultInstanceId).toBeNull();
    const a = oldList.instances[0];
    expect(await old.instance.delete(a.id, a.revision)).toEqual({
      defaultInstance: null,
    });
    expect(await old.instance.delete(a.id, a.revision)).toEqual({
      defaultInstance: null,
    });
    expect((await modern.instance.list(definition.id)).defaultInstanceId).toBe(
      'dashboard',
    );
    expect(await modern.instance.load('dashboard')).toMatchObject({
      kind: 'dashboard',
    });
    await expect(old.instance.load('dashboard')).rejects.toMatchObject({
      code: 'UNSUPPORTED_FORMAT',
    });
  });
  it('reorders visible objects around fixed hidden slots and rejects old writes to dashboards', async () => {
    const { old, modern } = hosts();
    await old.preference.saveOrder(definition.id, ['b', 'a']);
    expect(
      (await modern.instance.list(definition.id)).instances.map(
        item => item.id,
      ),
    ).toEqual(['b', 'dashboard', 'a']);
    const saved = await modern.instance.load('dashboard');
    await expect(old.instance.save(saved)).rejects.toMatchObject({
      code: 'UNSUPPORTED_FORMAT',
    });
    await expect(
      old.instance.rename(saved.id, 'Changed', saved.revision),
    ).rejects.toMatchObject({ code: 'UNSUPPORTED_FORMAT' });
    await expect(
      old.instance.delete(saved.id, saved.revision),
    ).rejects.toMatchObject({ code: 'UNSUPPORTED_FORMAT' });
    await expect(
      old.instance.create({ ...dashboard }, { requestId: 'unsupported' }),
    ).rejects.toMatchObject({ code: 'UNSUPPORTED_FORMAT' });
    expect((await modern.instance.load('dashboard')).title).toBe('Dashboard');
  });
  it('requires definition creation grants and replays exact dashboard create receipts', async () => {
    const { modern } = hosts();
    const input = { ...dashboard, title: 'Created' };
    const created = await modern.instance.create(input, { requestId: 'once' });
    expect(await modern.instance.create(input, { requestId: 'once' })).toEqual(
      created,
    );
    await expect(
      modern.instance.create(
        { ...input, scope: { type: 'public', source: 'shared' } },
        { requestId: 'shared' },
      ),
    ).rejects.toMatchObject({ code: 'FORBIDDEN' });
  });
});
