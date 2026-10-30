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
import { DatasetClient, WowProblemError } from "@ahoo-wang/wow-client";
import {
  DatasetResources,
  type ViewEngine,
  type ViewSource,
} from "@ahoo-wang/wow-view-engine";
import { datasetCatalogOf } from "@ahoo-wang/wow-view-store";
import {
  catalogCommands,
  type CatalogCommands,
} from "@/views/catalogCommands.ts";
import { catalogSources, createCatalogEngine } from "@/views/engine.ts";
import { sourcesOf } from "@/views/sources.ts";
import {
  referenceRoutes,
  type ReferenceActions,
  type ReferenceSend,
} from "@/views/referenceActions.ts";
import { CallerStore, callerHeaders } from "./caller.ts";
import { withCoSec } from "./cosec.ts";
import { settingsStore, type SettingsStore } from "./settings.ts";

/**
 * Whether this node serves datasets, as its directory answered: `null`
 * until it is asked, `false` when the node routes no directory at all — it
 * has no dataset query backend (`GET /wow/dataset` is the web server's own
 * 404, which carries no Wow code) — rather than a failure to say.
 */
export class ServingStore {
  private serving: boolean | null = null;
  private readonly listeners = new Set<() => void>();

  get = (): boolean | null => this.serving;

  set(serving: boolean): void {
    if (serving === this.serving) return;
    this.serving = serving;
    for (const listener of this.listeners) listener();
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };
}

/** Whether `error` is the web server's 404 for a route nobody serves: a problem with no Wow code. */
export function unrouted(error: unknown): boolean {
  for (let cause = error; cause instanceof Error; cause = cause.cause)
    if (cause instanceof WowProblemError)
      return cause.problem.status === 404 && !cause.code;
  return false;
}

/**
 * Keeps the sources' views as the catalog's `settings` have them, once read:
 * where a source takes no approver, none awaits one. The pages wait for the
 * settings, so no view of the sources is open on the definition replaced.
 */
export function followSettings(
  settings: SettingsStore,
  engine: ViewEngine,
  source: ViewSource,
): void {
  settings.subscribe(() => {
    const read = settings.settings();
    if (read && !read.requireSourceApproval)
      engine.register({ definition: sourcesOf(read), source });
  });
}

/** What the console talks to the analytics catalog with. */
export interface CatalogServices {
  callers: CallerStore;
  fetcher: Fetcher;
  commands: CatalogCommands;
  engine: ViewEngine;
  /** What the catalog is set to, read once a caller is in force. */
  settings: SettingsStore;
  /** The datasets this node serves, registered in `engine` as the directory lists them. */
  datasets: Pick<DatasetResources, "refresh">;
  /** Whether this node serves datasets at all. */
  serving: ServingStore;
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
  // A 401 signs out whoever was calling — a token the gateway or the
  // service refused, or a caller of headers a deployment does not read —
  // and the sign-in page says which.
  withCoSec(fetcher, tokens, () => callers.refused());
  fetcher.interceptors.request.use(callerHeaders(callers.get));
  const sources = catalogSources(fetcher);
  const engine = createCatalogEngine(sources);
  const settings = settingsStore(fetcher);
  followSettings(settings, engine, sources.sources);
  const directory = datasetCatalogOf(new DatasetClient({ fetcher }));
  const serving = new ServingStore();
  return {
    callers,
    fetcher,
    commands: catalogCommands(fetcher),
    engine,
    settings,
    serving,
    datasets: new DatasetResources(engine, {
      catalogs: {
        ...directory,
        // A node without a dataset query backend lists none, and the rows
        // page says so, rather than the web server's 404.
        list: (options) =>
          directory.list(options).then(
            (entries) => {
              serving.set(true);
              return entries;
            },
            (error: unknown) => {
              if (!unrouted(error)) throw error;
              serving.set(false);
              return [];
            },
          ),
      },
      onError: (entry, error) =>
        console.warn(
          `[datasets] ${entry.context}.${entry.dataset} is not served`,
          error,
        ),
    }),
    references: referenceRoutes(fetcher),
  };
}
