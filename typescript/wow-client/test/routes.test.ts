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
import { WowHeaders, WowProblemError } from '../src/index.js';
import { ServiceRoutes } from '../src/routes.js';
import { jsonResponse, stubFetch, testFetcher } from './fixtures/fetchStub';

const MANIFEST = {
  format: 1,
  contexts: [
    {
      name: 'shop',
      routes: [
        {
          name: 'summary',
          route: { method: 'GET', path: '/shop/summaries/{day}' },
        },
        { name: 'addNote', route: { method: 'POST', path: '/shop/notes' } },
      ],
    },
  ],
};

const client = () => ({ fetcher: testFetcher() });

afterEach(() => vi.unstubAllGlobals());

describe('ServiceRoutes', () => {
  it('calls a route by its name, its path variables and query filled in', async () => {
    const { requests } = stubFetch(() => jsonResponse({ total: 3 }));
    const routes = new ServiceRoutes(client(), { manifest: MANIFEST });
    const answer = await routes.call<{ total: number }>(
      'shop',
      'summary',
      {
        path: { day: 'mon day' },
        query: { limit: 3, open: true, skip: undefined },
        requestId: 'r-1',
      },
      { scope: { ownerId: 'bob' } },
    );
    expect(answer).toEqual({ total: 3 });
    expect(requests[0].method).toBe('GET');
    expect(requests[0].url).toBe(
      'http://localhost:8080/shop/summaries/mon%20day?limit=3&open=true',
    );
    expect(requests[0].headers.get(WowHeaders.REQUEST_ID)).toBe('r-1');
    expect(requests[0].headers.get(WowHeaders.SCOPE)).toBe('owner="bob"');
  });

  it('sends a body, and answers nothing for 204', async () => {
    const { requests } = stubFetch(() => new Response(null, { status: 204 }));
    const routes = new ServiceRoutes(client(), { manifest: MANIFEST });
    expect(
      await routes.call('shop', 'addNote', { body: { text: 'hi' } }),
    ).toBeUndefined();
    expect(requests[0].method).toBe('POST');
    expect(requests[0].body).toEqual({ text: 'hi' });
  });

  it('rejects a refusal with its problem', async () => {
    stubFetch(
      () =>
        new Response(
          JSON.stringify({
            type: 'urn:wow:error:shop.summary.closed',
            title: 'Closed',
            status: 409,
            code: 'shop.summary.closed',
          }),
          {
            status: 409,
            headers: { 'Content-Type': 'application/problem+json' },
          },
        ),
    );
    const routes = new ServiceRoutes(client(), { manifest: MANIFEST });
    const refused = await routes
      .call('shop', 'summary', { path: { day: 'mon' } })
      .catch(error => error);
    expect(refused).toBeInstanceOf(WowProblemError);
    expect((refused as WowProblemError).code).toBe('shop.summary.closed');
  });

  it('refuses a route the Manifest does not list, or a missing path variable, before sending', async () => {
    const { requests } = stubFetch();
    const routes = new ServiceRoutes(client(), { manifest: MANIFEST });
    await expect(routes.call('shop', 'gone')).rejects.toThrow(
      'The Manifest lists no service route gone of shop.',
    );
    await expect(routes.call('shop', 'summary')).rejects.toThrow(
      'needs its path variable day',
    );
    expect(requests).toEqual([]);
  });

  it("reads the service's Manifest once without one, again after a failure", async () => {
    let manifestReads = 0;
    const { requests } = stubFetch(request => {
      if (request.url.endsWith('/wow/manifest')) {
        manifestReads += 1;
        return manifestReads === 1
          ? jsonResponse({ status: 503 }, 503)
          : jsonResponse(MANIFEST);
      }
      return jsonResponse({ total: 1 });
    });
    const routes = new ServiceRoutes(client());
    await expect(
      routes.call('shop', 'summary', { path: { day: 'mon' } }),
    ).rejects.toBeDefined();
    expect(
      await routes.call('shop', 'summary', { path: { day: 'mon' } }),
    ).toEqual({ total: 1 });
    expect(
      await routes.call('shop', 'summary', { path: { day: 'tue' } }),
    ).toEqual({ total: 1 });
    expect(manifestReads).toBe(2);
    expect(
      requests.filter(request => request.url.endsWith('/wow/manifest')),
    ).toHaveLength(2);
  });
});
