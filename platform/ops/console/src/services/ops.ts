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
import type { ViewEngine } from "@ahoo-wang/wow-view-engine";
import { historySource, type HistorySource } from "@/detail/entryHistory.ts";
import { createOpsEngine, parkedSource } from "@/views/engine.ts";
import { parkedCommands, type ParkedCommands } from "@/views/parkedCommands.ts";
import { CallerHeaders, CallerStore } from "./caller.ts";
import { withCoSec } from "./cosec.ts";

/** What the console talks to the ops service with. */
export interface OpsServices {
  callers: CallerStore;
  fetcher: Fetcher;
  commands: ParkedCommands;
  engine: ViewEngine;
  /** An entry's event streams: its history. */
  history: HistorySource;
}

/**
 * The console's services on one fetcher, same-origin: the ops server serves
 * the console and the API from one place, and Vite forwards the API in
 * development. Every request carries the caller in force: the CoSec token it
 * signed in with, or the headers of a local run. A token the service refuses
 * (401) signs the console out.
 */
export function createOpsServices(
  tokens: TokenStorage = new TokenStorage(),
  callers: CallerStore = new CallerStore(undefined, tokens),
  fetcher: Fetcher = new Fetcher({ baseURL: "" }),
): OpsServices {
  withCoSec(fetcher, tokens, () => {
    if (callers.get()?.via === "cosec") callers.set(null);
  });
  fetcher.interceptors.request.use(new CallerHeaders(callers.get));
  return {
    callers,
    fetcher,
    commands: parkedCommands(fetcher),
    engine: createOpsEngine({ source: parkedSource(fetcher) }),
    history: historySource(fetcher),
  };
}
