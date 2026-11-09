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

import { describe, expect, it, vi } from 'vitest';
import { WowDatasetWordings } from '../src/index.js';
import { actingAs, installFake, WRITER_ROLES, type Caller } from './support.js';

const WRITER: Caller = {
  user: 'admin',
  tenant: 't1',
  roles: WRITER_ROLES,
  app: 'console',
};
const READER: Caller = { user: 'reader', tenant: 't1', app: 'console' };

function write() {
  return { requestId: `w-${crypto.randomUUID()}` };
}

describe('the V10 WowDatasetWordings', () => {
  it('sets, lists and clears a dataset’s wording, by revision', async () => {
    const fake = installFake();
    const writer = new WowDatasetWordings({ fetcher: actingAs(WRITER) });
    const reader = new WowDatasetWordings({ fetcher: actingAs(READER) });

    expect(await reader.list()).toEqual(new Map());
    const set = await writer.set(
      'sales.orders',
      { title: 'Orders', recordNoun: 'order', fields: { a: { label: 'A' } } },
      '0',
      write(),
    );
    expect(set).toBe('1');
    const again = await writer.set('sales.orders', {}, set, write());
    expect(again).toBe('2');
    expect((await reader.list()).get('sales.orders')).toEqual({
      wording: {},
      revision: '2',
    });
    expect(
      await writer.set('sales.orders', { title: 'Orders' }, again, write()),
    ).toBe('3');
    expect(await writer.clear('sales.orders', '3', write())).toBe('4');
    expect((await reader.list()).get('sales.orders')).toEqual({
      wording: {},
      revision: '4',
    });

    const clear = fake.requests.find(request =>
      request.path.endsWith('clear-dataset-wording'),
    )!;
    expect(clear.path).toBe(
      '/view-store/dataset_wording/clear-dataset-wording',
    );
    expect(clear.body).toEqual({ dataset: 'sales.orders' });
    expect(clear.headers.get('If-Match')).toBe('"3"');
  });

  // #5546: the wording is the tenant's whole, however many datasets it
  // covers. One read of at most 1,000 left the rest unread, and each of
  // them read as never written: revision '0', and every save a CONFLICT.
  it('lists every dataset’s wording, page after page', async () => {
    const fake = installFake();
    const writer = new WowDatasetWordings({ fetcher: actingAs(WRITER) });
    for (let index = 0; index < 1001; index++)
      await writer.set(
        `sales.data${index}`,
        { title: `Dataset ${index}` },
        '0',
        write(),
      );
    const queries = fake.requests.length;

    const listed = await writer.list();

    expect(listed.size).toBe(1001);
    expect(listed.get('sales.data1000')).toEqual({
      wording: { title: 'Dataset 1000' },
      revision: '1',
    });
    const reads = fake.requests.slice(queries);
    expect(reads.map(read => read.body)).toEqual(
      reads.map(() => expect.objectContaining({ kind: 'cursor', size: 100 })),
    );
    expect(reads).toHaveLength(11);
    expect(
      await writer.set(
        'sales.data1000',
        { title: 'Changed' },
        listed.get('sales.data1000')!.revision,
        write(),
      ),
    ).toBe('2');
  });

  it('stops reading pages once the caller lets the list go', async () => {
    installFake();
    const writer = new WowDatasetWordings({ fetcher: actingAs(WRITER) });
    for (let index = 0; index < 101; index++)
      await writer.set(`sales.data${index}`, {}, '0', write());
    const controller = new AbortController();
    const real = globalThis.fetch;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const answer = await real(input, init);
        controller.abort();
        return answer;
      }),
    );

    await expect(
      writer.list({ signal: controller.signal }),
    ).rejects.toBeDefined();
    expect(vi.mocked(globalThis.fetch)).toHaveBeenCalledTimes(1);
  });

  it('fails whole when a page after the first fails: no partial list stands for the whole', async () => {
    installFake();
    const writer = new WowDatasetWordings({ fetcher: actingAs(WRITER) });
    for (let index = 0; index < 101; index++)
      await writer.set(`sales.data${index}`, {}, '0', write());
    const real = globalThis.fetch;
    let reads = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        if (++reads === 2)
          return new Response('{"errorCode":"Unavailable"}', {
            status: 503,
            headers: { 'Content-Type': 'application/json' },
          });
        return real(input, init);
      }),
    );

    await expect(writer.list()).rejects.toMatchObject({
      name: 'ViewStoreError',
      code: 'UNAVAILABLE',
    });
    expect(reads).toBe(2);
  });

  it('answers the revision after the expected one when the result names none', async () => {
    const fake = installFake();
    const real = fake.fetch;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        await real(input, init);
        return new Response('{"status":"succeeded"}', {
          headers: { 'Content-Type': 'application/json' },
        });
      }),
    );
    expect(
      await new WowDatasetWordings({ fetcher: actingAs(WRITER) }).set(
        'sales.orders',
        { title: 'Orders' },
        '0',
        write(),
      ),
    ).toBe('1');
  });
});
