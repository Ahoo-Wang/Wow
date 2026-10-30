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

import { afterEach, describe, expect, expectTypeOf, it, vi } from 'vitest';
import { aggregation, filter, type FieldSortDescriptor } from '../src/dsl.js';
import {
  checkManifest,
  CommandClient,
  CommandRejectedError,
  type CommandResult,
  DatasetClient,
  EventQueryClient,
  fetchManifest,
  formatScope,
  formatWaitPlan,
  formatWaitTimeout,
  FrameworkErrorCodes,
  type Hop,
  LifecycleCommands,
  MANIFEST_FEATURES,
  newRequestId,
  OwnStateClient,
  parseRetryAfter,
  parseWaitPlan,
  type Problem,
  type RefusedResult,
  readProblem,
  type RouteManifest,
  requestIdOf,
  retryOf,
  SnapshotQueryClient,
  StateClient,
  type SystemEvent,
  toWowProblemError,
  wait,
  WaitPlan,
  WowProblemError,
} from '../src/index.js';
import { rememberRequestId } from '../src/problem.js';
import { snapshotOf } from '../src/testing.js';
import {
  BASE_URL,
  jsonResponse,
  readAll,
  sseResponse,
  stubFetch,
  testFetcher,
} from './fixtures/fetchStub';
import {
  aggregate,
  context,
  manifestOf,
  TEST_MANIFEST,
} from './fixtures/manifest';

const PRODUCT = { context: 'eshop-catalog', name: 'product' };

