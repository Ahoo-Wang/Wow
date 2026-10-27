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
import { aggregation, filter } from '../src/dsl.js';
import {
  CommandGateway,
  type CommandResult,
  DatasetClient,
  EventQueryClient,
  fetchManifest,
  formatScope,
  formatWaitPlan,
  formatWaitTimeout,
  FrameworkErrorCodes,
  LifecycleCommands,
  newRequestId,
  parseRetryAfter,
  type Problem,
  readProblem,
  requestIdOf,
  retryHintOf,
  SnapshotQueryClient,
  StateClient,
  type SystemEvent,
  toWowProblemError,
  wait,
  WowProblemError,
} from '../src/index.js';
import {
  BASE_URL,
  jsonResponse,
  readAll,
  sseResponse,
  stubFetch,
  testFetcher,
} from './fixtures/fetchStub';
import { TEST_MANIFEST } from './fixtures/manifest';

const PRODUCT = { context: 'eshop-catalog', name: 'product' };

function result(
  status: CommandResult['status'] = 'SUCCEEDED',
  stage: 'ACCEPTED' | 'COMMITTED' | 'HANDLED' = 'COMMITTED',
): CommandResult {
  return {
    format: 1,
    commandId: 'c1',
    requestId: 'r1',
    correlationId: 'c1',
    ref: { type: PRODUCT, tenantId: 't', id: 'p1' },
    hop: { stage },
    function: {
      kind: 'COMMAND',
      context: 'eshop-catalog',
      processor: 'product',
      name: 'CreateProduct',
    },
    status,
    version: 1,
    duplicate: false,
    error:
      status === 'SUCCEEDED'
        ? null
        : { code: 'eshop-catalog.product.blank-name', message: 'blank' },
    results: {},
    derived: [],
    signalTime: 1,
  };
}

function problemResponse(
  status: number,
  code: string | undefined,
  extra: Record<string, unknown> = {},
  headers: Record<string, string> = {},
): Response {
  return new Response(
    JSON.stringify({
      type: code ? `urn:wow:error:${code}` : 'about:blank',
      title: 'Problem',
      status,
      detail: 'details',
      ...(code ? { code } : {}),
      ...extra,
    }),
    {
      status,
      headers: { 'Content-Type': 'application/problem+json', ...headers },
    },
  );
}

const client = () => ({ fetcher: testFetcher() });

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('wait plans', () => {
  it('spells every hop as Wow-Wait reads it', () => {
    expect(
      formatWaitPlan([
        wait.accepted(),
        wait.committed(),
        wait.snapshot(),
        wait.handled('BasketCheckoutSaga.onBasketCheckedOut', 'eshop-basket'),
        wait.committed('eshop-ordering'),
        wait.snapshot('eshop-ordering'),
        wait.handled('OrderProcessSaga.onOrderSubmitted'),
        wait.accepted('eshop-inventory'),
      ]),
    ).toBe(
      'accepted > committed > snapshot > handled(eshop-basket/BasketCheckoutSaga.onBasketCheckedOut) > committed(eshop-ordering) > snapshot(eshop-ordering) > handled(OrderProcessSaga.onOrderSubmitted) > accepted(eshop-inventory)',
    );
    expect(formatWaitPlan('committed')).toBe('committed');
  });

  it('refuses what the header cannot carry', () => {
    expect(() => formatWaitPlan([])).toThrow(TypeError);
    expect(() => wait.committed('a b')).toThrow(TypeError);
    expect(() => wait.handled('x(y)')).toThrow(TypeError);
    expect(() => formatWaitPlan([{ stage: 'HANDLED' }])).toThrow(TypeError);
    expect(() =>
      formatWaitPlan([{ stage: 'COMMITTED', subscription: 'x' }]),
    ).toThrow(TypeError);
  });

  it('spells timeouts in seconds when whole, else milliseconds', () => {
    expect(formatWaitTimeout(10_000)).toBe('10s');
    expect(formatWaitTimeout(1500)).toBe('1500ms');
    expect(formatWaitTimeout('PT10S')).toBe('PT10S');
    expect(() => formatWaitTimeout(0)).toThrow(TypeError);
    expect(() => formatWaitTimeout(1.5)).toThrow(TypeError);
  });
});

