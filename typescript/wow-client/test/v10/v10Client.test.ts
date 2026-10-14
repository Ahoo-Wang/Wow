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
import { aggregation, filter } from '../../src/dsl';
import {
  CommandGateway,
  type CommandResult,
  commandSegment,
  DatasetClient,
  EventQueryClient,
  fetchManifest,
  formatScope,
  formatWaitPlan,
  formatWaitTimeout,
  FrameworkErrorCodes,
  LifecycleCommands,
  parseRetryAfter,
  readProblem,
  retryHintOf,
  SnapshotQueryClient,
  StateClient,
  toWowProblemError,
  wait,
  WowProblemError,
} from '../../src/v10';
import {
  BASE_URL,
  jsonResponse,
  readAll,
  sseResponse,
  stubFetch,
  testFetcher,
} from '../client/fetchStub';

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

  it('kebab-cases command names as the server routes them', () => {
    expect(commandSegment('AddCartItem')).toBe('add-cart-item');
    expect(commandSegment('HTTPRequest')).toBe('http-request');
    expect(commandSegment('AddItemV2')).toBe('add-item-v2');
    expect(commandSegment('add_item')).toBe('add-item');
    expect(commandSegment('wow.lifecycle.RestoreAggregate')).toBe(
      'wow.lifecycle.restore-aggregate',
    );
  });
});

describe('CommandGateway', () => {
  it('sends the Wow headers, the id and the body', async () => {
    const { requests } = stubFetch(() => jsonResponse(result()));
    const gateway = new CommandGateway(client());
    const answered = await gateway.send(
      '/eshop-catalog/product/{id}/create-product',
      { name: 'n', price: 1 },
      {
        id: 'p/1',
        requestId: 'r1',
        wait: { plan: [wait.committed()], timeout: 2000 },
        expectedVersion: 3,
        scope: { tenantId: 't' },
        space: 's',
        headers: { 'X-User': 'alice' },
      },
    );
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

  it('sends {} without a body, and no Wow header it was not given', async () => {
    const { requests } = stubFetch(() => jsonResponse(result()));
    await new CommandGateway(client()).send(
      '/eshop-catalog/product/create-product',
    );
    expect(requests[0].body).toEqual({});
    expect(
      [...requests[0].headers.keys()].filter(h => h.startsWith('wow-')),
    ).toEqual([]);
  });

  it('needs the id of a route that names it', async () => {
    stubFetch(() => jsonResponse(result()));
    await expect(
      new CommandGateway(client()).send('/c/a/{id}/x', {}),
    ).rejects.toThrow(TypeError);
  });

  it('rejects a command that did not succeed with its problem and result', async () => {
    stubFetch(() =>
      problemResponse(422, 'eshop-catalog.product.blank-name', {
        result: result('REJECTED'),
        violations: [{ path: 'name', code: 'NotBlank', message: 'blank' }],
      }),
    );
    const gateway = new CommandGateway(client());
    const error = await gateway
      .send('/eshop-catalog/product/create-product', {})
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
    const answered = await new CommandGateway(client()).send(
      '/eshop-catalog/product/create-product',
      {},
      { resolveRejected: true },
    );
    expect(answered.status).toBe('REJECTED');
  });

  it('still rejects a problem without a result when asked to resolve', async () => {
    stubFetch(() => problemResponse(401, 'wow.auth.unauthenticated'));
    await expect(
      new CommandGateway(client()).send(
        '/c/a/x',
        {},
        { resolveRejected: true },
      ),
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
    const error = await new CommandGateway(client())
      .send('/c/a/x', {})
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
    const stream = await new CommandGateway(client()).stream(
      '/c/a/{id}/x',
      {},
      { id: '1' },
    );
    const results = await readAll(stream);
    expect(results.map(r => [r.hop.stage, r.status])).toEqual([
      ['ACCEPTED', 'SUCCEEDED'],
      ['COMMITTED', 'REJECTED'],
    ]);
    expect(requests[0].headers.get('Accept')).toBe('text/event-stream');
  });

  it('sends lifecycle commands to their routes', async () => {
    const { requests } = stubFetch(() => jsonResponse(result()));
    const gateway = new CommandGateway(client());
    await gateway.lifecycle(
      '/eshop-catalog/product',
      'p1',
      LifecycleCommands.DELETE_AGGREGATE,
      undefined,
      { requestId: 'r' },
    );
    await gateway.lifecycle(
      '/eshop-catalog/product',
      'p1',
      LifecycleCommands.APPLY_TAGS,
      { tags: { region: ['eu'] } },
    );
    await gateway.lifecycle(
      '/eshop-catalog/product',
      'p1',
      LifecycleCommands.RESTORE_AGGREGATE,
    );
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
    await new CommandGateway({
      fetcher: testFetcher(),
      basePath: '/api',
    }).send('/c/a/x');
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
    expect(retryHintOf('wow.command.busy', 429).retryable).toBe(true);
    expect(retryHintOf('wow.command.contended', 409).retryable).toBe(true);
    expect(retryHintOf('wow.wait.timeout', 504).sameRequestId).toBe(true);
    expect(retryHintOf('wow.query.timeout', 503)).toEqual({
      retryable: true,
      sameRequestId: false,
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
    const stream = await events.listStream({
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

  it('ends a stream whose error event is no problem with a 500', async () => {
    stubFetch(() => sseResponse('event:error\ndata:{}\n\n'));
    const stream = await new EventQueryClient(PRODUCT, client()).listStream({
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

  it('reads the latest state, at a version or a time', async () => {
    const { requests } = stubFetch(() => jsonResponse(view));
    const states = new StateClient<{ name: string }>(PRODUCT, client());
    expect((await states.get('p1')).state.name).toBe('n');
    await states.get('p1', { version: 2 });
    await states.get('p1', { at: new Date(Date.UTC(2026, 0, 1)) });
    expect(requests.map(r => r.url)).toEqual([
      `${BASE_URL}/eshop-catalog/product/p1/state`,
      `${BASE_URL}/eshop-catalog/product/p1/state?version=2`,
      `${BASE_URL}/eshop-catalog/product/p1/state?at=2026-01-01T00%3A00%3A00.000Z`,
    ]);
  });

  it('answers notModified for the version held', async () => {
    stubFetch(
      () => new Response(null, { status: 304, headers: { ETag: '"2"' } }),
    );
    const states = new StateClient(PRODUCT, client());
    expect(await states.read('p1', { ifNoneMatch: 2 })).toEqual({
      notModified: true,
      version: 2,
    });
    await expect(states.get('p1')).rejects.toThrow('304');
  });

  it('rejects a missing state with its problem', async () => {
    stubFetch(() => problemResponse(404, 'wow.aggregate.not-found'));
    await expect(
      new StateClient(PRODUCT, client()).get('p1'),
    ).rejects.toMatchObject({ code: 'wow.aggregate.not-found', status: 404 });
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
