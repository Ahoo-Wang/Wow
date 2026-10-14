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
import { WowDatasetWordings } from '../../src/v10/index.js';
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

  it('refuses a reader without the role, and a stale revision', async () => {
    installFake();
    const writer = new WowDatasetWordings({ fetcher: actingAs(WRITER) });
    await writer.set('sales.orders', { title: 'Orders' }, '0', write());
    await expect(
      new WowDatasetWordings({ fetcher: actingAs(READER) }).set(
        'sales.orders',
        { title: 'Mine' },
        '1',
        write(),
      ),
    ).rejects.toMatchObject({ code: 'FORBIDDEN' });
    await expect(
      writer.set('sales.orders', { title: 'Stale' }, '0', write()),
    ).rejects.toMatchObject({ code: 'CONFLICT' });
  });

  it('answers the revision after the expected one when the result names none', async () => {
    const fake = installFake();
    const real = fake.fetch;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        await real(input, init);
        return new Response('{}', {
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
