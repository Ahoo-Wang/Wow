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
 * An id written into a path that a URL would resolve away, `.` or `..`, or
 * leave empty, is refused before anything is sent (#6889): `fetch` removes a
 * dot segment, `%2e` too, so the request would reach another route than the
 * one it names. Against a real HTTP server on 127.0.0.1, where the URL parser
 * is the runtime's own.
 */

import type { IncomingMessage, ServerResponse } from 'node:http';
import { describe, expect, it } from 'vitest';
import {
  CommandClient,
  DatasetClient,
  StateClient,
  type RoutingManifest,
} from '../src/index.js';
import { ServiceRoutes } from '../src/routes.js';
import { commandResultOf } from '../src/testing.js';
import { serve } from './fixtures/httpServer';
import { TEST_MANIFEST } from './fixtures/manifest';

const REF = { type: { context: 'c', name: 'a' }, tenantId: 't', id: '1' };

const answering = (_: IncomingMessage, response: ServerResponse) => {
  response.writeHead(200, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify(commandResultOf(REF)));
};

const sent = (received: readonly { method: string; url: string }[]) =>
  received.map(({ method, url }) => `${method} ${url}`);

describe('an id a path cannot carry', () => {
  it('refuses a command to the aggregate "." or ".." before it is sent, never sending it to another route', async () => {
    const { fetcher, received } = await serve(answering);
    const commands = new CommandClient(
      { fetcher },
      { manifest: TEST_MANIFEST },
    );
    for (const id of ['.', '..']) {
      await expect(commands.wait({ command: 'c.a.X', id })).rejects.toThrow(
        TypeError,
      );
    }
    // Without the refusal, `POST /c/a/x` (the route without an id) and `POST /c/x`.
    expect(sent(received)).toEqual([]);
  });

  it('refuses a state read of the aggregate ".", "..", or an empty id, before it is sent', async () => {
    const { fetcher, received } = await serve(answering);
    const states = new StateClient({ context: 'c', name: 'a' }, { fetcher });
    for (const id of ['.', '..', '']) {
      await expect(states.get(id)).rejects.toThrow(TypeError);
      await expect(states.trace(id)).rejects.toThrow(TypeError);
    }
    // Without the refusal, `GET /c/a/state`: the caller's own aggregate of a
    // type whose id is its owner's.
    expect(sent(received)).toEqual([]);
  });

  it("refuses a service route's path variable or a dataset name that a path cannot carry", async () => {
    const { fetcher, received } = await serve(answering);
    const manifest: RoutingManifest = {
      ...TEST_MANIFEST,
      contexts: [
        {
          name: 'c',
          routes: [
            {
              name: 'item',
              route: { method: 'GET', path: '/c/items/{id}' },
            },
          ],
        },
      ],
    };
    const routes = new ServiceRoutes({ fetcher }, { manifest });
    const datasets = new DatasetClient({ fetcher });
    for (const id of ['.', '..', '']) {
      await expect(routes.call('c', 'item', { path: { id } })).rejects.toThrow(
        TypeError,
      );
      expect(() => datasets.dataset('c', id)).toThrow(TypeError);
    }
    expect(sent(received)).toEqual([]);
  });
});
