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
 * A tenant's wording over a dataset (view engine D86), stored by the view
 * store under owner `(shared)` and read through its snapshot queries: set,
 * read back, kept to its application, refused at a stale revision, cleared.
 */

import { describe, expect, it } from 'vitest';
import { WowDatasetWordings } from '@ahoo-wang/wow-view-store';
import {
  actingAs,
  type Caller,
  type ViewStoreServer,
  viewStoreServers,
} from './viewStoreServer';

const run = Date.now();

function wordingsOf(caller: Caller, server: ViewStoreServer) {
  return new WowDatasetWordings({ fetcher: actingAs(caller, server) });
}

function write() {
  return { requestId: `wording-${crypto.randomUUID()}` };
}

describe.each(viewStoreServers.map(server => [server.name, server] as const))(
  'on %s',
  (name, server) => {
    const admin: Caller = {
      tenantId: `wording-${name.length}-${run}`,
      owner: 'admin',
      appId: 'console',
    };
    const dataset = `analytics.orders-${run}`;

    it("stores a dataset's wording and reads it back by dataset", async () => {
      const wordings = wordingsOf(admin, server);
      const wording = {
        title: '订单',
        recordNoun: '单',
        fields: { buyer: '买家', discount: false as const },
      };

      const revision = await wordings.set(dataset, wording, '0', write());
      expect(revision).toBe('1');
      expect((await wordings.list()).get(dataset)).toEqual({
        wording,
        revision: '1',
      });

      await wordings.load();
      expect(wordings.of(dataset)?.title).toBe('订单');

      // Another application of the tenant has none.
      const portal = wordingsOf({ ...admin, appId: 'portal' }, server);
      expect((await portal.list()).has(dataset)).toBe(false);

      // A stale revision is a conflict.
      await expect(
        wordings.set(dataset, { title: '旧' }, '0', write()),
      ).rejects.toMatchObject({ code: 'CONFLICT' });

      // Set again, whole: what it leaves out is no longer said.
      expect(
        await wordings.set(dataset, { title: '订单明细' }, '1', write()),
      ).toBe('2');
      expect((await wordings.list()).get(dataset)?.wording).toEqual({
        title: '订单明细',
      });

      await wordings.clear(dataset, '2', write());
      expect((await wordings.list()).has(dataset)).toBe(false);
    });
  },
);
