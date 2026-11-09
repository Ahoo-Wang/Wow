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
 * The Manifest a CommandClient reads when it is given none: one read for
 * every send waiting on it, each send's wait its own (#5485, #5538).
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { CommandClient, requestIdOf, WowProblemError } from '../src/index.js';
import { commandResultOf, problemOf, problemResponse } from '../src/testing.js';
import {
  BASE_URL,
  jsonResponse,
  stubFetch,
  testFetcher,
} from './fixtures/fetchStub';
import { TEST_MANIFEST } from './fixtures/manifest';

const REF = { type: { context: 'c', name: 'a' }, tenantId: 't', id: '2' };

const client = () => ({ fetcher: testFetcher() });

afterEach(() => vi.unstubAllGlobals());

describe("the service's Manifest a CommandClient reads", () => {
  it('rejects each send of a failed shared Manifest read with an error of its own, carrying its own request id', async () => {
    let answer!: (response: Response) => void;
    const { requests } = stubFetch(
      () => new Promise<Response>(resolve => (answer = resolve)),
    );
    const commands = new CommandClient(client());
    const sends = [
      commands.wait({ command: 'c.a.X', id: '1', requestId: 'write-a' }),
      commands.wait({ command: 'c.a.X', id: '2', requestId: 'write-b' }),
    ].map(sending => sending.catch((error: unknown) => error));
    await vi.waitFor(() => expect(requests).toHaveLength(1));
    answer(
      problemResponse(problemOf(503, 'wow.storage.unavailable'), {
        headers: { 'Retry-After': '2' },
      }),
    );
    const [first, second] = (await Promise.all(sends)) as WowProblemError[];
    expect(first).not.toBe(second);
    expect([first, second].map(requestIdOf)).toEqual(['write-a', 'write-b']);
    for (const failure of [first, second]) {
      expect(failure).toBeInstanceOf(WowProblemError);
      expect(failure).toMatchObject({
        status: 503,
        code: 'wow.storage.unavailable',
        retry: 'same-request',
        retryAfterMs: 2000,
      });
      // The read's own error, the same for both, explains each.
      expect(failure.cause).toBe(first.cause);
      expect(failure.cause).toBeInstanceOf(WowProblemError);
    }
    expect(requests).toHaveLength(1);
  });

  it('rejects each send of a Manifest read no server answered with an error of its own that names the read', async () => {
    const down = new TypeError('Failed to fetch');
    stubFetch(() => Promise.reject(down));
    const commands = new CommandClient(client());
    const failures = (await Promise.all(
      ['write-a', 'write-b'].map(requestId =>
        commands
          .wait({ command: 'c.a.X', id: '1', requestId })
          .catch((error: unknown) => error),
      ),
    )) as (Error & { cause: { cause: unknown } })[];
    expect(failures[0]).not.toBe(failures[1]);
    expect(failures.map(requestIdOf)).toEqual(['write-a', 'write-b']);
    for (const failure of failures) {
      expect(failure.message).toBe(
        "The service's Manifest (GET /wow/manifest) could not be read: Failed to fetch",
      );
      expect(failure.cause.cause).toBe(down);
    }
  });

  it('ends a send waiting for the shared Manifest once its signal aborts, while the others wait on and send', async () => {
    let answer!: (response: Response) => void;
    const { requests } = stubFetch(request =>
      request.url.endsWith('/wow/manifest')
        ? new Promise<Response>(resolve => (answer = resolve))
        : jsonResponse(commandResultOf(REF)),
    );
    const commands = new CommandClient(client());
    const cancellation = new AbortController();
    const abandoned = commands.wait({
      command: 'c.a.X',
      id: '1',
      requestId: 'cancelled',
      signal: cancellation.signal,
    });
    const continuing = commands.wait({ command: 'c.a.X', id: '2' });
    await vi.waitFor(() => expect(requests).toHaveLength(1));
    const reason = new DOMException('Left the page', 'AbortError');
    cancellation.abort(reason);
    // Settled with the Manifest still unanswered.
    const failure = await abandoned.catch((error: unknown) => error);
    expect(failure).toBe(reason);
    expect(requestIdOf(failure)).toBe('cancelled');
    answer(jsonResponse(TEST_MANIFEST));
    await expect(continuing).resolves.toMatchObject({ status: 'succeeded' });
    expect(requests.map(r => r.url)).toEqual([
      `${BASE_URL}/wow/manifest`,
      `${BASE_URL}/c/a/2/x`,
    ]);
  });

  it('sends nothing for a send whose signal aborted before it began, not even the Manifest read', async () => {
    const { requests } = stubFetch(() => jsonResponse(TEST_MANIFEST));
    const reason = new DOMException('Left the page', 'AbortError');
    await expect(
      new CommandClient(client()).wait({
        command: 'c.a.X',
        id: '1',
        signal: AbortSignal.abort(reason),
      }),
    ).rejects.toBe(reason);
    expect(requests).toEqual([]);
  });
});
