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

import { bind } from "@ahoo-wang/wow-view-engine/ui";
import { parkedDetail } from "@/detail/parkedDetail.tsx";
import { OVERVIEW } from "./overview.ts";
import { PARKED } from "./parked.ts";
import { parkedActions } from "./parkedActions.ts";
import type { ParkedCommands } from "./parkedCommands.ts";

/** Where the parked entries' views live. */
export const PARKED_PATH = "/parked";

/** Where the caller is named. */
export const CALLER_PATH = "/caller";

/** The console's home page: the overview board. */
export const OVERVIEW_PATH = "/overview";

/** The open view of the workbench, in its address. */
export const VIEW_PARAM = "view";

/** The parked entries' page on `view`, or on its default view (`null`). */
export function parkedPath(view: string | null): string {
  return view === null
    ? PARKED_PATH
    : `${PARKED_PATH}?${new URLSearchParams({ [VIEW_PARAM]: view })}`;
}

/**
 * The console's resources as it binds them (host-integration.md 4.2, 5):
 * the parked entries' page, how one is read, and the operator's actions,
 * which the engine places on every record view over them.
 */
export function consoleBindings(commands: ParkedCommands) {
  return [
    bind(PARKED, {
      route: parkedPath,
      reading: parkedDetail,
      actions: parkedActions(commands),
    }),
    // The overview has one board, the home page's; the console keeps no
    // boards of its own to open another in.
    bind(OVERVIEW, { route: () => OVERVIEW_PATH }),
  ];
}
