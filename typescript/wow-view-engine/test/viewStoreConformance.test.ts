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

import { afterEach } from 'vitest';
import {
  localStorageSnapshot,
  MemoryViewStore,
  type ViewInstance,
} from '../src/index.js';
import { recordConfig } from './fixtures.js';
import {
  describeViewStoreConformance,
  type ConformanceCapabilities,
} from './conformance/viewStoreConformance.js';

/**
 * The port's conformance suite over the implementations this package ships
 * (view-store-backend.md 7, V2). The Wow backend's client runs the same suite
 * from `typescript/integration-test` (V3).
 */

const SYSTEM_DEFINITION = 'conformance-system';

/** What operations serve read-only: a system view, seeded as the store's data. */
const system: ViewInstance = {
  id: 'conformance-system-standard',
  definitionId: SYSTEM_DEFINITION,
  title: 'Standard',
  scope: 'system',
  revision: 'ops-1',
  config: recordConfig(),
};

/**
 * One user, one browser: the memory store has no owners, so every owner the
 * suite asks for is that one user, and the cases about two users are
 * skipped by the capability rather than faked.
 */
const MEMORY: ConformanceCapabilities = {
  owners: false,
  personalViews: true,
  changeAudience: true,
  idempotentCreate: true,
  systemViews: { definitionId: SYSTEM_DEFINITION },
};

describeViewStoreConformance({
  name: 'MemoryViewStore',
  capabilities: MEMORY,
  connect: () => {
    const store = new MemoryViewStore({ instances: [system] });
    return () => store;
  },
});

let keys: string[] = [];
afterEach(() => {
  for (const key of keys) localStorage.removeItem(key);
  keys = [];
});

describeViewStoreConformance({
  name: 'MemoryViewStore over localStorageSnapshot',
  capabilities: MEMORY,
  // Every store opened is another tab over the one stored document, so
  // "another writer" in the suite is a second tab, as it is in a browser.
  connect: () => {
    const key = `conformance:${crypto.randomUUID()}`;
    keys.push(key);
    localStorage.setItem(
      key,
      JSON.stringify({ instances: [system], preferences: {} }),
    );
    return () =>
      new MemoryViewStore({
        snapshot: localStorageSnapshot(key, { events: null }),
      });
  },
});
