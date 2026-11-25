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
 * A command's whole answer is bounded by its wait and a grace, as the Kotlin
 * client's is (#6888), and an answer lost or unreadable after its head is an
 * unknown outcome. Against a real HTTP server on 127.0.0.1, so the abort
 * closes a real connection.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  CommandClient,
  requestIdOf,
  WaitPlan,
  WowProblemError,
} from '../src/index.js';
import { commandResultOf } from '../src/testing.js';
import { hangUntilAborted, stubFetch, testFetcher } from './fixtures/fetchStub';
import { serve, within } from './fixtures/httpServer';
import { TEST_MANIFEST } from './fixtures/manifest';

const REF = { type: { context: 'c', name: 'a' }, tenantId: 't', id: '1' };

/** The grace after a command's wait, as the Kotlin client's `RESPONSE_GRACE`. */
const GRACE_MS = 5_000;

/** Slack for a loaded machine, on top of a wait and its grace. */
const SLACK_MS = 1_500;

const WAIT_MS = 100;
const BOUND_MS = WAIT_MS + GRACE_MS + SLACK_MS;

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

/** That `failure` is the unknown outcome of the command sent with `sent`. */
function expectUnknownOutcome(failure: unknown, sent: string | undefined) {
  expect(failure).toBeInstanceOf(WowProblemError);
  expect(failure).toMatchObject({
    code: 'wow.command.unknown',
    retry: 'same-request',
  });
  expect(sent).toBeDefined();
  expect(requestIdOf(failure)).toBe(sent);
}

const settle = (pending: Promise<unknown>) =>
  pending.then(
    () => 'resolved',
    (error: unknown) => error,
  );

const accepted = JSON.stringify(
  commandResultOf(REF, { hop: { stage: 'accepted', context: 'c' } as never }),
);

describe('a command bounded by its wait and a grace', () => {
  it(
    'ends a wait whose answer stops after its head at its wait and a grace, as an unknown outcome, and closes its connection',
    async () => {
      const { fetcher, received } = await serve((_, response) => {
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.write('{"format":1,');
      });
      const settled = await within(
        settle(
          new CommandClient({ fetcher }, { manifest: TEST_MANIFEST }).wait({
            command: 'c.a.X',
            id: '1',
            timeout: WAIT_MS,
          }),
        ),
        BOUND_MS,
      );
      expect(settled).not.toBe('pending');
      expectUnknownOutcome(settled, received[0]?.requestId);
      await vi.waitFor(() => expect(received[0]?.closed).toBe(true));
    },
    BOUND_MS + 3_000,
  );

  it(
    'ends a wait whose node never answers at its wait and a grace, as an unknown outcome',
    async () => {
      const { fetcher, received } = await serve(() => {
        // Takes the request and never answers, as a node gone silent.
      });
      const settled = await within(
        settle(
          new CommandClient({ fetcher }, { manifest: TEST_MANIFEST }).wait({
            command: 'c.a.X',
            id: '1',
            timeout: WAIT_MS,
          }),
        ),
        BOUND_MS,
      );
      expect(settled).not.toBe('pending');
      expectUnknownOutcome(settled, received[0]?.requestId);
    },
    BOUND_MS + 3_000,
  );

  it(
    'ends a command stream that goes silent after a result at its wait and a grace, as an unknown outcome',
    async () => {
      const { fetcher, received } = await serve((_, response) => {
        response.writeHead(200, { 'Content-Type': 'text/event-stream' });
        response.write(`id:0\nevent:accepted\ndata:${accepted}\n\n`);
      });
      const reader = (
        await new CommandClient(
          { fetcher },
          { manifest: TEST_MANIFEST },
        ).stream({
          command: 'c.a.X',
          id: '1',
          wait: WaitPlan.COMMITTED,
          timeout: WAIT_MS,
        })
      ).getReader();
      await expect(reader.read()).resolves.toMatchObject({
        value: { hop: { stage: 'accepted' } },
      });
      const settled = await within(settle(reader.read()), BOUND_MS);
      expect(settled).not.toBe('pending');
      expectUnknownOutcome(settled, received[0]?.requestId);
    },
    BOUND_MS + 3_000,
  );

  it('bounds a command by the wait it states and 5 s more, and one that states none by the longest default wait, a minute, and 5 s more', async () => {
    const delays: number[] = [];
    const real = globalThis.setTimeout;
    vi.spyOn(globalThis, 'setTimeout').mockImplementation(((
      handler: () => void,
      ms?: number,
    ) => {
      delays.push(ms ?? 0);
      return real(handler, ms);
    }) as typeof setTimeout);
    stubFetch(hangUntilAborted);
    const commands = new CommandClient(
      { fetcher: testFetcher() },
      { manifest: TEST_MANIFEST },
    );
    // Only a bound is that long: the waits of `vi.waitFor` are shorter.
    const bounds = () => delays.filter(ms => ms >= GRACE_MS);
    const requests = [
      { timeout: 2_000 },
      { wait: WaitPlan.COMMITTED.within(3_000) },
      {},
      { wait: WaitPlan.COMMITTED },
    ];
    for (const [index, request] of requests.entries()) {
      const abort = new AbortController();
      const sent = commands.wait({
        command: 'c.a.X',
        id: '1',
        ...request,
        signal: abort.signal,
      });
      await vi.waitFor(() => expect(bounds()).toHaveLength(index + 1));
      abort.abort(new Error('done'));
      await sent.catch(() => undefined);
    }
    expect(bounds()).toEqual([7_000, 8_000, 65_000, 65_000]);
  });
});

