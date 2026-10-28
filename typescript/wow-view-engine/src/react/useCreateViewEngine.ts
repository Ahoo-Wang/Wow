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

import { useEffect, useState } from 'react';
import { ViewEngine, type ViewEngineOptions } from '../runtime/index.js';
import { browserRuntimeEnvironment } from './environment.js';

/**
 * Creates one engine for the lifetime of the component and disposes it on
 * unmount. An application that wants a longer life builds the engine itself
 * and passes it to the other hooks; they take an engine, never options.
 *
 * Options are read once. Changing them later has no effect, because a
 * definition set and a store are not render-time values.
 */
export function useCreateViewEngine(options: ViewEngineOptions): ViewEngine {
  const [engine] = useState(
    () =>
      new ViewEngine({
        ...options,
        // After the spread, so an explicit `environment: undefined` from a
        // caller's options object cannot drop page-visibility awareness.
        environment: options.environment ?? browserRuntimeEnvironment(),
      }),
  );
  useEffect(() => () => engine.dispose(), [engine]);
  return engine;
}
