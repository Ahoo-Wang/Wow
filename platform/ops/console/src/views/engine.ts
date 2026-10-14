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

import type { Fetcher } from "@ahoo-wang/fetcher";
import {
  localStorageSnapshot,
  MemoryViewStore,
  ViewEngine,
  type ViewEngineOptions,
  type ViewSource,
  type ViewStore,
} from "@ahoo-wang/wow-view-engine";
import { browserRuntimeEnvironment } from "@ahoo-wang/wow-view-engine/react";
import { viewSourceOf } from "@ahoo-wang/wow-view-store/v10";
import { ParkedSnapshotQueryClient } from "@/generated/index.ts";
import { overview } from "./overview.ts";
import { parked } from "./parked.ts";

/**
 * The parked entries as the engine's source: the generated snapshot query
 * client of `ops.parked` (`POST /ops/parked/snapshot/query`), adapted by the
 * V10 view store's `viewSourceOf` — paged, cursor, aggregate and describe.
 */
export function parkedSource(fetcher: Fetcher): ViewSource {
  return viewSourceOf(new ParkedSnapshotQueryClient({ fetcher }));
}

/**
 * Where an operator's saved views are kept: this browser. The ops service
 * hosts no view store (D141: it aggregates the ledgers and acts on them),
 * so the views are the operator's own, on this machine; the system views
 * are the definition's and ship with the console.
 */
export function browserViewStore(): ViewStore {
  return new MemoryViewStore({
    snapshot: localStorageSnapshot("wow-ops-console:views"),
  });
}

export interface OpsEngineOptions extends Omit<
  ViewEngineOptions,
  "resources" | "store"
> {
  source: ViewSource;
  store?: ViewStore;
}

/** An engine over the parked entries. */
export function createOpsEngine({
  source,
  store = browserViewStore(),
  ...options
}: OpsEngineOptions): ViewEngine {
  return new ViewEngine({
    resources: [{ definition: parked, source }, { definition: overview }],
    store,
    environment: browserRuntimeEnvironment({
      onError: ({ kind, error, context }) =>
        console.error(`[view-engine] ${kind} failed`, error, context),
    }),
    onIssue: (issue) => console.warn("[view-engine]", issue.code, issue),
    ...options,
  });
}
