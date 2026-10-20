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
 * the `WowViewStore` and the real view store service: every case
 * `MemoryViewStore` and the 9.x store pass. Each case works in a definition
 * of its own and each run is a tenant of its own, so one service serves the
 * whole run.
 *
 * The service deduplicates every write by its request id, creates
 * included (D191), so a retry from any store answers the first view:
 * `idempotentCreate` is `true` without the store remembering anything.
 * Both owners hold both writer roles: the cases write shared and stored
 * system views as either.
 */

import { WowViewStore } from '@ahoo-wang/wow-view-store';
import { describeViewStoreConformance } from '../../../wow-view-engine/test/conformance/viewStoreConformance.js';
import {
  actingAs,
  SHARED_WRITER,
  SYSTEM_VIEW_DEFINITION,
  SYSTEM_WRITER,
} from './viewStoreServer';

const tenantId = `conformance-${Date.now()}`;

describeViewStoreConformance({
  name: 'WowViewStore on the view store service',
  capabilities: {
    owners: true,
    personalViews: true,
    changeAudience: true,
    idempotentCreate: true,
    systemViews: { definitionId: SYSTEM_VIEW_DEFINITION },
    storedSystemViews: true,
  },
  connect:
    () =>
    ({ owner }) =>
      new WowViewStore({
        fetcher: actingAs({
          tenantId,
          user: owner,
          appId: 'conformance',
          roles: [SHARED_WRITER, SYSTEM_WRITER],
        }),
      }),
});
