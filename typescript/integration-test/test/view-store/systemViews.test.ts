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

/**
 * Stored system views: global views of an application, kept under the tenant
 * `(platform)` and the owner `(system)`. `WowViewStore` writes them there whatever
 * its caller's tenant, and every tenant lists them beside the configured
 * ones; who may write them is the security gateway's decision, and no
 * gateway stands in front of these servers.
 */

import { describe, expect, it } from 'vitest';
import type { ViewConfig } from '@ahoo-wang/wow-view-engine';
import { WowViewStore } from '@ahoo-wang/wow-view-store';
import {
  actingAs,
  type Caller,
  SYSTEM_VIEW_DEFINITION,
  type ViewStoreServer,
  viewStoreServers,
} from './viewStoreServer';

const run = Date.now();
const ADMIN: Caller = {
  tenantId: `system-a-${run}`,
  owner: 'admin',
  appId: 'system-views',
};
const READER: Caller = {
  tenantId: `system-b-${run}`,
  owner: 'reader',
  appId: 'system-views',
};

function storeOf(caller: Caller, server: ViewStoreServer): WowViewStore {
  return new WowViewStore({ fetcher: actingAs(caller, server) });
}

const config: ViewConfig = {
  kind: 'record',
  filter: { op: 'and', children: [] },
  filterMode: 'simple',
  refresh: { interval: null },
  sort: [],
  pageSize: 20,
  layout: 'table',
  table: { columns: [{ field: 'id' }] },
  card: { title: 'id', fields: [] },
};

function write() {
  return { requestId: `system-${crypto.randomUUID()}` };
}

describe.each(viewStoreServers.map(server => [server.name, server] as const))(
  'on %s',
  (_, server) => {
    it('a stored system view is created, read by every tenant, edited and deleted', async () => {
      const admin = storeOf(ADMIN, server);
      const reader = storeOf(READER, server);
      const definitionId = `system-${crypto.randomUUID()}`;

      const created = await admin.create(
        { definitionId, title: 'Everyone', scope: 'system', config },
        write(),
      );
      expect(created).toMatchObject({
        definitionId,
        title: 'Everyone',
        scope: 'system',
        stored: true,
      });
      const { id } = created;

      // Every tenant reads it, flagged as stored; another application not.
      expect(await reader.list(definitionId)).toEqual([
        {
          id,
          definitionId,
          title: 'Everyone',
          scope: 'system',
          kind: 'record',
          revision: created.revision,
          stored: true,
        },
      ]);
      expect(await reader.get(id)).toEqual(created);
      expect(
        await storeOf({ ...READER, appId: 'portal' }, server).list(
          definitionId,
        ),
      ).toEqual([]);

      // Edited at its revision (the content hash); a stale one conflicts.
      const renamed = await admin.rename(
        id,
        'Everyone, renamed',
        created.revision,
        write(),
      );
      expect(renamed).toMatchObject({
        id,
        title: 'Everyone, renamed',
        stored: true,
      });
      expect(renamed.revision).not.toBe(created.revision);
      await expect(
        reader.save(id, { ...config, pageSize: 50 }, created.revision, write()),
      ).rejects.toMatchObject({ code: 'CONFLICT', instance: renamed });
      const saved = await reader.save(
        id,
        { ...config, pageSize: 50 },
        renamed.revision,
        write(),
      );
      expect(saved.config).toMatchObject({ pageSize: 50 });

      // It never moves audience.
      await expect(
        admin.changeAudience(id, 'shared', saved.revision, write()),
      ).rejects.toMatchObject({ code: 'FORBIDDEN' });

      await admin.delete(id, saved.revision, write());
      expect(await reader.list(definitionId)).toEqual([]);
      await expect(reader.get(id)).rejects.toMatchObject({ code: 'NOT_FOUND' });
    });

    it('a configured system view stays read-only', async () => {
      const reader = storeOf({ ...READER, appId: 'conformance' }, server);
      const [configured] = await reader.list(SYSTEM_VIEW_DEFINITION);
      expect(configured).toMatchObject({ scope: 'system' });
      expect(configured).not.toHaveProperty('stored');
      await expect(
        reader.rename(configured!.id, 'Mine', configured!.revision, write()),
      ).rejects.toMatchObject({ code: 'FORBIDDEN' });
    });
  },
);
