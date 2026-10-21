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
import { viewSourceOf } from "@ahoo-wang/wow-view-store";
import {
  DatasetSnapshotQueryClient,
  SourceSnapshotQueryClient,
} from "@/generated/index.ts";
import { boards } from "./boards.ts";
import { datasets } from "./datasets.ts";
import { sources } from "./sources.ts";

/**
 * The catalog's sources and datasets as the engine's sources: the generated
 * snapshot query clients of `analytics-catalog.source` and
 * `analytics-catalog.dataset`, adapted by the view store's `viewSourceOf`.
 */
export function catalogSources(fetcher: Fetcher): {
  sources: ViewSource;
  datasets: ViewSource;
} {
  return {
    sources: viewSourceOf(new SourceSnapshotQueryClient({ fetcher })),
    datasets: viewSourceOf(new DatasetSnapshotQueryClient({ fetcher })),
  };
}

/**
 * Where a data owner's saved views are kept: this browser. The catalog hosts
 * no view store, so the views are the data owner's own, on this machine; the
 * system views are the definitions' and ship with the console.
 */
export function browserViewStore(): ViewStore {
  return new MemoryViewStore({
    snapshot: localStorageSnapshot("wow-analytics-catalog-console:views"),
  });
}

export interface CatalogEngineOptions extends Omit<
  ViewEngineOptions,
  "resources" | "store"
> {
  sources: ViewSource;
  datasets: ViewSource;
  store?: ViewStore;
}

/**
 * An engine over the catalog's sources and datasets; the datasets it serves
 * register beside them (`DatasetResources`), each by its own name.
 */
export function createCatalogEngine({
  sources: sourceSource,
  datasets: datasetSource,
  store = browserViewStore(),
  ...options
}: CatalogEngineOptions): ViewEngine {
  return new ViewEngine({
    resources: [
      { definition: sources, source: sourceSource },
      { definition: datasets, source: datasetSource },
      { definition: boards },
    ],
    store,
    environment: browserRuntimeEnvironment({
      onError: ({ kind, error, context }) =>
        console.error(`[view-engine] ${kind} failed`, error, context),
    }),
    onIssue: (issue) => console.warn("[view-engine]", issue.code, issue),
    ...options,
  });
}
