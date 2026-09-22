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

import { Fetcher } from '@ahoo-wang/fetcher';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  emptyPreferences,
  isViewStoreError,
  MemoryViewStore,
  toSummary,
  ViewEngine,
  type ViewInstance,
  type ViewStoreErrorCode,
} from '../src/index.js';
import { FetcherViewStore } from '../examples/FetcherViewStore.js';
import {
  ordersDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';

const instance: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'Mine',
  scope: 'personal',
  revision: 'r1',
  config: recordConfig(),
};

/** The one request the stub was asked for, as the store spelled it. */
interface Sent {
  url: string;
  method: string;
  headers: Headers;
  body: unknown;
}

let fetchMock: ReturnType<typeof vi.fn>;

function reply(body: unknown, init: ResponseInit = {}): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status: 200,
    headers: { 'content-type': 'application/json' },
    ...init,
  });
}

function sent(call = 0): Sent {
  const [input, init] = fetchMock.mock.calls[call] as [
    string | Request,
    RequestInit,
  ];
  const url = typeof input === 'string' ? input : input.url;
  return {
    url,
    method: String(init.method),
    headers: new Headers(init.headers),
    body: init.body ? JSON.parse(String(init.body)) : undefined,
  };
}

function store(basePath?: string): FetcherViewStore {
  return new FetcherViewStore({
    fetcher: new Fetcher({ baseURL: 'https://views.test' }),
    basePath,
  });
}

/** The code a rejected call reports, or the error itself when it is not one. */
async function codeOf(run: Promise<unknown>): Promise<ViewStoreErrorCode> {
  const error = await run.catch((caught: unknown) => caught);
  if (!isViewStoreError(error)) throw error;
  return error.code;
}

beforeEach(() => {
  fetchMock = vi.fn(() => Promise.resolve(reply(instance)));
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('FetcherViewStore reads', () => {
  it('lists the views of one definition', async () => {
    // What a backend answers with: the summary projection, kind included.
    fetchMock.mockResolvedValueOnce(reply([toSummary(instance)]));

    const summaries = await store().list('orders');

    expect(summaries).toHaveLength(1);
    expect(summaries[0].kind).toBe('record');
    expect(sent().url).toBe(
      'https://views.test/view-engine/definitions/orders/views',
    );
    expect(sent().method).toBe('GET');
  });

  it('escapes an id that would otherwise change the path', async () => {
    await store().get('a/b');

    expect(sent().url).toBe('https://views.test/view-engine/views/a%2Fb');
  });

  it('takes a base path of its own, with or without a trailing slash', async () => {
    await store('/api/views/').getPreferences('orders');

    expect(sent().url).toBe(
      'https://views.test/api/views/definitions/orders/preferences',
    );
  });

  /**
   * The revision is the whole point of this one: `'0'` is what the first
   * `setPreferences` sends as `If-Match`, so a host written against the
   * memory store writes the identical first request against this one.
   */
  it('reads a definition nobody has ordered yet as the memory store does', async () => {
    fetchMock.mockResolvedValueOnce(
      reply({ message: 'none' }, { status: 404 }),
    );

    const read = await store().getPreferences('orders');

    expect(read).toEqual(emptyPreferences());
    expect(read).toEqual(await new MemoryViewStore().getPreferences('orders'));
  });

  it('still reports a preference read that failed for any other reason', async () => {
    fetchMock.mockResolvedValueOnce(reply({ message: 'no' }, { status: 403 }));

    await expect(codeOf(store().getPreferences('orders'))).resolves.toBe(
      'FORBIDDEN',
    );
  });
});

describe('FetcherViewStore writes', () => {
  it('carries the request id, so a retry is one logical write', async () => {
    await store().create(
      {
        definitionId: 'orders',
        title: 'Mine',
        scope: 'personal',
        config: recordConfig(),
      },
      { requestId: 'req-1' },
    );

    expect(sent().method).toBe('POST');
    expect(sent().headers.get('Idempotency-Key')).toBe('req-1');
    expect(sent().body).toMatchObject({ title: 'Mine', scope: 'personal' });
  });

  it('carries the revision a save expects', async () => {
    await store().save('orders-1', recordConfig(), 'r1', {
      requestId: 'req-2',
    });

    expect(sent().url).toBe(
      'https://views.test/view-engine/views/orders-1/config',
    );
    expect(sent().headers.get('If-Match')).toBe('r1');
    expect(sent().headers.get('Idempotency-Key')).toBe('req-2');
  });

  it('sends a rename as a title, not as a config', async () => {
    await store().rename('orders-1', 'Theirs', 'r1', { requestId: 'req-3' });

    expect(sent().url).toBe(
      'https://views.test/view-engine/views/orders-1/title',
    );
    expect(sent().body).toEqual({ title: 'Theirs' });
  });

  it('deletes without reading a body back', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));

    await expect(
      store().delete('orders-1', 'r1', { requestId: 'req-4' }),
    ).resolves.toBeUndefined();
    expect(sent().method).toBe('DELETE');
    expect(sent().headers.get('If-Match')).toBe('r1');
  });

  it('sends preferences with the revision they were read at', async () => {
    const preferences = {
      order: ['orders-1'],
      defaultInstanceId: 'orders-1',
      revision: 'p3',
    };
    fetchMock.mockResolvedValueOnce(reply(preferences));

    await store().setPreferences('orders', preferences, { requestId: 'req-5' });

    expect(sent().headers.get('If-Match')).toBe('p3');
    expect(sent().body).toEqual(preferences);
  });
});

