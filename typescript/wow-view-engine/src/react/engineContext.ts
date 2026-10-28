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

import { createContext, useContext } from 'react';
import type { ViewEngine } from '../runtime/index.js';

/**
 * The engine the nearest `ViewHost` above holds; `undefined` outside one.
 * The host provides it, and every surface under it reads it through
 * `useEngine`.
 */
export const EngineContext = createContext<ViewEngine | undefined>(undefined);

/**
 * The engine a surface draws: its own, else the one the `ViewHost` above
 * holds. A surface with neither is a host's mistake, said as one.
 *
 * A host's own component under a `ViewHost` reads the one engine here too —
 * a record's reading that asks a registered definition of its own
 * (`engine.definitions`), say — rather than having it threaded down.
 */
export function useEngine(own?: ViewEngine): ViewEngine {
  const engine = useContext(EngineContext);
  const found = own ?? engine;
  if (!found)
    throw new Error(
      'No view engine: pass `engine`, or render inside a <ViewHost engine={…}>.',
    );
  return found;
}
