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
  SnapshotQueryClient,
} from "@ahoo-wang/wow-client";
import {
  DEFAULT_RUNTIME_LIMITS,
  ViewEngine,
  type ViewEngineOptions,
  type ViewSource,
  type ViewStore,
} from "@ahoo-wang/wow-view-engine";
import { browserRuntimeEnvironment } from "@ahoo-wang/wow-view-engine/react";
import type { Locale } from "@/i18n.tsx";
import {
  EXECUTION_FAILED_SOURCE,
  executionFailedDefinition,
} from "./executionFailed.ts";
import {
  EXECUTION_HISTORY_SOURCE,
  executionHistoryDefinition,
} from "./executionHistory.ts";
import { createLocalViewStore } from "./localViewStore.ts";

/**
 * The failed executions as a view source: the snapshot query client as it
 * is, on the console's own fetcher, so the base URL and the CoSec
 * interceptors apply to the engine's queries exactly as to the old pages'.
 */
export function executionFailedSource(): ViewSource {
  return new SnapshotQueryClient({
    basePath: EXECUTION_FAILED_SOURCE,
    fetcher,
  });
}

/**
 * The failed executions' event streams, on the same fetcher: an execution's
 * history in its detail (`execution_failed/event/paged`).
 */
export function executionHistorySource(): ViewSource {
  return new EventStreamQueryClient({
    basePath: EXECUTION_FAILED_SOURCE,
    fetcher,
  });
}

export interface ExecutionEngineOptions {
  locale: Locale;
  store: ViewStore;
  source?: ViewSource;
  /** Where an execution's history comes from; the event stream by default. */
  historySource?: ViewSource;
}

/**
 * An engine over the compensation service in one language: the failed
 * executions, and their event streams for an execution's history in its
 * detail. A definition carries its labels in one language (G12), so a change
 * of language builds a new engine over the same store.
 */
export function executionEngineOptions({
  locale,
  store,
  source = executionFailedSource(),
  historySource = executionHistorySource(),
}: ExecutionEngineOptions): ViewEngineOptions {
  return {
    definitions: [
      executionFailedDefinition(locale),
      executionHistoryDefinition(locale),
    ],
    // The service pages at most 100 rows at a time, and an export pages at
    // the runtime's largest size, so that is the largest this source takes.
    limits: { ...DEFAULT_RUNTIME_LIMITS, maxPageSize: 100 },
    store,
    resolveSource: (key) =>
      key === EXECUTION_HISTORY_SOURCE ? historySource : source,
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

/** The one store of this page load, shared by the engines of each language. */
export function localViewStore(): ViewStore {
  sharedStore ??= createLocalViewStore();
  return sharedStore;
}
