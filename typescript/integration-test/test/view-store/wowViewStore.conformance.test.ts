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
 * The `ViewStore` port's conformance suite (view-store-backend.md 7, 9) over
 * `WowViewStore` and each view store server (the standalone one and the
 * example server that embeds the starter): the cases `MemoryViewStore`
 * passes in the engine's own suite, against MongoDB. Every case works in a
 * definition of its own, so one server serves the whole run; each run is a
 * tenant of its own besides.
 *
 * Creating is not idempotent on the Wow server (it generates the id), but a
 * `WowViewStore` remembers the request ids of its own creates and asks the
 * replay route before posting a retry again, so a retry from the same store
 * — which is what the case sends — answers the first view:
 * `idempotentCreate` is `true`. A retry from another store instance still
 * makes a second view; no case sends one.
 */

import { WowViewStore } from '@ahoo-wang/wow-view-store';
import { describeViewStoreConformance } from '../../../wow-view-engine/test/conformance/viewStoreConformance.js';
import {
  actingAs,
  SYSTEM_VIEW_DEFINITION,
  viewStoreServers,
} from './viewStoreServer';

const tenantId = `conformance-${Date.now()}`;

for (const server of viewStoreServers)
  describeViewStoreConformance({
    name: `WowViewStore on ${server.name}`,
    capabilities: {
      owners: true,
      personalViews: true,
      changeAudience: true,
      idempotentCreate: true,
      systemViews: { definitionId: SYSTEM_VIEW_DEFINITION },
      // Stored system views (D81, #3854): written under
      // `tenant/(platform)/owner/(system)`, whatever this run's tenant.
      storedSystemViews: true,
    },
    connect:
      () =>
      ({ owner }) =>
        new WowViewStore({
          fetcher: actingAs({ tenantId, owner, appId: 'conformance' }, server),
        }),
  });
