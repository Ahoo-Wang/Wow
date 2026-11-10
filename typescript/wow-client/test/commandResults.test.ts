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
 * What `CommandClient` answers: a result that succeeded, a rejection, any other
 * problem, a retry hint, and the results it streams hop by hop.
 */

import { afterEach, describe, expect, expectTypeOf, it, vi } from 'vitest';
import {
  CommandClient,
  CommandRejectedError,
  type CommandResult,
  formatWaitPlan,
  FrameworkErrorCodes,
  type RefusedResult,
  requestIdOf,
  WaitPlan,
  WowProblemError,
} from '../src/index.js';
import { rememberRequestId } from '../src/problem.js';
import {
  jsonResponse,
  readAll,
  sseResponse,
  stubFetch,
} from './fixtures/fetchStub';
import { TEST_MANIFEST } from './fixtures/manifest';
import { client, problemResponse, result } from './fixtures/clients';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('CommandClient', () => {
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
});
