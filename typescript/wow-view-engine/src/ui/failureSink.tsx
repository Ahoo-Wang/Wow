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

import { createContext, useCallback, useContext, type ReactNode } from 'react';
import type {
  RuntimeEnvironment,
  ViewErrorEvent,
  ViewRuntime,
} from '../runtime/index.js';
import { reportError, viewPlace } from '../runtime/failures.js';

type Sink = (event: ViewErrorEvent) => void;

const FailureSinkContext = createContext<Sink | undefined>(undefined);

/**
 * Where the render boundaries below tell the host what they caught (D40):
 * the engine's `onError`, through the environment the view runs on, with the
 * open view named. The two workbenches and the embeds set it, because they
 * are the ones handed the engine; a boundary with none above it tells only
 * its own `onFailure`.
 */
export function FailureSink({
  environment,
  runtime,
  children,
}: {
  environment: RuntimeEnvironment;
  runtime: ViewRuntime | null | undefined;
  children: ReactNode;
}) {
  const sink = useCallback<Sink>(
    event =>
      reportError(environment, {
        ...event,
        context: {
          ...(runtime ? viewPlace(runtime) : {}),
          ...event.context,
        },
      }),
    [environment, runtime],
  );
  return (
    <FailureSinkContext.Provider value={sink}>
      {children}
    </FailureSinkContext.Provider>
  );
}

/** The nearest surface's way to the host's `onError`, if there is one. */
export function useFailureSink(): Sink | undefined {
  return useContext(FailureSinkContext);
}
