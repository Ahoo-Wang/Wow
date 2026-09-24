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

import { fixturePagedUsers } from './users';

function jsonResponse(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function eventStreamResponse(chunks: readonly string[]): Response {
  const encoder = new TextEncoder();
  return new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
        controller.close();
      },
    }),
    { headers: { 'Content-Type': 'text/event-stream' } },
  );
}

function toRequest(input: RequestInfo | URL, init?: RequestInit): Request {
  if (input instanceof Request) return new Request(input, init);
  const url = new URL(input.toString(), 'https://api.example.test');
  return new Request(url, init);
}

/**
 * Serves the snapshot query endpoints of a `users` aggregate at
 * `https://api.example.test`; other origins go to the real fetch.
 */
export function installFetchFixture(): () => void {
  const originalFetch = globalThis.fetch;

  globalThis.fetch = async (input, init) => {
    const request = toRequest(input, init);
    const url = new URL(request.url);
    if (url.origin !== 'https://api.example.test')
      return originalFetch(input, init);
    request.signal.throwIfAborted();
    const { pathname } = url;

    if (pathname === '/users/snapshot/single/state') {
      return jsonResponse(fixturePagedUsers.list[0]);
    }
    if (pathname === '/users/snapshot/list/state') {
      if (request.headers.get('Accept')?.includes('text/event-stream')) {
        // One row per event, as Wow streams a list.
        return eventStreamResponse(
          fixturePagedUsers.list.map(
            user => `data: ${JSON.stringify(user)}\n\n`,
          ),
        );
      }
      return jsonResponse(fixturePagedUsers.list);
    }
    if (pathname === '/users/snapshot/paged/state') {
      return jsonResponse(fixturePagedUsers);
    }
    if (pathname === '/users/snapshot/count') {
      return jsonResponse(fixturePagedUsers.total);
    }

    throw new Error(
      `Unexpected fixture request: ${request.method} ${url.href}`,
    );
  };

  return () => {
    globalThis.fetch = originalFetch;
  };
}
