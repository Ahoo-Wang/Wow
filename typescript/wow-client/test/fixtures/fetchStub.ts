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

import { Fetcher } from '@ahoo-wang/fetcher';
import { vi } from 'vitest';

export const BASE_URL = 'http://localhost:8080';

/** What a client handed to `fetch`, read back into plain values. */
export interface SentRequest {
  url: string;
  method?: string;
  headers: Headers;
  /** The JSON body, parsed; `undefined` when the request has none. */
  body: unknown;
  signal?: AbortSignal;
}

type Responder = (request: SentRequest) => Response | Promise<Response>;

/**
 * Stubs the global `fetch`, which the fetcher calls, with `respond`, and
 * records every request it receives.
 */
export function stubFetch(respond: Responder = () => jsonResponse({})) {
  const requests: SentRequest[] = [];
  const fetchMock = vi.fn(
    async (input: RequestInfo | URL, init?: RequestInit) => {
      const sent: SentRequest = {
        url: String(input),
        method: init?.method,
        headers: new Headers(init?.headers),
        body:
          typeof init?.body === 'string' ? JSON.parse(init.body) : init?.body,
        signal: init?.signal ?? undefined,
      };
      requests.push(sent);
      return respond(sent);
    },
  );
  vi.stubGlobal('fetch', fetchMock);
  return { requests, fetchMock };
}

/**
 * A fetcher on {@link BASE_URL} whose requests go to the stubbed `fetch`.
 *
 * It sends no default headers, so what the tests record is Wow's own wire:
 * fetcher 5 adds `Content-Type: application/json` to every request by
 * default, fetcher 6 only to a request with a body.
 */
export function testFetcher(): Fetcher {
  return new Fetcher({ baseURL: BASE_URL, headers: {} });
}

export function jsonResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** A server-sent event response whose body is `text`. */
export function sseResponse(text: string): Response {
  return new Response(text, {
    status: 200,
    headers: { 'Content-Type': 'text/event-stream' },
  });
}

/** The server-sent event lines of `rows`, one unnamed event per row. */
export function sseRows(rows: unknown[]): string {
  return rows.map(row => `data:${JSON.stringify(row)}\n\n`).join('');
}

/** The error event Wow ends a failed stream with. */
export const SSE_ERROR_EVENT =
  'event:NotFound\ndata:{"errorCode":"NotFound","errorMsg":"x"}\n\n';

/** A `fetch` answer that never settles unless the request is aborted. */
export function hangUntilAborted(request: SentRequest): Promise<Response> {
  return new Promise((_, reject) => {
    request.signal?.addEventListener('abort', () =>
      reject(request.signal?.reason),
    );
  });
}

/** Reads a stream to its end. */
export async function readAll<T>(stream: ReadableStream<T>): Promise<T[]> {
  const items: T[] = [];
  for await (const item of stream) items.push(item);
  return items;
}
