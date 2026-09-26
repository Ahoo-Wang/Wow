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
  MemoryViewStore,
  ViewEngine,
  type ViewSource,
  type ViewStore,
} from '@ahoo-wang/wow-view-engine';
import { browserRuntimeEnvironment } from '@ahoo-wang/wow-view-engine/react';
import { ordersDefinition } from './ordersDefinition.js';

// Step 2b: one engine for the page. It holds the definitions, where views
// are saved, and where each definition's rows come from.
export function createOrdersEngine(
  source: ViewSource,
  // Saved views live here. `MemoryViewStore` forgets them on reload; a host
  // keeps them in its own service by implementing `ViewStore`.
  store: ViewStore = new MemoryViewStore(),
): ViewEngine {
  return new ViewEngine({
    definitions: [ordersDefinition],
    store,
    resolveSource: key => {
      if (key !== ordersDefinition.source) throw new Error(`No source ${key}.`);
      return source;
    },
    // A failed query or save is shown where it happens; this is for your
    // logs as well.
    environment: browserRuntimeEnvironment({
      onError: ({ kind, error }) =>
        console.error(`[view-engine] ${kind}`, error),
    }),
  });
}