describe('FetcherViewStore failures', () => {
  it.each([
    [404, 'NOT_FOUND'],
    [401, 'FORBIDDEN'],
    [403, 'FORBIDDEN'],
    [400, 'INVALID'],
    [422, 'INVALID'],
    [500, 'UNAVAILABLE'],
    [503, 'UNAVAILABLE'],
    [418, 'UNAVAILABLE'],
  ])('maps %i onto %s', async (status, code) => {
    fetchMock.mockResolvedValueOnce(reply({ message: 'no' }, { status }));

    await expect(codeOf(store().get('orders-1'))).resolves.toBe(code);
  });

  it('reports a request that never arrived as unavailable', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'));

    await expect(codeOf(store().get('orders-1'))).resolves.toBe('UNAVAILABLE');
  });

  it('carries the state a conflict reports, so no second round trip is needed', async () => {
    const remote = { ...instance, revision: 'r2', title: 'Theirs' };
    fetchMock.mockResolvedValueOnce(reply(remote, { status: 409 }));

    const error = await store()
      .save('orders-1', recordConfig(), 'r1', { requestId: 'req-6' })
      .catch((caught: unknown) => caught);

    expect(isViewStoreError(error) && error.code).toBe('CONFLICT');
    // An instance write's conflict fills in the instance member, never the
    // preference one: the port keeps the two apart so nothing downstream has
    // to guess which of them arrived.
    expect(isViewStoreError(error) && error.instance).toMatchObject({
      revision: 'r2',
    });
    expect(isViewStoreError(error) && error.preferences).toBeUndefined();
  });

  it('is still a conflict when the body cannot be read', async () => {
    fetchMock.mockResolvedValueOnce(
      new Response('<html>412</html>', {
        status: 412,
        headers: { 'content-type': 'text/html' },
      }),
    );

    const error = await store()
      .save('orders-1', recordConfig(), 'r1', { requestId: 'req-7' })
      .catch((caught: unknown) => caught);

    expect(isViewStoreError(error) && error.code).toBe('CONFLICT');
    expect(isViewStoreError(error) && error.instance).toBeUndefined();
    expect(isViewStoreError(error) && error.preferences).toBeUndefined();
  });

  it('tells a preference conflict from an instance one by what it holds', async () => {
    const held = {
      order: ['orders-1'],
      defaultInstanceId: null,
      revision: 'p9',
    };
    fetchMock.mockResolvedValueOnce(reply(held, { status: 409 }));

    const error = await store()
      .setPreferences(
        'orders',
        { order: [], defaultInstanceId: null, revision: 'p8' },
        { requestId: 'req-8' },
      )
      .catch((caught: unknown) => caught);

    expect(isViewStoreError(error) && error.preferences).toEqual(held);
    expect(isViewStoreError(error) && error.instance).toBeUndefined();
  });

  it('holds nothing when the conflict body is neither of the two', async () => {
    fetchMock.mockResolvedValueOnce(
      reply({ revision: 'r2', why: 'moved' }, { status: 409 }),
    );

    const error = await store()
      .save('orders-1', recordConfig(), 'r1', { requestId: 'req-9' })
      .catch((caught: unknown) => caught);

    expect(isViewStoreError(error) && error.code).toBe('CONFLICT');
    expect(isViewStoreError(error) && error.instance).toBeUndefined();
    expect(isViewStoreError(error) && error.preferences).toBeUndefined();
  });
});

