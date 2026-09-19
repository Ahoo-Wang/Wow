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
 * What the UI suites share: the view they open, the one whose stored tree
 * the simple editor cannot draw, and an engine wired to a source they can
 * answer with.
 */

import { MemoryViewStore, ViewEngine } from '../../src/index.js';
import type { ViewInstance, ViewSource } from '../../src/index.js';
import { ordersDefinition, recordConfig, testSource } from '../fixtures.js';

export const mine: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'Mine',
  scope: 'personal',
  revision: '1',
  config: recordConfig(),
};

/**
 * A simple-mode config holding a tree only the advanced editor can show. It
 * opens, runs and saves; the kernel warns about it, and nothing more.
 */
export const mixed: ViewInstance = {
  ...mine,
  config: recordConfig({
    filterMode: 'simple',
    filter: {
      op: 'or',
      children: [{ field: 'warehouse', operator: 'EQ', value: 'CN' }],
    },
  }),
};

export function setup(source: ViewSource = testSource()) {
  const store = new MemoryViewStore({ instances: [mine] });
  const engine = new ViewEngine({
    definitions: [ordersDefinition()],
    store,
    resolveSource: () => source,
  });
  return { engine, store, source };
}
