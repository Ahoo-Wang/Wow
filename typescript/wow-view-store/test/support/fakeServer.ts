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
 * A fake `fetch` behind a real `Fetcher`: the store, the Fetcher's
 * interceptors and wow-client's error reading all run as they do in a host;
 * only the network is replaced. The fetcher asks as one caller — an
 * interceptor fills `{tenantId}` and `{ownerId}` where the store leaves them
 * out, as fetcher-cosec's resource attribution does from a token.
 */

import { vi } from 'vitest';
import {
  Fetcher,
  type FetchExchange,
  type RequestInterceptor,
} from '@ahoo-wang/fetcher';

export const BASE_URL = 'https://views.example.test/';

/** One request as the server saw it. */
export interface ServedRequest {
  method: string;
  /** Decoded, without the query. */
  path: string;
  query: URLSearchParams;
  headers: Headers;
  body: unknown;
}

/** Answers a request; `undefined` falls through to the next route. */
export type Route = (
  request: ServedRequest,
) => Response | Promise<Response> | undefined;

export interface FakeServer {
  /** A Fetcher on {@link BASE_URL} asking as `alice` of tenant `t1`. */
  fetcher: Fetcher;
  /** Every request received, in order. */
  requests: ServedRequest[];
  /** Adds a route, asked before the ones added earlier. */
  on(route: Route): void;
}

class Caller implements RequestInterceptor {
  readonly name = 'FakeCaller';
  readonly order = 0;
  intercept(exchange: FetchExchange): void {
    const path = exchange.ensureRequestUrlParams().path;
    path.tenantId ??= 't1';
    path.ownerId ??= 'alice';
  }
}

/**
 * Installs a fake global `fetch`. A request no route answers is a `404`
 * with Wow's `NotFound`, as the server answers a view it does not hold —
 * bar the list of system views, which a server with a view store always
 * answers (empty).
 */
export function fakeServer(...routes: Route[]): FakeServer {
  const requests: ServedRequest[] = [];
  const stack = [...routes].reverse();
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const url = new URL(String(input));
      const request: ServedRequest = {
        method: init.method ?? 'GET',
        path: decodeURIComponent(url.pathname),
        query: url.searchParams,
        headers: new Headers(init.headers),
        body: typeof init.body === 'string' ? JSON.parse(init.body) : init.body,
      };
      requests.push(request);
      if (init.signal?.aborted) throw init.signal.reason;
      for (const route of stack) {
        const answer = await route(request);
        if (answer) return answer;
      }
      // A server with a view store serves its system views, none by
      // default; a test of one released before it answers them 404.
      if (request.method === 'GET' && request.path.endsWith('/system-views'))
        return json([]);
      return wowError('NotFound', `${request.method} ${request.path}`, 404);
    }),
  );
  const fetcher = new Fetcher({ baseURL: BASE_URL });
  fetcher.interceptors.request.use(new Caller());
  return {
    fetcher,
    requests,
    on(route) {
      stack.unshift(route);
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
  bindingErrors: { name: string; msg: string; code?: string }[] = [],
): Response {
  return new Response(JSON.stringify({ errorCode, errorMsg, bindingErrors }), {
    status,
    headers: {
      'Content-Type': 'application/json',
      'Wow-Error-Code': errorCode,
    },
  });
}

/** A route for `method path` exactly. */
export function at(
  method: string,
  path: string,
  answer: (request: ServedRequest) => Response | Promise<Response>,
): Route {
  return request =>
    request.method === method && request.path === path
      ? answer(request)
      : undefined;
}
