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
 * Deep review r1 of the TypeScript packages: what `@ahoo-wang/wow-view-store`'s
 * README promises a host of the view store service, against the service.
 */

import type { ViewConfig } from '@ahoo-wang/wow-view-engine';
import { WowViewStore } from '@ahoo-wang/wow-view-store';
import { describe, expect, it } from 'vitest';
import { actingAs, type Caller, SHARED_WRITER } from './viewStoreServer';

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

const write = () => ({ requestId: `r1-${crypto.randomUUID()}` });

describe('r1: who may share a view', () => {
  it('r1 sharing a personal view needs the role that writes (shared), as the README says, so a user without it publishes nothing to the tenant', async () => {
    const tenantId = `r1-${crypto.randomUUID()}`;
    const bob: Caller = { tenantId, user: 'bob', appId: 'console', roles: [] };
    const alice: Caller = {
      tenantId,
      user: 'alice',
      appId: 'console',
      roles: [SHARED_WRITER],
    };
    const bobs = new WowViewStore({ fetcher: actingAs(bob) });
    const alices = new WowViewStore({ fetcher: actingAs(alice) });
    const definitionId = `r1-${crypto.randomUUID()}`;
    const mine = await bobs.create(
      { definitionId, title: 'Mine', scope: 'personal', config },
      write(),
    );

    // README (Permissions): creating a shared view, claiming one and
    // sharing a personal one all need `view-store.shared-writer`.
    await expect(
      bobs.create(
        { definitionId, title: 'Ours', scope: 'shared', config },
        write(),
      ),
    ).rejects.toMatchObject({ code: 'FORBIDDEN' });
    await expect(
      bobs.changeAudience(mine.id, 'shared', mine.revision, write()),
    ).rejects.toMatchObject({ code: 'FORBIDDEN' });
    // So the tenant's shared list holds nothing of Bob's.
    await expect(alices.get(mine.id)).rejects.toMatchObject({
      code: 'NOT_FOUND',
    });
  });
});
