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

import { fetcher } from "@ahoo-wang/fetcher";
import {
  EventStreamQueryClient,
  QueryDescriptorClient,
  SnapshotQueryClient,
} from "@ahoo-wang/wow-client";
import {
  ViewEngine,
  type ViewEngineOptions,
  type ViewSource,
} from "@ahoo-wang/wow-view-engine";
import { browserRuntimeEnvironment } from "@ahoo-wang/wow-view-engine/react";
import type { Locale } from "@/i18n.tsx";
import { EXECUTION_FAILED_SOURCE, executionFailed } from "./executionFailed.ts";
import { executionHistory } from "./executionHistory.ts";
import { overview } from "./overview.ts";
import { definitionText } from "./text.ts";
import { createConsoleViewStore } from "./viewStore.ts";

/** The compensation service's clients, on the console's own fetcher. */
const service = { basePath: EXECUTION_FAILED_SOURCE, fetcher };

/**
 * A query model of the service as a view source, described by its
 * capability descriptor (capabilities.md): the engine narrows the
 * definitions to what the deployment's storage answers. The clients bind
 * their own methods.
 */
function sourceOf(
  client: Pick<ViewSource, "paged" | "cursor" | "aggregate">,
  describe: ViewSource["describe"],
): ViewSource {
  const { paged, cursor, aggregate } = client;
  return { paged, cursor, aggregate, describe };
}

/** The failed executions, over their snapshots. */
export function executionFailedSource(): ViewSource {
  return sourceOf(
    new SnapshotQueryClient(service),
    new QueryDescriptorClient(service).describeSnapshot,
  );
}

/** Their event streams: an execution's history, and the outcomes by day. */
export function executionHistorySource(): ViewSource {
  return sourceOf(
    new EventStreamQueryClient(service),
    new QueryDescriptorClient(service).describeEventStream,
  );
}

export interface ExecutionEngineOptions extends Omit<
  ViewEngineOptions,
  "resources"
> {
  source?: ViewSource;
  historySource?: ViewSource;
  /** Starting words, for a test that draws with no host above. */
  locale?: Locale;
}

/**
 * An engine over the console's resources (host-integration.md 4): the
 * failed executions, their event streams, and the overview board over
 * both; a test gives its own sources and store.
 */
export function createExecutionEngine({
  source = executionFailedSource(),
  historySource = executionHistorySource(),
  locale,
  ...options
}: ExecutionEngineOptions): ViewEngine {
  return new ViewEngine({
    resources: [
      { definition: executionFailed, source },
      { definition: executionHistory, source: historySource },
      { definition: overview },
    ],
    ...(locale ? { text: definitionText(locale) } : {}),
    environment: browserRuntimeEnvironment({
      onError: ({ kind, error, context }) =>
        console.error(`[view-engine] ${kind} failed`, error, context),
    }),
    ...options,
  });
}

let shared: ViewEngine | undefined;

/** The console's one engine, over the service and the views it keeps. */
export function consoleEngine(): ViewEngine {
  shared ??= createExecutionEngine({ store: createConsoleViewStore() });
  return shared;
}
