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
  type Issue,
  type ViewEngineOptions,
  type ViewSource,
  type ViewStore,
} from "@ahoo-wang/wow-view-engine";
import { browserRuntimeEnvironment } from "@ahoo-wang/wow-view-engine/react";
import type { Locale } from "@/i18n.tsx";
import { EXECUTION_FAILED_SOURCE, executionFailed } from "./executionFailed.ts";
import {
  EXECUTION_HISTORY_SOURCE,
  executionHistory,
} from "./executionHistory.ts";
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
  locale: Locale;
  store: ViewStore;
  source?: ViewSource;
  /** Where an execution's history comes from; the event stream by default. */
  historySource?: ViewSource;
}

/**
 * An engine over the compensation service in one language: the failed
 * executions, their event streams — an execution's history in its detail,
 * and the outcomes by day — and the overview board over both. The
 * definitions are the same in every language and the engine says their
 * keys in `locale`'s words (`definitionText`); a change of language still
 * builds a new engine over the same store until one engine serves the
 * application (host-integration.md 4, H2).
 */
export function executionEngineOptions({
  locale,
  store,
  source = executionFailedSource(),
  historySource = executionHistorySource(),
}: ExecutionEngineOptions): ViewEngineOptions {
  return {
    definitions: [executionFailed, executionHistory, overview],
    text: definitionText(locale),
    store,
    resolveSource: (key) =>
      key === EXECUTION_HISTORY_SOURCE ? historySource : source,
    // The overview is nineteen panels, and an analysis panel may ask two or
    // three queries (its split's 「其他」, a cut's probe): more than the
    // default queue of 32 holds, which refused the last panels with
    // 「同时查询太多」 (2026-09-27, against the test service). Four still run
    // at once; the rest wait their turn instead of failing.
    limits: { maxQueuedQueries: 64 },
    environment: browserRuntimeEnvironment({
      onError: ({ kind, error, context }) =>
        console.error(`[view-engine] ${kind} failed`, error, context),
    }),
    // What the engine found about the definitions — a capability this
    // deployment lacks (the error search on MongoDB), a descriptor it could
    // not read — is for whoever works on the console, not its operators.
    ...(import.meta.env.DEV
      ? {
          onIssue: (issue: Issue) =>
            console.debug(`[view-engine] ${issue.code}`, issue),
        }
      : {}),
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
