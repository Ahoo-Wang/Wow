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
import { CommandClient, requestIdOf, WowHeaders } from '../src/index.js';
import { commandResultOf } from '../src/testing.js';
import { stubFetch, testFetcher } from './fixtures/fetchStub';
import { TEST_MANIFEST } from './fixtures/manifest';

const REF = { type: { context: 'c', name: 'a' }, tenantId: 't', id: '1' };

const client = () => ({ fetcher: testFetcher() });

afterEach(() => vi.unstubAllGlobals());

describe('a command stream', () => {
  it('fails a command stream whose body breaks with an error that carries the request id it was sent with', async () => {
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
    expect(failure).toBe(lost);
    const sent = requests[0].headers.get(WowHeaders.REQUEST_ID);
    expect(sent).toBeTypeOf('string');
    expect(requestIdOf(failure)).toBe(sent);
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
});
