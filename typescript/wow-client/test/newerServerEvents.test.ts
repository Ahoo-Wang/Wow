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
 * What a newer server may add to an event stream after 10.0, read by a 10.0
 * client (decision D484 ④): an event it does not know is skipped, whatever
 * its data; a result of a stage it does not know is still a result (⑤); and
 * an `end` of a reason it does not know leaves a command's outcome unknown
 * rather than reading as a stream that is whole.
 */

import { afterEach, describe, expect, it, vi } from 'vitest';
import { filter } from '../src/dsl.js';
import {
  CommandClient,
  requestIdOf,
  SnapshotQueryClient,
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

/** An event of `name` holding `data`, as JSON when it is not a string. */
function event(name: string, data: unknown): string {
  return `event:${name}\ndata:${typeof data === 'string' ? data : JSON.stringify(data)}\n\n`;
}

const COMMITTED = event('committed', commandResultOf(REF));
const END = event('end', { reason: 'complete', resumable: false });

/** What the command stream answered `text` reads: its results, or its failure. */
async function streamed(text: string, requestId = 'r-1') {
  stubFetch(() => sseResponse(text));
  const stream = await new CommandClient(client(), {
    manifest: TEST_MANIFEST,
  }).stream({ command: 'c.a.X', id: '1', requestId });
  const read: unknown[] = [];
  try {
    for await (const result of stream) read.push(result);
    return { read };
  } catch (failure) {
    return { read, failure };
  }
}

afterEach(() => vi.unstubAllGlobals());

describe("a command's event stream from a newer server", () => {
  it('skips an event this client does not know, whatever its data, and reads the results around it', async () => {
    const { read, failure } = await streamed(
      [
        event('progress', { percent: 50 }),
        COMMITTED,
        event('progress', 'not JSON at all'),
        // Shaped as a result, but of another stage than its name: not one.
        event('notice', commandResultOf(REF)),
        END,
      ].join(''),
    );
    expect(failure).toBeUndefined();
    expect(read).toMatchObject([{ hop: { stage: 'committed' } }]);
  });

  it('reads a result of a stage this client does not know as a result, its stage as the server wrote it', async () => {
    const indexed = commandResultOf(REF, {
      hop: { stage: 'indexed', context: 'c' } as never,
    });
    const { read, failure } = await streamed(
      [COMMITTED, event('indexed', indexed), END].join(''),
    );
    expect(failure).toBeUndefined();
    expect(read).toMatchObject([
      { hop: { stage: 'committed' } },
      { hop: { stage: 'indexed' } },
    ]);
  });

  it('fails as an unknown outcome on an end whose reason is not complete, carrying the request id', async () => {
    for (const end of [
      event('end', { reason: 'node-stopping', resumable: false }),
      event('end', { reason: 'superseded' }),
      event('end', {}),
      event('end', 'null'),
    ]) {
      const { read, failure } = await streamed(COMMITTED + end, 's-9');
      // The results before it were read; whether it committed is unknown.
      expect(read, end).toHaveLength(1);
      expect(failure, end).toBeInstanceOf(WowProblemError);
      expect(failure, end).toMatchObject({
        code: 'wow.command.unknown',
        retry: 'same-request',
      });
      expect((failure as Error).message, end).toMatch(
        /ended for an unknown reason/,
      );
      expect(requestIdOf(failure)).toBe('s-9');
    }
  });

  it('still fails as an unknown outcome on a result it knows whose data is no JSON', async () => {
    const { failure } = await streamed(
      event('committed', 'not a result') + END,
    );
    expect(failure).toMatchObject({ code: 'wow.command.unknown' });
  });
});

describe('a streamed list from a newer server', () => {
  it('skips an event this client does not know, its data JSON or not', async () => {
    stubFetch(() =>
      sseResponse(
        [
          'id:1\nevent:row\ndata:{"id":"1"}\n\n',
          event('progress', 'half way'),
          event('progress', { done: 1 }),
          'id:2\nevent:row\ndata:{"id":"2"}\n\n',
          event('end', { reason: 'complete', resumable: false }),
        ].join(''),
      ),
    );
    const rows = await readAll(
      await new SnapshotQueryClient(
        { context: 'eshop-catalog', name: 'product' },
        client(),
      ).stream({ filter: filter.matchAll() }),
    );
    expect(rows).toEqual([{ id: '1' }, { id: '2' }]);
  });
});
