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
 * Deep review r1 of the TypeScript packages: each test holds what the client
 * should do, and fails on main for the defect its issue names. The command
 * tests run against a real HTTP server on 127.0.0.1, so the URL parser, the
 * connection and its close are the runtime's own.
 */

import {
  createServer,
  type IncomingMessage,
  type ServerResponse,
} from 'node:http';
import type { AddressInfo } from 'node:net';
import { Fetcher } from '@ahoo-wang/fetcher';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { filter } from '../src/dsl.js';
import {
  CommandClient,
  requestIdOf,
  SnapshotQueryClient,
  StateClient,
  WaitPlan,
  WowProblemError,
} from '../src/index.js';
import { commandResultOf } from '../src/testing.js';
import { type SentRequest, sseResponse, stubFetch } from './fixtures/fetchStub';
import { TEST_MANIFEST } from './fixtures/manifest';

const REF = { type: { context: 'c', name: 'a' }, tenantId: 't', id: '1' };

/** The Kotlin client's grace past a command's wait (`RESPONSE_GRACE`). */
const GRACE_MS = 5_000;

/** Slack for a loaded machine, on top of a wait and its grace. */
const SLACK_MS = 1_500;

const closers: Array<() => Promise<void>> = [];

afterEach(async () => {
  for (const close of closers.splice(0)) await close();
});

interface Received {
  readonly method: string;
  readonly url: string;
  readonly requestId: string | undefined;
}

/** A real HTTP server on 127.0.0.1, and a fetcher on it. */
async function serve(
  handler: (request: IncomingMessage, response: ServerResponse) => void,
) {
  const received: Received[] = [];
  const server = createServer((request, response) => {
    const requestId = request.headers['wow-request-id'];
    received.push({
      method: request.method ?? '',
      url: request.url ?? '',
      requestId: typeof requestId === 'string' ? requestId : undefined,
    });
    handler(request, response);
  });
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address() as AddressInfo;
  closers.push(
    () =>
      new Promise<void>(resolve => {
        server.closeAllConnections();
        server.close(() => resolve());
      }),
  );
  const fetcher = new Fetcher({
    baseURL: `http://127.0.0.1:${port}`,
    headers: {},
  });
  return { fetcher, received };
}

function resultAt(stage: 'accepted' | 'committed' | 'snapshot'): string {
  return JSON.stringify(
    commandResultOf(REF, { hop: { stage, context: 'c' } as never }),
  );
}

/** What `pending` settles with, or `'pending'` once `ms` passed first. */
function within<T>(pending: Promise<T>, ms: number): Promise<T | 'pending'> {
  return Promise.race([
    pending,
    new Promise<'pending'>(resolve => setTimeout(() => resolve('pending'), ms)),
  ]);
}

/** Reads `stream` to its end: its results, and what it failed with. */
async function drain<T>(stream: ReadableStream<T>) {
  const read: T[] = [];
  let failure: unknown;
  try {
    for await (const item of stream) read.push(item);
  } catch (error) {
    failure = error;
  }
  return { read, failure };
}

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

describe('r1: a command stream that ends before its last result', () => {
  it('r1 a command stream that ends cleanly before its plan reaches its last hop fails as an unknown outcome', async () => {
    const { fetcher, received } = await serve((_, response) => {
      response.writeHead(200, { 'Content-Type': 'text/event-stream' });
      response.end(
        `: keep-alive\n\nid:0\nevent:accepted\ndata:${resultAt('accepted')}\n\n`,
      );
    });
    const stream = await new CommandClient(
      { fetcher },
      { manifest: TEST_MANIFEST },
    ).stream({ command: 'c.a.X', id: '1', wait: WaitPlan.SNAPSHOT });
    const { read, failure } = await drain(stream);
    expect(read).toHaveLength(1);
    expectUnknownOutcome(failure, received[0]?.requestId);
  });

  it('r1 a command stream answered 200 with no event stream fails as an unknown outcome', async () => {
    const { fetcher, received } = await serve((_, response) => {
      response.writeHead(200, { 'Content-Type': 'text/html' });
      response.end('<html><body>Sign in</body></html>');
    });
    const failure = await new CommandClient(
      { fetcher },
      { manifest: TEST_MANIFEST },
    )
      .stream({ command: 'c.a.X', id: '1', wait: WaitPlan.COMMITTED })
      .then(drain, (error: unknown) => ({ read: [], failure: error }));
    expect(failure.read).toEqual([]);
    expectUnknownOutcome(failure.failure, received[0]?.requestId);
  });
});

