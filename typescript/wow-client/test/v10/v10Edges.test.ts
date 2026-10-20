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
import { filter } from '../../src/dsl';
import {
  CommandGateway,
  EventQueryClient,
  readProblem,
  SnapshotQueryClient,
  StateClient,
  WowProblemError,
} from '../../src/v10';
import {
  jsonResponse,
  readAll,
  sseResponse,
  stubFetch,
  testFetcher,
} from '../client/fetchStub';

const PRODUCT = { context: 'eshop-catalog', name: 'product' };
const client = () => ({ fetcher: testFetcher() });

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('V10 client edges', () => {
  it('goes through the default fetcher and passes the signal', async () => {
    const { requests } = stubFetch(() => jsonResponse({}));
    const controller = new AbortController();
    await new CommandGateway().send(
      '/c/a/x',
      {},
      { signal: controller.signal },
    );
    expect(requests[0].url).toBe('/c/a/x');
    expect(requests[0].signal).toBeDefined();
  });

  it('rethrows a failure no server answered', async () => {
    stubFetch(() => {
      throw new TypeError('network');
    });
    const error = await new CommandGateway(client())
      .send('/c/a/x')
      .catch(e => e);
    expect(error).not.toBeInstanceOf(WowProblemError);
  });

  it('keeps a problem without a type, or with a type of its own', async () => {
    expect(
      await readProblem(
        new Response(JSON.stringify({ status: 500 }), {
          status: 500,
          statusText: 'Error',
        }),
      ),
    ).toEqual({ type: 'about:blank', title: 'Error', status: 500 });
    expect(
      await readProblem(
        new Response(
          JSON.stringify({ type: 'https://x', title: 'X', status: 400 }),
          { status: 400 },
        ),
      ),
    ).toEqual({ type: 'https://x', title: 'X', status: 400 });
  });

  it('answers a 304 without an ETag with the version held', async () => {
    stubFetch(() => new Response(null, { status: 304 }));
    expect(
      await new SnapshotQueryClient(PRODUCT, client()).describe('v9'),
    ).toEqual({ notModified: true, version: 'v9' });
    expect(
      await new StateClient(PRODUCT, client()).read('p1', {
        ifNoneMatch: '4-0123456789abcdef',
      }),
    ).toEqual({ notModified: true, version: 4, etag: '4-0123456789abcdef' });
  });

  it('reads unnamed events as rows and skips others', async () => {
    stubFetch(() =>
      sseResponse(
        'data:{"a":1}\n\nevent:other\ndata:{"b":2}\n\n' +
          'event:end\ndata:{"reason":"complete","resumable":false}\n\n',
      ),
    );
    const stream = await new EventQueryClient(PRODUCT, client()).listStream({
      filter: filter.eq('aggregateId', 'p1'),
    });
    expect(await readAll(stream)).toEqual([{ a: 1 }]);
  });
});
