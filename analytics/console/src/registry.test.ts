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

import type { Fetcher } from '@ahoo-wang/fetcher';
import {
  fetcherSend,
  fieldsOf,
  RegistryClient,
  type Send,
} from './registry.ts';

describe('RegistryClient', () => {
  const calls: unknown[][] = [];
  const send: Send = async (...args) => {
    calls.push(args);
    return [{ id: 'analytics.orders' }];
  };
  const client = new RegistryClient(send);
  beforeEach(() => calls.splice(0));

  it("lists the registry's datasets by id", async () => {
    expect(await client.list()).toEqual([{ id: 'analytics.orders' }]);
    expect(calls).toEqual([
      [
        'POST',
        '/dataset-registry/dataset/snapshot/list/state',
        {
          filter: { op: 'MATCH_ALL' },
          sort: [{ field: 'id', direction: 'ASC' }],
          limit: 500,
        },
      ],
    ]);
  });

  it('sends each command to its route', async () => {
    await client.draft('analytics.orders', 'orders');
    await client.draft('analytics.orders', 'orders', 'warehouse');
    await client.revise('analytics.orders', { title: 'Orders' });
    await client.review('analytics.orders', {
      id: {},
      phone: { level: 'CONFIDENTIAL' },
    });
    await client.publish('analytics.orders');
    await client.retire('analytics.orders');
    const base = '/dataset-registry/dataset/analytics.orders';
    expect(calls).toEqual([
      ['POST', `${base}/draft`, { table: 'orders' }],
      ['POST', `${base}/draft`, { table: 'orders', source: 'warehouse' }],
      ['PUT', `${base}/revise`, { document: { title: 'Orders' } }],
      [
        'PUT',
        `${base}/sensitivity`,
        { fields: { id: {}, phone: { level: 'CONFIDENTIAL' } } },
      ],
      ['POST', `${base}/publish`, {}],
      ['POST', `${base}/retire`, {}],
    ]);
  });

  it('names the fields of a declaration', () => {
    expect(fieldsOf({ fields: { id: {}, city: {} } })).toEqual(['id', 'city']);
    expect(fieldsOf({})).toEqual([]);
  });
});

describe('fetcherSend', () => {
  const fetcherAnswering = (response: Response) => {
    const requests: unknown[][] = [];
    const target = {
      fetch: async (...args: unknown[]) => {
        requests.push(args);
        return response;
      },
    } as unknown as Fetcher;
    return { target, requests };
  };

  it('waits for the snapshot and answers the JSON body', async () => {
    const { target, requests } = fetcherAnswering(new Response('{"ok":true}'));
    expect(await fetcherSend(target)('POST', '/x', { a: 1 })).toEqual({
      ok: true,
    });
    expect(requests).toEqual([
      [
        '/x',
        {
          method: 'POST',
          body: { a: 1 },
          headers: { 'Command-Wait-Stage': 'SNAPSHOT' },
        },
      ],
    ]);
  });

  it('answers null for an empty body', async () => {
    const { target } = fetcherAnswering(new Response(''));
    expect(await fetcherSend(target)('GET', '/x')).toBeNull();
  });

  it("rejects with the server's message, else the status", async () => {
    const refused = fetcherAnswering(
      new Response('{"errorMsg":"Every string field must be reviewed."}', {
        status: 400,
      }),
    );
    await expect(fetcherSend(refused.target)('POST', '/x', {})).rejects.toThrow(
      'Every string field must be reviewed.',
    );
    const forbidden = fetcherAnswering(
      new Response('', { status: 403, statusText: 'Forbidden' }),
    );
    await expect(
      fetcherSend(forbidden.target)('POST', '/x', {}),
    ).rejects.toThrow('403 Forbidden');
  });
});