describe('r1: a command is bounded by its wait and a grace', () => {
  const WAIT_MS = 100;
  const BOUND_MS = WAIT_MS + GRACE_MS + SLACK_MS;

  it(
    'r1 a wait whose answer stops after its head ends at its wait and a grace, as an unknown outcome',
    async () => {
      const { fetcher, received } = await serve((_, response) => {
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.write('{"format":1,');
      });
      const settled = await within(
        new CommandClient({ fetcher }, { manifest: TEST_MANIFEST })
          .wait({ command: 'c.a.X', id: '1', timeout: WAIT_MS })
          .then(
            () => 'resolved',
            (error: unknown) => error,
          ),
        BOUND_MS,
      );
      expect(settled).not.toBe('pending');
      expectUnknownOutcome(settled, received[0]?.requestId);
    },
    BOUND_MS + 3_000,
  );

  it(
    'r1 a wait whose node never answers ends at its wait and a grace, as an unknown outcome',
    async () => {
      const { fetcher, received } = await serve(() => {
        // Takes the request and never answers, as a node gone silent.
      });
      const settled = await within(
        new CommandClient({ fetcher }, { manifest: TEST_MANIFEST })
          .wait({ command: 'c.a.X', id: '1', timeout: WAIT_MS })
          .then(
            () => 'resolved',
            (error: unknown) => error,
          ),
        BOUND_MS,
      );
      expect(settled).not.toBe('pending');
      expectUnknownOutcome(settled, received[0]?.requestId);
    },
    BOUND_MS + 3_000,
  );

  it(
    'r1 a command stream that goes silent after its first result ends at its wait and a grace, as an unknown outcome',
    async () => {
      const { fetcher, received } = await serve((_, response) => {
        response.writeHead(200, { 'Content-Type': 'text/event-stream' });
        response.write(
          `id:0\nevent:accepted\ndata:${resultAt('accepted')}\n\n`,
        );
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
      const settled = await within(
        reader.read().then(
          () => 'read',
          (error: unknown) => error,
        ),
        BOUND_MS,
      );
      expect(settled).not.toBe('pending');
      expectUnknownOutcome(settled, received[0]?.requestId);
    },
    BOUND_MS + 3_000,
  );
});

describe('r1: an aggregate id that is a relative path segment', () => {
  const answering = (_: IncomingMessage, response: ServerResponse) => {
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(resultAt('committed'));
  };

  it('r1 a command to the aggregate "." or ".." is refused before it is sent, never sent to another route', async () => {
    const { fetcher, received } = await serve(answering);
    const commands = new CommandClient(
      { fetcher },
      { manifest: TEST_MANIFEST },
    );
    for (const id of ['.', '..']) {
      await expect(commands.wait({ command: 'c.a.X', id })).rejects.toThrow();
    }
    expect(received.map(({ method, url }) => `${method} ${url}`)).toEqual([]);
  });

  it('r1 a state read of the aggregate "." or ".." is refused before it is sent, never read from another route', async () => {
    const { fetcher, received } = await serve(answering);
    const states = new StateClient({ context: 'c', name: 'a' }, { fetcher });
    for (const id of ['.', '..']) {
      await expect(states.get(id)).rejects.toThrow();
    }
    expect(received.map(({ method, url }) => `${method} ${url}`)).toEqual([]);
  });
});

describe("r1: a resumed query stream honours a Retry-After up to the catalog's", () => {
  const delays: number[] = [];
  beforeEach(() => {
    delays.length = 0;
    const real = globalThis.setTimeout;
    vi.spyOn(globalThis, 'setTimeout').mockImplementation(((
      handler: () => void,
      ms?: number,
    ) => {
      delays.push(ms ?? 0);
      return real(handler, 0);
    }) as typeof setTimeout);
  });
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it("r1 a resume answered with a Retry-After beyond the catalog's 5 s fails at once with its problem, as the Kotlin client's", async () => {
    let asked = 0;
    stubFetch((_: SentRequest) => {
      asked++;
      if (asked === 1) {
        return sseResponse(
          'id:p1\nevent:row\ndata:{"id":"p1"}\n\n' +
            'event:end\ndata:{"reason":"permit-reclaimed","resumable":true}\n\n',
        );
      }
      return new Response(
        JSON.stringify({
          type: 'urn:wow:error:wow.query.busy',
          title: 'Query busy',
          status: 429,
          code: 'wow.query.busy',
          retry: 'new-request',
        }),
        {
          status: 429,
          headers: {
            'Content-Type': 'application/problem+json',
            'Retry-After': '30',
          },
        },
      );
    });
    const stream = await new SnapshotQueryClient(
      { context: 'c', name: 'a' },
      {
        fetcher: new Fetcher({ baseURL: 'http://localhost:8080', headers: {} }),
      },
    ).stream({ filter: filter.matchAll() });
    const { read, failure } = await drain(stream);
    expect(read).toEqual([{ id: 'p1' }]);
    expect(failure).toBeInstanceOf(WowProblemError);
    expect(failure).toMatchObject({ code: 'wow.query.busy' });
    expect(delays).not.toContain(30_000);
    expect(asked).toBe(2);
  });
});
