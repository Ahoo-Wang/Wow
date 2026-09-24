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
 * A fake `fetch` behind a real `Fetcher`: the hooks, fetcher-react, the
 * Fetcher's interceptors and wow-client's result extractors all run as they
 * do in an application; only the network is replaced.
 */

import { vi } from 'vitest';
import { Fetcher } from '@ahoo-wang/fetcher';

export const BASE_URL = 'https://wow.example.test/';

/** One request as the server saw it. */
export interface ServedRequest {
  url: string;
  path: string;
  method: string;
  headers: Headers;
  body: unknown;
  signal?: AbortSignal;
}

/** Answers a request, now or later. */
export type Handler = (request: ServedRequest) => Response | Promise<Response>;

export interface FakeServer {
  /** A Fetcher on {@link BASE_URL} that sends through the fake `fetch`. */
  fetcher: Fetcher;
  /** Every request received, in order. */
  requests: ServedRequest[];
  /** Replaces the handler of later requests. */
  handle(handler: Handler): void;
}

function abortError(signal: AbortSignal): unknown {
  return signal.reason ?? new DOMException('Aborted', 'AbortError');
}

/**
 * Installs a fake global `fetch` that routes every request to `handler`. It
 * behaves like the real one where the hooks can tell: an aborted signal
 * rejects the pending request with an `AbortError`, and a JSON body arrives
 * as the string the Fetcher serialised.
 */
export function fakeServer(handler: Handler): FakeServer {
  const requests: ServedRequest[] = [];
  let current = handler;
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const url = String(input);
      const signal = init.signal ?? undefined;
      const request: ServedRequest = {
        url,
        path: new URL(url).pathname,
        method: init.method ?? 'GET',
        headers: new Headers(init.headers),
        body: typeof init.body === 'string' ? JSON.parse(init.body) : init.body,
        signal,
      };
      requests.push(request);
      if (signal?.aborted) throw abortError(signal);
      const response = Promise.resolve(current(request));
      if (!signal) return response;
      return new Promise<Response>((resolve, reject) => {
        const onAbort = () => reject(abortError(signal));
        signal.addEventListener('abort', onAbort, { once: true });
        response.then(resolve, reject).finally(() => {
          signal.removeEventListener('abort', onAbort);
        });
      });
    }),
  );
  return {
    fetcher: new Fetcher({ baseURL: BASE_URL }),
    requests,
    handle(next) {
      current = next;
    },
  };
}

export function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

/** The error body and header a Wow server answers a failed request with. */
export function wowError(
  errorCode: string,
  errorMsg: string,
  status = 400,
): Response {
  return new Response(JSON.stringify({ errorCode, errorMsg }), {
    status,
    headers: {
      'Content-Type': 'application/json',
      'Wow-Error-Code': errorCode,
    },
  });
}

/** One server-sent event as a Wow server writes it. */
export interface SseEvent {
  /** The event name; Wow sends rows without one. */
  event?: string;
  data: unknown;
}

function encodeEvent({ event, data }: SseEvent, id: number): Uint8Array {
  const lines = [`id:${id}`];
  if (event) lines.push(`event:${event}`);
  lines.push(`data:${JSON.stringify(data)}`, '', '');
  return new TextEncoder().encode(lines.join('\n'));
}

/** A server-sent event stream the test writes to while the hook reads. */
export interface LiveStream {
  response: Response;
  /** Writes events as one network chunk. */
  send(...events: SseEvent[]): void;
  /** Ends the stream. */
  close(): void;
  /** Whether the reader cancelled the stream. */
  readonly cancelled: boolean;
}

export function liveStream(): LiveStream {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  let id = 0;
  let cancelled = false;
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c;
    },
    cancel() {
      cancelled = true;
    },
  });
  return {
    response: new Response(body, {
      headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' },
    }),
    send(...events) {
      const chunks = events.map(event => encodeEvent(event, id++));
      const size = chunks.reduce((total, chunk) => total + chunk.length, 0);
      const joined = new Uint8Array(size);
      let offset = 0;
      for (const chunk of chunks) {
        joined.set(chunk, offset);
        offset += chunk.length;
      }
      controller.enqueue(joined);
    },
    close() {
      controller.close();
    },
    get cancelled() {
      return cancelled;
    },
  };
}

/** A whole event stream, sent at once and closed. */
export function sse(...events: SseEvent[]): Response {
  const stream = liveStream();
  if (events.length > 0) stream.send(...events);
  stream.close();
  return stream.response;
}

/** A promise the test settles, for responses that arrive out of order. */
export function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}
