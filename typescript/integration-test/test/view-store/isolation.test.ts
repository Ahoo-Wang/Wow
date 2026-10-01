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
 * The view store keeps tenants and applications apart
 * (view-store-backend.md 4.1, 7「隔离测试」): a view saved in one tenant or one
 * application is not there for a caller of another, neither to read nor to
 * write, and neither are the preferences.
 */

import { describe, expect, it } from 'vitest';
import type { ViewConfig } from '@ahoo-wang/wow-view-engine';
import { WowViewStore } from '@ahoo-wang/wow-view-store';
import {
  actingAs,
  type Caller,
  type ViewStoreServer,
  viewStoreServers,
} from './viewStoreServer';

const run = Date.now();
const ALICE: Caller = {
  tenantId: `isolation-a-${run}`,
  owner: 'alice',
  appId: 'console',
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
  return { requestId: `isolation-${crypto.randomUUID()}` };
}

describe.each(viewStoreServers.map(server => [server.name, server] as const))(
  'on %s',
  (_, server) => {
    describe.each([
      ['another tenant', { ...ALICE, tenantId: `isolation-b-${run}` }],
      ['another application', { ...ALICE, appId: 'portal' }],
    ])('a caller of %s', (_, other: Caller) => {
      it('neither lists, reads nor writes the views and preferences', async () => {
        const alice = storeOf(ALICE, server);
        const stranger = storeOf(other, server);
        const definitionId = `isolation-${crypto.randomUUID()}`;
        const mine = await alice.create(
          { definitionId, title: 'Mine', scope: 'personal', config },
          write(),
        );
        const ours = await alice.create(
          { definitionId, title: 'Ours', scope: 'shared', config },
          write(),
        );
        const preferences = await alice.setPreferences(
          definitionId,
          { order: [ours.id], defaultInstanceId: ours.id, revision: '0' },
          write(),
        );

        expect(await stranger.list(definitionId)).toEqual([]);
        for (const view of [mine, ours]) {
          await expect(stranger.get(view.id)).rejects.toMatchObject({
            code: 'NOT_FOUND',
          });
          await expect(
            stranger.rename(view.id, 'Taken', view.revision, write()),
          ).rejects.toMatchObject({ code: 'NOT_FOUND' });
        }
        await expect(
          stranger.changeAudience(ours.id, 'personal', ours.revision, write()),
        ).rejects.toMatchObject({ code: 'NOT_FOUND' });
        expect(await stranger.getPreferences(definitionId)).toMatchObject({
          order: [],
          revision: '0',
        });

        expect(await alice.get(mine.id)).toEqual(mine);
        expect(await alice.get(ours.id)).toEqual(ours);
        expect(await alice.getPreferences(definitionId)).toEqual(preferences);
      });
    });
  },
);