describe('scopes and segments', () => {
  it('spells Wow-Scope as an RFC 8941 dictionary', () => {
    expect(
      formatScope({ tenantId: 't1', ownerId: 'u"1', spaceId: 's\\1' }),
    ).toBe('tenant="t1", owner="u\\"1", space="s\\\\1"');
    expect(() => formatScope({})).toThrow(TypeError);
  });
});

describe('CommandGateway', () => {
  it('sends the Wow headers, the id and the body', async () => {
    const { requests } = stubFetch(() => jsonResponse(result()));
    const gateway = new CommandGateway(client(), TEST_MANIFEST);
    const answered = await gateway.wait({
      command: 'eshop-catalog.product.CreateProduct',
      body: { name: 'n', price: 1 },
      id: 'p/1',
      requestId: 'r1',
      wait: [wait.committed()],
      timeout: 2000,
      expectedVersion: 3,
      targetTenant: 't',
      space: 's',
      headers: { 'X-User': 'alice' },
    });
    expect(answered.status).toBe('SUCCEEDED');
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

  it('sends {} without a body, and no Wow header it was not given but its request id', async () => {
    const { requests } = stubFetch(() => jsonResponse(result()));
    await new CommandGateway(client(), TEST_MANIFEST).wait({
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
    const gateway = new CommandGateway(client(), TEST_MANIFEST);
    await gateway.wait({
      command: 'Rename',
      type: { context: 'eshop-catalog', name: 'product' },
      id: 'p1',
    });
    expect(requests[0].url).toBe(`${BASE_URL}/eshop-catalog/product/p1/rename`);
    await expect(gateway.wait({ command: 'Rename', id: 'p1' })).rejects.toThrow(
      'not a full type name',
    );
    await expect(
      gateway.wait({ command: LifecycleCommands.DELETE_AGGREGATE, id: 'p1' }),
    ).rejects.toThrow("set the request's type");
    await expect(
      gateway.wait({ command: LifecycleCommands.APPLY_TAGS, type: PRODUCT }),
    ).rejects.toThrow("set the request's id");
    await expect(
      gateway.wait({ command: 'eshop-catalog.product.Rename' }),
    ).rejects.toThrow("set the request's id");
    await expect(
      gateway.wait({ command: 'eshop-catalog.product.Unknown', id: 'p1' }),
    ).rejects.toThrow('The Manifest lists no command Unknown');
    await gateway.wait({
      command: 'wow.snapshot.Rebuild',
      type: PRODUCT,
      body: { ids: ['p1'] },
    });
    await gateway.wait({
      command: 'wow.schedule.Enable',
      type: { context: 'eshop-catalog', name: 'schedule' },
      id: 'report',
    });
    expect(requests.slice(1).map(r => `${r.method} ${r.url}`)).toEqual([
      `POST ${BASE_URL}/eshop-catalog/product/wow.snapshot.rebuild`,
      `POST ${BASE_URL}/eshop-catalog/wow.schedule/report/enable`,
    ]);
  });

  it('sends a request by its own routes, else fetches the Manifest once', async () => {
    const { requests } = stubFetch(request =>
      jsonResponse(
        request.url.endsWith('/wow/manifest') ? TEST_MANIFEST : result(),
      ),
    );
    const gateway = new CommandGateway(client());
    await gateway.wait({
      command: 'shop.order.Create',
      routes: [{ method: 'POST', path: '/shop/order/{id}/place' }],
      id: 'o1',
    });
    await gateway.wait({ command: 'c.a.X', id: '1' });
    await gateway.wait({ command: 'shop.order.Create' });
    expect(requests.map(r => `${r.method} ${r.url}`)).toEqual([
      `POST ${BASE_URL}/shop/order/o1/place`,
      `GET ${BASE_URL}/wow/manifest`,
      `POST ${BASE_URL}/c/a/1/x`,
      `POST ${BASE_URL}/shop/order/create`,
    ]);
  });

  it('sends answering at accepted, its own timeout or 10 seconds, and waits for the plan', async () => {
    const { requests } = stubFetch(() => jsonResponse(result()));
    const gateway = new CommandGateway(client(), TEST_MANIFEST);
    await gateway.send({ command: 'c.a.X', id: '1', wait: [wait.accepted()] });
    await gateway.send({ command: 'c.a.X', id: '1', timeout: 3000 });
    // A request that waits for more is sent with wait, not send.
    await expect(
      gateway.send({ command: 'c.a.X', id: '1', wait: [wait.snapshot()] }),
    ).rejects.toThrow(
      'send answers on acceptance: use wait() for the plan snapshot.',
    );
    await gateway.wait({ command: 'c.a.X', id: '1' });
    expect(
      requests.map(r => [
        r.headers.get('Wow-Wait'),
        r.headers.get('Wow-Wait-Timeout'),
      ]),
    ).toEqual([
      ['accepted', '10s'],
      ['accepted', '3s'],
      [null, null],
    ]);
  });

  it('targets a scope only when the request names one', async () => {
    const { requests } = stubFetch(() => jsonResponse(result()));
    const gateway = new CommandGateway(client(), TEST_MANIFEST);
    await gateway.wait({
      command: 'c.a.X',
      id: '1',
      targetScope: { ownerId: 'bob' },
    });
    await gateway.wait({ command: 'c.a.X', id: '1' });
    expect(requests.map(r => r.headers.get('Wow-Scope'))).toEqual([
      'owner="bob"',
      null,
    ]);
  });

  it('rejects a command that did not succeed with its problem and result', async () => {
    stubFetch(() =>
      problemResponse(422, 'eshop-catalog.product.blank-name', {
        result: result('REJECTED'),
        violations: [{ path: 'name', code: 'NotBlank', message: 'blank' }],
      }),
    );
    const gateway = new CommandGateway(client(), TEST_MANIFEST);
    const error = await gateway
      .wait({ command: 'eshop-catalog.product.CreateProduct' })
      .catch(e => e);
    expect(error).toBeInstanceOf(WowProblemError);
    expect(error.status).toBe(422);
    expect(error.code).toBe('eshop-catalog.product.blank-name');
    expect(error.isFramework).toBe(false);
    expect(error.violations).toHaveLength(1);
    expect(error.result.status).toBe('REJECTED');
    expect(error.retry).toEqual({ retryable: false, sameRequestId: false });
    expect(error.message).toBe('[eshop-catalog.product.blank-name] details');
  });

  it('resolves the rejected result when asked', async () => {
    stubFetch(() =>
      problemResponse(422, 'eshop-catalog.product.blank-name', {
        result: result('REJECTED'),
      }),
    );
    const answered = await new CommandGateway(client(), TEST_MANIFEST).wait({
      command: 'eshop-catalog.product.CreateProduct',
      resolveRejected: true,
    });
    expect(answered.status).toBe('REJECTED');
  });

  it('still rejects a problem without a result when asked to resolve', async () => {
    stubFetch(() => problemResponse(401, 'wow.auth.unauthenticated'));
    await expect(
      new CommandGateway(client(), TEST_MANIFEST).wait({
        command: 'c.a.X',
        resolveRejected: true,
      }),
    ).rejects.toMatchObject({ code: FrameworkErrorCodes.AUTH_UNAUTHENTICATED });
  });

  it('carries the retry hint of a 503 and its Retry-After', async () => {
    stubFetch(() =>
      problemResponse(
        503,
        'wow.storage.unavailable',
        {},
        { 'Retry-After': '1' },
      ),
    );
    const error = await new CommandGateway(client(), TEST_MANIFEST)
      .wait({ command: 'c.a.X' })
      .catch(e => e);
    expect(error.retry).toEqual({
      retryable: true,
      sameRequestId: true,
      afterMs: 1000,
    });
    expect(error.isFramework).toBe(true);
  });

  it('streams one result per hop', async () => {
    const { requests } = stubFetch(() =>
      sseResponse(
        `id:0\nevent:accepted\ndata:${JSON.stringify(result('SUCCEEDED', 'ACCEPTED'))}\n\n` +
          `id:1\nevent:committed\ndata:${JSON.stringify(result('REJECTED'))}\n\n`,
      ),
    );
    const stream = await new CommandGateway(client(), TEST_MANIFEST).stream({
      command: 'c.a.X',
      id: '1',
    });
    const results = await readAll(stream);
    expect(results.map(r => [r.hop.stage, r.status])).toEqual([
      ['ACCEPTED', 'SUCCEEDED'],
      ['COMMITTED', 'REJECTED'],
    ]);
    expect(requests[0].headers.get('Accept')).toBe('text/event-stream');
  });

  it('reads a chain that ended before its plan, and an older server that says nothing of it', async () => {
    const ended: CommandResult = {
      ...result('SUCCEEDED', 'HANDLED'),
      hop: {
        stage: 'HANDLED',
        context: 'eshop-payment',
        subscription: 'OrderPaymentSaga.onPaymentSucceeded',
      },
      chainEnded: true,
      pending: [{ stage: 'COMMITTED', context: 'eshop-ordering' }],
    };
    stubFetch(() =>
      sseResponse(
        `id:0\nevent:committed\ndata:${JSON.stringify(result())}\n\n` +
          `id:1\nevent:handled\ndata:${JSON.stringify(ended)}\n\n`,
      ),
    );
    const results = await readAll(
      await new CommandGateway(client(), TEST_MANIFEST).stream({
        command: 'c.a.X',
        id: '1',
      }),
    );
    expect(results.map(r => r.chainEnded ?? false)).toEqual([false, true]);
    expect(results[1].status).toBe('SUCCEEDED');
    expect(results[1].pending).toEqual([
      { stage: 'COMMITTED', context: 'eshop-ordering' },
    ]);
    expect(formatWaitPlan(results[1].pending!)).toBe(
      'committed(eshop-ordering)',
    );

    stubFetch(() => jsonResponse(ended));
    const answered = await new CommandGateway(client(), TEST_MANIFEST).wait({
      command: 'c.a.X',
      id: '1',
    });
    expect(answered.chainEnded).toBe(true);
    expect(answered.pending).toHaveLength(1);
  });

  it('sends a generated request id when the options name none, and a failure carries it', async () => {
    // Review A3-16: a client that lost the answer retries with the same id.
    const UUID =
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
    let answer: () => Response | Promise<Response> = () =>
      jsonResponse(result());
    const { requests } = stubFetch(() => answer());
    const gateway = new CommandGateway(client(), TEST_MANIFEST);

    const rename = { command: 'eshop-catalog.product.Rename', id: 'p1' };
    await gateway.wait({ ...rename, body: { name: 'n' } });
    await gateway.wait({ ...rename, requestId: 'mine' });
    const generated = requests[0].headers.get('Wow-Request-Id');
    expect(generated).toMatch(UUID);
    expect(requests[1].headers.get('Wow-Request-Id')).toBe('mine');

    // The network fails: no answer, the error carries the id sent.
    answer = () => Promise.reject(new TypeError('Failed to fetch'));
    const lost = await gateway.wait(rename).catch((error: unknown) => error);
    expect(lost).toBeInstanceOf(Error);
    expect(lost).not.toBeInstanceOf(WowProblemError);
    expect(requestIdOf(lost)).toBe(requests[2].headers.get('Wow-Request-Id'));
    expect(requestIdOf(lost)).toMatch(UUID);
    const streamLost = await gateway
      .stream({ ...rename, requestId: 's1' })
      .catch((error: unknown) => error);
    expect(requestIdOf(streamLost)).toBe('s1');
    const deleteLost = await gateway
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
    const unknown = await gateway
      .wait({ ...rename, requestId: 'r-503' })
      .catch((error: unknown) => error);
    expect(unknown).toBeInstanceOf(WowProblemError);
    expect((unknown as WowProblemError).requestId).toBe('r-503');
    expect((unknown as WowProblemError).retry.sameRequestId).toBe(true);
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
    const gateway = new CommandGateway(client(), TEST_MANIFEST);
    const product = { type: PRODUCT, id: 'p1' };
    await gateway.wait({
      ...product,
      command: LifecycleCommands.DELETE_AGGREGATE,
      requestId: 'r',
    });
    await gateway.wait({
      ...product,
      command: LifecycleCommands.APPLY_TAGS,
      body: { tags: { region: ['eu'] } },
    });
    await gateway.wait({
      ...product,
      command: LifecycleCommands.RESTORE_AGGREGATE,
    });
    expect(requests.map(r => [r.method, r.url, r.body])).toEqual([
      ['DELETE', `${BASE_URL}/eshop-catalog/product/p1`, undefined],
      [
        'POST',
        `${BASE_URL}/eshop-catalog/product/p1/wow.lifecycle.apply-tags`,
        { tags: { region: ['eu'] } },
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
    await new CommandGateway(
      { fetcher: testFetcher(), basePath: '/api' },
      TEST_MANIFEST,
    ).wait({ command: 'c.a.X' });
    expect(requests[0].url).toBe(`${BASE_URL}/api/c/a/x`);
  });
});

describe('problems', () => {
  it('hints a retry for the codes a retry may get past', () => {
    expect(retryHintOf('wow.command.unknown', 503)).toEqual({
      retryable: true,
      sameRequestId: true,
    });
    expect(retryHintOf('wow.schedule.not-due', 503).retryable).toBe(true);
    expect(retryHintOf('wow.node.closing', 503).retryable).toBe(true);
    expect(retryHintOf('wow.node.standby', 503).retryable).toBe(true);
    expect(retryHintOf('wow.command.busy', 429).retryable).toBe(true);
    expect(retryHintOf('wow.command.contended', 409).retryable).toBe(true);
    expect(retryHintOf('wow.wait.timeout', 504).sameRequestId).toBe(true);
    // A query that ran out of time would run as long again (decision D254 ③).
    expect(retryHintOf('wow.query.timeout', 504)).toEqual({
      retryable: false,
      sameRequestId: false,
    });
    expect(retryHintOf('wow.query.busy', 429, 1000)).toEqual({
      retryable: true,
      sameRequestId: false,
      afterMs: 1000,
    });
    expect(retryHintOf(undefined, 503).retryable).toBe(true);
    expect(retryHintOf(undefined, 500).retryable).toBe(false);
    expect(retryHintOf('wow.aggregate.exists', 409).retryable).toBe(false);
    expect(retryHintOf('wow.schedule.job-invalid', 400).retryable).toBe(false);
  });

  it('reads Retry-After as seconds or a date', () => {
    expect(parseRetryAfter('2')).toBe(2000);
    expect(
      parseRetryAfter(
        'Wed, 21 Oct 2015 07:28:05 GMT',
        Date.UTC(2015, 9, 21, 7, 28, 0),
      ),
    ).toBe(5000);
    expect(parseRetryAfter('soon')).toBeUndefined();
    expect(parseRetryAfter(null)).toBeUndefined();
  });

  it('reads the code from the type when the body names none', async () => {
    const problem = await readProblem(
      new Response(
        JSON.stringify({ type: 'urn:wow:error:wow.node.closing', status: 503 }),
        { status: 503, statusText: 'Service Unavailable' },
      ),
    );
    expect(problem).toMatchObject({
      code: 'wow.node.closing',
      title: 'Service Unavailable',
    });
  });

  it('gathers the extension members of a problem as its extensions', async () => {
    const boards = [{ id: 'b1', title: 'Ops' }];
    const problem = await readProblem(
      new Response(
        JSON.stringify({
          type: 'urn:wow:error:view-store.referenced-by-shared-dashboard',
          title: 'Unprocessable Content',
          status: 422,
          detail: 'Shown by shared dashboards.',
          instance: '/view-store/view/v1/claim-view',
          code: 'view-store.referenced-by-shared-dashboard',
          boards,
        }),
        { status: 422 },
      ),
    );
    expect(problem.extensions).toEqual({ boards });
    expect(problem.detail).toBe('Shown by shared dashboards.');
    const streamed = new WowProblemError({
      type: 'about:blank',
      title: 'Failed',
      status: 500,
      hint: 'x',
    } as Problem);
    expect(streamed.problem.extensions).toEqual({ hint: 'x' });
    const given = { type: 'x', title: 't', status: 500, extensions: { a: 1 } };
    expect(new WowProblemError(given).problem).toBe(given);
    expect(
      new WowProblemError({ type: 'x', title: 't', status: 500 }).problem
        .extensions,
    ).toBeUndefined();
  });

  it('makes a problem of a status when the body is not one', async () => {
    const problem = await readProblem(
      new Response('oops', { status: 502, statusText: 'Bad Gateway' }),
    );
    expect(problem).toEqual({
      type: 'about:blank',
      title: 'Bad Gateway',
      status: 502,
    });
    const error = new WowProblemError(problem);
    expect(error.code).toBeUndefined();
    expect(error.isFramework).toBe(false);
    expect(error.message).toBe('502 Bad Gateway');
  });

  it('converts only what a server answered', async () => {
    expect(await toWowProblemError(new Error('network'))).toBeUndefined();
    expect(await toWowProblemError(null)).toBeUndefined();
    const known = new WowProblemError({ type: 'x', title: 't', status: 500 });
    expect(await toWowProblemError(known)).toBe(known);
    expect(await toWowProblemError({ cause: known })).toBe(known);
    expect(
      await toWowProblemError({ exchange: { response: new Response('{}') } }),
    ).toBeUndefined();
  });
});

describe('query clients', () => {
  it('answers a single query that matches nothing with undefined, and any other 404 with its problem', async () => {
    const products = new SnapshotQueryClient<{ name: string }, 'state.name'>(
      PRODUCT,
      client(),
    );
    const query = { filter: filter.eq('state.name', 'n') };
    stubFetch(() => problemResponse(404, FrameworkErrorCodes.QUERY_NOT_FOUND));
    expect(await products.single(query)).toBeUndefined();
    stubFetch(() =>
      problemResponse(404, FrameworkErrorCodes.QUERY_DATASET_NOT_FOUND),
    );
    await expect(products.single(query)).rejects.toMatchObject({
      status: 404,
      code: FrameworkErrorCodes.QUERY_DATASET_NOT_FOUND,
    });
    // A route that does not exist answers a 404 without a Wow code.
    stubFetch(() => problemResponse(404, undefined));
    await expect(products.single(query)).rejects.toMatchObject({
      status: 404,
    });
  });

  it('posts each operation with its kind', async () => {
    const { requests } = stubFetch(() => jsonResponse([]));
    const products = new SnapshotQueryClient<{ name: string }, 'state.name'>(
      PRODUCT,
      client(),
    );
    const query = { filter: filter.eq('state.name', 'n') };
    await products.list({ ...query, limit: 5 });
    await products.paged({ ...query, total: 'EXACT' });
    await products.cursor({ ...query, size: 2 });
    await products.single(query);
    await products.count({ ...query, limit: 10 });
    await products.aggregate({ metrics: [aggregation.count('n')] });
    expect(requests.map(r => (r.body as { kind: string }).kind)).toEqual([
      'list',
      'paged',
      'cursor',
      'single',
      'count',
      'aggregate',
    ]);
    expect(new Set(requests.map(r => r.url))).toEqual(
      new Set([`${BASE_URL}/eshop-catalog/product/snapshot/query`]),
    );
    expect(requests[0].body).toEqual({
      kind: 'list',
      filter: { op: 'EQ', field: 'state.name', value: 'n' },
      limit: 5,
    });
  });

  it('streams rows until an error event', async () => {
    const { requests } = stubFetch(() =>
      sseResponse(
        'id:0\nevent:row\ndata:{"version":1}\n\n' +
          'event:error\ndata:{"type":"urn:wow:error:wow.query.failed","title":"t","status":500,"code":"wow.query.failed"}\n\n',
      ),
    );
    const events = new EventQueryClient(PRODUCT, client());
    const stream = await events.stream({
      filter: filter.eq('aggregateId', 'p1'),
    });
    const rows: unknown[] = [];
    const failure = await (async () => {
      for await (const row of stream) rows.push(row);
    })().catch(e => e);
    expect(rows).toEqual([{ version: 1 }]);
    expect(failure).toBeInstanceOf(WowProblemError);
    expect(failure.code).toBe('wow.query.failed');
    expect(requests[0].url).toBe(
      `${BASE_URL}/eshop-catalog/product/events/query`,
    );
    expect(requests[0].headers.get('Accept')).toBe('text/event-stream');
  });

  it('queries the events model on the unified engine (decision D235)', async () => {
    const { requests } = stubFetch(request =>
      request.method === 'GET'
        ? new Response(JSON.stringify({ model: 'EVENTS', version: 'v2' }), {
            status: 200,
            headers: { ETag: '"v2"', 'Content-Type': 'application/json' },
          })
        : jsonResponse({ count: 2, exact: true }),
    );
    const events = new EventQueryClient(PRODUCT, client());
    const added = filter.elementMatch(
      'events',
      filter.eq('name', 'eshop-catalog.product.ProductAdded'),
    );
    expect(await events.count({ filter: added })).toEqual({
      count: 2,
      exact: true,
    });
    await events.cursor({ filter: filter.eq('commandType', 'x'), size: 10 });
    await events.aggregate({
      elements: [aggregation.element('events')],
      groupBy: [aggregation.terms('name', 'event')],
      metrics: [aggregation.count('n')],
    });
    const described = await events.describe();
    expect(described).toMatchObject({ notModified: false, version: 'v2' });
    expect(new Set(requests.map(r => r.url))).toEqual(
      new Set([`${BASE_URL}/eshop-catalog/product/events/query`]),
    );
    expect(requests[0].body).toEqual({
      kind: 'count',
      filter: {
        op: 'ELEMENT_MATCH',
        field: 'events',
        predicate: {
          op: 'EQ',
          field: 'name',
          value: 'eshop-catalog.product.ProductAdded',
        },
      },
    });
    expect(requests[2].body).toMatchObject({
      kind: 'aggregate',
      elements: [{ path: 'events' }],
    });
  });

  it('ends a stream whose error event is no problem with a 500', async () => {
    stubFetch(() => sseResponse('event:error\ndata:{}\n\n'));
    const stream = await new EventQueryClient(PRODUCT, client()).stream({
      filter: filter.eq('aggregateId', 'p1'),
    });
    await expect(readAll(stream)).rejects.toMatchObject({ status: 500 });
  });

  it('describes a model, conditionally on the version held', async () => {
    const { requests } = stubFetch(request =>
      request.headers.get('If-None-Match') === '"v1"'
        ? new Response(null, { status: 304, headers: { ETag: '"v1"' } })
        : new Response(JSON.stringify({ model: 'snapshot' }), {
            status: 200,
            headers: { ETag: '"v1"', 'Content-Type': 'application/json' },
          }),
    );
    const products = new SnapshotQueryClient(PRODUCT, client());
    expect(await products.describe()).toEqual({
      notModified: false,
      version: 'v1',
      descriptor: { model: 'snapshot' },
    });
    expect(await products.describe('v1')).toEqual({
      notModified: true,
      version: 'v1',
    });
    expect(requests[0].method).toBe('GET');
  });

  it('answers a descriptor without an ETag', async () => {
    stubFetch(() => jsonResponse({ model: 'events' }));
    expect(await new EventQueryClient(PRODUCT, client()).describe()).toEqual({
      notModified: false,
      descriptor: { model: 'events' },
    });
  });

  it('lists and queries datasets', async () => {
    const { requests } = stubFetch(() => jsonResponse([]));
    const datasets = new DatasetClient(client());
    await datasets.list();
    await datasets.dataset('eshop-ordering', 'sales').list({
      filter: filter.matchAll(),
    });
    expect(requests.map(r => [r.method, r.url])).toEqual([
      ['GET', `${BASE_URL}/wow/dataset`],
      ['POST', `${BASE_URL}/wow/dataset/eshop-ordering/sales/query`],
    ]);
  });
});

describe('StateClient', () => {
  const view = {
    ref: { type: PRODUCT, tenantId: 't', id: 'p1' },
    version: 2,
    scope: {},
    deleted: false,
    tags: {},
    state: { name: 'n' },
  };

  it('reads the latest state, at a version or a time, or of a minimum version', async () => {
    const { requests } = stubFetch(() => jsonResponse(view));
    const states = new StateClient<{ name: string }>(PRODUCT, client());
    expect((await states.get('p1')).state.name).toBe('n');
    await states.get('p1', { version: 2 });
    await states.get('p1', { at: new Date(Date.UTC(2026, 0, 1)) });
    await states.get('p1', { minVersion: 3 });
    expect(requests.map(r => r.url)).toEqual([
      `${BASE_URL}/eshop-catalog/product/p1/state`,
      `${BASE_URL}/eshop-catalog/product/p1/state?version=2`,
      `${BASE_URL}/eshop-catalog/product/p1/state?at=2026-01-01T00%3A00%3A00.000Z`,
      `${BASE_URL}/eshop-catalog/product/p1/state?minVersion=3`,
    ]);
  });

  it('answers notModified for the entity tag held', async () => {
    const { requests } = stubFetch(
      () =>
        new Response(null, {
          status: 304,
          headers: { ETag: '"2-0123456789abcdef"' },
        }),
    );
    const states = new StateClient(PRODUCT, client());
    expect(
      await states.read('p1', { ifNoneMatch: '2-0123456789abcdef' }),
    ).toEqual({
      notModified: true,
      version: 2,
      etag: '2-0123456789abcdef',
    });
    expect(requests[0].headers.get('If-None-Match')).toBe(
      '"2-0123456789abcdef"',
    );
    await expect(states.get('p1')).rejects.toThrow('304');
  });

  it('answers a state with its entity tag', async () => {
    stubFetch(
      () =>
        new Response(JSON.stringify(view), {
          headers: {
            'Content-Type': 'application/json',
            ETag: '"2-0123456789abcdef"',
          },
        }),
    );
    expect(await new StateClient(PRODUCT, client()).read('p1')).toEqual({
      notModified: false,
      view,
      etag: '2-0123456789abcdef',
    });
  });

  it('rejects a missing state with its problem', async () => {
    stubFetch(() => problemResponse(404, 'wow.aggregate.not-found'));
    await expect(
      new StateClient(PRODUCT, client()).get('p1'),
    ).rejects.toMatchObject({ code: 'wow.aggregate.not-found', status: 404 });
  });

  it('finds a state, or undefined only for an aggregate that does not exist', async () => {
    let answer: () => Response = () => jsonResponse(view);
    stubFetch(() => answer());
    const states = new StateClient<{ name: string }>(PRODUCT, client());
    expect((await states.find('p1'))?.state.name).toBe('n');
    answer = () => problemResponse(404, 'wow.aggregate.not-found');
    expect(await states.find('p1', { version: 2 })).toBeUndefined();
    // Another 404, a route or a query that does not exist, is a mistake.
    answer = () => problemResponse(404, 'wow.query.not-found');
    await expect(states.find('p1')).rejects.toMatchObject({ status: 404 });
    answer = () => problemResponse(403, 'wow.aggregate.forbidden');
    await expect(states.find('p1')).rejects.toMatchObject({ status: 403 });
  });

  it('reads a trace', async () => {
    const { requests } = stubFetch(() =>
      jsonResponse({ ref: view.ref, commits: [] }),
    );
    await new StateClient(PRODUCT, client()).trace('p1', {
      from: 2,
      to: 5,
      limit: 3,
    });
    expect(requests[0].url).toBe(
      `${BASE_URL}/eshop-catalog/product/p1/state/trace?from=2&to=5&limit=3`,
    );
  });
  it("types the system events a trace carries beside the aggregate's own", async () => {
    // The shape edge/http's HistoryRoutesTest reads from a trace after
    // `wow.lifecycle.transfer-owner`, and an event of the aggregate itself,
    // named `context.aggregate.EventName`.
    type ProductEvent =
      | {
          readonly name: 'eshop-catalog.product.ProductRenamed';
          readonly revision: number;
          readonly body: { readonly title: string };
        }
      | SystemEvent;
    const commit = (version: number, events: unknown[]) => ({
      version,
      commitTime: version * 1000,
      commandId: `c${version}`,
      requestId: `r${version}`,
      commandType: 'wow.lifecycle.TransferOwner',
      events,
      scope: { ownerId: 'bob' },
      deleted: false,
      tags: {},
      state: {},
    });
    stubFetch(() =>
      jsonResponse({
        ref: view.ref,
        commits: [
          commit(4, [
            {
              name: 'wow.lifecycle.OwnerTransferred',
              revision: 1,
              body: { ownerId: 'bob' },
            },
          ]),
          commit(5, [
            {
              name: 'eshop-catalog.product.ProductRenamed',
              revision: 1,
              body: { title: 'Pear' },
            },
            { name: 'wow.ScheduleCancelled', revision: 1, body: { key: 'k' } },
          ]),
        ],
      }),
    );
    const trace = await new StateClient<unknown, ProductEvent>(
      PRODUCT,
      client(),
    ).trace('p1');
    const read = trace.commits.flatMap(c =>
      c.events.map(event => {
        switch (event.name) {
          case 'wow.lifecycle.OwnerTransferred':
            return event.body.ownerId;
          case 'eshop-catalog.product.ProductRenamed':
            return event.body.title;
          case 'wow.ScheduleCancelled':
            return event.body.key;
          default:
            return event.name;
        }
      }),
    );
    expect(read).toEqual(['bob', 'Pear', 'k']);
    const deleted: SystemEvent = {
      name: 'wow.lifecycle.AggregateDeleted',
      revision: 1,
      body: {},
    };
    const wrong: SystemEvent = {
      name: 'wow.lifecycle.SpaceTransferred',
      revision: 1,
      // @ts-expect-error a lifecycle event's body is the kernel's shape
      body: { ownerId: 'x' },
    };
    void [deleted, wrong];
  });
});

describe('fetchManifest', () => {
  it('gets /wow/manifest', async () => {
    const { requests } = stubFetch(() =>
      jsonResponse({ format: 1, contexts: [] }),
    );
    expect(await fetchManifest(client())).toEqual({ format: 1, contexts: [] });
    expect(requests[0].url).toBe(`${BASE_URL}/wow/manifest`);
  });
});