function result(
  status: CommandResult['status'] = 'succeeded',
  stage: 'accepted' | 'committed' | 'handled' = 'committed',
): CommandResult {
  return {
    format: 1,
    commandId: 'c1',
    requestId: 'r1',
    correlationId: 'c1',
    ref: { type: PRODUCT, tenantId: 't', id: 'p1' },
    hop: { stage },
    function: {
      kind: 'command',
      context: 'eshop-catalog',
      processor: 'product',
      name: 'CreateProduct',
    },
    status,
    version: 1,
    duplicate: false,
    error:
      status === 'succeeded'
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
  it('spells every hop as Wow-Wait reads it, and reads it back', () => {
    const plan = WaitPlan.of(
      wait.accepted(),
      wait.committed(),
      wait.snapshot(),
      wait.handled('BasketCheckoutSaga.onBasketCheckedOut', 'eshop-basket'),
      wait.committed('eshop-ordering'),
      wait.snapshot('eshop-ordering'),
      wait.handled('OrderProcessSaga.onOrderSubmitted'),
      wait.accepted('eshop-inventory'),
    );
    const spelled =
      'accepted > committed > snapshot > handled(eshop-basket/BasketCheckoutSaga.onBasketCheckedOut) > committed(eshop-ordering) > snapshot(eshop-ordering) > handled(OrderProcessSaga.onOrderSubmitted) > accepted(eshop-inventory)';
    expect(formatWaitPlan(plan)).toBe(spelled);
    expect(parseWaitPlan(spelled).hops).toEqual(plan.hops);
    expect(
      parseWaitPlan(
        ' committed(ctx) >handled( wow.snapshot ) > handled(snapshot)',
      ).hops,
    ).toEqual([
      wait.committed('ctx'),
      wait.snapshot(),
      wait.handled('snapshot'),
    ]);
  });

  it('holds Kotlin WaitPlan constants, a default timeout of 10 seconds, and within', () => {
    expect(WaitPlan.DEFAULT_TIMEOUT).toBe(10_000);
    expect(WaitPlan.DEFAULT).toMatchObject({
      hops: [wait.snapshot()],
      timeout: 10_000,
      isDefault: true,
    });
    expect(WaitPlan.SNAPSHOT.isDefault).toBe(false);
    expect(formatWaitPlan(WaitPlan.COMMITTED)).toBe('committed');
    expect(formatWaitPlan(WaitPlan.ACCEPTED)).toBe('accepted');
    const within = WaitPlan.COMMITTED.within(3000);
    expect(within.timeout).toBe(3000);
    expect(WaitPlan.COMMITTED.timeout).toBe(10_000);
    expect(WaitPlan.DEFAULT.within(1000).isDefault).toBe(true);
    expect(Object.isFrozen(within)).toBe(true);
  });

  it('refuses what the header cannot carry', () => {
    expect(() => WaitPlan.of()).toThrow(TypeError);
    expect(() => WaitPlan.COMMITTED.within(0)).toThrow(TypeError);
    expect(() => wait.committed('a b')).toThrow(TypeError);
    expect(() => wait.handled('x(y)')).toThrow(TypeError);
    expect(() =>
      formatWaitPlan(WaitPlan.of({ stage: 'handled' } as Hop)),
    ).toThrow(TypeError);
    expect(() =>
      formatWaitPlan(
        WaitPlan.of({ stage: 'committed', subscription: 'x' } as Hop),
      ),
    ).toThrow(TypeError);
    for (const value of [
      'committed(',
      'accepted(a(b))',
      '> committed',
      '',
      'shipped',
      'handled',
      'handled(a/b/c)',
      'committed(a b)',
    ])
      expect(() => parseWaitPlan(value), value).toThrow(TypeError);
  });

  it('spells timeouts in seconds when whole, else milliseconds', () => {
    expect(formatWaitTimeout(10_000)).toBe('10s');
    expect(formatWaitTimeout(1500)).toBe('1500ms');
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
      commands.wait({ command: LifecycleCommands.APPLY_TAGS, type: PRODUCT }),
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

  it('builds a rejection of a rejected result only, its words from what the result says', () => {
    const bare = { ...result('rejected'), error: null };
    const rejected = new CommandRejectedError(bare);
    expect(rejected.code).toBe('');
    expect(rejected.message).toBe(
      'eshop-catalog.product/p1 rejected the command.',
    );
    expect(rejected.requestId).toBe('r1');
    expect(rejected.problem).toBeUndefined();
    expect(() => new CommandRejectedError(result('failed'))).toThrow(
      'A rejection has a rejected result, not failed.',
    );
  });

  it('remembers the request id of the first send an error failed, and none for what is not an object', () => {
    const error = new Error('down');
    rememberRequestId(error, 'first');
    rememberRequestId(error, 'second');
    expect(requestIdOf(error)).toBe('first');
    rememberRequestId('down', 'r');
    expect(requestIdOf('down')).toBeUndefined();
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

  it('rejects a rejected result with a CommandRejectedError, its problem when one came', async () => {
    stubFetch(() =>
      problemResponse(422, 'eshop-catalog.product.blank-name', {
        result: result('rejected'),
        violations: [{ path: 'name', code: 'not-blank', message: 'blank' }],
      }),
    );
    const commands = new CommandClient(client(), { manifest: TEST_MANIFEST });
    const error = await commands
      .wait({ command: 'eshop-catalog.product.CreateProduct' })
      .catch((e: unknown) => e);
    expect(error).toBeInstanceOf(CommandRejectedError);
    const rejected = error as CommandRejectedError;
    expect(rejected.code).toBe('eshop-catalog.product.blank-name');
    expect(rejected.result.status).toBe('rejected');
    expect(rejected.requestId).toBe('r1');
    expect(rejected.problem?.status).toBe(422);
    expect(rejected.problem?.violations).toHaveLength(1);
    expect(rejected.cause).toBeInstanceOf(WowProblemError);
    expect(rejected.message).toBe(
      'eshop-catalog.product/p1 rejected the command: eshop-catalog.product.blank-name blank',
    );

    // A waited result the aggregate rejected, answered 200, and with send.
    stubFetch(() => jsonResponse(result('rejected')));
    const answered = await commands
      .wait({ command: 'c.a.X', id: '1' })
      .catch((e: unknown) => e);
    expect(answered).toBeInstanceOf(CommandRejectedError);
    expect((answered as CommandRejectedError).problem).toBeUndefined();
    await expect(commands.send({ command: 'c.a.X', id: '1' })).rejects.toThrow(
      CommandRejectedError,
    );
  });

  it('rejects an admission refusal that decided no aggregate id as a rejection, with no id', async () => {
    const refused = {
      ...result('rejected'),
      ref: { ...result('rejected').ref, id: null },
    };
    stubFetch(() =>
      problemResponse(400, 'wow.command.invalid', { result: refused }),
    );
    const error = await new CommandClient(client(), { manifest: TEST_MANIFEST })
      .wait({ command: 'eshop-catalog.product.CreateProduct' })
      .catch((e: unknown) => e);
    expect(error).toBeInstanceOf(CommandRejectedError);
    expect((error as CommandRejectedError).result.ref.id).toBeNull();
    expect((error as CommandRejectedError).message).toContain(
      'eshop-catalog.product/(no id) rejected',
    );
  });

  it('rejects anything else with a WowProblemError: no result, a failed one, a status it does not know', async () => {
    const commands = new CommandClient(client(), { manifest: TEST_MANIFEST });
    stubFetch(() => problemResponse(401, 'wow.auth.unauthenticated'));
    await expect(commands.wait({ command: 'c.a.X' })).rejects.toMatchObject({
      name: 'WowProblemError',
      code: FrameworkErrorCodes.AUTH_UNAUTHENTICATED,
    });
    stubFetch(() =>
      problemResponse(500, 'wow.command.failed', { result: result('failed') }),
    );
    const failed = await commands
      .wait({ command: 'c.a.X' })
      .catch((e: unknown) => e);
    expect(failed).toBeInstanceOf(WowProblemError);
    expect((failed as WowProblemError).result?.status).toBe('failed');
    stubFetch(() => jsonResponse(result('paused-by-a-newer-service')));
    const newer = await commands
      .wait({ command: 'c.a.X' })
      .catch((e: unknown) => e);
    expect(newer).toBeInstanceOf(WowProblemError);
    expect((newer as WowProblemError).code).toBe(
      FrameworkErrorCodes.COMMAND_FAILED,
    );
    expect((newer as WowProblemError).retry).toBe('none');
    expect((newer as WowProblemError).result?.status).toBe(
      'paused-by-a-newer-service',
    );
  });

  it('types what send and wait answer as a result that succeeded, and a rejection as one that may name no id', () => {
    const answers = (commands: CommandClient) => ({
      waited: commands.wait({ command: 'c.a.X' }),
      sent: commands.send({ command: 'c.a.X' }),
    });
    type Answers = ReturnType<typeof answers>;
    expectTypeOf<Answers['waited']>().toEqualTypeOf<Promise<CommandResult>>();
    expectTypeOf<Answers['sent']>().toEqualTypeOf<Promise<CommandResult>>();
    expectTypeOf<CommandResult['ref']['id']>().toEqualTypeOf<string>();
    expectTypeOf<RefusedResult['ref']['id']>().toEqualTypeOf<string | null>();
    expectTypeOf<
      CommandRejectedError['result']
    >().toEqualTypeOf<RefusedResult>();
    expectTypeOf<WowProblemError['result']>().toEqualTypeOf<
      RefusedResult | undefined
    >();
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
    const error = (await new CommandClient(client(), {
      manifest: TEST_MANIFEST,
    })
      .wait({ command: 'c.a.X' })
      .catch((e: unknown) => e)) as WowProblemError;
    expect(error.retry).toBe('same-request');
    expect(error.retryAfterMs).toBe(1000);
    expect(error.isFramework).toBe(true);
  });

  it('streams one result per hop', async () => {
    const { requests } = stubFetch(() =>
      sseResponse(
        `id:0\nevent:accepted\ndata:${JSON.stringify(result('succeeded', 'accepted'))}\n\n` +
          `id:1\nevent:committed\ndata:${JSON.stringify(result('rejected'))}\n\n`,
      ),
    );
    const stream = await new CommandClient(client(), {
      manifest: TEST_MANIFEST,
    }).stream({
      command: 'c.a.X',
      id: '1',
    });
    const results = await readAll(stream);
    expect(results.map(r => [r.hop.stage, r.status])).toEqual([
      ['accepted', 'succeeded'],
      ['committed', 'rejected'],
    ]);
    expect(requests[0].headers.get('Accept')).toBe('text/event-stream');
  });

  it('reads a chain that ended before its plan, and an older server that says nothing of it', async () => {
    const ended: CommandResult = {
      ...result('succeeded', 'handled'),
      hop: {
        stage: 'handled',
        context: 'eshop-payment',
        subscription: 'OrderPaymentSaga.onPaymentSucceeded',
      },
      chainEnded: true,
      pending: [{ stage: 'committed', context: 'eshop-ordering' }],
    };
    stubFetch(() =>
      sseResponse(
        `id:0\nevent:committed\ndata:${JSON.stringify(result())}\n\n` +
          `id:1\nevent:handled\ndata:${JSON.stringify(ended)}\n\n`,
      ),
    );
    const results = await readAll(
      await new CommandClient(client(), { manifest: TEST_MANIFEST }).stream({
        command: 'c.a.X',
        id: '1',
      }),
    );
    expect(results.map(r => r.chainEnded ?? false)).toEqual([false, true]);
    expect(results[1].status).toBe('succeeded');
    expect(results[1].pending).toEqual([
      { stage: 'committed', context: 'eshop-ordering' },
    ]);
    expect(formatWaitPlan(WaitPlan.of(...results[1].pending!))).toBe(
      'committed(eshop-ordering)',
    );

    stubFetch(() => jsonResponse(ended));
    const answered = await new CommandClient(client(), {
      manifest: TEST_MANIFEST,
    }).wait({
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
      command: LifecycleCommands.APPLY_TAGS,
      body: { tags: { region: ['eu'] } },
    });
    await commands.wait({
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
    await new CommandClient(
      { fetcher: testFetcher(), basePath: '/api' },
      { manifest: TEST_MANIFEST },
    ).wait({ command: 'c.a.X' });
    expect(requests[0].url).toBe(`${BASE_URL}/api/c/a/x`);
  });
});

describe('problems', () => {
  it('reads what a retry may do from the problem, else from the catalog', () => {
    const problem = (status: number, members: Record<string, unknown>) => ({
      type: 'about:blank',
      title: 'T',
      status,
      ...members,
    });
    // The member every problem a Wow service writes carries wins.
    expect(
      retryOf(problem(503, { code: 'wow.command.unknown', retry: 'none' })),
    ).toBe('none');
    expect(retryOf(problem(503, { code: 'wow.command.unknown' }))).toBe(
      'same-request',
    );
    expect(retryOf(problem(429, { code: 'wow.query.busy' }))).toBe(
      'new-request',
    );
    // A query that ran out of time would run as long again (decision D254 ③).
    expect(retryOf(problem(504, { code: 'wow.query.timeout' }))).toBe('none');
    expect(retryOf(problem(409, { code: 'wow.aggregate.exists' }))).toBe(
      'none',
    );
    // A body that is no Wow problem: a proxy's 503 or 429.
    expect(retryOf(problem(503, {}))).toBe('same-request');
    expect(retryOf(problem(429, {}))).toBe('same-request');
    expect(retryOf(problem(500, {}))).toBe('none');
    const busy = new WowProblemError(
      problem(429, { code: 'wow.query.busy', retry: 'new-request' }) as never,
    );
    expect(busy.retryAfterMs).toBe(1000);
    expect(
      new WowProblemError(
        problem(409, { code: 'wow.aggregate.exists' }) as never,
      ).retryAfterMs,
    ).toBeUndefined();
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
    await products.paged({ ...query, total: 'exact' });
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
      filter: { op: 'eq', field: 'state.name', value: 'n' },
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
        ? new Response(JSON.stringify({ model: 'events', version: 'v2' }), {
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
        op: 'element-match',
        field: 'events',
        predicate: {
          op: 'eq',
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

  it("keeps a field's sort as the service describes it, its index fact included", async () => {
    const sort = { paged: true, cursor: false, indexed: false };
    stubFetch(() =>
      jsonResponse({
        model: 'snapshot',
        fields: [{ path: 'state.name', sort }],
      }),
    );
    const answer = await new SnapshotQueryClient(PRODUCT, client()).describe();
    const described = answer.notModified ? undefined : answer.descriptor;
    expect(described?.fields[0].sort).toEqual(sort);
    expectTypeOf(sort).toExtend<FieldSortDescriptor>();
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
  const ref = { type: PRODUCT, tenantId: 't', id: 'p1' };
  // The snapshot record's shape, a snapshot query's row (decision D348 ⑦).
  const snapshot = snapshotOf(ref, { name: 'n' }, { version: 2 });

  it('reads the latest state, at a version or a time, or of a minimum version', async () => {
    const { requests } = stubFetch(() => jsonResponse(snapshot));
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
        new Response(JSON.stringify(snapshot), {
          headers: {
            'Content-Type': 'application/json',
            ETag: '"2-0123456789abcdef"',
          },
        }),
    );
    expect(await new StateClient(PRODUCT, client()).read('p1')).toEqual({
      notModified: false,
      snapshot,
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
    let answer: () => Response = () => jsonResponse(snapshot);
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

  it("reads the caller's own state and trace by no id, and by id as any other", async () => {
    let answer: () => Response = () => jsonResponse(snapshot);
    const { requests } = stubFetch(() => answer());
    const states = new OwnStateClient<{ name: string }>(PRODUCT, client());
    expect((await states.getOwn()).state.name).toBe('n');
    expect((await states.readOwn({ version: 2 })).notModified).toBe(false);
    expect((await states.findOwn({ minVersion: 3 }))?.state.name).toBe('n');
    await states.get('p1');
    answer = () => jsonResponse({ ref, commits: [] });
    await states.traceOwn({ limit: 3 });
    expect(requests.map(r => r.url)).toEqual([
      `${BASE_URL}/eshop-catalog/product/state`,
      `${BASE_URL}/eshop-catalog/product/state?version=2`,
      `${BASE_URL}/eshop-catalog/product/state?minVersion=3`,
      `${BASE_URL}/eshop-catalog/product/p1/state`,
      `${BASE_URL}/eshop-catalog/product/state/trace?limit=3`,
    ]);
    answer = () => problemResponse(404, 'wow.aggregate.not-found');
    expect(await states.findOwn()).toBeUndefined();
    answer = () => problemResponse(400, 'wow.query.malformed');
    await expect(states.getOwn()).rejects.toMatchObject({
      code: 'wow.query.malformed',
      status: 400,
    });
  });

  it('reads a trace', async () => {
    const { requests } = stubFetch(() => jsonResponse({ ref, commits: [] }));
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
        ref,
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

  it('refuses a Manifest of another format, or requiring what it does not understand', async () => {
    stubFetch(() => jsonResponse({ format: 2, contexts: [] }));
    await expect(fetchManifest(client())).rejects.toThrow(
      'this client reads format 1',
    );
    const requiring = { ...TEST_MANIFEST, requires: ['routes-v2'] };
    expect(() => checkManifest(requiring)).toThrow(
      'requires routes-v2, which this client does not understand',
    );
    expect(() => new CommandClient(client(), { manifest: requiring })).toThrow(
      'does not understand',
    );
    expect(MANIFEST_FEATURES.size).toBe(0);
  });
});
