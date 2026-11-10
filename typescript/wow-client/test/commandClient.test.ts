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
 * `CommandClient` sending: its headers, ids and bodies, the routes it takes
 * from the Manifest, its waits and scopes, its request ids, lifecycle commands
 * and base path.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  CommandClient,
  LifecycleCommands,
  newRequestId,
  type RouteManifest,
  requestIdOf,
  WaitPlan,
  WowProblemError,
} from '../src/index.js';
import {
  BASE_URL,
  jsonResponse,
  stubFetch,
  testFetcher,
} from './fixtures/fetchStub';
import {
  aggregate,
  context,
  manifestOf,
  TEST_MANIFEST,
} from './fixtures/manifest';
import { client, PRODUCT, problemResponse, result } from './fixtures/clients';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('CommandClient', () => {
  it('sends the Wow headers, the id and the body', async () => {
    const { requests } = stubFetch(() => jsonResponse(result()));
    const commands = new CommandClient(client(), { manifest: TEST_MANIFEST });
    const answered = await commands.wait({
      command: 'eshop-catalog.product.CreateProduct',
      body: { name: 'n', price: 1 },
      id: 'p/1',
      requestId: 'r1',
      wait: WaitPlan.COMMITTED,
      timeout: 2000,
      expectedVersion: 3,
      targetTenant: 't',
      space: 's',
      headers: { 'X-User': 'alice' },
    });
    expect(answered.status).toBe('succeeded');
    const [sent] = requests;
    expect(sent.method).toBe('POST');
    expect(sent.url).toBe(
      `${BASE_URL}/eshop-catalog/product/p%2F1/create-product`,
    );
    expect(sent.body).toEqual({ name: 'n', price: 1 });
    expect(Object.fromEntries(sent.headers)).toMatchObject({
      'wow-request-id': 'r1',
      'wow-wait': 'committed',
      'wow-wait-timeout': '2s',
      'if-match': '"3"',
      'wow-scope': 'tenant="t"',
      'wow-space': 's',
      'x-user': 'alice',
    });
  });

  it("sends a command by no id to the caller's own aggregate of one whose id is its owner's", async () => {
    const BASKET = '/shop/basket';
    const owned = manifestOf([
      context('shop', [
        {
          ...aggregate('basket', [
            {
              name: 'Clear',
              aliases: [],
              schema: '#/$defs/Clear',
              creation: 'none',
              routes: [
                { method: 'POST', path: `${BASKET}/{id}/clear` },
                { method: 'POST', path: `${BASKET}/clear` },
              ],
            },
            {
              name: LifecycleCommands.DELETE_AGGREGATE,
              aliases: [],
              schema: `#/$defs/${LifecycleCommands.DELETE_AGGREGATE}`,
              creation: 'none',
              routes: [
                { method: 'DELETE', path: `${BASKET}/{id}` },
                { method: 'DELETE', path: BASKET },
              ],
              system: true,
            },
          ]),
          idSource: 'owner',
        },
      ]),
    ]);
    const { requests } = stubFetch(() => jsonResponse(result()));
    const commands = new CommandClient(client(), { manifest: owned });
    const type = { context: 'shop', name: 'basket' };
    await commands.wait({ command: 'shop.basket.Clear' });
    await commands.wait({ command: 'shop.basket.Clear', id: 'u1' });
    await commands.wait({ command: LifecycleCommands.DELETE_AGGREGATE, type });
    await commands.wait({
      command: LifecycleCommands.DELETE_AGGREGATE,
      type,
      id: 'u1',
    });
    expect(requests.map(r => [r.method, r.url])).toEqual([
      ['POST', `${BASE_URL}/shop/basket/clear`],
      ['POST', `${BASE_URL}/shop/basket/u1/clear`],
      ['DELETE', `${BASE_URL}/shop/basket`],
      ['DELETE', `${BASE_URL}/shop/basket/u1`],
    ]);
  });

  it("sends a command whose body names its aggregate by no id, the body naming the request's", async () => {
    const STOCK = '/shop/stock';
    const named = manifestOf([
      context('shop', [
        aggregate('stock', [
          {
            name: 'Restock',
            aliases: [],
            schema: '#/$defs/Restock',
            creation: 'allowed',
            routes: [{ method: 'POST', path: `${STOCK}/restock` }],
            idProperty: 'productId',
          },
        ]),
      ]),
    ]);
    const { requests } = stubFetch(() => jsonResponse(result()));
    const commands = new CommandClient(client(), { manifest: named });
    const command = 'shop.stock.Restock';
    await commands.wait({ command, body: { productId: 'mug', quantity: 1 } });
    await commands.wait({
      command,
      body: { productId: 'mug', quantity: 2 },
      id: 'mug',
    });
    // A body that names none is given the request's id, which the route has no place for.
    await commands.wait({ command, body: { quantity: 3 }, id: 'cup' });
    await commands.wait({
      command,
      body: { productId: null, quantity: 4 },
      id: 'cup',
    });
    expect(requests.map(r => [r.method, r.url])).toEqual(
      Array(4).fill(['POST', `${BASE_URL}/shop/stock/restock`]),
    );
    expect(requests.map(r => r.body)).toEqual([
      { productId: 'mug', quantity: 1 },
      { productId: 'mug', quantity: 2 },
      { quantity: 3, productId: 'cup' },
      { productId: 'cup', quantity: 4 },
    ]);
    // A body that names another aggregate, or holds no string id, is the code's mistake: nothing is sent.
    await expect(
      commands.wait({ command, body: { productId: 'mug' }, id: 'cup' }),
    ).rejects.toThrow(
      'The request names the aggregate cup, but the body of shop.stock.Restock names mug: send it without an id.',
    );
    await expect(
      commands.wait({ command, body: { productId: 7 }, id: 'cup' }),
    ).rejects.toThrow(
      'The request names the aggregate cup, but the body of shop.stock.Restock names 7: send it without an id.',
    );
    expect(requests).toHaveLength(4);
  });

  it('refuses an empty id before sending anything', async () => {
    const { requests } = stubFetch(() => jsonResponse(result()));
    const commands = new CommandClient(client(), { manifest: TEST_MANIFEST });
    // An empty id would send /eshop-catalog/product//create-product, a route no service has.
    await expect(
      commands.wait({
        command: 'eshop-catalog.product.CreateProduct',
        body: { name: 'n', price: 1 },
        id: '',
      }),
    ).rejects.toThrow(
      new TypeError(
        'The path /eshop-catalog/product/{id}/create-product of eshop-catalog.product.CreateProduct needs id.',
      ),
    );
    expect(requests).toHaveLength(0);
  });

  it('sends {} without a body, and no Wow header it was not given but its request id', async () => {
    const { requests } = stubFetch(() => jsonResponse(result()));
    await new CommandClient(client(), { manifest: TEST_MANIFEST }).wait({
      command: 'eshop-catalog.product.CreateProduct',
    });
    expect(requests[0].url).toBe(
      `${BASE_URL}/eshop-catalog/product/create-product`,
    );
    expect(requests[0].body).toEqual({});
    expect(
      [...requests[0].headers.keys()].filter(h => h.startsWith('wow-')),
    ).toEqual(['wow-request-id']);
  });

  it('routes by the Manifest, the type from the full name or given, a system command by its type', async () => {
    const { requests } = stubFetch(() => jsonResponse(result()));
    const commands = new CommandClient(client(), { manifest: TEST_MANIFEST });
    await commands.wait({
      command: 'Rename',
      type: { context: 'eshop-catalog', name: 'product' },
      id: 'p1',
    });
    expect(requests[0].url).toBe(`${BASE_URL}/eshop-catalog/product/p1/rename`);
    await expect(
      commands.wait({ command: 'Rename', id: 'p1' }),
    ).rejects.toThrow('not a full type name');
    await expect(
      commands.wait({ command: LifecycleCommands.DELETE_AGGREGATE, id: 'p1' }),
    ).rejects.toThrow("set the request's type");
    await expect(
      commands.wait({
        command: LifecycleCommands.TRANSFER_SPACE,
        type: PRODUCT,
      }),
    ).rejects.toThrow("set the request's id");
    await expect(
      commands.wait({ command: 'eshop-catalog.product.Rename' }),
    ).rejects.toThrow("set the request's id");
    await expect(
      commands.wait({ command: 'eshop-catalog.product.Unknown', id: 'p1' }),
    ).rejects.toThrow('The Manifest lists no command Unknown');
    await commands.wait({
      command: 'wow.snapshot.Rebuild',
      type: PRODUCT,
      body: { ids: ['p1'] },
    });
    await commands.wait({
      command: 'wow.schedule.Enable',
      type: { context: 'eshop-catalog', name: 'schedule' },
      id: 'report',
    });
    expect(requests.slice(1).map(r => `${r.method} ${r.url}`)).toEqual([
      `POST ${BASE_URL}/eshop-catalog/product/wow.snapshot.rebuild`,
      `POST ${BASE_URL}/eshop-catalog/wow.schedule/report/enable`,
    ]);
  });

  it('refuses a route a command is not sent by, and fetches the Manifest again after a failure', async () => {
    let manifestAnswers = 0;
    const { requests } = stubFetch(request => {
      if (!request.url.endsWith('/wow/manifest')) return jsonResponse(result());
      manifestAnswers++;
      return manifestAnswers === 1
        ? problemResponse(503, 'wow.storage.unavailable')
        : jsonResponse(TEST_MANIFEST);
    });
    const commands = new CommandClient(client());
    await expect(commands.wait({ command: 'c.a.X', id: '1' })).rejects.toThrow(
      WowProblemError,
    );
    // The Manifest is fetched again after the failure.
    await commands.wait({ command: 'c.a.X', id: '1' });
    // A system command of a context without commands of its own is none.
    await expect(
      commands.wait({
        command: 'wow.schedule.Enable',
        type: { context: 'c', name: 'schedule' },
        id: 'j',
      }),
    ).rejects.toThrow('The Manifest lists no command wow.schedule.Enable');
    expect(requests.filter(r => r.url.endsWith('/wow/manifest'))).toHaveLength(
      2,
    );
    const routed = (routes: RouteManifest[]) =>
      new CommandClient(client(), {
        manifest: manifestOf([
          context('c', [
            aggregate('a', [
              {
                name: 'X',
                aliases: [],
                schema: '#/$defs/X',
                creation: 'none',
                routes,
              },
            ]),
          ]),
        ]),
      });
    await expect(
      routed([{ method: 'GET', path: '/c/a/{id}/x' }]).wait({
        command: 'c.a.X',
        id: '1',
      }),
    ).rejects.toThrow('a command is sent by POST or DELETE');
    await expect(
      routed([]).wait({ command: 'c.a.X', id: '1' }),
    ).rejects.toThrow('no route to an aggregate by its id');
  });

  it('finds a command by a former name', async () => {
    const { requests } = stubFetch(() => jsonResponse(result()));
    const manifest = manifestOf([
      context('c', [
        aggregate('a', [
          {
            name: 'X2',
            aliases: ['X'],
            schema: '#/$defs/X2',
            creation: 'none',
            routes: [{ method: 'POST', path: '/c/a/{id}/x2' }],
          },
        ]),
      ]),
    ]);
    await new CommandClient(client(), { manifest: manifest }).wait({
      command: 'c.a.X',
      id: '1',
    });
    expect(requests[0].url).toBe(`${BASE_URL}/c/a/1/x2`);
  });

  it('fetches the Manifest once for sends made together, and again after a failed fetch', async () => {
    let manifestAnswers = 0;
    const { requests } = stubFetch(async request => {
      if (!request.url.endsWith('/wow/manifest')) return jsonResponse(result());
      manifestAnswers++;
      // Answered after a turn, so both first sends are waiting for it.
      await new Promise(resolve => setTimeout(resolve, 5));
      return manifestAnswers === 1
        ? problemResponse(503, 'wow.storage.unavailable')
        : jsonResponse(TEST_MANIFEST);
    });
    const commands = new CommandClient(client());
    const failed = await Promise.allSettled([
      commands.wait({ command: 'c.a.X', id: '1' }),
      commands.wait({ command: 'c.a.X', id: '2' }),
    ]);
    expect(failed.map(settled => settled.status)).toEqual([
      'rejected',
      'rejected',
    ]);
    await Promise.all([
      commands.wait({ command: 'c.a.X', id: '3' }),
      commands.wait({ command: 'c.a.X', id: '4' }),
    ]);
    expect(requests.filter(r => r.url.endsWith('/wow/manifest'))).toHaveLength(
      2,
    );
    expect(
      requests.filter(r => !r.url.endsWith('/wow/manifest')).map(r => r.url),
    ).toEqual([`${BASE_URL}/c/a/3/x`, `${BASE_URL}/c/a/4/x`]);
  });

  it('fetches the Manifest once when it is given none', async () => {
    const { requests } = stubFetch(request =>
      jsonResponse(
        request.url.endsWith('/wow/manifest') ? TEST_MANIFEST : result(),
      ),
    );
    const commands = new CommandClient(client());
    await commands.wait({ command: 'c.a.X', id: '1' });
    await commands.wait({ command: 'c.a.X', id: '2' });
    expect(requests.map(r => `${r.method} ${r.url}`)).toEqual([
      `GET ${BASE_URL}/wow/manifest`,
      `POST ${BASE_URL}/c/a/1/x`,
      `POST ${BASE_URL}/c/a/2/x`,
    ]);
  });

  it('sends answering at accepted within its own timeout, and waits for the plan as Kotlin sends it', async () => {
    const { requests } = stubFetch(() => jsonResponse(result()));
    const commands = new CommandClient(client(), { manifest: TEST_MANIFEST });
    await commands.send({ command: 'c.a.X', id: '1', wait: WaitPlan.ACCEPTED });
    await commands.send({ command: 'c.a.X', id: '1', timeout: 3000 });
    // A request that waits for more is sent with wait, not send.
    await expect(
      commands.send({ command: 'c.a.X', id: '1', wait: WaitPlan.SNAPSHOT }),
    ).rejects.toThrow(
      'send answers on acceptance: use wait() for the plan snapshot.',
    );
    await commands.wait({ command: 'c.a.X', id: '1' });
    // Only a timeout: the service's default plan within it, sent without a plan.
    await commands.wait({ command: 'c.a.X', id: '1', timeout: 2000 });
    // A plan's own timeout is sent; the request's overrides it.
    await commands.wait({
      command: 'c.a.X',
      id: '1',
      wait: WaitPlan.COMMITTED.within(5000),
    });
    await commands.wait({
      command: 'c.a.X',
      id: '1',
      wait: WaitPlan.COMMITTED.within(5000),
      timeout: 1500,
    });
    expect(
      requests.map(r => [
        r.headers.get('Wow-Wait'),
        r.headers.get('Wow-Wait-Timeout'),
      ]),
    ).toEqual([
      ['accepted', null],
      ['accepted', '3s'],
      [null, null],
      [null, '2s'],
      ['committed', '5s'],
      ['committed', '1500ms'],
    ]);
  });

  it('targets a scope only when the request names one', async () => {
    const { requests } = stubFetch(() => jsonResponse(result()));
    const commands = new CommandClient(client(), { manifest: TEST_MANIFEST });
    await commands.wait({
      command: 'c.a.X',
      id: '1',
      targetScope: { ownerId: 'bob' },
    });
    await commands.wait({ command: 'c.a.X', id: '1' });
    expect(requests.map(r => r.headers.get('Wow-Scope'))).toEqual([
      'owner="bob"',
      null,
    ]);
  });

  it('sends a generated request id when the options name none, and a failure carries it', async () => {
    // Review A3-16: a client that lost the answer retries with the same id.
    const UUID =
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
    let answer: () => Response | Promise<Response> = () =>
      jsonResponse(result());
    const { requests } = stubFetch(() => answer());
    const commands = new CommandClient(client(), { manifest: TEST_MANIFEST });

    const rename = { command: 'eshop-catalog.product.Rename', id: 'p1' };
    await commands.wait({ ...rename, body: { name: 'n' } });
    await commands.wait({ ...rename, requestId: 'mine' });
    const generated = requests[0].headers.get('Wow-Request-Id');
    expect(generated).toMatch(UUID);
    expect(requests[1].headers.get('Wow-Request-Id')).toBe('mine');

    // The network fails: no answer, the error carries the id sent.
    answer = () => Promise.reject(new TypeError('Failed to fetch'));
    const lost = await commands.wait(rename).catch((error: unknown) => error);
    expect(lost).toBeInstanceOf(Error);
    expect(lost).not.toBeInstanceOf(WowProblemError);
    expect(requestIdOf(lost)).toBe(requests[2].headers.get('Wow-Request-Id'));
    expect(requestIdOf(lost)).toMatch(UUID);
    const streamLost = await commands
      .stream({ ...rename, requestId: 's1' })
      .catch((error: unknown) => error);
    expect(requestIdOf(streamLost)).toBe('s1');
    const deleteLost = await commands
      .wait({
        command: LifecycleCommands.DELETE_AGGREGATE,
        type: PRODUCT,
        id: 'p1',
      })
      .catch((error: unknown) => error);
    expect(requestIdOf(deleteLost)).toBe(
      requests[4].headers.get('Wow-Request-Id'),
    );

    // A problem without a result (a proxy's 503) carries it too.
    answer = () =>
      new Response('upstream down', {
        status: 503,
        statusText: 'Service Unavailable',
      });
    const unknown = await commands
      .wait({ ...rename, requestId: 'r-503' })
      .catch((error: unknown) => error);
    expect(unknown).toBeInstanceOf(WowProblemError);
    expect((unknown as WowProblemError).requestId).toBe('r-503');
    expect((unknown as WowProblemError).retry).toBe('same-request');
    expect(requestIdOf(unknown)).toBe('r-503');
    expect(requestIdOf('not an error')).toBeUndefined();
    expect(requestIdOf(new Error('of no command'))).toBeUndefined();
  });

  it('generates request ids without randomUUID, as an insecure page has none', () => {
    const UUID =
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
    expect(newRequestId()).toMatch(UUID);
    const crypto = globalThis.crypto;
    vi.stubGlobal('crypto', {
      getRandomValues: crypto.getRandomValues.bind(crypto),
    });
    expect(newRequestId()).toMatch(UUID);
    vi.stubGlobal('crypto', undefined);
    expect(newRequestId()).toMatch(UUID);
    vi.stubGlobal('crypto', crypto);
    expect(newRequestId()).not.toBe(newRequestId());
  });

  it('sends lifecycle commands to their routes', async () => {
    const { requests } = stubFetch(() => jsonResponse(result()));
    const commands = new CommandClient(client(), { manifest: TEST_MANIFEST });
    const product = { type: PRODUCT, id: 'p1' };
    await commands.wait({
      ...product,
      command: LifecycleCommands.DELETE_AGGREGATE,
      requestId: 'r',
    });
    await commands.wait({
      ...product,
      command: LifecycleCommands.TRANSFER_SPACE,
      body: { spaceId: 'eu' },
    });
    await commands.wait({
      ...product,
      command: LifecycleCommands.RESTORE_AGGREGATE,
    });
    expect(requests.map(r => [r.method, r.url, r.body])).toEqual([
      ['DELETE', `${BASE_URL}/eshop-catalog/product/p1`, undefined],
      [
        'POST',
        `${BASE_URL}/eshop-catalog/product/p1/wow.lifecycle.transfer-space`,
        { spaceId: 'eu' },
      ],
      [
        'POST',
        `${BASE_URL}/eshop-catalog/product/p1/wow.lifecycle.restore-aggregate`,
        {},
      ],
    ]);
    expect(requests[0].headers.get('Wow-Request-Id')).toBe('r');
  });

  it('puts the base path before every route', async () => {
    const { requests } = stubFetch(() => jsonResponse(result()));
    await new CommandClient(
      { fetcher: testFetcher(), basePath: '/api' },
      { manifest: TEST_MANIFEST },
    ).wait({ command: 'c.a.X' });
    expect(requests[0].url).toBe(`${BASE_URL}/api/c/a/x`);
  });
});
