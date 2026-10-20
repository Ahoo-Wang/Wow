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

/** The query route of the `user` aggregate's snapshots. */
export const USERS_ROUTE = '/directory/user/snapshot/query';

/**
 * Serves the snapshot query route of a `user` aggregate at
 * `https://api.example.test`, by the `kind` each query names; other origins go
 * to the real fetch.
 */
export function installFetchFixture(): () => void {
  const originalFetch = globalThis.fetch;

  globalThis.fetch = async (input, init) => {
    const request = toRequest(input, init);
    const url = new URL(request.url);
    if (url.origin !== 'https://api.example.test')
      return originalFetch(input, init);
    request.signal.throwIfAborted();
    if (url.pathname === USERS_ROUTE) {
      const { kind } = (await request.json()) as { kind?: string };
      switch (kind) {
        case 'single':
          return jsonResponse(fixturePagedUsers.list[0]);
        case 'list':
          if (request.headers.get('Accept')?.includes('text/event-stream')) {
            // One row per event, then the end, as a Wow service streams a list.
            return eventStreamResponse([
              ...fixturePagedUsers.list.map(
                (user, id) =>
                  `id:${id}\nevent:row\ndata:${JSON.stringify(user)}\n\n`,
              ),
              `event:end\ndata:${JSON.stringify({ reason: 'complete' })}\n\n`,
            ]);
          }
          return jsonResponse(fixturePagedUsers.list);
        case 'paged':
          return jsonResponse({ ...fixturePagedUsers, exact: true });
        case 'count':
          return jsonResponse({ count: fixturePagedUsers.total, exact: true });
      }
    }

    throw new Error(
      `Unexpected fixture request: ${request.method} ${url.href}`,
    );
  };

  return () => {
    globalThis.fetch = originalFetch;
  };
}
