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

import type {
  DashboardFilters,
  ViewHandOver,
} from "@ahoo-wang/wow-view-engine";
import type { ViewDestination } from "@ahoo-wang/wow-view-engine/ui";
import { useCallback } from "react";
import { useLocation, useNavigate } from "react-router";
import { OVERVIEW_BOARD } from "./overview.ts";

/** The console's pages the engine's ways off a board or a view lead to. */
export const HOME_PATH = "/";
export const EXECUTIONS_PATH = "/executions";
export const EVENTS_PATH = "/events";
export const BOARDS_PATH = "/boards";

/** The open view (or board) of a workbench page, in its address. */
export const VIEW_PARAM = "view";

/**
 * What a page is handed with the history entry it is opened on: a view a
 * board sent to a workbench (`handOver`), or the board's filters as they
 * were left (`filters`) — the engine's `ViewRouteState`. History state
 * rather than the address: a hand-over is a whole view, and the entry keeps
 * it through a reload and the back button.
 */
export interface NavigationState {
  handOver?: ViewHandOver;
  filters?: DashboardFilters;
}

/** The state of the entry a page was opened on, read defensively. */
export function navigationState(state: unknown): NavigationState {
  return state && typeof state === "object" ? (state as NavigationState) : {};
}

/** A workbench page on `view`, or on its default view (`null`). */
export function withView(path: string, view: string | null): string {
  return view === null
    ? path
    : `${path}?${new URLSearchParams({ [VIEW_PARAM]: view })}`;
}

/**
 * Where a board's own page is: the home page for the overview — the way
 * back from a view it opened lands where the reader reads it — and the
 * dashboard workbench for any other. The overview's `route`.
 */
export function boardPath(instanceId: string | null): string {
  return instanceId === OVERVIEW_BOARD
    ? HOME_PATH
    : withView(BOARDS_PATH, instanceId);
}

/**
 * The console's route for the engine (`ViewEngineProvider`'s `navigate`):
 * a way off a board or a view reaches it already resolved through its
 * definition's `route` (the bindings, `ViewsHost`), and goes there with
 * what the page opens with; a panel's own page stays in the console, and
 * any other site opens apart. A target no page of the console holds goes
 * nowhere.
 */
export function useViewNavigation(): (to: ViewDestination) => void {
  const navigate = useNavigate();
  return useCallback(
    (to: ViewDestination) => {
      if (to.kind === "route") void navigate(to.path, { state: to.state });
      else if (to.kind === "url") {
        if (to.url.startsWith("/") && !to.url.startsWith("//"))
          void navigate(to.url);
        else window.open(to.url, "_blank", "noopener,noreferrer");
      }
    },
    [navigate],
  );
}

/**
 * A board's filters kept in its history entry: read where the page opens,
 * written back (replacing the entry) as the reader changes them, so a
 * reload and the way back from a workbench find them as they were left.
 */
export function useBoardFilters(): {
  initialFilters: DashboardFilters | null;
  onFiltersChange(filters: DashboardFilters): void;
} {
  const location = useLocation();
  const navigate = useNavigate();
  const state = navigationState(location.state);
  const { pathname, search, hash } = location;
  const onFiltersChange = useCallback(
    (filters: DashboardFilters) => {
      if (JSON.stringify(filters) === JSON.stringify(state.filters)) return;
      void navigate(
        { pathname, search, hash },
        { replace: true, state: { ...state, filters } },
      );
    },
    [navigate, pathname, search, hash, state],
  );
  return { initialFilters: state.filters ?? null, onFiltersChange };
}
