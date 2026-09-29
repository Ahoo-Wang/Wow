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
  type ViewStore,
} from "@ahoo-wang/wow-view-engine";
import { browserRuntimeEnvironment } from "@ahoo-wang/wow-view-engine/react";
import type { Locale } from "@/i18n.tsx";
import { EXECUTION_FAILED_SOURCE, executionFailed } from "./executionFailed.ts";
import { executionHistory } from "./executionHistory.ts";
import { createLocalViewStore } from "./localViewStore.ts";
import { overview } from "./overview.ts";
import { definitionText } from "./text.ts";

/**
 * The compensation service's query capability descriptors
 * (`execution_failed/snapshot/schema`, `execution_failed/event/schema`):
 * what each query model admits on this deployment. The engine reads them
 * through each source's `describe` and narrows the definitions to them, so
 * a control the storage cannot answer is never offered — the error search
 * on a MongoDB snapshot store without a text index (G15) — and the page and
 * aggregation limits are the server's own (capabilities.md, C6).
 */
function descriptorClient(): QueryDescriptorClient {
  return new QueryDescriptorClient({
    basePath: EXECUTION_FAILED_SOURCE,
    fetcher,
  });
}

/**
 * The failed executions as a view source: the snapshot query client, on the
 * console's own fetcher so the base URL and the CoSec interceptors apply to
 * the engine's queries exactly as to the commands', described by the
 * snapshot model's descriptor. The clients bind their own methods.
 */
export function executionFailedSource(): ViewSource {
  const snapshots = new SnapshotQueryClient({
    basePath: EXECUTION_FAILED_SOURCE,
    fetcher,
  });
  return {
    paged: snapshots.paged,
    cursor: snapshots.cursor,
    aggregate: snapshots.aggregate,
    describe: descriptorClient().describeSnapshot,
  };
}

/**
 * The failed executions' event streams, on the same fetcher: an execution's
 * history in its detail (`execution_failed/event/paged`) and the outcomes on
 * the overview, described by the event stream model's descriptor.
 */
export function executionHistorySource(): ViewSource {
  const streams = new EventStreamQueryClient({
    basePath: EXECUTION_FAILED_SOURCE,
    fetcher,
  });
  return {
    paged: streams.paged,
    cursor: streams.cursor,
    aggregate: streams.aggregate,
    describe: descriptorClient().describeEventStream,
  };
}

export interface ExecutionEngineOptions {
  store: ViewStore;
  source?: ViewSource;
  /** Where the event streams come from; the service's by default. */
  historySource?: ViewSource;
  /**
   * The language the definitions' keys are said in until a Provider says
   * them (`ViewEngine.setText`): for a test that reads the engine alone.
   * The console's engine takes none — its Provider says them in the
   * language in force, and a change of language rebuilds nothing.
   */
  locale?: Locale;
}

/**
 * The console's engine (host-integration.md 4): the failed executions over
 * their snapshots, their event streams — an execution's history in its
 * detail, and the outcomes by day — and the overview board over both, each
 * definition registered with its source. The board queries nothing of its
 * own; the query queue makes room for its panels by itself.
 */
export function executionEngineOptions({
  store,
  source = executionFailedSource(),
  historySource = executionHistorySource(),
  locale,
}: ExecutionEngineOptions): ViewEngineOptions {
  return {
    resources: [
      { definition: executionFailed, source },
      { definition: executionHistory, source: historySource },
      { definition: overview },
    ],
    store,
    ...(locale ? { text: definitionText(locale) } : {}),
    environment: browserRuntimeEnvironment({
      onError: ({ kind, error, context }) =>
        console.error(`[view-engine] ${kind} failed`, error, context),
    }),
  };
}

export function createExecutionEngine(
  options: ExecutionEngineOptions,
): ViewEngine {
  return new ViewEngine(executionEngineOptions(options));
}

let sharedStore: ViewStore | undefined;

/** The one store of this page load. */
export function localViewStore(): ViewStore {
  sharedStore ??= createLocalViewStore();
  return sharedStore;
}

let sharedEngine: ViewEngine | undefined;

/**
 * The console's one engine, over the service and this browser's store:
 * the three pages share it — its queries, its preferences, the descriptors
 * it has read — and a change of language only redraws it.
 */
export function consoleEngine(): ViewEngine {
  sharedEngine ??= createExecutionEngine({ store: localViewStore() });
  return sharedEngine;
}
