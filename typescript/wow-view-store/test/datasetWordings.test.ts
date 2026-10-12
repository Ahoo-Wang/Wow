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

import { afterEach, describe, expect, it, vi } from 'vitest';
import { ViewStoreError } from '@ahoo-wang/wow-view-engine';
import { WowDatasetWordings } from '../src/index.js';
import { at, fakeServer, json, wowError } from './support/fakeServer.js';

const SHARED = '/view-store/tenant/t1/owner/(shared)';
const WORDING = `${SHARED}/datasets/analytics.orders/wording`;
const LIST = `${SHARED}/dataset_wording/snapshot/list`;

afterEach(() => {
  vi.unstubAllGlobals();
});

const stored = [
  {
    version: 3,
    state: {
      dataset: 'analytics.orders',
      title: '订单',
      recordNoun: null,
      fields: { buyer: '买家', discount: false },
    },
  },
  {
    version: 1,
    state: { dataset: 'analytics.refunds', title: null, fields: null },
  },
];

describe('WowDatasetWordings', () => {
  it("lists the tenant's wording under (shared), by dataset, what is stored and nothing more", async () => {
    const server = fakeServer(at('POST', LIST, () => json(stored)));
    const wordings = new WowDatasetWordings({ fetcher: server.fetcher });

    const listed = await wordings.list();

    expect([...listed]).toEqual([
      [
        'analytics.orders',
        {
          wording: {
            title: '订单',
            fields: { buyer: '买家', discount: false },
          },
          revision: '3',
        },
      ],
      ['analytics.refunds', { wording: {}, revision: '1' }],
    ]);
    expect(server.requests[0]!.body).toMatchObject({ limit: 1000 });
  });

  it('answers each dataset from what it loaded, and keeps it when a load fails', async () => {
    const server = fakeServer(at('POST', LIST, () => json(stored)));
    const wordings = new WowDatasetWordings({ fetcher: server.fetcher });
    expect(wordings.of('analytics.orders')).toBeUndefined();

    await wordings.load();
    expect(wordings.of('analytics.orders')).toEqual({
      title: '订单',
      fields: { buyer: '买家', discount: false },
    });
    expect(wordings.of('analytics.missing')).toBeUndefined();

    server.on(
      at('POST', LIST, () => wowError('ServiceUnavailable', 'down', 503)),
    );
    await expect(wordings.load()).rejects.toBeInstanceOf(ViewStoreError);
    // The datasets still register in the words last read.
    expect(wordings.of('analytics.orders')?.title).toBe('订单');
  });

  it('writes the wording whole at the expected revision, and answers the new one', async () => {
    const server = fakeServer(
      at('PUT', WORDING, () =>
        json({ errorCode: 'Ok', aggregateId: 'w', aggregateVersion: 4 }),
      ),
    );
    const wordings = new WowDatasetWordings({ fetcher: server.fetcher });

    const revision = await wordings.set(
      'analytics.orders',
      { title: '订单', fields: { buyer: '买家' } },
      '3',
      { requestId: 'r1' },
    );

    expect(revision).toBe('4');
    const [put] = server.requests;
    expect(put!.body).toEqual({ title: '订单', fields: { buyer: '买家' } });
    expect(put!.headers.get('Command-Aggregate-Version')).toBe('3');
    expect(put!.headers.get('Command-Request-Id')).toBe('r1');
  });

  it('clears a wording at the expected revision', async () => {
    const server = fakeServer(
      at('DELETE', WORDING, () =>
        json({ errorCode: 'Ok', aggregateId: 'w', aggregateVersion: 5 }),
      ),
    );
    const wordings = new WowDatasetWordings({ fetcher: server.fetcher });

    await wordings.clear('analytics.orders', '4', { requestId: 'r2' });

    expect(server.requests[0]!.headers.get('Command-Aggregate-Version')).toBe(
      '4',
    );
  });

  it('reads a stale revision as the port reads it', async () => {
    const server = fakeServer(
      at('PUT', WORDING, () =>
        wowError('CommandExpectVersionConflict', 'stale', 409),
      ),
    );
    const wordings = new WowDatasetWordings({ fetcher: server.fetcher });

    await expect(
      wordings.set('analytics.orders', {}, '1', { requestId: 'r3' }),
    ).rejects.toMatchObject({ code: 'CONFLICT' });
  });
});
