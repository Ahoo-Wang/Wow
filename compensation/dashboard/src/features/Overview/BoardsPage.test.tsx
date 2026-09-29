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

import type * as Ui from "@ahoo-wang/wow-view-engine/ui";
import type {
  DashboardWorkbenchProps,
  ViewBinding,
  ViewEngineProviderProps,
} from "@ahoo-wang/wow-view-engine/ui";
import { act, render, screen } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { I18nProvider } from "@/i18n.tsx";
import BoardsPage from "./BoardsPage.tsx";
import { withViews } from "../App/withViews.tsx";

const seen = vi.hoisted(() => ({
  props: undefined as DashboardWorkbenchProps | undefined,
  binding: undefined as ViewBinding | undefined,
}));

// The workbench itself is the engine's, tested there; this page is the
// wiring around it: which board, which filters, and the way back out.
vi.mock("@ahoo-wang/wow-view-engine/ui", async (actual) => {
  const ui = await actual<typeof Ui>();
  return {
    ...ui,
    // What a record panel over the failed executions takes (`bind`): the
    // page's own binding of it.
    ViewEngineProvider: (props: ViewEngineProviderProps) => {
      const bound = props.bindings?.find(
        (entry) => entry.definitionId === "execution-failed",
      );
      if (!props.engine && bound) seen.binding = bound;
      return <ui.ViewEngineProvider {...props} />;
    },
    DashboardWorkbench: (props: DashboardWorkbenchProps) => {
      seen.props = props;
      return <p>workbench of {props.definitionId}</p>;
    },
  };
});

function renderAt(search: string, state: unknown = null) {
  const router = createMemoryRouter(
    [
      {
        path: "/boards",
        element: withViews(<BoardsPage />).element,
      },
    ],
    { initialEntries: [{ pathname: "/boards", search, state }] },
  );
  render(
    <I18nProvider>
      <RouterProvider router={router} />
    </I18nProvider>,
  );
  return router;
}

describe("BoardsPage", () => {
  beforeEach(() => {
    seen.props = undefined;
    localStorage.setItem("wow-dashboard-locale", "en");
  });

  it("opens the board the route names, under the filters it was handed", () => {
    const filters = { values: { window: { type: "preset", preset: "today" } } };
    renderAt("?view=system%3Aoverview%3Ahome", { filters });
    expect(screen.getByText("workbench of overview")).toBeInTheDocument();
    expect(seen.props).toMatchObject({
      instanceId: "system:overview:home",
      initialFilters: filters,
      landmark: "region",
    });
    // The engine, the words and the commands on the failed executions'
    // records are the host's (`ViewsHost`), bound once for every page.
    expect(seen.props?.engine).toBeUndefined();
    expect(seen.props?.locale).toBeUndefined();
    // The board's record panels over the failed executions take their row
    // and bulk commands, and the bulk command's line, from the binding.
    expect(seen.binding?.actions?.row).toBeTypeOf("function");
    expect(seen.binding?.actions?.bulk).toBeTypeOf("function");
    expect(seen.binding?.bulk?.run).toBeTypeOf("function");
  });

  it("puts the board the reader opens in the route", () => {
    const router = renderAt("");
    expect(seen.props?.instanceId).toBeNull();
    act(() => seen.props?.onInstanceChange?.(null));
    expect(router.state.location.search).toBe("");
    act(() => seen.props?.onInstanceChange?.("mine"));
    expect(router.state.location.search).toBe("?view=mine");
    act(() => seen.props?.onInstanceChange?.(null));
    expect(router.state.location.search).toBe("");
  });
});
