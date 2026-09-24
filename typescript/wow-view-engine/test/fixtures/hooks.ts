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
 * What every hook suite opens against: one engine over a memory store, the
 * orders definition and a source that answers. Each suite names the pieces
 * it cares about — the instances it starts from, its own source, or a store
 * it keeps a handle on — and takes the rest as it comes.
 */

import {
  MemoryViewStore,
  ViewEngine,
  type DataViewDefinition,
  type ViewInstance,
  type ViewSource,
} from '../../src/index.js';
import { mine, ordersDefinition, testSource } from '../fixtures.js';

export function engineWith(
  options: {
    instances?: ViewInstance[];
    source?: ViewSource;
    store?: MemoryViewStore;
    definitions?: DataViewDefinition[];
  } = {},
): { engine: ViewEngine; store: MemoryViewStore } {
  const store =
    options.store ??
    new MemoryViewStore({ instances: options.instances ?? [mine] });
  const engine = new ViewEngine({
    definitions: options.definitions ?? [ordersDefinition()],
    store,
    resolveSource: () => options.source ?? testSource(),
  });
  return { engine, store };
}
