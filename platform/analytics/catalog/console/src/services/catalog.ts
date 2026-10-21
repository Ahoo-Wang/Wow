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

import { Fetcher } from "@ahoo-wang/fetcher";
import { TokenStorage } from "@ahoo-wang/fetcher-cosec";
import { DatasetClient } from "@ahoo-wang/wow-client";
import { DatasetResources, type ViewEngine } from "@ahoo-wang/wow-view-engine";
import { datasetCatalogOf } from "@ahoo-wang/wow-view-store";
import {
  catalogCommands,
  type CatalogCommands,
} from "@/views/catalogCommands.ts";
import { catalogSources, createCatalogEngine } from "@/views/engine.ts";
import {
  fetchReferenceActions,
  referenceSend,
  type ReferenceActions,
  type ReferenceSend,
} from "@/views/referenceActions.ts";
import { CallerHeaders, CallerStore } from "./caller.ts";
import { withCoSec } from "./cosec.ts";

/** What the console talks to the analytics catalog with. */
export interface CatalogServices {
  callers: CallerStore;
  fetcher: Fetcher;
  commands: CatalogCommands;
  engine: ViewEngine;
  /** The datasets this node serves, registered in `engine` as the directory lists them. */
  datasets: Pick<DatasetResources, "refresh" | "duplicates">;
  /** The commands a served dataset's rows offer on the aggregates they refer to (D238 ⑤, D257). */
  references: {
    actions(dataset: string): Promise<ReferenceActions>;
    send: ReferenceSend;
  };
}

/**
 * The console's services on one fetcher, same-origin: the catalog server
 * serves the console, its API and the datasets it publishes from one place,
 * and Vite forwards them in development. Every request carries the caller in
 * force: the CoSec token it signed in with, or the headers of a local run. A
 * token the service refuses (401) signs the console out.
 */
export function createCatalogServices(
  tokens: TokenStorage = new TokenStorage(),
  callers: CallerStore = new CallerStore(undefined, tokens),
  fetcher: Fetcher = new Fetcher({ baseURL: "" }),
): CatalogServices {
  withCoSec(fetcher, tokens, () => {
    if (callers.get()?.via === "cosec") callers.set(null);
  });
  fetcher.interceptors.request.use(new CallerHeaders(callers.get));
  const engine = createCatalogEngine(catalogSources(fetcher));
  return {
    callers,
    fetcher,
    commands: catalogCommands(fetcher),
    engine,
    datasets: new DatasetResources(
      engine,
      datasetCatalogOf(new DatasetClient({ fetcher })),
      (entry, error) =>
        console.warn(
          `[datasets] ${entry.contextName}.${entry.name} is not served`,
          error,
        ),
    ),
    references: {
      actions: (dataset) => fetchReferenceActions(fetcher, dataset),
      send: referenceSend(fetcher),
    },
  };
}
