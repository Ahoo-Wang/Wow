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
 * The `ViewStore` port's conformance suite over the V10 `WowViewStore` and
 * the in-memory stand-in for the V10 service: the cases the real service
 * passes in `typescript/integration-test/test/view-store/`, here without
 * a JVM. The service deduplicates every write by its request id, creates
 * included, so `idempotentCreate` holds whichever store retries.
 */

import { beforeEach } from 'vitest';
import { WowViewStore } from '../src/index.js';
import { describeViewStoreConformance } from '../../wow-view-engine/test/conformance/viewStoreConformance.js';
import { actingAs, installFake, WRITER_ROLES } from './support.js';

const SYSTEM_DEFINITION = 'conformance-system';

beforeEach(() => {
  installFake({
    systemViews: [
      {
        id: 'configured-1',
        definitionId: SYSTEM_DEFINITION,
        title: 'Configured',
        config: { kind: 'record' },
      },
    ],
  });
});

/** The views one list page answers: small, so a few views cross pages. */
const PAGE = 3;

describeViewStoreConformance({
  name: 'V10 WowViewStore on the fake V10 service',
  capabilities: {
    owners: true,
    personalViews: true,
    changeAudience: true,
    idempotentCreate: true,
    systemViews: { definitionId: SYSTEM_DEFINITION },
    storedSystemViews: true,
    // A small page, which the store is told too: every view is a write.
    pageSize: PAGE,
  },
  connect:
    () =>
    ({ owner }) =>
      new WowViewStore({
        fetcher: actingAs({
          user: owner,
          tenant: 't1',
          roles: WRITER_ROLES,
          app: 'conformance',
        }),
        pageSize: PAGE,
      }),
});
