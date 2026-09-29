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
import { executionDetail } from "@/features/Executions/detail/executionDetail.tsx";
import {
  executionCommands,
  type ExecutionCommands,
} from "@/features/Executions/executionCommands.ts";
import { executionActions } from "./executionActions.ts";
import { EXECUTION_FAILED } from "./executionFailed.ts";
import { EXECUTION_HISTORY } from "./executionHistory.ts";
import { OVERVIEW, OVERVIEW_BOARD } from "./overview.ts";

/** The console's pages the engine's ways off a board or a view lead to. */
export const HOME_PATH = "/";
export const EXECUTIONS_PATH = "/executions";
export const EVENTS_PATH = "/events";
export const BOARDS_PATH = "/boards";

/** The open view (or board) of a workbench page, in its address. */
export const VIEW_PARAM = "view";

/** A workbench page on `view`, or on its default view (`null`). */
export function withView(path: string, view: string | null): string {
  return view === null
    ? path
    : `${path}?${new URLSearchParams({ [VIEW_PARAM]: view })}`;
}

/**
 * Where a board's own page is: the home page for the overview — the way
 * back from a view it opened lands where the reader reads it — and the
 * dashboard workbench for any other.
 */
export function boardPath(instanceId: string | null): string {
  return instanceId === OVERVIEW_BOARD
    ? HOME_PATH
    : withView(BOARDS_PATH, instanceId);
}

/** Where the failed executions' views live. */
export function executionsPath(view: string | null): string {
  return withView(EXECUTIONS_PATH, view);
}

/**
 * The console's resources as it binds them (host-integration.md 4.2, 5):
 * where each one's views live, one line each — the engine takes every way
 * off a board or a view there through the router, and draws the top bar's
 * places from it (`useViewNavigation`) — and, on the failed executions,
 * how one is read (D60) and the compensation commands, declared: the
 * engine places them on every record view over them, the workbench's and
 * a board's record panel alike (D39), asks, runs and reports them. A test
 * binds its own commands.
 */
export function consoleBindings(
  commands: ExecutionCommands = executionCommands(),
) {
  return [
    bind(EXECUTION_FAILED, {
      route: executionsPath,
      reading: executionDetail(commands),
      actions: executionActions(commands),
    }),
    bind(EXECUTION_HISTORY, { route: (view) => withView(EVENTS_PATH, view) }),
    bind(OVERVIEW, { route: boardPath }),
  ];
}

/** The console's bindings, over the service's commands. */
export const ROUTES = consoleBindings();