describe('an answer lost or unreadable after its head', () => {
  it('rejects a wait whose connection is lost after its head as an unknown outcome, the loss its cause', async () => {
    const { fetcher, received } = await serve((_, response) => {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.write('{"format":1,', () => response.socket?.destroy());
    });
    const failure = await settle(
      new CommandClient({ fetcher }, { manifest: TEST_MANIFEST }).wait({
        command: 'c.a.X',
        id: '1',
      }),
    );
    expectUnknownOutcome(failure, received[0]?.requestId);
    expect((failure as WowProblemError).cause).toBeDefined();
  });

  it('rejects a wait answered 200 with what is no JSON as an unknown outcome', async () => {
    const { fetcher, received } = await serve((_, response) => {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end('<html><body>Sign in</body></html>');
    });
    const failure = await settle(
      new CommandClient({ fetcher }, { manifest: TEST_MANIFEST }).wait({
        command: 'c.a.X',
        id: '1',
      }),
    );
    expectUnknownOutcome(failure, received[0]?.requestId);
  });

  it('rejects a command stream whose event holds no result as an unknown outcome', async () => {
    const { fetcher, received } = await serve((_, response) => {
      response.writeHead(200, { 'Content-Type': 'text/event-stream' });
      response.end('id:0\nevent:accepted\ndata:not a result\n\n');
    });
    const stream = await new CommandClient(
      { fetcher },
      { manifest: TEST_MANIFEST },
    ).stream({ command: 'c.a.X', id: '1' });
    const failure = await settle(stream.getReader().read());
    expectUnknownOutcome(failure, received[0]?.requestId);
  });

  it("rejects with the caller's own abort, not as an unknown outcome, the request id still carried", async () => {
    const { fetcher, received } = await serve((_, response) => {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.write('{"format":1,');
    });
    const abort = new AbortController();
    const left = new Error('The caller left.');
    const pending = settle(
      new CommandClient({ fetcher }, { manifest: TEST_MANIFEST }).wait({
        command: 'c.a.X',
        id: '1',
        signal: abort.signal,
      }),
    );
    await vi.waitFor(() => expect(received).toHaveLength(1));
    abort.abort(left);
    const failure = await pending;
    expect(failure).toBe(left);
    expect(requestIdOf(failure)).toBe(received[0]?.requestId);
  });
});
