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
 * A command's result stream past its response's start: what a read that
 * fails carries, and where a cancel goes (#5484).
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  CommandClient,
  requestIdOf,
  WaitPlan,
  WowHeaders,
  WowProblemError,
} from '../src/index.js';
import { commandResultOf } from '../src/testing.js';
import {
  readAll,
  sseResponse,
  stubFetch,
  testFetcher,
} from './fixtures/fetchStub';
import { TEST_MANIFEST } from './fixtures/manifest';

const REF = { type: { context: 'c', name: 'a' }, tenantId: 't', id: '1' };

const client = () => ({ fetcher: testFetcher() });

/** The event a command's stream ends with once whole, after its last result. */
const END = 'event:end\ndata:{"reason":"complete"}\n\n';

afterEach(() => vi.unstubAllGlobals());

describe('a command stream', () => {
  it('fails a command stream whose body breaks as an unknown outcome that carries the request id it was sent with', async () => {
    let body!: ReadableStreamDefaultController<Uint8Array>;
    const { requests } = stubFetch(
      () =>
        new Response(
          new ReadableStream<Uint8Array>({ start: c => void (body = c) }),
          { headers: { 'Content-Type': 'text/event-stream' } },
        ),
    );
    const reader = (
      await new CommandClient(client(), { manifest: TEST_MANIFEST }).stream({
        command: 'c.a.X',
        id: '1',
      })
    ).getReader();
    body.enqueue(
      new TextEncoder().encode(
        `event:committed\ndata:${JSON.stringify(commandResultOf(REF))}\n\n`,
      ),
    );
    await expect(reader.read()).resolves.toMatchObject({
      done: false,
      value: { hop: { stage: 'committed' } },
    });
    const lost = new TypeError('Connection lost after the response began');
    body.error(lost);
    const failure = await reader.read().catch((error: unknown) => error);
    // Past the head the command was taken: whether it committed is unknown.
    expect(failure).toBeInstanceOf(WowProblemError);
    expect(failure).toMatchObject({
      code: 'wow.command.unknown',
      retry: 'same-request',
      cause: lost,
    });
    const sent = requests[0].headers.get(WowHeaders.REQUEST_ID);
    expect(sent).toBeTypeOf('string');
    expect(requestIdOf(failure)).toBe(sent);
  });

  it('reads the results of a command stream with or without a space after the colon alike', async () => {
    const result = commandResultOf(REF);
    for (const field of ['data:', 'data: ']) {
      stubFetch(
        () =>
          new Response(
            `event:committed\n${field}${JSON.stringify(result)}\n\n${END}`,
            {
              headers: { 'Content-Type': 'text/event-stream' },
            },
          ),
      );
      const stream = await new CommandClient(client(), {
        manifest: TEST_MANIFEST,
      }).stream({ command: 'c.a.X', id: '1' });
      expect(await readAll(stream)).toMatchObject([
        { hop: { stage: 'committed' } },
      ]);
    }
  });

  it('ends a command stream on its end event, which is no result, and fails one that ends without it as unknown', async () => {
    const committed = `event:committed\ndata:${JSON.stringify(commandResultOf(REF))}\n\n`;
    // A keep-alive after `end` is not read: the stream is whole.
    stubFetch(
      () =>
        new Response(`: keep-alive\n\n${committed}${END}: keep-alive\n\n`, {
          headers: { 'Content-Type': 'text/event-stream' },
        }),
    );
    const whole = await new CommandClient(client(), {
      manifest: TEST_MANIFEST,
    }).stream({ command: 'c.a.X', id: '1' });
    expect(await readAll(whole)).toMatchObject([
      { hop: { stage: 'committed' } },
    ]);
    // Closed cleanly after the commit, without `end`: the snapshot may still
    // come, or the stream was cut short; the outcome is unknown either way.
    stubFetch(
      () =>
        new Response(committed, {
          headers: { 'Content-Type': 'text/event-stream' },
        }),
    );
    const read: unknown[] = [];
    const failure = await (async () => {
      for await (const result of await new CommandClient(client(), {
        manifest: TEST_MANIFEST,
      }).stream({ command: 'c.a.X', id: '1', requestId: 's1' }))
        read.push(result);
    })().catch((error: unknown) => error);
    expect(read).toHaveLength(1);
    expect(failure).toBeInstanceOf(WowProblemError);
    expect(failure).toMatchObject({
      code: 'wow.command.unknown',
      status: 503,
      retry: 'same-request',
    });
    expect(requestIdOf(failure)).toBe('s1');
  });

  it('cancels the response of a command stream its reader cancels', async () => {
    let cancelled: unknown;
    stubFetch(
      () =>
        new Response(
          new ReadableStream<Uint8Array>({
            cancel: reason => void (cancelled = reason),
          }),
          { headers: { 'Content-Type': 'text/event-stream' } },
        ),
    );
    const stream = await new CommandClient(client(), {
      manifest: TEST_MANIFEST,
    }).stream({ command: 'c.a.X', id: '1', requestId: 's1' });
    await stream.cancel('left the page');
    // The cancel reaches the body through the event stream's pipes.
    await vi.waitFor(() => expect(cancelled).toBe('left the page'));
  });

  it('fails a command stream that ends cleanly before its plan reaches its last hop as an unknown outcome', async () => {
    const accepted = commandResultOf(REF, {
      hop: { stage: 'accepted', context: 'c' } as never,
    });
    const { requests } = stubFetch(() =>
      sseResponse(`id:0\nevent:accepted\ndata:${JSON.stringify(accepted)}\n\n`),
    );
    const stream = await new CommandClient(client(), {
      manifest: TEST_MANIFEST,
    }).stream({ command: 'c.a.X', id: '1', wait: WaitPlan.SNAPSHOT });
    const read: unknown[] = [];
    const failure = await (async () => {
      for await (const result of stream) read.push(result);
    })().catch((error: unknown) => error);
    expect(read).toHaveLength(1);
    expect(failure).toBeInstanceOf(WowProblemError);
    expect(failure).toMatchObject({
      code: 'wow.command.unknown',
      retry: 'same-request',
    });
    expect(requestIdOf(failure)).toBe(
      requests[0].headers.get(WowHeaders.REQUEST_ID),
    );
  });

  it('fails a command stream answered 200 with no event stream, a gateway sign-in page say, as an unknown outcome', async () => {
    const { requests } = stubFetch(
      () =>
        new Response('<html><body>Sign in</body></html>', {
          status: 200,
          headers: { 'Content-Type': 'text/html' },
        }),
    );
    const failure = await new CommandClient(client(), {
      manifest: TEST_MANIFEST,
    })
      .stream({ command: 'c.a.X', id: '1', wait: WaitPlan.COMMITTED })
      .then(readAll)
      .catch((error: unknown) => error);
    expect(failure).toBeInstanceOf(WowProblemError);
    expect(failure).toMatchObject({ code: 'wow.command.unknown' });
    expect(requestIdOf(failure)).toBe(
      requests[0].headers.get(WowHeaders.REQUEST_ID),
    );
  });
});
