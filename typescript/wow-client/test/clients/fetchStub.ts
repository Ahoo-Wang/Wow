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
import { describe, expect, it, vi } from 'vitest';
import { toWowError, WowError } from '../../src';

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

/** A fetcher on {@link BASE_URL} whose requests go to the stubbed `fetch`. */
export function testFetcher(): Fetcher {
  return new Fetcher({ baseURL: BASE_URL });
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

export type AbortArg = AbortController | AbortSignal | undefined;

/**
 * Asserts that `call` cancels its request both through an `AbortController`
 * and through an `AbortSignal`: the signal `fetch` receives is aborted and
 * the call rejects.
 */
export async function expectCancellable(
  call: (abort: AbortArg) => Promise<unknown>,
): Promise<void> {
  const { requests } = stubFetch(hangUntilAborted);

  const controller = new AbortController();
  const byController = call(controller);
  await vi.waitFor(() => expect(requests).toHaveLength(1));
  expect(requests[0].signal?.aborted).toBe(false);
  controller.abort();
  await expect(byController).rejects.toThrow();
  expect(requests[0].signal?.aborted).toBe(true);

  const signalSource = new AbortController();
  const bySignal = call(signalSource.signal);
  await vi.waitFor(() => expect(requests).toHaveLength(2));
  expect(requests[1].signal).toBe(signalSource.signal);
  expect(requests[1].signal?.aborted).toBe(false);
  signalSource.abort();
  await expect(bySignal).rejects.toThrow();
  expect(requests[1].signal?.aborted).toBe(true);
}

/**
 * Asserts that `call` rejects on a non-2xx JSON error response, and that
 * `toWowError` reads the `ErrorInfo` and the status from the rejection.
 */
export async function expectWowErrorOnFailure(
  call: () => Promise<unknown>,
): Promise<void> {
  const errorInfo = {
    errorCode: 'NotFound',
    errorMsg: 'Aggregate not found.',
    bindingErrors: [],
  };
  stubFetch(() => jsonResponse(errorInfo, 404));
  const rejection = await call().then(
    () => expect.unreachable('the call should reject'),
    (error: unknown) => error,
  );
  const wowError = await toWowError(rejection);
  expect(wowError).toBeInstanceOf(WowError);
  expect(wowError).toMatchObject({ ...errorInfo, status: 404 });
}

/**
 * Asserts that the stream `call` resolves to errors with a `WowError` at the
 * server's error event, after passing the rows before it.
 */
export async function expectStreamErrorEvent(
  call: () => Promise<ReadableStream<unknown>>,
  rowsBefore = '',
): Promise<void> {
  stubFetch(() => sseResponse(rowsBefore + SSE_ERROR_EVENT));
  const stream = await call();
  const error = await readAll(stream).then(
    () => expect.unreachable('the stream should error'),
    (e: unknown) => e,
  );
  expect(error).toBeInstanceOf(WowError);
  expect(error).toMatchObject({ errorCode: 'NotFound', errorMsg: 'x' });
}

const EVENT_STREAM = 'text/event-stream';

/** One public method of a client and the request it must send. */
export interface ClientCase<C> {
  name: string;
  call: (client: C, abort?: AbortArg) => Promise<unknown>;
  method: 'GET' | 'POST';
  /** The URL path after {@link BASE_URL}. */
  path: string;
  /** The JSON body; absent for a request without one. */
  body?: unknown;
  /** Headers the request must carry besides `Accept`. */
  headers?: Record<string, string>;
  /** Whether the method answers with a server-sent event stream. */
  stream?: boolean;
  /** The JSON answer, or the rows of the stream. */
  result: unknown;
}

/**
 * For every case: the request it sends, the result it parses, that both an
 * `AbortController` and an `AbortSignal` cancel it, and how it fails.
 */
export function describeClientCases<C>(
  createClient: () => C,
  cases: ClientCase<C>[],
): void {
  describe.each(cases)('$name', testCase => {
    it('sends its request and parses the answer', async () => {
      const { requests } = stubFetch(() =>
        testCase.stream
          ? sseResponse(sseRows(testCase.result as unknown[]))
          : jsonResponse(testCase.result),
      );

      const result = await testCase.call(createClient());

      expect(requests).toHaveLength(1);
      const [sent] = requests;
      expect(sent.method).toBe(testCase.method);
      expect(sent.url).toBe(`${BASE_URL}${testCase.path}`);
      // Compared as it goes over the wire: JSON drops the undefined members.
      expect(sent.body).toStrictEqual(
        testCase.body === undefined
          ? undefined
          : JSON.parse(JSON.stringify(testCase.body)),
      );
      for (const [name, value] of Object.entries(testCase.headers ?? {})) {
        expect(sent.headers.get(name)).toBe(value);
      }
      if (testCase.stream) {
        expect(sent.headers.get('Accept')).toBe(EVENT_STREAM);
        // The stream yields the rows themselves, not event envelopes.
        const rows = await readAll(result as ReadableStream<unknown>);
        expect(rows).toStrictEqual(testCase.result);
      } else {
        expect(sent.headers.get('Accept')).not.toBe(EVENT_STREAM);
        expect(result).toStrictEqual(testCase.result);
      }
    });

    it('is cancelled by an AbortController and by an AbortSignal', () =>
      expectCancellable(abort => testCase.call(createClient(), abort)));

    it('rejects on an error response with the ErrorInfo', () =>
      expectWowErrorOnFailure(() => testCase.call(createClient())));

    if (testCase.stream) {
      it('errors the stream with a WowError at an error event', () =>
        expectStreamErrorEvent(
          () =>
            testCase.call(createClient()) as Promise<ReadableStream<unknown>>,
          sseRows((testCase.result as unknown[]).slice(0, 1)),
        ));
    }
  });
}
