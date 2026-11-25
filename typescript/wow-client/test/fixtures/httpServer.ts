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

import {
  createServer,
  type IncomingMessage,
  type ServerResponse,
} from 'node:http';
import type { AddressInfo } from 'node:net';
import { Fetcher } from '@ahoo-wang/fetcher';
import { afterEach } from 'vitest';

/** A request the server received. */
export interface Received {
  readonly method: string;
  readonly url: string;
  /** Its `Wow-Request-Id`. */
  readonly requestId: string | undefined;
  /** Whether its connection has closed. */
  closed: boolean;
}

const closers: Array<() => Promise<void>> = [];

afterEach(async () => {
  for (const close of closers.splice(0)) await close();
});

/**
 * A real HTTP server on 127.0.0.1 answering with `handler`, and a fetcher on
 * it, so the URL parser, the connection and its close are the runtime's own,
 * not a stub's. It is closed after the test.
 */
export async function serve(
  handler: (request: IncomingMessage, response: ServerResponse) => void,
) {
  const received: Received[] = [];
  const server = createServer((request, response) => {
    const requestId = request.headers['wow-request-id'];
    const seen: Received = {
      method: request.method ?? '',
      url: request.url ?? '',
      requestId: typeof requestId === 'string' ? requestId : undefined,
      closed: false,
    };
    received.push(seen);
    response.on('close', () => {
      seen.closed = true;
    });
    handler(request, response);
  });
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  const { port } = server.address() as AddressInfo;
  closers.push(
    () =>
      new Promise<void>(resolve => {
        server.closeAllConnections();
        server.close(() => resolve());
      }),
  );
  const fetcher = new Fetcher({
    baseURL: `http://127.0.0.1:${port}`,
    headers: {},
  });
  return { fetcher, received };
}

/** What `pending` settles with, or `'pending'` once `ms` passed first. */
export function within<T>(
  pending: Promise<T>,
  ms: number,
): Promise<T | 'pending'> {
  return Promise.race([
    pending,
    new Promise<'pending'>(resolve => setTimeout(() => resolve('pending'), ms)),
  ]);
}
