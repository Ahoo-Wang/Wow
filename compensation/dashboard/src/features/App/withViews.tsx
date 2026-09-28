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

import type { ReactNode } from "react";
import {
  MemoryViewStore,
  type ViewEngine,
  type ViewSource,
  type ViewStore,
} from "@ahoo-wang/wow-view-engine";
import { createExecutionEngine } from "@/views/engine.ts";
import type { ExecutionCommands } from "../Executions/executionCommands.ts";
import { ViewsHost } from "./ViewsHost.tsx";

/** What a test puts the console's views over instead of the service. */
export interface TestViews {
  store?: ViewStore;
  source?: ViewSource;
  historySource?: ViewSource;
  commands?: ExecutionCommands;
}

/**
 * `page` under the console's host (`ViewsHost`), on an engine of its own
 * over what the test gives — as the app's route puts every page under it.
 * Returns the engine too, for a test that asks it.
 */
export function withViews(
  page: ReactNode,
  { store, source, historySource, commands }: TestViews = {},
): { element: ReactNode; engine: ViewEngine } {
  const engine = createExecutionEngine({
    store: store ?? new MemoryViewStore(),
    source,
    historySource,
  });
  return {
    engine,
    element: (
      <ViewsHost engine={engine} commands={commands}>
        {page}
      </ViewsHost>
    ),
  };
}
