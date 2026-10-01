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
 * The view engine against whichever example server runs (DEPLOY-10): the one
 * built from this commit embeds the view store, and a released image (9.1.5
 * and before, `WOW_SERVER_VERSION`) has none. A console or any other host of
 * the engine pointed at such a server is told the server keeps no views —
 * `UNSUPPORTED` — and never that a view it asks for "no longer exists".
 */

import {
  DEFAULT_RUNTIME_LIMITS,
  defaultRuntimeEnvironment,
  ViewEngine,
} from '@ahoo-wang/wow-view-engine';
import { WowViewStore } from '@ahoo-wang/wow-view-store';
import { describe, expect, it } from 'vitest';
import {
  freshTenant,
  orderSource,
  ordersDefinition,
} from '../view-engine/salesOrders';
import { actingAs } from '../view-store/viewStoreServer';

const version = process.env.WOW_SERVER_VERSION || undefined;
const serverURL =
  process.env.WOW_EXAMPLE_SERVER_URL ?? 'http://localhost:8080/';

const store = new WowViewStore({
  fetcher: actingAs(
    {
      tenantId: `released-${Date.now()}`,
      owner: 'released-alice',
      appId: 'released-smoke',
    },
    { name: 'the example server', url: serverURL },
  ),
});
const definitionId = `released-${Math.random().toString(36).slice(2)}`;

describe.skipIf(version === undefined)(
  `the view store on a server without one (${version})`,
  () => {
    it('answers a list, a read and the preferences as UNSUPPORTED', async () => {
      // One at a time: a refusal nobody awaits yet is an unhandled one.
      for (const ask of [
        () => store.list(definitionId),
        () => store.get(`${definitionId}-view`),
        () => store.getPreferences(definitionId),
      ])
        await expect(ask()).rejects.toMatchObject({
          name: 'ViewStoreError',
          code: 'UNSUPPORTED',
        });
    });

    it('says so through the engine: the list beside nothing, and a view by id', async () => {
      const engine = new ViewEngine({
        resources: [
          {
            definition: ordersDefinition,
            source: orderSource(freshTenant('released')),
          },
        ],
        store,
        limits: DEFAULT_RUNTIME_LIMITS,
        environment: defaultRuntimeEnvironment({ timeZone: 'UTC' }),
      });

      const listing = await engine.list(ordersDefinition.id);
      expect(listing.failed?.code).toBe('view.list.failed.unsupported');
      await expect(engine.open(`${definitionId}-view`)).rejects.toMatchObject({
        code: 'UNSUPPORTED',
      });
      engine.dispose();
    });
  },
);

describe.skipIf(version !== undefined)(
  'the view store on the server built from this commit',
  () => {
    it('answers a list and a missing view as a store does', async () => {
      await expect(store.list(definitionId)).resolves.toEqual([]);
      await expect(store.get(`${definitionId}-view`)).rejects.toMatchObject({
        code: 'NOT_FOUND',
      });
    });
  },
);
