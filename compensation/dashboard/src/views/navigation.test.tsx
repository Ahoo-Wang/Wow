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

import type { ViewNavigation } from "@ahoo-wang/wow-view-engine";
import { act, render } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ViewRoute } from "@ahoo-wang/wow-view-engine/ui";
import {
  boardPath,
  navigationState,
  useBoardFilters,
  useViewNavigation,
  withView,
} from "./navigation.ts";
import { OVERVIEW_BOARD } from "./overview.ts";

const FILTERS = {
  values: { window: { type: "relative", amount: 30, unit: "day" } },
} as const;

const handed = (
  definitionId: string,
  kind: "view" | "unsaved" = "view",
): ViewNavigation =>
  kind === "view"
    ? {
        kind,
        definitionId,
        instanceId: `system:${definitionId}:all`,
        scopeFilter: null,
        filter: null,
      }
    : {
        kind,
        definitionId,
        title: "These records",
        config: {} as never,
        scopeFilter: null,
      };

describe("the console's routes", () => {
  it("puts a view in its workbench's address, and a view nobody saved on the default", () => {
    expect(withView("/executions", "system:execution-failed:all")).toBe(
      "/executions?view=system%3Aexecution-failed%3Aall",
    );
    expect(withView("/events", null)).toBe("/events");
  });

  it("goes back to the overview on the home page, and to others' pages", () => {
    expect(boardPath(OVERVIEW_BOARD)).toBe("/");
    expect(boardPath("mine")).toBe("/boards?view=mine");
  });
});

describe("navigationState", () => {
  it("reads nothing out of an entry with no state", () => {
    expect(navigationState(null)).toEqual({});
    expect(navigationState("x")).toEqual({});
    expect(navigationState({ filters: FILTERS })).toEqual({ filters: FILTERS });
  });
});

describe("the console's route", () => {
  afterEach(() => vi.restoreAllMocks());

  function mount(hook: () => void, initial: unknown = null) {
    const router = createMemoryRouter(
      [
        {
          path: "*",
          Component() {
            hook();
            return null;
          },
        },
      ],
      { initialEntries: [{ pathname: "/", state: initial }] },
    );
    render(<RouterProvider router={router} />);
    return router;
  }

  it("follows a way off the board as its route says, keeps a panel's own page inside, and opens another site apart", () => {
    let go: ReturnType<typeof useViewNavigation> = () => undefined;
    const router = mount(() => {
      go = useViewNavigation();
    });
    const to = handed("execution-failed");
    const routed: ViewRoute = {
      kind: "route",
      path: "/executions?view=all",
      state: { handOver: to as never },
      target: to as never,
    };
    act(() => go(routed));
    expect(router.state.location.pathname).toBe("/executions");
    expect(router.state.location.state).toEqual({ handOver: to });

    act(() => go({ kind: "url", url: "/events" }));
    expect(router.state.location.pathname).toBe("/events");

    const open = vi.spyOn(window, "open").mockReturnValue(null);
    act(() => go({ kind: "url", url: "https://example.com" }));
    expect(open).toHaveBeenCalledWith(
      "https://example.com",
      "_blank",
      "noopener,noreferrer",
    );
    // A definition no page of the console holds is bound no route: it
    // arrives as it came, and goes nowhere.
    act(() => go(handed("elsewhere")));
    expect(router.state.location.pathname).toBe("/events");
  });

  it("keeps a board's filters in its entry, written once per change", () => {
    let filters: ReturnType<typeof useBoardFilters> | undefined;
    const router = mount(
      () => {
        filters = useBoardFilters();
      },
      { filters: FILTERS },
    );
    expect(filters?.initialFilters).toEqual(FILTERS);
    const key = router.state.location.key;
    act(() => filters?.onFiltersChange(FILTERS));
    expect(router.state.location.key).toBe(key);

    const narrower = {
      values: { window: { type: "preset", preset: "today" } },
    };
    act(() => filters?.onFiltersChange(narrower as never));
    expect(router.state.location.state).toEqual({ filters: narrower });
    expect(router.state.historyAction).toBe("REPLACE");
  });

  it("opens a board on its defaults when its entry holds no filters", () => {
    let filters: ReturnType<typeof useBoardFilters> | undefined;
    mount(() => {
      filters = useBoardFilters();
    });
    expect(filters?.initialFilters).toBeNull();
  });
});
