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
  ViewNavigation,
} from "@ahoo-wang/wow-view-engine";
import { useCallback } from "react";
import { useLocation, useNavigate } from "react-router";
import { EXECUTION_FAILED } from "./executionFailed.ts";
import { EXECUTION_HISTORY } from "./executionHistory.ts";
import { OVERVIEW_BOARD } from "./overview.ts";

/** The console's pages the engine's ways off a board or a view lead to. */
export const HOME_PATH = "/";
export const EXECUTIONS_PATH = "/executions";
export const EVENTS_PATH = "/executions/events";
export const BOARDS_PATH = "/boards";

/** The open view (or board) of a workbench page, in its address. */
export const VIEW_PARAM = "view";

/**
 * What a page is handed with the history entry it is opened on: a view a
 * board sent to a workbench (`handOver`), or the board's filters as they
 * were left (`filters`). History state rather than the address: a hand-over
 * is a whole view, and the entry keeps it through a reload and the back
 * button.
 */
export interface NavigationState {
  handOver?: ViewHandOver;
  filters?: DashboardFilters;
}

/** The state of the entry a page was opened on, read defensively. */
export function navigationState(state: unknown): NavigationState {
  return state && typeof state === "object" ? (state as NavigationState) : {};
}

/** A workbench page on `view`, or on its default view. */
function withView(path: string, view: string | null): string {
  return view === null
    ? path
    : `${path}?${new URLSearchParams({ [VIEW_PARAM]: view })}`;
}

/**
 * Where a board's own page is: the home page for the overview — the way
 * back from a view it opened lands where the reader reads it — and the
 * dashboard workbench for any other.
 */
export function boardPath(instanceId: string): string {
  return instanceId === OVERVIEW_BOARD
    ? HOME_PATH
    : withView(BOARDS_PATH, instanceId);
}

export type Destination =
  | { kind: "page"; to: string; state?: NavigationState }
  | { kind: "external"; url: string }
  | { kind: "none" };

/**
 * Where a way off a board or a view goes in this console (`ViewNavigation`):
 * a view of the failed executions to their workbench, one of the event
 * streams to theirs, each handed the view it asked for; a board — another,
 * or the way back to the one a view was opened from — to its page, under
 * the filters it carries; a panel's own page as it says.
 */
export function destinationOf(to: ViewNavigation): Destination {
  switch (to.kind) {
    case "view":
    case "unsaved": {
      const page =
        to.definitionId === EXECUTION_FAILED
          ? EXECUTIONS_PATH
          : to.definitionId === EXECUTION_HISTORY
            ? EVENTS_PATH
            : null;
      if (page === null) return { kind: "none" };
      return {
        kind: "page",
        to: withView(page, to.kind === "view" ? to.instanceId : null),
        state: { handOver: to },
      };
    }
    case "dashboard":
      return {
        kind: "page",
        to: boardPath(to.instanceId),
        state: { filters: to.filters },
      };
    case "url":
      return to.url.startsWith("/") && !to.url.startsWith("//")
        ? { kind: "page", to: to.url }
        : { kind: "external", url: to.url };
  }
}

/** The console's route for the engine (`onNavigate`). */
export function useViewNavigation(): (to: ViewNavigation) => void {
  const navigate = useNavigate();
  return useCallback(
    (to: ViewNavigation) => {
      const destination = destinationOf(to);
      if (destination.kind === "page")
        void navigate(destination.to, { state: destination.state });
      else if (destination.kind === "external")
        window.open(destination.url, "_blank", "noopener,noreferrer");
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