/**
 * `permissions` is synchronous — the engine asks it before every command —
 * so an HTTP store answers from what it fetched in advance. These are about
 * the mapping and the default, which is the whole of what the port asks for:
 * the server remains the boundary, and this only decides which buttons look
 * pressable.
 */
describe('FetcherViewStore permissions', () => {
  it('allows everything for a definition nothing was loaded for', () => {
    const allowed = store().permissions('orders');

    expect(allowed).toMatchObject({
      createPersonal: true,
      createShared: true,
      reorder: true,
      setDefault: true,
    });
    expect(allowed.instance('orders-1')).toEqual({
      save: true,
      rename: true,
      delete: true,
    });
  });

  it("fetches one definition's permissions and answers from them", async () => {
    fetchMock.mockResolvedValueOnce(
      reply({
        createPersonal: true,
        createShared: false,
        reorder: true,
        setDefault: false,
        instances: { 'orders-1': { save: false, delete: false } },
      }),
    );
    const held = store();

    await held.loadPermissions('orders');

    expect(sent().url).toBe(
      'https://views.test/view-engine/definitions/orders/permissions',
    );
    expect(sent().method).toBe('GET');
    expect(held.permissions('orders')).toMatchObject({
      createPersonal: true,
      createShared: false,
      reorder: true,
      setDefault: false,
    });
    // Only what the server named about this instance is narrowed; a rename
    // it said nothing about stays allowed.
    expect(held.permissions('orders').instance('orders-1')).toEqual({
      save: false,
      rename: true,
      delete: false,
    });
  });

  it("takes an instance the answer never named on the answer's own default", async () => {
    fetchMock.mockResolvedValueOnce(
      reply({ instanceDefault: { delete: false } }),
    );
    const held = store();

    await held.loadPermissions('orders');

    // A view created since the answer was read is one of these.
    expect(held.permissions('orders').instance('orders-9')).toEqual({
      save: true,
      rename: true,
      delete: false,
    });
  });

  it('reads a silent answer as allowed, the way an absent method is read', async () => {
    fetchMock.mockResolvedValueOnce(reply({}));
    const held = store();

    await held.loadPermissions('orders');

    expect(held.permissions('orders')).toMatchObject({
      createPersonal: true,
      createShared: true,
    });
  });

  it('leaves a definition it was never asked about alone', async () => {
    fetchMock.mockResolvedValueOnce(reply({ createShared: false }));
    const held = store();

    await held.loadPermissions('orders');

    expect(held.permissions('waybills').createShared).toBe(true);
  });

  it("reports a refused permission read as the port's own failure", async () => {
    fetchMock.mockResolvedValueOnce(reply({ message: 'no' }, { status: 403 }));

    await expect(codeOf(store().loadPermissions('orders'))).resolves.toBe(
      'FORBIDDEN',
    );
  });
});

describe('FetcherViewStore as the engine sees it', () => {
  it('opens a view through the engine and saves an edit back', async () => {
    const engine = new ViewEngine({
      definitions: [ordersDefinition()],
      store: store(),
      resolveSource: () => testSource(),
      environment: testEnvironment().environment,
      newId: () => 'req-open',
    });

    const runtime = await engine.open('orders-1');
    runtime.edit({ pageSize: 50 });
    fetchMock.mockResolvedValueOnce(
      reply({
        ...instance,
        revision: 'r2',
        config: runtime.getSnapshot().draft,
      }),
    );
    const saved = await engine.save(runtime);

    expect(saved.revision).toBe('r2');
    expect(runtime.getSnapshot().dirty).toBe(false);
    // The port needed nothing of the engine beyond its own eight methods.
    expect(sent(1).method).toBe('PUT');
    expect(sent(1).headers.get('If-Match')).toBe('r1');
  });
});
